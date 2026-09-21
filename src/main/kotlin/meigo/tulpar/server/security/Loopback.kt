package meigo.tulpar.server.security

/**
 * Recognises loopback hosts, by IPv4/IPv6 literal or the `localhost`
 * hostname, including the IPv4-mapped IPv6 form a dual-stack socket reports
 * (`::ffff:127.0.0.1`).
 */
object Loopback {
    fun matches(host: String): Boolean {
        val h = host.trim()
        val normalized = when {
            h.startsWith("::ffff:") || h.startsWith("::FFFF:") -> h.substring(7)
            else -> h
        }
        return normalized == "127.0.0.1" ||
            normalized == "::1" ||
            normalized == "0:0:0:0:0:0:0:1" ||
            normalized.startsWith("127.") ||
            normalized.equals("localhost", ignoreCase = true)
    }
}
