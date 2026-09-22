package meigo.tulpar.server.repo

import meigo.tulpar.server.apg.ApgArchive
import meigo.tulpar.server.apg.ApgValidator
import meigo.tulpar.server.apg.ApgVersion
import meigo.tulpar.server.apg.ChecksumAlgo
import meigo.tulpar.server.security.Identifiers
import meigo.tulpar.server.security.sanitizeForLog
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * The package repository: an immutable index snapshot over a `pool/` directory.
 *
 * Source of truth for metadata is the `.apg` itself (the bytes libAPG reads),
 * not a sidecar. The scanner reads metadata from each archive, records the file
 * size + sha256 + signature presence, and builds the index. Reads are lock-free
 * (served from an immutable snapshot held in an AtomicReference); a reindex swaps
 * in a fresh snapshot atomically.
 */
class Repository(val root: File) {

    private val log = LoggerFactory.getLogger(Repository::class.java)
    private val snapshot = AtomicReference(Snapshot(emptyList(), Instant.EPOCH))

    /** Structural validator for indexing (no checksum verification: libAPG does none). */
    private val indexValidator = ApgValidator(verifyChecksums = false)

    val poolDir: File get() = root.resolve("pool")
    val repodataFile: File get() = root.resolve("repodata.json")

    /** An immutable index snapshot. */
    private class Snapshot(val entries: List<PackageEntry>, val generatedAt: Instant) {
        val byKey: Map<String, PackageEntry> =
            entries.associateBy { "${it.channel}/${it.name}/${it.version}/${it.arch}" }
        val byName: Map<String, List<PackageEntry>> =
            entries.groupBy { it.name }
    }

    /** Current entries (immutable snapshot). */
    fun entries(): List<PackageEntry> = snapshot.get().entries

    /**
     * True once the repository has completed its startup initialization: the
     * first reindex, or an explicit [markReady] for configurations that start
     * with an empty index (reindexOnStart=false). Health reports "starting"
     * (503) until then.
     */
    @Volatile
    private var readyFlag = false

    fun isReady(): Boolean = readyFlag

    /** Mark the repository ready without rescanning (empty-index startup). */
    fun markReady() {
        readyFlag = true
    }

    fun byName(name: String): List<PackageEntry> = snapshot.get().byName[name] ?: emptyList()

    fun find(channel: String, name: String, version: String, arch: String): PackageEntry? =
        snapshot.get().byKey["$channel/$name/$version/$arch"]

    fun channels(): List<String> = snapshot.get().entries.map { it.channel }.distinct().sorted()

    /** Resolve the on-disk `.apg` file for an entry. */
    fun fileFor(entry: PackageEntry): File = root.resolve(entry.coordinates.relativePath)

    /** Resolve the on-disk `.sig` for an entry, or null if unsigned. */
    fun signatureFor(entry: PackageEntry): File? =
        root.resolve(entry.coordinates.signaturePath).takeIf { it.isFile }

    /**
     * Rescan `pool/` from disk and atomically replace the index. Returns the new
     * package count. Malformed `.apg` files are logged and skipped, never fatal.
     */
    fun reindex(): Int {
        val pool = poolDir
        val found = ArrayList<PackageEntry>()
        if (pool.isDirectory) {
            // pool/<channel>/<name>/<arch>/<file>.apg
            pool.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { channelDir ->
                val channel = channelDir.name
                channelDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".apg") }
                    .forEach { apg ->
                        runCatching { scanFile(apg, channel) }
                            .onSuccess { it?.let(found::add) }
                            .onFailure { log.warn("skipping malformed package {}: {}", apg, sanitizeForLog(it.message.orEmpty())) }
                    }
            }
        }
        // Latest build first within each name: the Tulpar client resolves to
        // the first satisfying build, so ordering follows ver_compare. The
        // remaining tie-breakers keep the listing deterministic even for
        // ver_compare-equal versions such as "0:9.9" and "9.9".
        val ordered = found.sortedWith(
            Comparator.comparing { e: PackageEntry -> e.name }
                .thenComparing(Comparator { a, b -> ApgVersion.compare(b.version, a.version) })
                .thenComparing { e -> e.arch }
                .thenComparing { e -> e.channel }
                .thenComparing { e -> e.version },
        )
        snapshot.set(Snapshot(ordered, Instant.now()))
        readyFlag = true
        log.info("indexed {} package(s) from {}", ordered.size, pool)
        return ordered.size
    }

    /**
     * Read one `.apg` into a PackageEntry. A package is indexed only when
     * libAPG would accept it (the core compatibility invariant); tolerated but
     * libAPG-rejected archives in a pre-existing pool are flagged with a
     * warning and excluded from the installable index, never silently listed.
     */
    private fun scanFile(apg: File, channel: String): PackageEntry? {
        val archive = ApgArchive.read(apg)
        val meta = archive.metadata() ?: run {
            log.warn("excluding {}: no parseable metadata.json with name/version strings", sanitizeForLog(apg.path))
            return null
        }
        val compat = indexValidator.validate(archive)
        if (!compat.libapgCompatible) {
            log.warn(
                "excluding {}: libAPG would not accept this package ({})",
                sanitizeForLog(apg.path), sanitizeForLog(compat.rejectionReasons.joinToString("; ")),
            )
            return null
        }
        val coords = PackageCoordinates.of(meta, channel)
        // Identifiers become URL and filesystem segments; the same allowlist
        // as the publish path is enforced here for out-of-band pool contents.
        if (!Identifiers.isSafeChannel(channel) || !Identifiers.isSafeName(coords.name) ||
            !Identifiers.isSafeVersion(coords.version) || !Identifiers.isSafeArch(coords.arch)
        ) {
            log.warn(
                "excluding {}: identifiers outside the allowed character set ({}/{}/{})",
                sanitizeForLog(apg.path),
                sanitizeForLog(coords.name), sanitizeForLog(coords.version), sanitizeForLog(coords.arch),
            )
            return null
        }
        val sha256 = ChecksumAlgo.SHA256.hexFile(apg)
        val sig = File(apg.parentFile, apg.name + ".sig").isFile
        return PackageEntry(
            coordinates = coords,
            metadata = meta,
            size = apg.length(),
            sha256 = sha256,
            signed = sig,
            updatedEpochMillis = apg.lastModified(),
        )
    }

    /**
     * Build the canonical repodata document from the current snapshot.
     *
     * The document is byte-for-byte reproducible for the same pool state:
     * `generated_at` is derived from the newest package mtime (not wall
     * clock), and entries carry a deterministic order. The Tulpar client only
     * reads `packages[]`, so `meta` stays informational.
     */
    fun buildRepoData(serverName: String): RepoData {
        val snap = snapshot.get()
        val generated = if (snap.entries.isEmpty()) {
            Instant.EPOCH
        } else {
            Instant.ofEpochMilli(snap.entries.maxOf { it.updatedEpochMillis })
        }
        val generatedIso = ISO.format(generated)
        val packages = snap.entries.map { entry ->
            RepoPackage.from(entry, ISO.format(Instant.ofEpochMilli(entry.updatedEpochMillis)))
        }
        return RepoData(
            meta = RepoMeta(
                format = RepoData.FORMAT,
                server = serverName,
                generated_at = generatedIso,
                package_count = packages.size,
                channels = channels(),
            ),
            packages = packages,
        )
    }

    /** Generate repodata.json and write it to the repository root atomically. */
    fun writeRepoData(serverName: String): File {
        val json = buildRepoData(serverName).toJson(pretty = true)
        root.mkdirs()
        // Write to a sibling temp file then ATOMIC_MOVE so readers never see a
        // partially written index (same filesystem, so the move is atomic).
        val tmp = File.createTempFile("repodata-", ".json.tmp", root)
        try {
            tmp.writeText(json)
            Files.move(
                tmp.toPath(), repodataFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return repodataFile
    }

    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
