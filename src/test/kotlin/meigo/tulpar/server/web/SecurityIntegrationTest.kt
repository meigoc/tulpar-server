package meigo.tulpar.server.web

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.config.LimitsConfig
import meigo.tulpar.server.config.TulparConfig
import meigo.tulpar.server.repo.Repository
import meigo.tulpar.server.security.DownloadLimiter
import meigo.tulpar.server.security.IpGuard
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityIntegrationTest {

    private val root: File = WebTestSupport.tempRepo()

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    private fun ctxWith(limits: LimitsConfig): ServerContext {
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64")
        val config = TulparConfig(limits = limits).copy(repo = TulparConfig().repo.copy(root = root.path))
        val repo = Repository(root).apply { reindex() }
        return ServerContext(config, repo, IpGuard(limits), DownloadLimiter(limits))
    }

    @Test
    fun `rate limit returns 429 after threshold`() = testApplication {
        val limits = LimitsConfig(
            maxRequestsPerWindow = 3,
            windowMillis = 60_000,
            banDurationMillis = 60_000,
            exemptLoopback = false,
        )
        application { tulparModule(ctxWith(limits)) }

        // First 3 allowed, 4th banned.
        repeat(3) { assertEquals(HttpStatusCode.OK, client.get("/api/v2/health").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/v2/health").status)
    }

    @Test
    fun `loopback exemption disables rate limit`() = testApplication {
        val limits = LimitsConfig(maxRequestsPerWindow = 1, exemptLoopback = true)
        application { tulparModule(ctxWith(limits)) }
        repeat(5) { assertEquals(HttpStatusCode.OK, client.get("/api/v2/health").status) }
    }

    @Test
    fun `download concurrency cap returns 429 when slots exhausted`() = testApplication {
        // Pre-fill the limiter so the next acquire fails, proving the route emits 429.
        val limits = LimitsConfig(maxDownloadsPerIP = 1, exemptLoopback = false)
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64")
        val config = TulparConfig(limits = limits).copy(repo = TulparConfig().repo.copy(root = root.path))
        val repo = Repository(root).apply { reindex() }
        val limiter = DownloadLimiter(limits)
        val ctx = ServerContext(config, repo, IpGuard(limits.copy(exemptLoopback = true)), limiter)
        application { tulparModule(ctx) }

        // Occupy the only slot for the test client's IP, then attempt a download.
        val ip = "localhost"
        assertTrue(limiter.tryAcquire(ip))
        val resp = client.get("/api/v2/download/main/curl/7.85.0/x86_64")
        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
    }
}
