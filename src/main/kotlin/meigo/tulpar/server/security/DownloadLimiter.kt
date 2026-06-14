package meigo.tulpar.server.security

import meigo.tulpar.server.config.LimitsConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-IP download concurrency and throughput limiting.
 *
 * Ports the legacy PackageDownloadManager:
 *   - at most [LimitsConfig.maxDownloadsPerIP] concurrent downloads per IP
 *     (callers emit HTTP 429 when [tryAcquire] returns false),
 *   - throughput capped at [LimitsConfig.maxDownloadSpeed] bytes/sec per IP
 *     via a simple token bucket ([throttleDelayMillis] tells the streamer how
 *     long to sleep before sending the next chunk; 0 = no limit).
 */
class DownloadLimiter(
    private val limits: LimitsConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val active = ConcurrentHashMap<String, AtomicInteger>()
    private val totalDownloads = AtomicLong(0)
    private val rejected = AtomicLong(0)

    private fun exempt(ip: String): Boolean = limits.exemptLoopback && Loopback.matches(ip)

    /** Try to start a download for [ip]; false if the per-IP cap is reached. */
    fun tryAcquire(ip: String): Boolean {
        if (exempt(ip)) { totalDownloads.incrementAndGet(); return true }
        var acquired = false
        // compute() holds the bin lock, so the cap check + increment is atomic
        // with respect to release() (which also uses compute) — the per-IP limit
        // can never be transiently exceeded.
        active.compute(ip) { _, existing ->
            val counter = existing ?: AtomicInteger(0)
            if (counter.get() >= limits.maxDownloadsPerIP) {
                counter // unchanged; acquired stays false
            } else {
                counter.incrementAndGet()
                acquired = true
                counter
            }
        }
        if (acquired) totalDownloads.incrementAndGet() else rejected.incrementAndGet()
        return acquired
    }

    /** Release a previously acquired download slot. */
    fun release(ip: String) {
        if (exempt(ip)) return
        // compute() holds the bin lock so decrement-to-zero and removal are atomic
        // w.r.t. a concurrent computeIfAbsent in tryAcquire (no lost counter).
        active.compute(ip) { _, counter ->
            if (counter == null) null
            else if (counter.decrementAndGet() <= 0) null else counter
        }
    }

    val maxDownloadSpeed: Long get() = limits.maxDownloadSpeed
    val bufferSize: Int get() = limits.bufferSize

    fun activeDownloads(): Int = active.values.sumOf { it.get() }
    fun totalDownloads(): Long = totalDownloads.get()
    fun rejectedDownloads(): Long = rejected.get()

    /** True if throughput limiting is active. */
    fun throttled(ip: String): Boolean = !exempt(ip) && limits.maxDownloadSpeed > 0

    /**
     * A per-(IP,stream) token bucket. The streamer calls [reserve] before each
     * chunk; the returned value is how many millis to sleep to respect the rate.
     */
    inner class Bucket(private val ip: String) {
        private var windowStart = clock()
        private var bytesThisWindow = 0L

        /** @return sleep millis to honour the rate before sending [chunk] bytes. */
        fun reserve(chunk: Int): Long {
            if (!throttled(ip)) return 0
            val rate = limits.maxDownloadSpeed
            val now = clock()
            val elapsed = now - windowStart
            if (elapsed >= 1000) {
                windowStart = now
                bytesThisWindow = 0
            }
            if (bytesThisWindow + chunk > rate) {
                val wait = 1000 - elapsed
                windowStart = now + wait
                bytesThisWindow = chunk.toLong()
                return wait.coerceAtLeast(0)
            }
            bytesThisWindow += chunk
            return 0
        }
    }

    fun bucketFor(ip: String): Bucket = Bucket(ip)
}
