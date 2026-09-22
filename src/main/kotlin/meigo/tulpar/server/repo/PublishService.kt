package meigo.tulpar.server.repo

import meigo.tulpar.server.apg.ApgSignature
import meigo.tulpar.server.apg.ApgValidationResult
import meigo.tulpar.server.apg.ApgValidator
import meigo.tulpar.server.config.PublishConfig
import meigo.tulpar.server.security.Identifiers
import meigo.tulpar.server.security.PathSafety
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Outcome of a publish attempt. */
sealed interface PublishResult {
    data class Success(
        val coordinates: PackageCoordinates,
        val warnings: List<String>,
        val signed: Boolean,
    ) : PublishResult

    /**
     * @param conflict maps to HTTP 409 (duplicate/collision); otherwise the
     *        route answers 422 (semantic rejection) or 400 (structural).
     */
    data class Rejected(
        val reason: String,
        val errors: List<String> = emptyList(),
        val conflict: Boolean = false,
    ) : PublishResult
}

/** Outcome of a delete (yank) attempt. */
sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data object NotFound : DeleteResult
}

/**
 * Publishes uploaded `.apg` packages into the repository.
 *
 * The uploaded archive bytes are written verbatim — never repackaged — so any
 * detached `.apg.sig` stays valid (libAPG verifies the signature over the exact
 * bytes). Validation reads the metadata from inside the archive, derives the
 * canonical pool path, and the index is rebuilt afterwards.
 *
 * Writes are atomic (temp file + move) and serialised on a single lock so two
 * concurrent uploads of the same coordinates can't interleave.
 */
class PublishService(
    private val repository: Repository,
    private val config: PublishConfig,
    private val defaultChannel: String,
) {
    private val log = LoggerFactory.getLogger(PublishService::class.java)
    private val writeLock = Any()
    private val validator = ApgValidator(verifyChecksums = true)

    /**
     * Store an uploaded package from in-memory bytes. Small payloads only;
     * the HTTP route streams uploads through [publishStaged] instead.
     */
    fun publish(apgBytes: ByteArray, sigBytes: ByteArray?, channel: String? = null): PublishResult {
        if (apgBytes.size > config.maxUploadBytes) {
            return PublishResult.Rejected("upload exceeds ${config.maxUploadBytes} bytes")
        }
        val staged = java.io.File.createTempFile("tulpar-upload-", ".apg", stagingDir())
        return try {
            staged.writeBytes(apgBytes)
            publishStaged(staged, sigBytes, channel)
        } catch (e: java.io.IOException) {
            PublishResult.Rejected("failed to stage upload: ${e.message}")
        } finally {
            staged.delete()
        }
    }

    /**
     * Store a package whose bytes are already staged in a temp file [apgFile]
     * (same filesystem as the pool). The file is validated by streaming —
     * payload bytes never live on the heap — then atomically moved into place.
     * [apgFile] is consumed: deleted on every failure path and moved on
     * success; the caller must not touch it afterwards.
     */
    fun publishStaged(apgFile: File, sigBytes: ByteArray?, channel: String? = null): PublishResult {
        val ch = channel?.takeIf { it.isNotBlank() } ?: defaultChannel

        if (apgFile.length() > config.maxUploadBytes) {
            apgFile.delete()
            return PublishResult.Rejected("upload exceeds ${config.maxUploadBytes} bytes")
        }

        // Validate by streaming the staged file (no disk write, bounded heap).
        val validation: ApgValidationResult = validator.validate(apgFile)
        if (!validation.libapgCompatible) {
            return PublishResult.Rejected("package is not acceptable to libAPG", validation.rejectionReasons)
        }
        val meta = validation.metadata
            ?: return PublishResult.Rejected("archive has no parseable metadata.json with name/version strings")
        if (config.validate && !validation.ok) {
            return PublishResult.Rejected("package failed validation", validation.errors)
        }

        // Detached-signature policy: a present .sig must verify against the
        // keyring even when signatures are optional; a missing .sig is only
        // rejected under requireSignature.
        if (sigBytes != null) {
            if (sigBytes.size > config.maxSignatureBytes) {
                return PublishResult.Rejected("signature exceeds ${config.maxSignatureBytes} bytes")
            }
            val verdict = verifySignatureFile(apgFile, sigBytes)
            if (verdict != ApgSignature.VerifyResult.VERIFIED) {
                return PublishResult.Rejected("detached signature failed verification ($verdict)")
            }
        } else if (config.requireSignature) {
            return PublishResult.Rejected("a detached signature (.sig) is required by server policy")
        }

        val coords = PackageCoordinates.of(meta, ch)

        // The pool path is derived from attacker-controlled metadata, so the
        // identifiers go through the allowlists and the resolved target must
        // stay strictly inside the repository root.
        if (!Identifiers.isSafeChannel(ch) || !Identifiers.isSafeName(coords.name) ||
            !Identifiers.isSafeVersion(coords.version) || !Identifiers.isSafeArch(coords.arch)
        ) {
            return PublishResult.Rejected(
                "package identifiers outside the allowed character set (channel/name/version/architecture)",
            )
        }
        if (!PathSafety.allSafe(ch, coords.name, coords.version, coords.arch)) {
            return PublishResult.Rejected("package metadata contains unsafe path segments (name/version/architecture/channel)")
        }
        val target = PathSafety.resolveContained(repository.root, coords.relativePath)
            ?: return PublishResult.Rejected("resolved package path escapes the repository root")

        synchronized(writeLock) {
            if (target.isFile && !config.allowOverwrite) {
                return PublishResult.Rejected("package already exists: ${coords.relativePath}", conflict = true)
            }
            // On case-insensitive filesystems "Pkg" would silently overwrite
            // "pkg"; detect the collision through the index.
            if (!config.allowOverwrite) {
                val canonicalTarget = Identifiers.canonical(coords.relativePath)
                val collides = repository.entries().any {
                    Identifiers.canonical(it.coordinates.relativePath) == canonicalTarget &&
                        it.coordinates.relativePath != coords.relativePath
                }
                if (collides) {
                    return PublishResult.Rejected(
                        "package collides with an existing one on case-insensitive filesystems: ${coords.relativePath}",
                        conflict = true,
                    )
                }
            }
            target.parentFile.mkdirs()
            // Atomic same-filesystem move: readers never see a partial package.
            try {
                Files.move(
                    apgFile.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(apgFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val sigFile = File(target.parentFile, target.name + ".sig")
            if (sigBytes != null) atomicWrite(sigFile, sigBytes) else if (config.allowOverwrite) sigFile.delete()

            repository.reindex()
            writeRepoDataSafe()
        }

        val size = target.length()
        log.info("published {} ({} bytes, signed={})", coords.relativePath, size, sigBytes != null)
        return PublishResult.Success(coords, validation.warnings, sigBytes != null)
    }

    /**
     * Reindex under the publish write lock (used by the admin console so a
     * console-triggered reindex cannot interleave with a publish and snapshot
     * the pool mid-move). Also refreshes the on-disk repodata.json.
     */
    fun reindexUnderLock(): Int = synchronized(writeLock) {
        val n = repository.reindex()
        writeRepoDataSafe()
        n
    }

    /** Refresh on-disk repodata.json; failures are logged, never fatal. */
    private fun writeRepoDataSafe() {
        runCatching { repository.writeRepoData(meigo.tulpar.server.Version.SERVER_NAME) }
            .onFailure { log.warn("failed to write repodata.json: {}", it.message) }
    }

    /** Staging directory for uploads: inside the repo root so moves are same-filesystem. */
    fun stagingDir(): File {
        val dir = File(repository.root, ".tmp")
        dir.mkdirs()
        return dir
    }

    /** Remove a package build (and its signature) and reindex. */
    fun delete(channel: String, name: String, version: String, arch: String): DeleteResult {
        synchronized(writeLock) {
            val entry = repository.find(channel, name, version, arch) ?: return DeleteResult.NotFound
            val apg = repository.fileFor(entry)
            val sig = File(apg.parentFile, apg.name + ".sig")
            apg.delete()
            if (sig.isFile) sig.delete()
            repository.reindex()
            writeRepoDataSafe()
            return DeleteResult.Deleted
        }
    }

    /**
     * Verify a detached signature over a staged package file against the
     * configured libAPG-compatible keyring. Mirrors libAPG trans_commit: the
     * keyring is loaded per verification (keyring.c keyring_load), and an
     * unusable keyring fails closed.
     */
    private fun verifySignatureFile(apgFile: File, sigBytes: ByteArray): ApgSignature.VerifyResult {
        if (config.keyringDir.isBlank()) {
            log.warn("signature presented but publish.keyringDir is not configured; rejecting")
            return ApgSignature.VerifyResult.EMPTY_KEYRING
        }
        val keyring = ApgSignature.loadKeyring(File(config.keyringDir))
        if (keyring.isEmpty()) return ApgSignature.VerifyResult.EMPTY_KEYRING
        return ApgSignature.verifyFile(apgFile, sigBytes, keyring)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File.createTempFile(target.name, ".part", target.parentFile)
        try {
            tmp.writeBytes(bytes)
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            // If the move succeeded tmp is already gone; clean up on any failure.
            if (tmp.exists()) tmp.delete()
        }
    }
}
