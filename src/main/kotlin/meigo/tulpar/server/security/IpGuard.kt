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

    @Volatile
    private var lastEviction: Long = 0

    /** Approximate number of tracked source IPs (test/inspection hook). */
    fun trackedIps(): Int = hits.size

    /** True for loopback addresses (exempt when configured). */
    private fun isLoopback(ip: String): Boolean = Loopback.matches(ip)

    /**
     * Record a request from [ip] and decide whether to allow it.
     *
     * [proxySupplied] marks addresses that came from X-Forwarded-For: the
     * loopback exemption never applies to them, so a reverse proxy running on
     * localhost cannot make every proxied client exempt from rate limiting.
     *
     * @return true to allow, false to reject (rate-limited or banned).
     */
    fun allow(ip: String, proxySupplied: Boolean = false): Boolean {
        if (limits.exemptLoopback && !proxySupplied && isLoopback(ip)) return true
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
        evictStale(now)
        return true
    }

    /**
     * Drop per-IP windows whose entries have all aged out, so the map stays
     * bounded under a flood of distinct source addresses. Runs at most once
     * per eviction interval. Removal goes through computeIfPresent, which is
     * atomic per key against a concurrent computeIfAbsent in allow() — an
     * in-flight request can never add to a detached window.
     */
    private fun evictStale(now: Long) {
        if (now - lastEviction < EVICTION_INTERVAL_MILLIS) return
        lastEviction = now
        val cutoff = now - limits.windowMillis
        for (key in hits.keys) {
            hits.computeIfPresent(key) { _, window ->
                synchronized(window) {
                    if (window.isEmpty() || window.last() < cutoff) null else window
                }
            }
        }
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

    private companion object {
        /** Stale-window sweeps run at most once per interval. */
        const val EVICTION_INTERVAL_MILLIS = 60_000L
    }
}
