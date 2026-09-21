package meigo.tulpar.server.apg

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Builds real `.apg` byte payloads for tests. */
object ApgTestFixtures {

    /** Build a tar.xz from a map of normalized path -> bytes. Dirs are inferred. */
    fun tarXz(files: Map<String, ByteArray>): ByteArray =
        tar(files, Compression.XZ)

    /** Build a tar.zst (the compression the production pool uses). */
    fun tarZstd(files: Map<String, ByteArray>): ByteArray =
        tar(files, Compression.ZSTD)

    /** Build a tar.bz2 (a filter libAPG rejects). */
    fun tarBz2(files: Map<String, ByteArray>): ByteArray =
        tar(files, Compression.BZ2)

    /** Build an uncompressed tar (libAPG accepts raw tar). */
    fun tarRaw(files: Map<String, ByteArray>): ByteArray =
        tar(files, Compression.NONE)

    /** Build a tar.gz. */
    fun tarGz(files: Map<String, ByteArray>): ByteArray =
        tar(files, Compression.GZIP)

    private enum class Compression { NONE, GZIP, XZ, ZSTD, BZ2 }

    private fun compressWrap(bos: ByteArrayOutputStream, compression: Compression): OutputStream =
        when (compression) {
            Compression.NONE -> bos
            Compression.GZIP -> GzipCompressorOutputStream(bos)
            Compression.XZ -> XZCompressorOutputStream(bos)
            Compression.ZSTD -> ZstdOutputStreamNoFinalizer(bos)
            Compression.BZ2 -> BZip2CompressorOutputStream(bos)
        }

    private fun tar(files: Map<String, ByteArray>, compression: Compression): ByteArray {
        val bos = ByteArrayOutputStream()
        compressWrap(bos, compression).use { comp ->
            TarArchiveOutputStream(comp).use { tar ->
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

    /** Minimal metadata JSON (strict, libAPG-shaped). */
    fun metadataJson(
        name: String = "pkg",
        version: String = "1.0",
        arch: String? = "x86_64",
        extra: String = "",
    ): ByteArray {
        val archJson = if (arch == null) "null" else "\"$arch\""
        val tail = if (extra.isEmpty()) "" else ", $extra"
        return """{"name":"$name","version":"$version","architecture":$archJson$tail}""".toByteArray()
    }

    /** tar.xz whose data/ contains a character device node (libAPG rejects these). */
    fun tarXzWithDevices(): ByteArray = customTarXz { tar ->
        addFile(tar, "metadata.json", metadataJson())
        addDir(tar, "data/")
        // A device node needs the LF_CHR link flag; the default constructor
        // writes LF_NORMAL, so use the byte-typed constructor.
        val dev = TarArchiveEntry("data/dev/null", TarConstants.LF_CHR)
        dev.devMajor = 1
        dev.devMinor = 3
        tar.putArchiveEntry(dev)
        tar.closeArchiveEntry()
    }

    /** tar.xz with a regular file [target] followed by a hardlink entry [linkName]. */
    fun tarXzWithHardlink(linkName: String, target: String): ByteArray = customTarXz { tar ->
        addFile(tar, "metadata.json", metadataJson())
        addDir(tar, "data/")
        addFile(tar, "data/f", "content".toByteArray())
        val hl = TarArchiveEntry(linkName, TarConstants.LF_LINK)
        hl.linkName = target
        tar.putArchiveEntry(hl)
        tar.closeArchiveEntry()
    }

    /** tar.xz whose hardlink entry appears BEFORE its target file. */
    fun tarXzWithHardlinkBeforeTarget(): ByteArray = customTarXz { tar ->
        addFile(tar, "metadata.json", metadataJson())
        addDir(tar, "data/")
        val hl = TarArchiveEntry("data/hl", TarConstants.LF_LINK)
        hl.linkName = "data/f"
        tar.putArchiveEntry(hl)
        tar.closeArchiveEntry()
        addFile(tar, "data/f", "content".toByteArray())
    }

    private fun customTarXz(body: (TarArchiveOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        XZCompressorOutputStream(bos).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                body(tar)
            }
        }
        return bos.toByteArray()
    }

    private fun addFile(tar: TarArchiveOutputStream, path: String, bytes: ByteArray) {
        val entry = TarArchiveEntry(path)
        entry.size = bytes.size.toLong()
        tar.putArchiveEntry(entry)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }

    private fun addDir(tar: TarArchiveOutputStream, path: String) {
        tar.putArchiveEntry(TarArchiveEntry(path))
        tar.closeArchiveEntry()
    }
}
