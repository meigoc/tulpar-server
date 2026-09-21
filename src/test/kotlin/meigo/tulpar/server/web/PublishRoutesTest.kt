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
import kotlin.test.assertTrue

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
            uploadForm(ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")),
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
    fun `upload with a present-but-invalid signature is rejected`() = testApplication {
        // Security invariant: the server never stores a package whose attached
        // signature does not verify, even when signatures are optional.
        application { tulparModule(ctx()) }
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64"), sig = "sig".toByteArray()),
        ) { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
    }

    @Test
    fun `upload with a keyring-verified signature succeeds`() = testApplication {
        val keyring = java.nio.file.Files.createTempDirectory("tulpar-web-keyring").toFile()
        try {
            val kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val spec = java.security.KeyFactory.getInstance("Ed25519")
                .getKeySpec(kp.public, java.security.spec.EdECPublicKeySpec::class.java)
            val y = spec.point.y.toByteArray()
            val raw = ByteArray(32)
            val srcOff = maxOf(0, y.size - 32)
            System.arraycopy(y, srcOff, raw, 32 - (y.size - srcOff), y.size - srcOff)
            for (i in 0 until 16) {
                val t = raw[i]; raw[i] = raw[31 - i]; raw[31 - i] = t
            }
            if (spec.point.isXOdd) raw[31] = (raw[31].toInt() or 0x80).toByte()
            File(keyring, "web.key").writeBytes(raw)

            val publish = PublishConfig(
                enabled = true, tokens = listOf(token), validate = true,
                allowOverwrite = true, keyringDir = keyring.path,
            )
            val config = TulparConfig(publish = publish)
                .copy(repo = TulparConfig().repo.copy(root = root.path))
            val repo = Repository(root).apply { reindex() }

            application { tulparModule(ServerContext(config, repo)) }
            val pkg = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
            val sig = java.security.Signature.getInstance("Ed25519").apply {
                setParameter(java.security.spec.EdDSAParameterSpec(true))
                initSign(kp.private)
                update(pkg)
            }.sign()

            val resp = client.submitFormWithBinaryData(
                "/api/v2/packages",
                uploadForm(pkg, sig = sig),
            ) { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.Created, resp.status)

            // .sig is served back verbatim
            val served = client.get("/api/v2/download/main/curl/7.85.0/x86_64.sig")
            assertEquals(HttpStatusCode.OK, served.status)
            assertEquals(sig.toList(), served.readBytes().toList())
        } finally {
            keyring.deleteRecursively()
        }
    }

    @Test
    fun `non-archive payload is rejected with 400 by the magic preflight`() = testApplication {
        application { tulparModule(ctx()) }
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm("garbage".toByteArray()),
        ) { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `structurally valid archive failing libAPG policy is rejected with 422`() = testApplication {
        application { tulparModule(ctx()) }
        // A real tar.xz whose metadata has no name/version: recognizable
        // archive (passes the preflight) but not indexable.
        val pkg = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to """{"description":"no identity"}""".toByteArray(),
                "data/usr/bin/x" to "y".toByteArray(),
            ),
        )
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(pkg),
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

class PublishLimitsTest {

    private val root: File = WebTestSupport.tempRepo()
    private val token = "secret-token"

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    private fun ctxWithUploadCap(capBytes: Long): ServerContext {
        val publish = PublishConfig(
            enabled = true, tokens = listOf(token), validate = true,
            allowOverwrite = true, maxUploadBytes = capBytes,
        )
        val config = TulparConfig(publish = publish).copy(repo = TulparConfig().repo.copy(root = root.path))
        val repo = Repository(root).apply { reindex() }
        return ServerContext(config, repo)
    }

    private fun uploadForm(pkg: ByteArray) = formData {
        append("apg", pkg, Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"pkg.apg\"")
        })
    }

    @Test
    fun `upload over the size cap returns 413`() = testApplication {
        val pkg = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        application { tulparModule(ctxWithUploadCap(capBytes = pkg.size.toLong() / 2)) }
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(pkg),
        ) { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun `failed uploads leave no temp files behind`() = testApplication {
        val pkg = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        application { tulparModule(ctxWithUploadCap(capBytes = pkg.size.toLong() / 2)) }
        client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(pkg),
        ) { header(HttpHeaders.Authorization, "Bearer $token") }

        val staging = File(root, ".tmp")
        val leftovers = if (staging.isDirectory) staging.listFiles()?.toList() ?: emptyList() else emptyList()
        assertEquals(emptyList(), leftovers, "staging dir must be empty after a rejected upload")
        // nothing indexed either
        assertEquals(0, Repository(root).apply { reindex() }.entries().size)
    }

    @Test
    fun `successful publish leaves no temp files behind`() = testApplication {
        application { tulparModule(ctxWithUploadCap(capBytes = 10L * 1024 * 1024)) }
        val pkg = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            uploadForm(pkg),
        ) { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Created, resp.status)

        val staging = File(root, ".tmp")
        val leftovers = if (staging.isDirectory) staging.listFiles()?.toList() ?: emptyList() else emptyList()
        assertEquals(emptyList(), leftovers, "staging dir must be empty after a successful publish")
        // the package itself is in the pool, byte-identical
        val stored = File(root, "pool/main/curl/x86_64/curl-7.85.0-x86_64.apg")
        assertTrue(stored.isFile)
        assertEquals(pkg.toList(), stored.readBytes().toList())
    }
}

class PublishStatusCodesTest {

    private val root: File = WebTestSupport.tempRepo()
    private val token = "secret-token"

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    private fun ctx(): ServerContext {
        val publish = PublishConfig(enabled = true, tokens = listOf(token), validate = true, allowOverwrite = false)
        val config = TulparConfig(publish = publish).copy(repo = TulparConfig().repo.copy(root = root.path))
        val repo = Repository(root).apply { reindex() }
        return ServerContext(config, repo)
    }

    private fun uploadForm(pkg: ByteArray) = formData {
        append("apg", pkg, Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"pkg.apg\"")
        })
    }

    @Test
    fun `duplicate publish returns 409 conflict`() = testApplication {
        application { tulparModule(ctx()) }
        val pkg = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val first = client.submitFormWithBinaryData("/api/v2/packages", uploadForm(pkg)) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Created, first.status)
        val second = client.submitFormWithBinaryData("/api/v2/packages", uploadForm(pkg)) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertContains(second.bodyAsText(), "conflict")
    }

    @Test
    fun `rate limiting responds with the uniform JSON error model`() = testApplication {
        val limits = meigo.tulpar.server.config.LimitsConfig(
            maxRequestsPerWindow = 1, banDurationMillis = 60_000, exemptLoopback = false,
        )
        val config = TulparConfig(limits = limits).copy(repo = TulparConfig().repo.copy(root = root.path))
        application {
            tulparModule(
                ServerContext(config, Repository(root).apply { reindex() }, meigo.tulpar.server.security.IpGuard(limits)),
            )
        }
        client.get("/api/v2/health")
        val banned = client.get("/api/v2/health")
        assertEquals(HttpStatusCode.TooManyRequests, banned.status)
        assertContains(banned.bodyAsText(), "\"error\":\"rate_limited\"")
    }
}

class PublishMultipartAbuseTest {

    private val root: File = WebTestSupport.tempRepo()
    private val token = "secret-token"

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    private fun ctx(): ServerContext {
        val publish = PublishConfig(enabled = true, tokens = listOf(token), validate = true, allowOverwrite = true)
        val config = TulparConfig(publish = publish).copy(repo = TulparConfig().repo.copy(root = root.path))
        return ServerContext(config, Repository(root).apply { reindex() })
    }

    @Test
    fun `oversized non-file form field is rejected without buffering it all`() = testApplication {
        application { tulparModule(ctx()) }
        val pkg = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val huge = "z".repeat(200_000) // > the 64 KiB form-field cap
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            formData {
                append("apg", pkg, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"pkg.apg\"")
                })
                append("channel", huge)
            },
        ) { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
        // nothing was indexed or staged
        assertEquals(0, Repository(root).apply { reindex() }.entries().size)
        val staging = File(root, ".tmp")
        assertTrue(
            !staging.isDirectory || (staging.listFiles()?.isEmpty() ?: true),
            "staging dir must be empty after rejection",
        )
    }

    @Test
    fun `declared Content-Length beyond caps is rejected up front with 413`() = testApplication {
        application { tulparModule(ctx()) }
        // Ktor's test client recomputes Content-Length from the body, so the
        // declared-length precheck is exercised with a raw request whose CL
        // header is set by hand on a channel body (CL is trusted, body small).
        val resp = client.post("/api/v2/packages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=xyz")
            header(HttpHeaders.ContentLength, (9L * 1024 * 1024 * 1024).toString())
            setBody(io.ktor.utils.io.ByteReadChannel("--xyz--\r\n"))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun `two package parts in one upload is a 400`() = testApplication {
        application { tulparModule(ctx()) }
        val pkg = ApgTestFixtures.validV2Package("curl", "7.85.0", "x86_64")
        val resp = client.submitFormWithBinaryData(
            "/api/v2/packages",
            formData {
                append("apg", pkg, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"a.apg\"")
                })
                append("package", pkg, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"b.apg\"")
                })
            },
        ) { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `non-multipart body is a 400`() = testApplication {
        application { tulparModule(ctx()) }
        val resp = client.post("/api/v2/packages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, "application/octet-stream")
            setBody(ByteArray(100))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
