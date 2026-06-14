package meigo.tulpar.server.security

import meigo.tulpar.server.config.LimitsConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-IP request rate limiting and banning.
 *
 * Ports the legacy Java RequestLimiter: a sliding window of request timestamps
 * per IP triggers an automatic ban once [LimitsConfig.maxRequestsPerWindow] is
 * exceeded within [LimitsConfig.windowMillis]; bans last
 * [LimitsConfig.banDurationMillis]. Operators can ban/unban manually.
 *
 * A clock is injected so tests are deterministic.
 */
class IpGuard(
    private val limits: LimitsConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val bannedUntil = ConcurrentHashMap<String, Long>()
    private val blockedCount = AtomicLong(0)

    /** True for loopback addresses (exempt when configured). */
    private fun isLoopback(ip: String): Boolean = Loopback.matches(ip)

    /**
     * Record a request from [ip] and decide whether to allow it.
     * @return true to allow, false to reject (rate-limited or banned).
     */
    fun allow(ip: String): Boolean {
        if (limits.exemptLoopback && isLoopback(ip)) return true
        val now = clock()

        bannedUntil[ip]?.let { until ->
            if (now < until) { blockedCount.incrementAndGet(); return false }
            bannedUntil.remove(ip)
        }

        val window = hits.computeIfAbsent(ip) { ArrayDeque() }
        synchronized(window) {
            val cutoff = now - limits.windowMillis
            while (window.isNotEmpty() && window.first() < cutoff) window.removeFirst()
            window.addLast(now)
            if (window.size > limits.maxRequestsPerWindow) {
                bannedUntil[ip] = now + limits.banDurationMillis
                window.clear()
                blockedCount.incrementAndGet()
                return false
            }
        }
        return true
    }

    /** Manually ban an IP for the configured duration. */
    fun ban(ip: String) {
        bannedUntil[ip] = clock() + limits.banDurationMillis
    }

    /** Manually lift a ban. Returns true if the IP was banned. */
    fun unban(ip: String): Boolean = bannedUntil.remove(ip) != null

    fun isBanned(ip: String): Boolean {
        val until = bannedUntil[ip] ?: return false
        if (clock() >= until) { bannedUntil.remove(ip); return false }
        return true
    }

    /** Snapshot of currently banned IPs → ban-expiry epoch millis. */
    fun banList(): Map<String, Long> {
        val now = clock()
        bannedUntil.entries.removeIf { it.value <= now }
        return bannedUntil.toMap()
    }

    /** Total requests rejected due to rate limiting or bans since startup. */
    fun blockedRequests(): Long = blockedCount.get()
}
