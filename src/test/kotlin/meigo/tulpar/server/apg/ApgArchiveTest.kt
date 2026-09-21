package meigo.tulpar.server.apg

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApgArchiveTest {

    @Test
    fun `reads metadata data files and sums from a real tar xz`() {
        val bytes = ApgTestFixtures.validV2Package()
        val archive = ApgArchive.read(ByteArrayInputStream(bytes))

        val meta = archive.metadata()
        assertNotNull(meta)
        assertEquals("apgexample", meta.name)
        assertEquals("metadata.json", archive.metadataFileName())

        assertEquals(listOf("usr/bin/apgexample"), archive.dataFiles())
        assertTrue(archive.hasDataDir())

        assertNotNull(archive.sums())
    }

    @Test
    fun `sums detection follows priority and finds crc32 over md5 when no sha256`() {
        val bytes = ApgTestFixtures.validV2Package()
        val archive = ApgArchive.read(ByteArrayInputStream(bytes))
        // fixture has md5sums + crc32sums (no sha256sums) → crc32 wins by priority
        assertEquals(ChecksumAlgo.CRC32, archive.sums()!!.first)
    }

    @Test
    fun `path normalization strips leading dot-slash`() {
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "./metadata.json" to """{"name":"x","version":"1"}""".toByteArray(),
                "./data/usr/bin/x" to "y".toByteArray(),
            ),
        )
        val archive = ApgArchive.read(ByteArrayInputStream(bytes))
        assertTrue(archive.has("metadata.json"))
        assertEquals(listOf("usr/bin/x"), archive.dataFiles())
    }

    @Test
    fun `meta-json is not a metadata source (libAPG reads only metadata-json)`() {
        // libAPG parse_package() reads exactly "metadata.json" (src/package.c:241).
        // A package carrying only meta.json is not installable; the reader still
        // surfaces its presence so tooling can warn publishers.
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf("meta.json" to """{"name":"x","version":"1"}""".toByteArray()),
        )
        val archive = ApgArchive.read(ByteArrayInputStream(bytes))
        assertNull(archive.metadataFileName())
        assertNull(archive.metadata())
        assertTrue(archive.hasLegacyMetaJson())
    }

    @Test
    fun `zstd-compressed tar is read like any supported filter`() {
        val bytes = ApgTestFixtures.tarZstd(
            linkedMapOf(
                "metadata.json" to """{"name":"z","version":"1"}""".toByteArray(),
                "data/usr/bin/z" to "payload".toByteArray(),
            ),
        )
        val archive = ApgArchive.read(ByteArrayInputStream(bytes))
        assertEquals(ApgFilter.ZSTD, archive.filter)
        assertEquals("z", archive.metadata()?.name)
    }

    @Test
    fun `bzip2-compressed tar is rejected (not a libAPG filter)`() {
        val bytes = ApgTestFixtures.tarBz2(
            linkedMapOf("metadata.json" to """{"name":"z","version":"1"}""".toByteArray()),
        )
        val e = assertFailsWith<ApgFormatException> { ApgArchive.read(ByteArrayInputStream(bytes)) }
        assertTrue(e.message!!.contains("bzip2"), e.message)
    }

    @Test
    fun `uncompressed tar is accepted (libAPG reads raw tar)`() {
        val bytes = ApgTestFixtures.tarRaw(
            linkedMapOf(
                "metadata.json" to """{"name":"raw","version":"1"}""".toByteArray(),
                "data/usr/bin/raw" to "x".toByteArray(),
            ),
        )
        val archive = ApgArchive.read(ByteArrayInputStream(bytes))
        assertEquals(ApgFilter.NONE, archive.filter)
        assertEquals("raw", archive.metadata()?.name)
    }

    @Test
    fun `dot-dot segments are rejected (libAPG SECURE_NODOTDOT)`() {
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"x","version":"1"}""".toByteArray(),
                "../evil" to "x".toByteArray(),
            ),
        )
        val e = assertFailsWith<ApgFormatException> { ApgArchive.read(ByteArrayInputStream(bytes)) }
        assertTrue(e.message!!.contains(".."), e.message)
    }

    @Test
    fun `device nodes are rejected (libAPG cannot create them unprivileged)`() {
        val bytes = ApgTestFixtures.tarXzWithDevices()
        val e = assertFailsWith<ApgFormatException> { ApgArchive.read(ByteArrayInputStream(bytes)) }
        assertTrue(e.message!!.contains("device"), e.message)
    }

    @Test
    fun `unsafe hardlink targets are rejected (libAPG is_safe_relative_path)`() {
        val bytes = ApgTestFixtures.tarXzWithHardlink("data/f", "foo/../data/f")
        val e = assertFailsWith<ApgFormatException> { ApgArchive.read(ByteArrayInputStream(bytes)) }
        assertTrue(e.message!!.contains("hardlink"), e.message)
    }

    @Test
    fun `hardlink to a later entry is rejected (libAPG requires the target to exist)`() {
        val bytes = ApgTestFixtures.tarXzWithHardlinkBeforeTarget()
        val e = assertFailsWith<ApgFormatException> { ApgArchive.read(ByteArrayInputStream(bytes)) }
        assertTrue(e.message!!.contains("hardlink"), e.message)
    }

    @Test
    fun `absolute entry paths are treated as relative (libAPG re-roots them)`() {
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"x","version":"1"}""".toByteArray(),
                "/data/usr/bin/x" to "y".toByteArray(),
            ),
        )
        val archive = ApgArchive.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("usr/bin/x"), archive.dataFiles())
    }
}
