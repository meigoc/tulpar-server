package meigo.tulpar.server.apg

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApgValidatorTest {

    private fun validate(bytes: ByteArray): ApgValidationResult =
        ApgValidator().validate(ApgArchive.read(ByteArrayInputStream(bytes)))

    @Test
    fun `valid v2 package passes`() {
        val result = validate(ApgTestFixtures.validV2Package())
        assertTrue(result.ok, "errors: ${result.errors}")
        assertEquals(2, result.detectedVersion)
        assertEquals("apgexample", result.metadata?.name)
    }

    @Test
    fun `missing data dir fails`() {
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"x","version":"1","description":"d","maintainer":"m","homepage":"h"}""".toByteArray(),
                "md5sums" to "\n".toByteArray(),
            ),
        )
        val result = validate(bytes)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("data/") })
    }

    @Test
    fun `checksum mismatch fails`() {
        val payload = "real".toByteArray()
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"x","version":"1","type":"binary","description":"d","maintainer":"m","homepage":"h"}""".toByteArray(),
                "data/usr/bin/x" to payload,
                "md5sums" to "usr/bin/x deadbeefdeadbeefdeadbeefdeadbeef\n".toByteArray(),
                "crc32sums" to "usr/bin/x ${ChecksumAlgo.CRC32.hex(payload)}\n".toByteArray(),
            ),
        )
        val result = validate(bytes)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("mismatch") }, "errors: ${result.errors}")
    }

    @Test
    fun `meta-json-only package validates but warns about libAPG compat`() {
        val payload = "x".toByteArray()
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "meta.json" to """{"name":"x","version":"1","type":"binary","description":"d","maintainer":"m","homepage":"h"}""".toByteArray(),
                "data/usr/bin/x" to payload,
                "md5sums" to "usr/bin/x ${ChecksumAlgo.MD5.hex(payload)}\n".toByteArray(),
                "crc32sums" to "usr/bin/x ${ChecksumAlgo.CRC32.hex(payload)}\n".toByteArray(),
            ),
        )
        val result = validate(bytes)
        assertTrue(result.ok, "errors: ${result.errors}")
        assertTrue(result.warnings.any { it.contains("meta.json") })
    }

    @Test
    fun `metadata-json-only package warns that stock libAPG needs meta-json`() {
        val result = validate(ApgTestFixtures.validV2Package())
        assertTrue(result.warnings.any { it.contains("libAPG") }, "warnings: ${result.warnings}")
    }
}
