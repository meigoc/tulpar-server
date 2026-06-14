package meigo.tulpar.server.web

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsRoutesTest {

    private val root: File = WebTestSupport.tempRepo()

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    @Test
    fun `metrics endpoint returns a snapshot`() = testApplication {
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64")
        val ctx = WebTestSupport.context(root)
        application { tulparModule(ctx) }

        val resp = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertContains(body, "packages")
        assertContains(body, "heapUsedBytes")
    }

    @Test
    fun `requests are recorded in the request log`() = testApplication {
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64")
        val ctx = WebTestSupport.context(root)
        application { tulparModule(ctx) }

        client.get("/api/v2/health")
        client.get("/api/v2/packages")

        assertTrue(ctx.requestLog.totalCount() >= 2)
        val uris = ctx.requestLog.recent().map { it.uri }
        assertTrue(uris.any { it.contains("/api/v2/health") })
        assertTrue(uris.any { it.contains("/api/v2/packages") })
    }
}
