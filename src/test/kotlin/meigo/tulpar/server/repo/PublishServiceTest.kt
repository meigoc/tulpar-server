package meigo.tulpar.server.repo

import meigo.tulpar.server.apg.ApgTestFixtures
import meigo.tulpar.server.config.PublishConfig
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.EdDSAParameterSpec
import java.security.spec.EdECPublicKeySpec
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
        keyringDir: String = "",
    ): PublishService {
        val repo = Repository(root).apply { reindex() }
        return PublishService(
            repo,
            PublishConfig(
                enabled = true, tokens = listOf("t"), validate = validate,
                requireSignature = requireSignature, allowOverwrite = allowOverwrite,
                keyringDir = keyringDir,
            ),
            defaultChannel = "main",
        )
    }

    /**
     * An ephemeral libAPG-compatible signing setup: raw-32-byte public key in a
     * keyring dir, private key signing with Ed25519ph (prehash=true) exactly
     * like libsodium's streaming API used by libAPG sign_file.
     */
    private class Signer {
        val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val keyringDir: File = Files.createTempDirectory("tulpar-keyring").toFile()

        init {
            val spec = java.security.KeyFactory.getInstance("Ed25519")
                .getKeySpec(keyPair.public, EdECPublicKeySpec::class.java)
            val y = spec.point.y.toByteArray()
            val raw = ByteArray(32)
            val srcOff = maxOf(0, y.size - 32)
            System.arraycopy(y, srcOff, raw, 32 - (y.size - srcOff), y.size - srcOff)
            for (i in 0 until 16) {
                val t = raw[i]; raw[i] = raw[31 - i]; raw[31 - i] = t
            }
            if (spec.point.isXOdd) raw[31] = (raw[31].toInt() or 0x80).toByte()
            File(keyringDir, "test.key").writeBytes(raw)
        }

        fun sign(pkgBytes: ByteArray): ByteArray {
            val sig = Signature.getInstance("Ed25519")
            sig.setParameter(EdDSAParameterSpec(true))
            sig.initSign(keyPair.private)
            sig.update(pkgBytes)
            return sig.sign()
        }

        fun cleanup() = keyringDir.deleteRecursively().let {}
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
    fun `stores a verified signature alongside the package`() {
        val signer = Signer()
        try {
            val svc = service(keyringDir = signer.keyringDir.path)
            val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
            val result = svc.publish(bytes, signer.sign(bytes))
            val success = assertIs<PublishResult.Success>(result)
            assertTrue(success.signed)
            val storedSig = File(root, success.coordinates.signaturePath)
            assertTrue(storedSig.isFile)
            assertTrue(storedSig.readBytes().contentEquals(signer.sign(bytes)), "sig stored verbatim")
        } finally {
            signer.cleanup()
        }
    }

    @Test
    fun `rejects a present-but-invalid signature even when signatures are optional`() {
        val signer = Signer()
        try {
            val svc = service(requireSignature = false, keyringDir = signer.keyringDir.path)
            val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
            val result = svc.publish(bytes, ByteArray(64)) // well-formed length, wrong signature
            val rejected = assertIs<PublishResult.Rejected>(result)
            assertTrue(rejected.reason.contains("signature"), rejected.reason)
            assertFalse(File(root, "pool/main/curl/x86_64/curl-7.85.0-x86_64.apg").exists())
        } finally {
            signer.cleanup()
        }
    }

    @Test
    fun `rejects a malformed signature (wrong length)`() {
        val signer = Signer()
        try {
            val svc = service(keyringDir = signer.keyringDir.path)
            val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
            val rejected = assertIs<PublishResult.Rejected>(svc.publish(bytes, "signature-bytes".toByteArray()))
            assertTrue(rejected.reason.contains("signature"), rejected.reason)
        } finally {
            signer.cleanup()
        }
    }

    @Test
    fun `rejects a signature when no keyring is configured`() {
        val svc = service(keyringDir = "")
        val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val rejected = assertIs<PublishResult.Rejected>(svc.publish(bytes, ByteArray(64)))
        assertTrue(rejected.reason.contains("signature"), rejected.reason)
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
    fun `requireSignature accepts a validly signed upload`() {
        val signer = Signer()
        try {
            val svc = service(requireSignature = true, keyringDir = signer.keyringDir.path)
            val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
            assertIs<PublishResult.Success>(svc.publish(bytes, signer.sign(bytes)))
        } finally {
            signer.cleanup()
        }
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
        val signer = Signer()
        try {
            val svc = service(keyringDir = signer.keyringDir.path)
            val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
            svc.publish(bytes, signer.sign(bytes))

            val deleted = svc.delete("main", "curl", "7.85.0", "x86_64")
            assertEquals(DeleteResult.Deleted, deleted)
            assertFalse(File(root, "pool/main/curl/x86_64/curl-7.85.0-x86_64.apg").exists())
            assertFalse(File(root, "pool/main/curl/x86_64/curl-7.85.0-x86_64.apg.sig").exists())

            assertEquals(DeleteResult.NotFound, svc.delete("main", "curl", "7.85.0", "x86_64"))
        } finally {
            signer.cleanup()
        }
    }

    @Test
    fun `rejects upload over the configured size cap`() {
        val svc = service()
        val bytes = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        // Simulate the cap by rebuilding the service with a tiny maxUploadBytes.
        val repo = Repository(root)
        val tiny = PublishService(
            repo,
            PublishConfig(enabled = true, tokens = listOf("t"), maxUploadBytes = bytes.size.toLong() - 1),
            defaultChannel = "main",
        )
        val rejected = assertIs<PublishResult.Rejected>(tiny.publish(bytes, null))
        assertTrue(rejected.reason.contains("exceeds"), rejected.reason)
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
        // Rejected by the identifier allowlist (first line of defense) or by
        // segment/containment checks — the exact wording is not the contract.
        assertTrue(
            rejected.reason.contains("identifiers") ||
                rejected.reason.contains("unsafe") ||
                rejected.reason.contains("escapes"),
            "reason: ${rejected.reason}",
        )
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

    @Test
    fun `rejects a name with a control character (identifier allowlist)`() {
        val svc = service()
        val payload = "x".toByteArray()
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"bad\u0000name","version":"1.0","architecture":"x86_64","type":"binary"}""".toByteArray(),
                "data/usr/bin/x" to payload,
            ),
        )
        val rejected = assertIs<PublishResult.Rejected>(svc.publish(bytes, null))
        assertTrue(rejected.reason.contains("identifiers"), rejected.reason)
    }

    @Test
    fun `rejects a windows-reserved package name`() {
        val svc = service()
        val bytes = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"name":"CON","version":"1.0","architecture":"x86_64"}""".toByteArray(),
                "data/usr/bin/x" to "x".toByteArray(),
            ),
        )
        val rejected = assertIs<PublishResult.Rejected>(svc.publish(bytes, null))
        assertTrue(rejected.reason.contains("identifiers"), rejected.reason)
    }

    @Test
    fun `rejects a case-insensitive collision with an existing package`() {
        val svc = service(allowOverwrite = false)
        // Publish "curl" first, then "CURL" — same file on a case-insensitive FS.
        assertIs<PublishResult.Success>(svc.publish(ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64"), null))
        val collision = svc.publish(ApgTestFixtures.validV2Package("CURL", "7.85.0", "x86_64"), null)
        val rejected = assertIs<PublishResult.Rejected>(collision)
        assertTrue(rejected.reason.contains("case-insensitive"), rejected.reason)
    }

    @Test
    fun `accepts an epoch version identifier`() {
        val svc = service()
        val bytes = ApgTestFixtures.validV2Package("pkg", "1:2.3", "x86_64")
        val success = assertIs<PublishResult.Success>(svc.publish(bytes, null))
        assertTrue(File(root, success.coordinates.relativePath).isFile)
    }
}
