package meigo.tulpar.server.apg

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Tar entry kind, as classified during the single streaming read. */
enum class ApgEntryKind { DIRECTORY, FILE, SYMLINK, HARDLINK, CHAR_DEVICE, BLOCK_DEVICE, FIFO, OTHER }

/** One entry seen in the archive (no payload retained). */
data class ApgEntry(
    val path: String,
    val kind: ApgEntryKind,
    val size: Long,
    val linkTarget: String?,
)

/** Per-file digests of a `data/` entry, computed on the fly without retaining bytes. */
data class ApgDigests(val md5: String, val crc32: String, val sha256: String)

/** Bounds applied while reading an archive; every limit is a hard failure. */
data class ApgReadLimits(
    val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
    val maxEntryBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxEntries: Int = 200_000,
    val maxPathLength: Int = 4095,          // libAPG PATH_MAX is 4096 incl. dest prefix
    val maxComponentLength: Int = 255,      // filesystem NAME_MAX; libAPG extraction fails above it
    val maxMetaBytes: Int = 1 * 1024 * 1024, // metadata.json / sums files are tiny
    val xzMemoryLimitKb: Int = 64 * 1024,    // 64 MiB decoder memory cap
    val zstdLongMax: Int = 27,               // 2^27 = 128 MiB window cap (prod uses 2^23)
    val captureDataDigests: Boolean = false,
)

/** Compression filters libAPG accepts (src/archive.c). Anything else is rejected. */
enum class ApgFilter { NONE, GZIP, XZ, ZSTD }

/**
 * In-memory view of an `.apg` archive read without extracting to disk and
 * without buffering payload bytes.
 *
 * An `.apg` is a tar stream under an optional compression filter. libAPG
 * accepts tar under {none, gzip, xz, zstd} (src/archive.c:61-64); any other
 * filter (bzip2, lz4, lzma, ...) is rejected here with a clear error so the
 * server never indexes a package a libAPG client could not read.
 *
 * A single streaming pass:
 *  - validates every entry against libAPG's extraction safety rules
 *    (no `..` path segments, no character/block device nodes, hardlink targets
 *    relative, `..`-free, and already-present earlier in the archive);
 *  - captures the small root files (`metadata.json`, the `*sums` files);
 *  - records the entry list (name, kind, size);
 *  - optionally computes per-`data/`-file digests inline (no bytes retained),
 *    used only by checksum verification, never by bulk indexing.
 *
 * Paths are normalized: a leading `./` and a leading `/` are stripped, so
 * `./data/x`, `data/x`, and `/data/x` are the same entry (libAPG resolves
 * absolute entry paths under the extraction root, i.e. relative).
 */
class ApgArchive private constructor(
    val filter: ApgFilter,
    val entries: List<ApgEntry>,
    private val rootFiles: Map<String, ByteArray>,
    private val dataDigests: Map<String, ApgDigests>,
    val totalDecompressedBytes: Long,
    /** True when `data/` digests were computed during the read. */
    val capturedDigests: Boolean,
) {
    /** Bytes of `metadata.json` if present (the only name libAPG reads). */
    fun metadataBytes(): ByteArray? = rootFiles["metadata.json"]

    /** Which metadata filename was found. Only `metadata.json` is recognized. */
    fun metadataFileName(): String? = if (rootFiles.containsKey("metadata.json")) "metadata.json" else null

    /** True if a `meta.json` (legacy/libAPG-internal name) is present but unused. */
    fun hasLegacyMetaJson(): Boolean = rootFiles.containsKey("meta.json")

    /** Parsed metadata, or null if absent or not strict-parseable. */
    fun metadata(): ApgMetadata? = metadataBytes()?.let { ApgMetadata.parse(it) }

    /** First present sums file in libAPG priority order, with its algorithm. */
    fun sums(): Pair<ChecksumAlgo, ByteArray>? {
        for (algo in ChecksumAlgo.byPriority) {
            rootFiles[algo.fileName]?.let { return algo to it }
        }
        return null
    }

    fun has(path: String): Boolean = rootFiles.containsKey(normalize(path)) || entries.any { it.path == normalize(path) }

    /** Raw bytes of a captured root file (metadata/sums), else null. */
    fun bytes(path: String): ByteArray? = rootFiles[normalize(path)]

    /** Computed digests for a `data/`-relative path, when capture was enabled. */
    fun digests(dataRelPath: String): ApgDigests? = dataDigests[normalize(dataRelPath)]

    /** Paths of regular files under `data/` (relative to `data/`). */
    fun dataFiles(): List<String> = entries
        .filter { it.kind == ApgEntryKind.FILE && it.path.startsWith("data/") }
        .map { it.path.removePrefix("data/") }
        .sorted()

    /** Script names present under `scripts/` (basename only). */
    fun scriptNames(): List<String> = entries
        .filter { it.path.startsWith("scripts/") }
        .map { it.path.removePrefix("scripts/") }
        .filter { it.isNotEmpty() && !it.contains('/') }
        .sorted()

    /** True if a `data/` directory entry (or anything under it) exists. */
    fun hasDataDir(): Boolean = entries.any { it.path == "data" || it.path == "data/" || it.path.startsWith("data/") }

    companion object {
        private val GZIP_MAGIC = byteArrayOf(0x1F.toByte(), 0x8B.toByte())
        private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
        private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
        private val BZIP2_MAGIC = byteArrayOf(0x42, 0x5A) // "BZ"

        fun read(file: File, limits: ApgReadLimits = ApgReadLimits()): ApgArchive =
            file.inputStream().buffered().use { read(it, limits) }

        fun read(input: InputStream, limits: ApgReadLimits = ApgReadLimits()): ApgArchive {
            val buffered = if (input.markSupported()) input else BufferedInputStream(input, 64 * 1024)
            val (filter, decompressed) = openFilter(buffered, limits)
            return readTar(filter, decompressed, limits)
        }

        /** Detect the compression filter by magic bytes and wrap the stream. */
        private fun openFilter(src: InputStream, limits: ApgReadLimits): Pair<ApgFilter, InputStream> {
            src.mark(8)
            val head = ByteArray(6)
            var n = 0
            while (n < 6) {
                val r = src.read(head, n, 6 - n)
                if (r < 0) break
                n += r
            }
            src.reset()
            return when {
                n >= 6 && head.copyOfRange(0, 6).contentEquals(XZ_MAGIC) ->
                    ApgFilter.XZ to XZCompressorInputStream.builder()
                        .setInputStream(src)
                        .setMemoryLimitKiB(limits.xzMemoryLimitKb)
                        .get()
                n >= 4 && head.copyOfRange(0, 4).contentEquals(ZSTD_MAGIC) ->
                    ApgFilter.ZSTD to ZstdInputStreamNoFinalizer(src).apply { setLongMax(limits.zstdLongMax) }
                n >= 2 && head.copyOfRange(0, 2).contentEquals(GZIP_MAGIC) ->
                    ApgFilter.GZIP to GzipCompressorInputStream(src)
                n >= 2 && head.copyOfRange(0, 2).contentEquals(BZIP2_MAGIC) ->
                    throw ApgFormatException("unsupported compression filter: bzip2 (libAPG accepts only none/gzip/xz/zstd)")
                else -> {
                    // Uncompressed tar, or an unknown/unsupported filter. Verify a
                    // tar header is present so a random binary is not treated as tar.
                    if (looksLikeTar(src)) ApgFilter.NONE to src
                    else throw ApgFormatException("unrecognized compression filter or not a tar archive")
                }
            }
        }

        /** A tar archive has the magic "ustar" at offset 257 of the first header. */
        private fun looksLikeTar(src: InputStream): Boolean {
            src.mark(512)
            val header = ByteArray(512)
            var read = 0
            while (read < 512) {
                val r = src.read(header, read, 512 - read)
                if (r < 0) break
                read += r
            }
            src.reset()
            if (read < 263) return false
            return header[257] == 'u'.code.toByte() && header[258] == 's'.code.toByte() &&
                header[259] == 't'.code.toByte() && header[260] == 'a'.code.toByte() &&
                header[261] == 'r'.code.toByte()
        }

        private fun readTar(filter: ApgFilter, decompressed: InputStream, limits: ApgReadLimits): ApgArchive {
            val rootFiles = HashMap<String, ByteArray>()
            val entries = ArrayList<ApgEntry>()
            val dataDigests = HashMap<String, ApgDigests>()
            val seenFiles = HashSet<String>() // regular-file paths, for hardlink order checks
            var total = 0L

            TarArchiveInputStream(decompressed).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry
                while (entry != null) {
                    val e = entry!!
                    val raw = e.name
                    val norm = normalize(raw)

                    if (entries.size >= limits.maxEntries) {
                        throw ApgFormatException("archive exceeds entry-count limit (${limits.maxEntries})")
                    }
                    if (raw.length > limits.maxPathLength || norm.length > limits.maxPathLength) {
                        throw ApgFormatException("entry path exceeds length limit (${limits.maxPathLength}): $raw")
                    }
                    if (maxComponentLength(norm) > limits.maxComponentLength) {
                        throw ApgFormatException(
                            "entry path component exceeds ${limits.maxComponentLength} characters: $raw",
                        )
                    }
                    if (hasDotDotSegment(norm)) {
                        throw ApgFormatException("entry path contains '..': $raw")
                    }

                    val kind = classify(e)
                    if (kind == ApgEntryKind.CHAR_DEVICE || kind == ApgEntryKind.BLOCK_DEVICE) {
                        throw ApgFormatException("device nodes are not permitted in a package: $raw")
                    }
                    if (kind == ApgEntryKind.HARDLINK) {
                        val target = normalize(e.linkName ?: "")
                        if ((e.linkName ?: "").startsWith("/") || hasDotDotSegment(target)) {
                            throw ApgFormatException("hardlink target is unsafe: ${e.linkName}")
                        }
                        if (target !in seenFiles) {
                            throw ApgFormatException("hardlink target not present earlier in archive: ${e.linkName}")
                        }
                    }

                    entries.add(ApgEntry(norm, kind, e.size, if (kind == ApgEntryKind.SYMLINK || kind == ApgEntryKind.HARDLINK) e.linkName else null))

                    if (kind == ApgEntryKind.FILE) {
                        seenFiles.add(norm)
                        val declared = e.size
                        if (declared > limits.maxEntryBytes) {
                            throw ApgFormatException("entry too large: $raw ($declared bytes)")
                        }
                        val isRoot = !norm.contains('/')
                        val isData = norm.startsWith("data/")
                        val captureRoot = isRoot && (norm == "metadata.json" || norm == "meta.json" || ChecksumAlgo.byPriority.any { it.fileName == norm })

                        if (captureRoot) {
                            val bytes = readGuarded(tar, limits.maxMetaBytes.toLong(), "file $raw")
                            total += bytes.size
                            rootFiles[norm] = bytes
                        } else if (limits.captureDataDigests && isData) {
                            val rel = norm.removePrefix("data/")
                            val dig = hashStreaming(tar, limits.maxEntryBytes, "file $raw")
                            total += dig.bytes
                            dataDigests[rel] = dig.digests
                        } else {
                            total += skipGuarded(tar, limits.maxEntryBytes, "file $raw")
                        }
                        if (total > limits.maxTotalBytes) {
                            throw ApgFormatException("archive exceeds decompressed-size limit (${limits.maxTotalBytes} bytes)")
                        }
                    }
                    entry = tar.nextEntry
                }
            }
            return ApgArchive(filter, entries, rootFiles, dataDigests, total, limits.captureDataDigests)
        }

        private fun classify(e: TarArchiveEntry): ApgEntryKind = when {
            e.isDirectory -> ApgEntryKind.DIRECTORY
            e.isSymbolicLink -> ApgEntryKind.SYMLINK
            e.isLink -> ApgEntryKind.HARDLINK
            e.isCharacterDevice -> ApgEntryKind.CHAR_DEVICE
            e.isBlockDevice -> ApgEntryKind.BLOCK_DEVICE
            e.isFIFO -> ApgEntryKind.FIFO
            e.isFile -> ApgEntryKind.FILE
            else -> ApgEntryKind.OTHER
        }

        private fun readGuarded(src: InputStream, limit: Long, what: String): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var count = 0L
            while (true) {
                val r = src.read(buf)
                if (r < 0) break
                count += r
                if (count > limit) throw ApgFormatException("$what exceeds $limit bytes")
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }

        private fun skipGuarded(src: InputStream, limit: Long, what: String): Long {
            val buf = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                val r = src.read(buf)
                if (r < 0) break
                count += r
                if (count > limit) throw ApgFormatException("$what exceeds $limit bytes")
            }
            return count
        }

        private fun hashStreaming(src: InputStream, limit: Long, what: String): HashResult {
            val md5 = MessageDigest.getInstance("MD5")
            val sha = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            val buf = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                val r = src.read(buf)
                if (r < 0) break
                count += r
                if (count > limit) throw ApgFormatException("$what exceeds $limit bytes")
                md5.update(buf, 0, r)
                sha.update(buf, 0, r)
                crc.update(buf, 0, r)
            }
            return HashResult(
                count,
                ApgDigests(md5.digest().toHex(), "%08x".format(crc.value), sha.digest().toHex()),
            )
        }

        private class HashResult(val bytes: Long, val digests: ApgDigests)

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** True if any path segment is exactly `..` (mirrors libAPG is_safe_relative_path). */
        internal fun hasDotDotSegment(path: String): Boolean {
            val p = path.replace('\\', '/')
            for (seg in p.split('/')) if (seg == "..") return true
            return false
        }

        /** Length of the longest path component (empty segments ignored). */
        internal fun maxComponentLength(path: String): Int {
            var max = 0
            for (seg in path.split('/')) if (seg.isNotEmpty() && seg.length > max) max = seg.length
            return max
        }

        internal fun normalize(path: String): String {
            var p = path.replace('\\', '/')
            while (p.startsWith("./")) p = p.substring(2)
            while (p.startsWith("/")) p = p.substring(1)
            return p
        }
    }
}

/** Thrown when an `.apg` archive cannot be read or violates structural/safety limits. */
class ApgFormatException(message: String) : Exception(message)
