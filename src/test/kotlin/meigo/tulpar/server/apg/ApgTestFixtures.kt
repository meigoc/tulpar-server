package meigo.tulpar.server.apg

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayOutputStream

/** Builds real `.apg` (tar.xz) byte payloads for tests. */
object ApgTestFixtures {

    /** Build a tar.xz from a map of normalized path -> bytes. Dirs are inferred. */
    fun tarXz(files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        XZCompressorOutputStream(bos).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                val dirs = LinkedHashSet<String>()
                for (path in files.keys) {
                    val segs = path.split("/")
                    var acc = ""
                    for (i in 0 until segs.size - 1) {
                        acc += segs[i] + "/"
                        dirs.add(acc)
                    }
                }
                for (dir in dirs) {
                    tar.putArchiveEntry(TarArchiveEntry(dir))
                    tar.closeArchiveEntry()
                }
                for ((path, bytes) in files) {
                    val entry = TarArchiveEntry(path)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return bos.toByteArray()
    }

    /** A minimal valid v2-ish package: metadata.json + data/ + md5sums (+crc32sums). */
    fun validV2Package(name: String = "apgexample", version: String = "0.0.0", arch: String? = null): ByteArray {
        val payload = "binary-content\n".toByteArray()
        val rel = "usr/bin/$name"
        val md5 = ChecksumAlgo.MD5.hex(payload)
        val crc = ChecksumAlgo.CRC32.hex(payload)
        val archJson = if (arch == null) "null" else "\"$arch\""
        val metadata = """
            {
              "name": "$name", "version": "$version", "type": "binary",
              "architecture": $archJson, "description": "test package",
              "maintainer": "NurOS Developers <dev@nuros.org>",
              "license": "GPL-3.0", "homepage": "https://nuros.org",
              "tags": ["test"], "dependencies": [], "conflicts": [],
              "provides": [], "replaces": [], "conf": []
            }
        """.trimIndent()
        return tarXz(
            linkedMapOf(
                "metadata.json" to metadata.toByteArray(),
                "data/$rel" to payload,
                // path-first order, like the real APGexample
                ChecksumAlgo.MD5.fileName to "$rel $md5\n".toByteArray(),
                ChecksumAlgo.CRC32.fileName to "$rel $crc\n".toByteArray(),
            ),
        )
    }
}
