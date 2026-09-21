package meigo.tulpar.server.apg

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApgValidatorTest {

    private fun validate(bytes: ByteArray): ApgValidationResult =
        ApgValidator().validateBytes(bytes)

    @Test
    fun `valid v2 package passes`() {
        val result = validate(ApgTestFixtures.validV2Package())
        assertTrue(result.ok, "errors: ${result.errors} rejections: ${result.rejectionReasons}")
        assertTrue(result.libapgCompatible)
        assertEquals(2, result.detectedVersion)
        assertEquals("apgexample", result.metadata?.name)
    }

    @Test
    fun `missing data dir is a libAPG rejection`() {
        // libAPG install_data_dir fails without data/ (src/install/install.c).
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"x","version":"1","description":"d","maintainer":"m","homepage":"h"}""".toByteArray(),
                "md5sums" to "\n".toByteArray(),
            ),
        )
        val result = validate(bytes)
        assertFalse(result.libapgCompatible)
        assertFalse(result.ok)
        assertTrue(result.rejectionReasons.any { it.contains("data/") }, result.rejectionReasons.toString())
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
        // A checksum mismatch is server policy, not a libAPG rejection:
        // libAPG never reads sums files.
        assertTrue(result.libapgCompatible)
    }

    @Test
    fun `listed-but-missing file in sums is an integrity error`() {
        val payload = "real".toByteArray()
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"x","version":"1"}""".toByteArray(),
                "data/usr/bin/x" to payload,
                "md5sums" to "usr/bin/x ${ChecksumAlgo.MD5.hex(payload)}\nusr/bin/ghost ${ChecksumAlgo.MD5.hex(payload)}\n".toByteArray(),
            ),
        )
        val result = validate(bytes)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("missing from archive") }, "errors: ${result.errors}")
    }

    @Test
    fun `meta-json-only package is rejected (libAPG reads only metadata-json)`() {
        val payload = "x".toByteArray()
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "meta.json" to """{"name":"x","version":"1","type":"binary","description":"d","maintainer":"m","homepage":"h"}""".toByteArray(),
                "data/usr/bin/x" to payload,
            ),
        )
        val result = validate(bytes)
        assertFalse(result.libapgCompatible)
        assertFalse(result.ok)
        assertTrue(result.rejectionReasons.any { it.contains("metadata.json") }, result.rejectionReasons.toString())
        assertTrue(result.rejectionReasons.any { it.contains("meta.json") }, result.rejectionReasons.toString())
    }

    @Test
    fun `metadata-json-only package is fully compatible with libAPG 2x`() {
        val result = validate(ApgTestFixtures.validV2Package())
        assertTrue(result.libapgCompatible)
        assertFalse(result.warnings.any { it.contains("meta.json") }, "warnings: ${result.warnings}")
    }

    @Test
    fun `invalid strict JSON metadata is a rejection`() {
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"x","version":"1.0",}""".toByteArray(),
                "data/usr/bin/x" to "y".toByteArray(),
            ),
        )
        val result = validate(bytes)
        assertFalse(result.libapgCompatible)
        assertTrue(result.rejectionReasons.any { it.contains("strict JSON") }, result.rejectionReasons.toString())
    }

    @Test
    fun `missing name or version blocks indexing but not libAPG compatibility`() {
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"description":"no name"}""".toByteArray(),
                "data/usr/bin/x" to "y".toByteArray(),
            ),
        )
        val result = validate(bytes)
        assertTrue(result.libapgCompatible, result.rejectionReasons.toString())
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("name") && it.contains("version") }, "errors: ${result.errors}")
    }

    @Test
    fun `package without sums files still passes with a warning`() {
        // easyfetch-style: libAPG-compatible, no checksum files at all.
        val bytes = ApgTestFixtures.tarZstd(
            linkedMapOf(
                "metadata.json" to ApgTestFixtures.metadataJson("nosums", "1-1"),
                "data/usr/bin/nosums" to "bin".toByteArray(),
            ),
        )
        val result = validate(bytes)
        assertTrue(result.ok, "errors: ${result.errors} rejections: ${result.rejectionReasons}")
        assertEquals(0, result.detectedVersion)
        assertTrue(result.warnings.any { it.contains("no checksum file") }, result.warnings.toString())
    }

    @Test
    fun `device node package is rejected`() {
        val result = validate(ApgTestFixtures.tarXzWithDevices())
        assertFalse(result.libapgCompatible)
        assertTrue(result.rejectionReasons.any { it.contains("device") }, result.rejectionReasons.toString())
    }

    @Test
    fun `recommended metadata fields are warnings not errors`() {
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"x","version":"1"}""".toByteArray(),
                "data/usr/bin/x" to "y".toByteArray(),
            ),
        )
        val result = validate(bytes)
        assertTrue(result.ok, "errors: ${result.errors}")
        assertTrue(result.warnings.any { it.contains("description") }, result.warnings.toString())
        assertTrue(result.warnings.any { it.contains("type") }, result.warnings.toString())
    }
}
