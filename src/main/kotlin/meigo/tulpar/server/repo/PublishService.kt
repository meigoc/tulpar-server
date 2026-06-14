package meigo.tulpar.server.repo

import meigo.tulpar.server.apg.ApgArchive
import meigo.tulpar.server.apg.ApgValidationResult
import meigo.tulpar.server.apg.ApgValidator
import meigo.tulpar.server.config.PublishConfig
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

    data class Rejected(val reason: String, val errors: List<String> = emptyList()) : PublishResult
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
     * Store an uploaded package.
     *
     * @param apgBytes   the raw `.apg` payload (stored byte-for-byte)
     * @param sigBytes   optional detached signature payload
     * @param channel    target channel, defaults to the repo default
     */
    fun publish(apgBytes: ByteArray, sigBytes: ByteArray?, channel: String? = null): PublishResult {
        val ch = channel?.takeIf { it.isNotBlank() } ?: defaultChannel

        // Read metadata + validate from the bytes (no disk write yet).
        val archive = try {
            ApgArchive.read(apgBytes.inputStream())
        } catch (e: Exception) {
            return PublishResult.Rejected("cannot read archive: ${e.message}")
        }
        val meta = archive.metadata()
            ?: return PublishResult.Rejected("archive has no parseable metadata.json/meta.json")

        val validation: ApgValidationResult = validator.validate(archive)
        if (config.validate && !validation.ok) {
            return PublishResult.Rejected("package failed validation", validation.errors)
        }
        if (config.requireSignature && sigBytes == null) {
            return PublishResult.Rejected("a detached signature (.sig) is required by server policy")
        }

        val coords = PackageCoordinates.of(meta, ch)

        // Security: the path is derived from attacker-controlled metadata
        // (name/version/architecture) and the channel. Reject any segment that
        // isn't a safe path component, and verify the resolved target stays
        // strictly inside pool/ — otherwise a crafted "name":"../../etc/x" could
        // write outside the repository.
        if (!PathSafety.allSafe(ch, coords.name, coords.version, coords.arch)) {
            return PublishResult.Rejected("package metadata contains unsafe path segments (name/version/architecture/channel)")
        }
        val target = PathSafety.resolveContained(repository.root, coords.relativePath)
            ?: return PublishResult.Rejected("resolved package path escapes the repository root")

        synchronized(writeLock) {
            if (target.isFile && !config.allowOverwrite) {
                return PublishResult.Rejected("package already exists: ${coords.relativePath}")
            }
            target.parentFile.mkdirs()
            atomicWrite(target, apgBytes)
            val sigFile = File(target.parentFile, target.name + ".sig")
            if (sigBytes != null) atomicWrite(sigFile, sigBytes) else if (config.allowOverwrite) sigFile.delete()

            repository.reindex()
        }

        log.info("published {} ({} bytes, signed={})", coords.relativePath, apgBytes.size, sigBytes != null)
        return PublishResult.Success(coords, validation.warnings, sigBytes != null)
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
            return DeleteResult.Deleted
        }
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
