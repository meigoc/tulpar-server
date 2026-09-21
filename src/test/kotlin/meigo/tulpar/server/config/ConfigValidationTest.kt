package meigo.tulpar.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigValidationTest {

    private fun base(publish: PublishConfig = PublishConfig()) = TulparConfig(publish = publish)

    @Test
    fun `defaults are valid`() {
        assertTrue(ConfigValidation.validate(TulparConfig()).valid)
    }

    @Test
    fun `publish enabled without tokens is a startup error`() {
        val result = ConfigValidation.validate(base(PublishConfig(enabled = true, tokens = emptyList())))
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("no tokens") }, result.errors.toString())
    }

    @Test
    fun `short tokens are rejected`() {
        val result = ConfigValidation.validate(
            base(PublishConfig(enabled = true, tokens = listOf("short"))),
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains(ConfigValidation.MIN_TOKEN_LENGTH.toString()) })
    }

    @Test
    fun `long tokens pass`() {
        val token = "t".repeat(ConfigValidation.MIN_TOKEN_LENGTH)
        assertTrue(ConfigValidation.validate(base(PublishConfig(enabled = true, tokens = listOf(token)))).valid)
    }

    @Test
    fun `requireSignature without keyringDir is an error`() {
        val token = "t".repeat(ConfigValidation.MIN_TOKEN_LENGTH)
        val result = ConfigValidation.validate(
            base(PublishConfig(enabled = true, tokens = listOf(token), requireSignature = true)),
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("keyringDir") })
    }

    @Test
    fun `default keystore passwords produce a loud warning not an error`() {
        val config = TulparConfig(
            server = ServerConfig(
                tls = TlsConfig(enabled = true, keyStorePath = "/tmp/ks.p12", keyStorePassword = "changeit"),
            ),
        )
        val result = ConfigValidation.validate(config)
        assertTrue(result.valid, result.errors.toString())
        assertTrue(result.warnings.any { it.contains("keystore password") }, result.warnings.toString())
    }

    @Test
    fun `httpsRedirect without tls is an error`() {
        val result = ConfigValidation.validate(
            TulparConfig(server = ServerConfig(httpsRedirect = true, tls = TlsConfig(enabled = false))),
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("httpsRedirect") })
    }

    @Test
    fun `bad ports and limits are errors`() {
        val result = ConfigValidation.validate(
            TulparConfig(
                server = ServerConfig(port = 70000),
                limits = LimitsConfig(maxRequestsPerWindow = 0, bufferSize = 10),
            ),
        )
        assertFalse(result.valid)
        assertEquals(true, result.errors.any { it.contains("server.port") })
        assertEquals(true, result.errors.any { it.contains("maxRequestsPerWindow") })
        assertEquals(true, result.errors.any { it.contains("bufferSize") })
    }

    @Test
    fun `all problems are reported at once`() {
        val result = ConfigValidation.validate(
            TulparConfig(
                server = ServerConfig(port = -1),
                repo = RepoConfig(root = ""),
                publish = PublishConfig(enabled = true),
            ),
        )
        assertTrue(result.errors.size >= 3, result.errors.toString())
    }

    @Test
    fun `production deployment config validates cleanly`() {
        // The live network1 configuration must keep starting under 2.0.0.
        val token = "x".repeat(51)
        val config = TulparConfig(
            server = ServerConfig(
                address = "127.0.0.1", port = 8080, runInBackground = true,
                httpsRedirect = false, behindProxy = true, tls = TlsConfig(enabled = false),
            ),
            repo = RepoConfig(root = "/opt/tulpar/repo-data", defaultChannel = "main", reindexOnStart = true),
            limits = LimitsConfig(
                maxRequestsPerWindow = 300, windowMillis = 60000, banDurationMillis = 60000,
                maxDownloadsPerIP = 20, maxDownloadSpeed = 0, bufferSize = 65536, exemptLoopback = true,
            ),
            publish = PublishConfig(
                enabled = true, tokens = listOf(token), validate = false,
                requireSignature = false, allowOverwrite = false,
            ),
            metrics = MetricsConfig(enabled = true, intervalMillis = 300000),
        )
        val result = ConfigValidation.validate(config)
        assertTrue(result.valid, result.errors.toString())
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }
}

class ConfigUnknownKeysTest {

    @Test
    fun `unknown keys are detected and known keys are not flagged`() {
        val file = java.nio.file.Files.createTempFile("tulpar-conf", ".conf").toFile()
        try {
            file.writeText(
                """
                server {
                    port = 9090
                    behindProxy = true
                    adresss = "typo.example"
                }
                publish {
                    enabled = false
                    tokenz = ["typo"]
                }
                limts {
                    bufferSize = 4096
                }
                """.trimIndent(),
            )
            val unknown = ConfigFactory.unknownKeys(file)
            assertEquals(listOf("limts.bufferSize", "publish.tokenz", "server.adresss"), unknown)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing file yields no warnings`() {
        assertEquals(emptyList(), ConfigFactory.unknownKeys(java.io.File("/nonexistent-tulpar.conf")))
    }
}

class ConfigValidationEdgeTest {

    @Test
    fun `blank token entries are rejected`() {
        val result = ConfigValidation.validate(
            TulparConfig(publish = PublishConfig(enabled = true, tokens = listOf("x".repeat(32), ""))),
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("blank") }, result.errors.toString())
    }

    @Test
    fun `maxSignatureBytes upper bound prevents Int overflow`() {
        val result = ConfigValidation.validate(
            TulparConfig(
                publish = PublishConfig(
                    enabled = true,
                    tokens = listOf("x".repeat(32)),
                    maxSignatureBytes = 3L * 1024 * 1024 * 1024,
                ),
            ),
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("maxSignatureBytes") }, result.errors.toString())
    }
}
