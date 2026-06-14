package meigo.tulpar.server.security

import meigo.tulpar.server.config.LimitsConfig
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
