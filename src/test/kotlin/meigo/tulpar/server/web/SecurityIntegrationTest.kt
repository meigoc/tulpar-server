package meigo.tulpar.server.web

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.server.testing.*
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.config.LimitsConfig
import meigo.tulpar.server.config.ServerConfig
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

class DownloadsAndProxyTest {

    private val root: File = WebTestSupport.tempRepo()

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    private fun ctx(behindProxy: Boolean = false, limits: LimitsConfig = LimitsConfig()): ServerContext {
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64")
        val config = TulparConfig(server = ServerConfig(behindProxy = behindProxy), limits = limits)
            .copy(repo = TulparConfig().repo.copy(root = root.path))
        val repo = Repository(root).apply { reindex() }
        return ServerContext(config, repo)
    }

    @Test
    fun `HEAD download returns headers without body`() = testApplication {
        application { tulparModule(ctx()) }
        val resp = client.head("/api/v2/download/main/curl/7.85.0/x86_64")
        assertEquals(HttpStatusCode.OK, resp.status)
        val len = resp.headers[HttpHeaders.ContentLength]?.toLong()
        assertTrue(len != null && len > 0, "Content-Length must be set: $len")
        assertEquals(0, resp.bodyAsBytes().size)
    }

    @Test
    fun `Range request resumes a download with 206`() = testApplication {
        application { tulparModule(ctx()) }
        val full = client.get("/api/v2/download/main/curl/7.85.0/x86_64").bodyAsBytes()
        val resp = client.get("/api/v2/download/main/curl/7.85.0/x86_64") {
            header(HttpHeaders.Range, "bytes=10-19")
        }
        assertEquals(HttpStatusCode.PartialContent, resp.status)
        assertEquals(full.copyOfRange(10, 20).toList(), resp.bodyAsBytes().toList())
    }

    @Test
    fun `conditional GET on repodata uses ETag and returns 304`() = testApplication {
        application { tulparModule(ctx()) }
        val first = client.get("/api/v2/repodata")
        assertEquals(HttpStatusCode.OK, first.status)
        val etag = first.headers[HttpHeaders.ETag]
        assertTrue(etag != null, "ETag expected on repodata")
        val second = client.get("/api/v2/repodata") { header(HttpHeaders.IfNoneMatch, etag!!) }
        assertEquals(HttpStatusCode.NotModified, second.status)
    }

    @Test
    fun `XFF rightmost hop is used behind a proxy and ignored otherwise`() = testApplication {
        // behindProxy=true: the rightmost XFF entry is the proxy-vouched peer.
        val limits = LimitsConfig(maxRequestsPerWindow = 2, exemptLoopback = false)
        application { tulparModule(ctx(behindProxy = true, limits = limits)) }
        // Two requests "from" 1.2.3.4 (rightmost), then it is banned; a
        // different rightmost address keeps working (proves per-IP keying).
        repeat(2) {
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v2/health") { header("X-Forwarded-For", "9.9.9.9, 1.2.3.4") }.status,
            )
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get("/api/v2/health") { header("X-Forwarded-For", "spoofed, 1.2.3.4") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v2/health") { header("X-Forwarded-For", "1.2.3.4, 5.6.7.8") }.status,
        )
    }

    @Test
    fun `spoofed XFF is ignored when not behind a proxy`() = testApplication {
        val limits = LimitsConfig(maxRequestsPerWindow = 2, exemptLoopback = false)
        application { tulparModule(ctx(behindProxy = false, limits = limits)) }
        // All requests come from the test client's socket IP regardless of XFF.
        repeat(2) {
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v2/health") { header("X-Forwarded-For", "8.8.8.8") }.status,
            )
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get("/api/v2/health") { header("X-Forwarded-For", "9.9.9.9") }.status,
        )
    }

    @Test
    fun `proxied loopback traffic is NOT exempt from rate limiting`() = testApplication {
        // The reverse proxy runs on localhost; every client would claim
        // 127.0.0.1 through XFF if the exemption applied to proxy headers.
        val limits = LimitsConfig(maxRequestsPerWindow = 2, exemptLoopback = true)
        application { tulparModule(ctx(behindProxy = true, limits = limits)) }
        repeat(2) {
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v2/health") { header("X-Forwarded-For", "127.0.0.1") }.status,
            )
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get("/api/v2/health") { header("X-Forwarded-For", "127.0.0.1") }.status,
        )
    }
}

class ProxyEdgeCasesTest {

    private val root: File = WebTestSupport.tempRepo()

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    @Test
    fun `missing XFF behind proxy does not grant loopback exemption`() = testApplication {
        // A proxy that fails to append XFF must not turn every request into
        // an exempt loopback client.
        val limits = LimitsConfig(maxRequestsPerWindow = 2, exemptLoopback = true)
        val config = TulparConfig(server = ServerConfig(behindProxy = true), limits = limits)
            .copy(repo = TulparConfig().repo.copy(root = root.path))
        application { tulparModule(ServerContext(config, Repository(root).apply { reindex() })) }
        repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/api/v2/health").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/v2/health").status)
    }

    @Test
    fun `multiple XFF header lines are all considered`() = testApplication {
        // A proxy appending a separate header line instead of merging must
        // still yield the rightmost (proxy-vouched) address.
        val limits = LimitsConfig(maxRequestsPerWindow = 2, exemptLoopback = false)
        val config = TulparConfig(server = ServerConfig(behindProxy = true), limits = limits)
            .copy(repo = TulparConfig().repo.copy(root = root.path))
        application { tulparModule(ServerContext(config, Repository(root).apply { reindex() })) }
        repeat(2) {
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v2/health") {
                    header("X-Forwarded-For", "spoofed.by.client")
                    header("X-Forwarded-For", "7.7.7.7")
                }.status,
            )
        }
        // Third request: the rightmost line's address (7.7.7.7) is banned.
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get("/api/v2/health") {
                header("X-Forwarded-For", "other.spoof")
                header("X-Forwarded-For", "7.7.7.7")
            }.status,
        )
    }
}

class IndexIdentifierGuardTest {

    @Test
    fun `pool contents with hostile identifiers are excluded at index time`() {
        val root = WebTestSupport.tempRepo()
        try {
            // Simulate out-of-band placement: a valid package whose metadata
            // name contains a space (not URL/FS-safe per the allowlist).
            val dir = File(root, "pool/main/hostile name/x86_64")
            dir.mkdirs()
            File(dir, "pkg-1-x86_64.apg").writeBytes(
                meigo.tulpar.server.apg.ApgTestFixtures.tarZstd(
                    linkedMapOf(
                        "metadata.json" to """{"name":"hostile name","version":"1","architecture":"x86_64"}""".toByteArray(),
                        "data/usr/bin/x" to "y".toByteArray(),
                    ),
                ),
            )
            // A well-formed package for contrast.
            WebTestSupport.place(root, "good", "1.0", "x86_64")

            val repo = meigo.tulpar.server.repo.Repository(root)
            repo.reindex()
            val names = repo.entries().map { it.name }
            assertEquals(listOf("good"), names)
        } finally {
            root.deleteRecursively()
        }
    }
}
