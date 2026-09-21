package meigo.tulpar.server.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import meigo.tulpar.server.apg.ApgTestFixtures
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckCommandTest {

    private fun writeTmpPkg(bytes: ByteArray): File {
        val f = Files.createTempFile("tulpar-check", ".apg").toFile()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `check exits 0 for a libAPG-acceptable package`() {
        val f = writeTmpPkg(ApgTestFixtures.validV2Package("goodpkg", "1.0", "x86_64"))
        try {
            CheckCommand().parse(listOf(f.path))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `check exits 0 for a package without sums files (libAPG ignores them)`() {
        // easyfetch-style: metadata + data/, no checksum files.
        val f = writeTmpPkg(
            ApgTestFixtures.tarZstd(
                linkedMapOf(
                    "metadata.json" to ApgTestFixtures.metadataJson("nosums", "1-1"),
                    "data/usr/bin/nosums" to "bin".toByteArray(),
                ),
            ),
        )
        try {
            CheckCommand().parse(listOf(f.path))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `check exits 1 for a libAPG-rejected package`() {
        // meta.json only: libAPG parse_package would reject.
        val f = writeTmpPkg(
            ApgTestFixtures.tarXz(
                linkedMapOf("meta.json" to """{"name":"x","version":"1"}""".toByteArray()),
            ),
        )
        try {
            val e = assertFailsWith<ProgramResult> { CheckCommand().parse(listOf(f.path)) }
            assertEquals(1, e.statusCode)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `check exits 1 for a non-archive file`() {
        val f = writeTmpPkg("definitely not an archive".toByteArray())
        try {
            val e = assertFailsWith<ProgramResult> { CheckCommand().parse(listOf(f.path)) }
            assertEquals(1, e.statusCode)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `check exits 1 for a rejected compression filter`() {
        val f = writeTmpPkg(
            ApgTestFixtures.tarBz2(
                linkedMapOf(
                    "metadata.json" to ApgTestFixtures.metadataJson("bz", "1.0"),
                    "data/x" to "y".toByteArray(),
                ),
            ),
        )
        try {
            val e = assertFailsWith<ProgramResult> { CheckCommand().parse(listOf(f.path)) }
            assertEquals(1, e.statusCode)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `version command exits 0 and names the libAPG target`() {
        VersionCommand().parse(emptyList())
        assertEquals(40, meigo.tulpar.server.Version.LIBAPG_TARGET.length)
    }
}
