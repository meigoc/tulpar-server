package meigo.tulpar.server.web

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.apg.ApgTestFixtures
import meigo.tulpar.server.config.PublishConfig
import meigo.tulpar.server.config.TulparConfig
import meigo.tulpar.server.repo.Repository
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PublishRoutesTest {

    private val root: File = WebTestSupport.tempRepo()
    private val token = "secret-token"

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    private fun ctx(enabled: Boolean = true, tokens: List<String> = listOf(token)): ServerContext {
        val publish = PublishConfig(enabled = enabled, tokens = tokens, validate = true, allowOverwrite = true)
        val config = TulparConfig(publish = publish).copy(repo = TulparConfig().repo.copy(root = root.path))
        val repo = Repository(root).apply { reindex() }
        return ServerContext(config, repo)
    }

    private fun uploadForm(pkg: ByteArray, sig: ByteArray? = null, channel: String? = null) =
        formData {
            append("apg", pkg, Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"pkg.apg\"")
            })
            if (sig != null) append("sig", sig, Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"pkg.apg.sig\"")
            })
            if (channel != null) append("channel", channel)
        }

    @Test
    fun `upload requires auth`() = testApplication {
        application { tulparModule(ctx()) }
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(ApgTestFixtures.validV2Package("curl", "1.0.0", "x86_64")),
        )
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `upload rejected when publishing disabled`() = testApplication {
        application { tulparModule(ctx(enabled = false)) }
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(ApgTestFixtures.validV2Package("curl", "1.0.0", "x86_64")),
        ) { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `valid upload with token publishes and appears in index`() = testApplication {
        application { tulparModule(ctx()) }
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64"), sig = "sig".toByteArray()),
        ) { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.Created, resp.status)
        val body = resp.bodyAsText()
        assertContains(body, "pool/main/curl/x86_64/curl-7.85.0-x86_64.apg")

        // now visible via the read API
        val list = client.get("/api/v2/packages").bodyAsText()
        assertContains(list, "curl")
        // and downloadable
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v2/download/main/curl/7.85.0/x86_64").status,
        )
    }

    @Test
    fun `invalid package is rejected with 422`() = testApplication {
        application { tulparModule(ctx()) }
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm("garbage".toByteArray()),
        ) { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
    }

    @Test
    fun `wrong token is unauthorized`() = testApplication {
        application { tulparModule(ctx()) }
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(ApgTestFixtures.validV2Package("curl", "1.0.0", "x86_64")),
        ) { header(HttpHeaders.Authorization, "Bearer wrong") }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `delete removes a published package`() = testApplication {
        application { tulparModule(ctx()) }
        client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")),
        ) { header(HttpHeaders.Authorization, "Bearer $token") }

        val del = client.delete("/api/v2/packages/main/curl/7.85.0/x86_64") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, del.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v2/download/main/curl/7.85.0/x86_64").status,
        )
    }
}
