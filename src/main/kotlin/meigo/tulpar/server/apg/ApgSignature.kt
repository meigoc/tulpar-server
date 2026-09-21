package meigo.tulpar.server.apg

import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.EdECPublicKeySpec
import java.security.spec.EdECPoint
import java.security.spec.EdDSAParameterSpec
import java.security.spec.NamedParameterSpec

/**
 * Detached package-signature verification matching libAPG (src/sign/sodium/ at
 * the pinned commit) and its keyring semantics (src/sign/sodium/keyring.c).
 *
 * The scheme is **Ed25519ph** (RFC 8032 prehashed Ed25519), NOT plain Ed25519.
 * libAPG signs and verifies through libsodium's streaming API
 * (`crypto_sign_init`/`update`/`final_verify`), which libsodium aliases to
 * `crypto_sign_ed25519ph_*` (crypto_sign.h:23): the signed value is
 * SHA-512(package) under the `dom2(1,"")` separator. A plain-Ed25519 verifier
 * therefore rejects every genuine libAPG signature. The JDK reaches the same
 * result with `Signature("Ed25519")` + `EdDSAParameterSpec(prehash = true)`,
 * which hashes the message internally — verified differentially against a C
 * signer that uses libAPG's exact call sequence (conformance/oracle/
 * apg_sigtool.c) and against the oracle's `keyring_verify`.
 *
 * Other libAPG facts reproduced here:
 *  - a `.sig` file is RAW 64 bytes (no armor, no key ID, no metadata);
 *  - a keyring is a directory; every `*.key` file contributes its FIRST 32
 *    bytes as a public key; shorter files are skipped; longer files are
 *    truncated (keyring.c:38-82 — reproduced deliberately; upstream quirk);
 *  - verification tries every key and succeeds on the first match; an empty or
 *    missing keyring verifies nothing (fail-closed).
 *
 * libAPG hashes the package once and tries each key over a copied hash state.
 * The JDK Signature API cannot copy Ed25519 state, so the file is re-read per
 * key; verification therefore runs against a File (publish stages uploads to a
 * temp file first). Keyrings are tiny, so the extra reads are negligible and
 * memory stays bounded.
 */
object ApgSignature {

    private val log = LoggerFactory.getLogger(ApgSignature::class.java)

    const val SIGNATURE_BYTES = 64
    const val PUBLIC_KEY_BYTES = 32

    /** Ed25519ph: verify the raw message with the JDK computing SHA-512 internally. */
    private val PREHASH = EdDSAParameterSpec(true)

    /** Outcome of a verification attempt, detailed enough for clear rejections. */
    enum class VerifyResult {
        /** Exactly 64 raw bytes that verify against some keyring key. */
        VERIFIED,

        /** The `.sig` content is not exactly 64 raw bytes. */
        MALFORMED,

        /** Well-formed signature, but no keyring key validates it (wrong key or tampered). */
        UNKNOWN_KEY_OR_BAD_SIGNATURE,

        /** No usable keys were loaded from the keyring directory. */
        EMPTY_KEYRING,
    }

    /**
     * Load a libAPG-compatible keyring: every `*.key` file in [dir], first 32
     * bytes each, in name order. Returns an empty list for a missing or
     * unreadable directory (libAPG yields an empty/NULL keyring, which verifies
     * nothing — fail-closed).
     */
    fun loadKeyring(dir: File): List<ByteArray> {
        if (!dir.isDirectory) {
            log.warn("keyring directory not found: {}", dir.path)
            return emptyList()
        }
        val keys = ArrayList<ByteArray>()
        val files = dir.listFiles()?.sortedBy { it.name } ?: return emptyList()
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(".key")) continue
            val bytes = try {
                f.readBytes()
            } catch (e: Exception) {
                log.warn("unreadable keyring file {}: {}", f.path, e.message)
                continue
            }
            // keyring.c: fread must return KEY_BYTES or the key is skipped.
            if (bytes.size < PUBLIC_KEY_BYTES) continue
            keys.add(bytes.copyOfRange(0, PUBLIC_KEY_BYTES))
        }
        return keys
    }

    /** Verify a detached signature over the exact bytes of [pkgFile]. */
    fun verifyFile(pkgFile: File, sigBytes: ByteArray, keyring: List<ByteArray>): VerifyResult {
        if (keyring.isEmpty()) return VerifyResult.EMPTY_KEYRING
        if (sigBytes.size != SIGNATURE_BYTES) return VerifyResult.MALFORMED
        val keys = keyring.mapNotNull { decodePublicKey(it) }
        if (keys.isEmpty()) return VerifyResult.UNKNOWN_KEY_OR_BAD_SIGNATURE
        for (publicKey in keys) {
            val ok = pkgFile.inputStream().buffered().use { input ->
                runCatching {
                    val sig = Signature.getInstance("Ed25519")
                    sig.setParameter(PREHASH)
                    sig.initVerify(publicKey)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        sig.update(buf, 0, r)
                    }
                    sig.verify(sigBytes)
                }.getOrDefault(false)
            }
            if (ok) return VerifyResult.VERIFIED
        }
        return VerifyResult.UNKNOWN_KEY_OR_BAD_SIGNATURE
    }

    /** Verify a detached signature over in-memory package bytes (tests, small payloads). */
    fun verifyBytes(pkgBytes: ByteArray, sigBytes: ByteArray, keyring: List<ByteArray>): VerifyResult {
        if (keyring.isEmpty()) return VerifyResult.EMPTY_KEYRING
        if (sigBytes.size != SIGNATURE_BYTES) return VerifyResult.MALFORMED
        val keys = keyring.mapNotNull { decodePublicKey(it) }
        for (publicKey in keys) {
            val ok = runCatching {
                val sig = Signature.getInstance("Ed25519")
                sig.setParameter(PREHASH)
                sig.initVerify(publicKey)
                sig.update(pkgBytes)
                sig.verify(sigBytes)
            }.getOrDefault(false)
            if (ok) return VerifyResult.VERIFIED
        }
        return VerifyResult.UNKNOWN_KEY_OR_BAD_SIGNATURE
    }

    /**
     * Build an Ed25519 [PublicKey] from a raw 32-byte little-endian encoding,
     * the on-disk libsodium format (`crypto_sign_PUBLICKEYBYTES`). Returns null
     * if the bytes cannot be interpreted, so a corrupt keyring entry does not
     * abort verification against the remaining keys.
     */
    fun decodePublicKey(raw: ByteArray): PublicKey? {
        if (raw.size != PUBLIC_KEY_BYTES) return null
        // libsodium stores y little-endian with the x sign bit in the MSB of the
        // last byte; EdECPoint wants (xOdd, y) with y an unsigned big-endian value.
        val big = raw.reversedArray()
        val xOdd = (big[0].toInt() and 0x80) != 0
        big[0] = (big[0].toInt() and 0x7F).toByte()
        val point = EdECPoint(xOdd, BigInteger(1, big))
        return runCatching {
            KeyFactory.getInstance("Ed25519")
                .generatePublic(EdECPublicKeySpec(NamedParameterSpec.ED25519, point))
        }.getOrNull()
    }
}
