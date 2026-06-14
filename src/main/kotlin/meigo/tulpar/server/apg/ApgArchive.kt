package meigo.tulpar.server.apg

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * In-memory view of an `.apg` archive's contents, read without extracting to disk.
 *
 * An `.apg` is a tar stream under a compression filter — `xz` is canonical
 * (apg-docs/apgv2.md, libAPG uses `archive_read_support_filter_xz`), but the
 * official builder's C layer can emit zstd/bz2/gz/lz4/lzma too, so we let
 * Commons Compress auto-detect the compressor.
 *
 * Paths are normalized: any leading `./` and a leading `/` are stripped so that
 * `./data/bin/x`, `data/bin/x`, and `/data/bin/x` are treated identically.
 */
class ApgArchive private constructor(
    /** Raw bytes of every regular file entry, keyed by normalized path. */
    private val files: Map<String, ByteArray>,
    /** All entry paths (including directories), normalized. */
    val entries: List<String>,
) {
    /** Bytes of `metadata.json` if present, else `meta.json` (libAPG's name), else null. */
    fun metadataBytes(): ByteArray? = files["metadata.json"] ?: files["meta.json"]

    /** Which metadata filename was actually found, or null if none. */
    fun metadataFileName(): String? = when {
        files.containsKey("metadata.json") -> "metadata.json"
        files.containsKey("meta.json") -> "meta.json"
        else -> null
    }

    /** Parsed metadata, or null if absent/unparseable. */
    fun metadata(): ApgMetadata? = metadataBytes()?.let { ApgMetadata.parse(it.decodeToString()) }

    /** Bytes of the first present sums file in libAPG priority order, with its algo. */
    fun sums(): Pair<ChecksumAlgo, ByteArray>? {
        for (algo in ChecksumAlgo.byPriority) {
            files[algo.fileName]?.let { return algo to it }
        }
        return null
    }

    fun has(path: String): Boolean = files.containsKey(normalize(path))
    fun bytes(path: String): ByteArray? = files[normalize(path)]

    /** Paths of regular files under `data/` (relative to `data/`). */
    fun dataFiles(): List<String> =
        files.keys.filter { it.startsWith("data/") }.map { it.removePrefix("data/") }.sorted()

    /** Script names present under `scripts/` (basename only). */
    fun scriptNames(): List<String> =
        files.keys.filter { it.startsWith("scripts/") }
            .map { it.removePrefix("scripts/") }
            .filter { it.isNotEmpty() && !it.contains('/') }
            .sorted()

    /** True if a (regular file) `data/` entry exists. */
    fun hasDataDir(): Boolean =
        entries.any { it == "data" || it == "data/" || it.startsWith("data/") }

    companion object {
        /** Hard cap on a single decompressed entry to guard against tar bombs. */
        const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

        fun read(file: File, maxTotalBytes: Long = 2L * 1024 * 1024 * 1024): ApgArchive =
            file.inputStream().buffered().use { read(it, maxTotalBytes) }

        fun read(input: InputStream, maxTotalBytes: Long = 2L * 1024 * 1024 * 1024): ApgArchive {
            val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
            // Auto-detect the compression filter (xz canonical; others tolerated).
            val decompressed = CompressorStreamFactory().createCompressorInputStream(buffered)
            val files = HashMap<String, ByteArray>()
            val entries = ArrayList<String>()
            var total = 0L

            TarArchiveInputStream(decompressed).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val norm = normalize(entry.name)
                    entries.add(norm)
                    if (!entry.isDirectory) {
                        val size = entry.size
                        if (size > MAX_ENTRY_BYTES) {
                            throw ApgFormatException("entry too large: ${entry.name} ($size bytes)")
                        }
                        val data = tar.readNBytesGuarded(MAX_ENTRY_BYTES)
                        total += data.size
                        if (total > maxTotalBytes) {
                            throw ApgFormatException("archive exceeds size limit ($maxTotalBytes bytes)")
                        }
                        files[norm] = data
                    }
                    entry = tar.nextEntry
                }
            }
            return ApgArchive(files, entries)
        }

        internal fun normalize(path: String): String {
            var p = path.replace('\\', '/')
            while (p.startsWith("./")) p = p.substring(2)
            p = p.trimStart('/')
            return p
        }

        private fun InputStream.readNBytesGuarded(limit: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var read = this.read(buf)
            var count = 0L
            while (read >= 0) {
                count += read
                if (count > limit) throw ApgFormatException("entry exceeds $limit bytes")
                out.write(buf, 0, read)
                read = this.read(buf)
            }
            return out.toByteArray()
        }
    }
}

/** Thrown when an `.apg` archive cannot be read or violates structural limits. */
class ApgFormatException(message: String) : Exception(message)
