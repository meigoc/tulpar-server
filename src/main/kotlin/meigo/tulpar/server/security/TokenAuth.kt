package meigo.tulpar.server.security

import meigo.tulpar.server.config.PublishConfig
import java.security.MessageDigest

/**
 * Bearer-token authorisation for publish/delete operations.
 *
 * Tokens come from [PublishConfig.tokens]. Comparison is constant-time to avoid
 * leaking token length/prefix via timing. The legacy server had no auth at all
 * on its (read-only) endpoints; write access here is gated.
 */
class TokenAuth(private val publish: PublishConfig) {

    private val tokenDigests: Set<String> =
        publish.tokens.filter { it.isNotBlank() }.map { sha256(it) }.toSet()

    val enabled: Boolean get() = publish.enabled
    val hasTokens: Boolean get() = tokenDigests.isNotEmpty()

    /** True if [authorizationHeader] carries a valid `Bearer <token>`. */
    fun authorize(authorizationHeader: String?): Boolean {
        if (!publish.enabled || tokenDigests.isEmpty()) return false
        val header = authorizationHeader?.trim() ?: return false
        if (!header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return false
        val token = header.substring(7).trim()
        if (token.isEmpty()) return false
        val candidate = sha256(token)
        // Constant-time membership check over digests.
        var match = false
        for (d in tokenDigests) {
            if (constantTimeEquals(d, candidate)) match = true
        }
        return match
    }

    private fun sha256(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray()
        val bb = b.toByteArray()
        if (ab.size != bb.size) return false
        var result = 0
        for (i in ab.indices) result = result or (ab[i].toInt() xor bb[i].toInt())
        return result == 0
    }
}
