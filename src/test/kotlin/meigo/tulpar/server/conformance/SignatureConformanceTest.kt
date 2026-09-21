package meigo.tulpar.server.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import meigo.tulpar.server.apg.ApgSignature
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Differential signature tests.
 *
 * Part 1 (hermetic): replay the recorded libAPG oracle verdicts
 * (`goldens-signatures.json`, produced by `apg_oracle keyring` over vectors
 * signed with libAPG's exact libsodium streaming sequence via `apg_sigtool`)
 * and assert the JVM verifier reaches the same accept/reject decision for
 * every scenario — valid, wrong-key, any-key-signs, malleable S, tampered
 * payload, truncated sig, empty payload, empty keyring, short key file,
 * 40-byte key file (truncation quirk), missing keyring dir.
 *
 * Part 2 (keyring loader semantics): the Kotlin keyring loader must mirror
 * keyring.c byte handling (32-byte cut, short-file skip, name ordering).
 *
 * Vectors are Ed25519ph signatures (see ApgSignature KDoc); a plain-Ed25519
 * verifier would fail every one of them, which is itself asserted.
 */
class SignatureConformanceTest {

    private val json = Json {}

    private fun sigDir(): File {
        val url = javaClass.classLoader.getResource("conformance/signatures/payload.bin")
            ?: fail("signature fixtures missing")
        return File(url.toURI()).parentFile
    }

    private fun loadGoldens(): List<GoldenCase> {
        val stream = javaClass.classLoader.getResourceAsStream("conformance/goldens-signatures.json")
            ?: fail("goldens-signatures.json missing")
        val root = json.parseToJsonElement(stream.use { it.readBytes().decodeToString() })
        return root.jsonArray.map { item ->
            val o = item.jsonObject
            GoldenCase(
                name = o["case"]!!.jsonPrimitive.content,
                keyring = o["keyring"]!!.jsonPrimitive.content,
                pkg = o["pkg"]!!.jsonPrimitive.content,
                sig = o["sig"]!!.jsonPrimitive.content,
                loaded = o["oracle"]!!.jsonObject["keyring_loaded"]!!.jsonPrimitive.boolean,
                verified = o["oracle"]!!.jsonObject["verified"]!!.jsonPrimitive.boolean,
            )
        }
    }

    private data class GoldenCase(
        val name: String,
        val keyring: String,
        val pkg: String,
        val sig: String,
        val loaded: Boolean,
        val verified: Boolean,
    )

    @Test
    fun `JVM verifier matches the libAPG oracle on every signature scenario`() {
        val base = sigDir()
        val failures = ArrayList<String>()
        for (c in loadGoldens()) {
            val ringDir = File(base, c.keyring)
            val keyring = if (ringDir.isDirectory) ApgSignature.loadKeyring(ringDir) else emptyList()
            val sigBytes = File(base, c.sig).readBytes()
            val result = ApgSignature.verifyFile(File(base, c.pkg), sigBytes, keyring)

            val serverVerified = result == ApgSignature.VerifyResult.VERIFIED
            if (serverVerified != c.verified) {
                failures.add("${c.name}: oracle verified=${c.verified} server=$serverVerified ($result)")
            }
        }
        if (failures.isNotEmpty()) fail("signature differential mismatches:\n  " + failures.joinToString("\n  "))
    }

    @Test
    fun `genuine libAPG signatures are Ed25519ph and plain Ed25519 rejects them`() {
        // Regression guard for the D-015 discovery: a plain-Ed25519 verifier
        // (no prehash parameter) must NOT accept the libAPG-signed payload.
        val base = sigDir()
        val keyring = ApgSignature.loadKeyring(File(base, "keyring-a"))
        val sigBytes = File(base, "payload.sig").readBytes()
        val pkgBytes = File(base, "payload.bin").readBytes()

        assertEquals(ApgSignature.VerifyResult.VERIFIED, ApgSignature.verifyBytes(pkgBytes, sigBytes, keyring))

        val pub = ApgSignature.decodePublicKey(keyring[0])!!
        val plain = java.security.Signature.getInstance("Ed25519")
        plain.initVerify(pub)
        plain.update(pkgBytes)
        assertEquals(false, plain.verify(sigBytes), "plain Ed25519 must reject an Ed25519ph signature")
    }

    @Test
    fun `keyring loader mirrors libAPG byte handling`() {
        val base = sigDir()

        // 31-byte key file: skipped (keyring.c fread != KEY_BYTES).
        assertTrue(ApgSignature.loadKeyring(File(base, "keyring-short")).isEmpty())

        // 40-byte key file: first 32 bytes used (libAPG truncation quirk).
        val truncated = ApgSignature.loadKeyring(File(base, "keyring-long"))
        assertEquals(1, truncated.size)
        assertEquals(32, truncated[0].size)

        // Missing directory: empty keyring, verifies nothing (fail-closed).
        assertTrue(ApgSignature.loadKeyring(File(base, "keyring-nonexistent")).isEmpty())
        assertEquals(
            ApgSignature.VerifyResult.EMPTY_KEYRING,
            ApgSignature.verifyBytes("x".toByteArray(), ByteArray(64), emptyList()),
        )

        // Signature length is enforced: 63 or 65 raw bytes are malformed.
        val ring = ApgSignature.loadKeyring(File(base, "keyring-a"))
        assertEquals(
            ApgSignature.VerifyResult.MALFORMED,
            ApgSignature.verifyBytes(File(base, "payload.bin").readBytes(), ByteArray(63), ring),
        )
        assertEquals(
            ApgSignature.VerifyResult.MALFORMED,
            ApgSignature.verifyBytes(File(base, "payload.bin").readBytes(), ByteArray(65), ring),
        )
    }
}
