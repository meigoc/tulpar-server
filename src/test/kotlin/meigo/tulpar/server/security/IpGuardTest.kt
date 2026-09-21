package meigo.tulpar.server.security

import meigo.tulpar.server.config.LimitsConfig
import meigo.tulpar.server.security.sanitizeForLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpGuardTest {

    private var now = 1_000_000L
    private fun guard(max: Int = 3, window: Long = 1000, ban: Long = 5000, exemptLoopback: Boolean = false) =
        IpGuard(
            LimitsConfig(
                maxRequestsPerWindow = max,
                windowMillis = window,
                banDurationMillis = ban,
                exemptLoopback = exemptLoopback,
            ),
        ) { now }

    @Test
    fun `allows up to the limit then bans`() {
        val g = guard(max = 3)
        val ip = "1.2.3.4"
        assertTrue(g.allow(ip))
        assertTrue(g.allow(ip))
        assertTrue(g.allow(ip))
        // 4th within window exceeds maxRequestsPerWindow=3
        assertFalse(g.allow(ip))
        assertTrue(g.isBanned(ip))
    }

    @Test
    fun `ban expires after duration`() {
        val g = guard(max = 1, ban = 5000)
        val ip = "5.6.7.8"
        assertTrue(g.allow(ip))
        assertFalse(g.allow(ip)) // banned
        now += 5001
        assertTrue(g.allow(ip)) // ban expired
    }

    @Test
    fun `sliding window forgets old hits`() {
        val g = guard(max = 2, window = 1000)
        val ip = "9.9.9.9"
        assertTrue(g.allow(ip))
        assertTrue(g.allow(ip))
        now += 1001 // both hits now outside the window
        assertTrue(g.allow(ip))
        assertTrue(g.allow(ip))
    }

    @Test
    fun `manual ban and unban`() {
        val g = guard()
        val ip = "10.0.0.1"
        g.ban(ip)
        assertTrue(g.isBanned(ip))
        assertFalse(g.allow(ip))
        assertTrue(g.unban(ip))
        assertTrue(g.allow(ip))
    }

    @Test
    fun `loopback exemption`() {
        val g = guard(max = 1, exemptLoopback = true)
        repeat(10) { assertTrue(g.allow("127.0.0.1")) }
        repeat(10) { assertTrue(g.allow("::1")) }
    }

    @Test
    fun `ban list reports active bans`() {
        val g = guard(max = 1)
        g.allow("a"); g.allow("a") // bans a
        assertTrue(g.banList().containsKey("a"))
    }

    @Test
    fun `blocked request counter increments`() {
        val g = guard(max = 1)
        g.allow("x"); g.allow("x"); g.allow("x")
        assertEquals(2, g.blockedRequests())
    }
}

class IpGuardBoundsTest {

    private var now = 1_000_000L
    private fun guard(max: Int = 100, window: Long = 60_000, exemptLoopback: Boolean = false) =
        IpGuard(
            LimitsConfig(
                maxRequestsPerWindow = max,
                windowMillis = window,
                banDurationMillis = 1000,
                exemptLoopback = exemptLoopback,
            ),
        ) { now }

    @Test
    fun `stale windows are evicted so tracked IPs stay bounded`() {
        val g = guard(max = 5, window = 1000)
        repeat(2000) { i -> g.allow("10.0.${i / 256}.${i % 256}") }
        assertTrue(g.trackedIps() > 0)
        // Advance past both the window and the eviction interval; the next
        // allow() triggers a sweep.
        now += 61_000
        g.allow("10.9.9.9")
        assertEquals(1, g.trackedIps(), "all stale windows must be evicted, leaving only the fresh one")
    }

    @Test
    fun `proxy-supplied loopback is not exempt`() {
        val g = guard(max = 1, exemptLoopback = true)
        // TCP loopback (trusted): exempt, unlimited.
        repeat(10) { assertTrue(g.allow("127.0.0.1", proxySupplied = false)) }
        // XFF-claimed loopback (untrusted): limited like any other client.
        assertTrue(g.allow("127.0.0.1", proxySupplied = true))
        assertFalse(g.allow("127.0.0.1", proxySupplied = true))
    }

    @Test
    fun `ipv4-mapped ipv6 loopback is recognized`() {
        val g = guard(max = 1, exemptLoopback = true)
        repeat(5) { assertTrue(g.allow("::ffff:127.0.0.1")) }
    }

    @Test
    fun `distinct ips are limited independently`() {
        val g = guard(max = 1)
        assertTrue(g.allow("1.1.1.1"))
        assertFalse(g.allow("1.1.1.1"))
        assertTrue(g.allow("2.2.2.2"))
        assertFalse(g.allow("2.2.2.2"))
    }
}

class LoopbackTest {
    @Test
    fun `recognizes ipv4 and ipv6 loopback forms`() {
        assertTrue(Loopback.matches("127.0.0.1"))
        assertTrue(Loopback.matches("127.5.5.5"))
        assertTrue(Loopback.matches("::1"))
        assertTrue(Loopback.matches("0:0:0:0:0:0:0:1"))
        assertTrue(Loopback.matches("localhost"))
        assertTrue(Loopback.matches("::ffff:127.0.0.1"))
    }

    @Test
    fun `non-loopback addresses do not match`() {
        assertFalse(Loopback.matches("8.8.8.8"))
        assertFalse(Loopback.matches("2001:db8::1"))
        assertFalse(Loopback.matches("::ffff:8.8.8.8"))
        assertFalse(Loopback.matches("128.0.0.1"))
    }
}

class SanitizeLogTest {
    @Test
    fun `control characters are replaced`() {
        assertEquals("a_b_c", sanitizeForLog("a\nb\rc"))
        assertEquals("GET_x", sanitizeForLog("GET\u0000x"))
        assertEquals("plain/path?q=1", sanitizeForLog("plain/path?q=1"))
        assertEquals("del_7f", sanitizeForLog("del\u007F7f"))
    }
}
