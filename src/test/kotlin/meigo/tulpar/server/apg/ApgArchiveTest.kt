package meigo.tulpar.server.apg

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `prefers metadata-json but falls back to meta-json`() {
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf("meta.json" to """{"name":"x","version":"1"}""".toByteArray()),
        )
        val archive = ApgArchive.read(ByteArrayInputStream(bytes))
        assertEquals("meta.json", archive.metadataFileName())
        assertEquals("x", archive.metadata()?.name)
    }
}
