package meigo.tulpar.server.repo

import meigo.tulpar.server.apg.ApgArchive
import meigo.tulpar.server.apg.ChecksumAlgo
import org.slf4j.LoggerFactory
import java.io.File
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
                            .onFailure { log.warn("skipping malformed package {}: {}", apg, it.message) }
                    }
            }
        }
        val ordered = found.sortedWith(compareBy({ it.name }, { it.version }, { it.arch }))
        snapshot.set(Snapshot(ordered, Instant.now()))
        log.info("indexed {} package(s) from {}", ordered.size, pool)
        return ordered.size
    }

    /** Read one `.apg` into a PackageEntry, validating its metadata exists. */
    private fun scanFile(apg: File, channel: String): PackageEntry? {
        val bytes = apg.readBytes()
        val archive = ApgArchive.read(apg)
        val meta = archive.metadata() ?: run {
            log.warn("no parseable metadata in {}", apg)
            return null
        }
        val coords = PackageCoordinates.of(meta, channel)
        val sha256 = ChecksumAlgo.SHA256.hex(bytes)
        val sig = File(apg.parentFile, apg.name + ".sig").isFile
        return PackageEntry(
            coordinates = coords,
            metadata = meta,
            size = bytes.size.toLong(),
            sha256 = sha256,
            signed = sig,
            updatedEpochMillis = apg.lastModified(),
        )
    }

    /** Build the canonical repodata document from the current snapshot. */
    fun buildRepoData(serverName: String): RepoData {
        val snap = snapshot.get()
        val generatedIso = ISO.format(snap.generatedAt)
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

    /** Generate repodata.json and write it to the repository root. */
    fun writeRepoData(serverName: String): File {
        val json = buildRepoData(serverName).toJson(pretty = true)
        root.mkdirs()
        repodataFile.writeText(json)
        return repodataFile
    }

    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
