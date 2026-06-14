package meigo.tulpar.server.security

/** Recognises loopback hosts, by IP literal or the `localhost` hostname. */
object Loopback {
    fun matches(host: String): Boolean =
        host == "127.0.0.1" ||
            host == "::1" ||
            host == "0:0:0:0:0:0:0:1" ||
            host.startsWith("127.") ||
            host.equals("localhost", ignoreCase = true)
}
