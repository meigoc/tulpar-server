package meigo.tulpar.server.repo

import meigo.tulpar.server.apg.ApgTestFixtures
import meigo.tulpar.server.config.PublishConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PublishServiceTest {

    private val root: File = Files.createTempDirectory("tulpar-publish-test").toFile()

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    private fun service(
        validate: Boolean = true,
        requireSignature: Boolean = false,
        allowOverwrite: Boolean = false,
    ): PublishService {
        val repo = Repository(root).apply { reindex() }
        return PublishService(
            repo,
            PublishConfig(enabled = true, tokens = listOf("t"), validate = validate, requireSignature = requireSignature, allowOverwrite = allowOverwrite),
            defaultChannel = "main",
        )
    }

    @Test
    fun `publishes a valid package and indexes it`() {
        val svc = service()
        val repo = Repository(root)
        val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")

        val result = svc.publish(bytes, null)
        val success = assertIs<PublishResult.Success>(result)
        assertEquals("pool/main/curl/x86_64/curl-7.85.0-x86_64.apg", success.coordinates.relativePath)
        assertTrue(File(root, success.coordinates.relativePath).isFile)

        // bytes preserved exactly
        assertTrue(File(root, success.coordinates.relativePath).readBytes().contentEquals(bytes))

        repo.reindex()
        assertEquals(1, repo.entries().size)
    }

    @Test
    fun `stores signature alongside package`() {
        val svc = service()
        val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val result = svc.publish(bytes, "signature-bytes".toByteArray())
        val success = assertIs<PublishResult.Success>(result)
        assertTrue(success.signed)
        assertTrue(File(root, success.coordinates.signaturePath).isFile)
    }

    @Test
    fun `rejects invalid archive`() {
        val svc = service()
        val result = svc.publish("not an archive".toByteArray(), null)
        assertIs<PublishResult.Rejected>(result)
    }

    @Test
    fun `requireSignature rejects unsigned upload`() {
        val svc = service(requireSignature = true)
        val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val result = svc.publish(bytes, null)
        val rejected = assertIs<PublishResult.Rejected>(result)
        assertTrue(rejected.reason.contains("signature"))
    }

    @Test
    fun `refuses overwrite by default but allows when configured`() {
        val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val svc = service(allowOverwrite = false)
        assertIs<PublishResult.Success>(svc.publish(bytes, null))
        assertIs<PublishResult.Rejected>(svc.publish(bytes, null))

        val svc2 = service(allowOverwrite = true)
        assertIs<PublishResult.Success>(svc2.publish(bytes, null))
    }

    @Test
    fun `delete removes package and signature`() {
        val svc = service()
        val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        svc.publish(bytes, "sig".toByteArray())

        val deleted = svc.delete("main", "curl", "7.85.0", "x86_64")
        assertEquals(DeleteResult.Deleted, deleted)
        assertFalse(File(root, "pool/main/curl/x86_64/curl-7.85.0-x86_64.apg").exists())
        assertFalse(File(root, "pool/main/curl/x86_64/curl-7.85.0-x86_64.apg.sig").exists())

        assertEquals(DeleteResult.NotFound, svc.delete("main", "curl", "7.85.0", "x86_64"))
    }

    @Test
    fun `rejects package whose metadata name escapes the pool`() {
        val svc = service()
        // Craft a package whose metadata.json name contains path traversal.
        val payload = "x".toByteArray()
        val bytes = meigo.tulpar.server.apg.ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"../../../../tmp/evil","version":"1.0","type":"binary","description":"d","maintainer":"m","homepage":"h"}""".toByteArray(),
                "data/usr/bin/x" to payload,
                "md5sums" to "usr/bin/x ${meigo.tulpar.server.apg.ChecksumAlgo.MD5.hex(payload)}\n".toByteArray(),
                "crc32sums" to "usr/bin/x ${meigo.tulpar.server.apg.ChecksumAlgo.CRC32.hex(payload)}\n".toByteArray(),
            ),
        )
        val result = svc.publish(bytes, null)
        val rejected = assertIs<PublishResult.Rejected>(result)
        assertTrue(rejected.reason.contains("unsafe") || rejected.reason.contains("escapes"), "reason: ${rejected.reason}")
        // nothing was written outside (or inside) the pool
        assertFalse(File("/tmp/evil").exists())
        assertFalse(File(root, "pool").walkTopDown().any { it.name.endsWith(".apg") })
    }

    @Test
    fun `rejects channel with path traversal`() {
        val svc = service()
        val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val result = svc.publish(bytes, null, channel = "../escape")
        assertIs<PublishResult.Rejected>(result)
    }

    @Test
    fun `publishes to a custom channel`() {
        val svc = service()
        val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val success = assertIs<PublishResult.Success>(svc.publish(bytes, null, channel = "extra"))
        assertEquals("extra", success.coordinates.channel)
        assertTrue(File(root, "pool/extra/curl/x86_64/curl-7.85.0-x86_64.apg").isFile)
    }
}
