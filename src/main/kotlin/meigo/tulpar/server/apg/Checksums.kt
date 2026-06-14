package meigo.tulpar.server.apg

import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Parsing and verification of APG checksum files (`sha256sums`, `crc32sums`,
 * `md5sums`).
 *
 * The ecosystem is inconsistent about line order:
 *   - libAPG's `checksum.c` parses `HASH  RELPATH` (hash first).
 *   - apgcheck and the real APGexample package write `RELPATH HASH` (path first).
 *
 * We read *tolerantly*: a line is split into two fields and the one that looks
 * like a hash of the expected width is taken as the hash, the other as the path.
 * When we generate sums (M5 publish), we emit the canonical `HASH  RELPATH`.
 */
enum class ChecksumAlgo(val fileName: String, val hexWidth: Int) {
    SHA256("sha256sums", 64),
    CRC32("crc32sums", 8),
    MD5("md5sums", 32);

    /** Compute this algorithm's lowercase hex digest over [bytes]. */
    fun hex(bytes: ByteArray): String = when (this) {
        SHA256 -> bytes.messageDigestHex("SHA-256")
        MD5 -> bytes.messageDigestHex("MD5")
        CRC32 -> {
            val crc = CRC32().apply { update(bytes) }.value
            "%08x".format(crc)
        }
    }

    companion object {
        /** Detection priority matches libAPG `verify_checksums`: sha256 → crc32 → md5. */
        val byPriority = listOf(SHA256, CRC32, MD5)
    }
}

/** One `relPath -> expectedHash` entry from a sums file (path relative to `data/`). */
data class ChecksumEntry(val relPath: String, val hash: String)

object Checksums {
    private val hexRegex = Regex("^[0-9a-fA-F]+$")

    /**
     * Parse a sums-file body into entries, tolerating either field order.
     *
     * Blank lines and `#` comments are skipped. For a two-field line, the field
     * matching [algo]'s hex width (and consisting only of hex digits) is treated
     * as the hash; the other becomes the path. If neither/both match the width,
     * we fall back to libAPG's convention (first field = hash).
     */
    fun parse(body: String, algo: ChecksumAlgo): List<ChecksumEntry> {
        val out = ArrayList<ChecksumEntry>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) continue
            val a = parts[0].trim()
            val b = parts[1].trim()

            val aIsHash = a.length == algo.hexWidth && hexRegex.matches(a)
            val bIsHash = b.length == algo.hexWidth && hexRegex.matches(b)

            val (hash, path) = when {
                aIsHash && !bIsHash -> a to b
                bIsHash && !aIsHash -> b to a
                else -> a to b // ambiguous: libAPG convention (hash first)
            }
            if (path.isNotEmpty()) out.add(ChecksumEntry(path, hash.lowercase()))
        }
        return out
    }

    /** Render entries to canonical `HASH  RELPATH` form (two spaces, libAPG order). */
    fun render(entries: List<ChecksumEntry>): String =
        entries.joinToString("\n", postfix = "\n") { "${it.hash}  ${it.relPath}" }
}

private fun ByteArray.messageDigestHex(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm).digest(this)
    val sb = StringBuilder(digest.size * 2)
    for (byte in digest) sb.append("%02x".format(byte))
    return sb.toString()
}
