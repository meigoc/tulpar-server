package meigo.tulpar.server.security

import meigo.tulpar.server.config.LimitsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadLimiterTest {

    private var now = 0L
    private fun limiter(maxPerIp: Int = 2, speed: Long = 0, exemptLoopback: Boolean = false) =
        DownloadLimiter(
            LimitsConfig(
                maxDownloadsPerIP = maxPerIp,
                maxDownloadSpeed = speed,
                bufferSize = 1024,
                exemptLoopback = exemptLoopback,
            ),
        ) { now }

    @Test
    fun `concurrency cap enforced per IP`() {
        val l = limiter(maxPerIp = 2)
        val ip = "1.1.1.1"
        assertTrue(l.tryAcquire(ip))
        assertTrue(l.tryAcquire(ip))
        assertFalse(l.tryAcquire(ip)) // 3rd blocked
        l.release(ip)
        assertTrue(l.tryAcquire(ip)) // slot freed
    }

    @Test
    fun `different IPs are independent`() {
        val l = limiter(maxPerIp = 1)
        assertTrue(l.tryAcquire("a"))
        assertTrue(l.tryAcquire("b"))
        assertFalse(l.tryAcquire("a"))
    }

    @Test
    fun `loopback exempt from cap`() {
        val l = limiter(maxPerIp = 1, exemptLoopback = true)
        assertTrue(l.tryAcquire("127.0.0.1"))
        assertTrue(l.tryAcquire("127.0.0.1"))
    }

    @Test
    fun `no throttle when speed is zero`() {
        val l = limiter(speed = 0)
        assertFalse(l.throttled("1.2.3.4"))
        assertEquals(0, l.bucketFor("1.2.3.4").reserve(10_000))
    }

    @Test
    fun `token bucket delays when exceeding rate`() {
        val l = limiter(speed = 1000) // 1000 bytes/sec
        val ip = "2.2.2.2"
        assertTrue(l.throttled(ip))
        val b = l.bucketFor(ip)
        assertEquals(0, b.reserve(800))   // under budget
        val wait = b.reserve(800)         // would exceed 1000 in this window
        assertTrue(wait > 0, "expected a positive wait, got $wait")
    }

    @Test
    fun `counters track downloads and rejections`() {
        val l = limiter(maxPerIp = 1)
        l.tryAcquire("x")
        l.tryAcquire("x") // rejected
        assertEquals(1, l.totalDownloads())
        assertEquals(1, l.rejectedDownloads())
    }
}
