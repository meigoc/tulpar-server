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

class WebModuleTest {

    private val root: File = WebTestSupport.tempRepo()

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    private fun testRepo(block: suspend ApplicationTestBuilder.() -> Unit) {
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64", withSig = true)
        WebTestSupport.place(root, "apgexample", "0.0.0", null)
        val ctx = WebTestSupport.context(root)
        testApplication {
            application { tulparModule(ctx) }
            block()
        }
    }

    @Test
    fun `health reports package count`() = testRepo {
        val resp = client.get("/api/v2/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertContains(body, "\"status\":\"ok\"")
        assertContains(body, "\"packages\":2")
    }

    @Test
    fun `version endpoint`() = testRepo {
        val body = client.get("/api/v2/version").bodyAsText()
        assertContains(body, "2.0.0")
        assertContains(body, "tulpar-repodata/2")
    }

    @Test
    fun `list returns all packages`() = testRepo {
        val body = client.get("/api/v2/packages").bodyAsText()
        assertContains(body, "\"count\":2")
        assertContains(body, "curl")
        assertContains(body, "apgexample")
    }

    @Test
    fun `list filters by arch`() = testRepo {
        val body = client.get("/api/v2/packages?arch=x86_64").bodyAsText()
        assertContains(body, "curl")
        assertTrue(!body.contains("apgexample"))
    }

    @Test
    fun `search by query`() = testRepo {
        val body = client.get("/api/v2/packages?q=curl").bodyAsText()
        assertContains(body, "curl")
        assertTrue(!body.contains("apgexample"))
    }

    @Test
    fun `package detail lists builds`() = testRepo {
        val resp = client.get("/api/v2/packages/curl")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertContains(body, "7.85.0")
        assertContains(body, "sha256")
    }

    @Test
    fun `unknown package detail is 404`() = testRepo {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v2/packages/nope").status)
    }

    @Test
    fun `repodata json served at both paths`() = testRepo {
        val a = client.get("/repodata.json").bodyAsText()
        val b = client.get("/api/v2/repodata").bodyAsText()
        assertContains(a, "tulpar-repodata/2")
        assertContains(b, "curl")
    }

    @Test
    fun `download streams the apg`() = testRepo {
        val resp = client.get("/api/v2/download/main/curl/7.85.0/x86_64")
        assertEquals(HttpStatusCode.OK, resp.status)
        val disp = resp.headers[HttpHeaders.ContentDisposition]
        assertContains(disp ?: "", "curl-7.85.0-x86_64.apg")
        assertTrue(resp.readRawBytes().isNotEmpty())
    }

    @Test
    fun `download signature when present`() = testRepo {
        val resp = client.get("/api/v2/download/main/curl/7.85.0/x86_64.sig")
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `download missing signature is 404`() = testRepo {
        // apgexample has no .sig
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v2/download/main/apgexample/0.0.0/noarch.sig").status,
        )
    }

    @Test
    fun `download unknown package is 404`() = testRepo {
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v2/download/main/ghost/1.0/x86_64").status,
        )
    }

    @Test
    fun `path traversal in segment is rejected`() = testRepo {
        // encoded ".." should not escape; resolves to 404, never a file outside pool
        val resp = client.get("/api/v2/download/main/..%2f..%2f..%2fetc/passwd/x86_64")
        assertTrue(resp.status == HttpStatusCode.NotFound || resp.status == HttpStatusCode.BadRequest)
    }
}
