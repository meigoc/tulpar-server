package meigo.tulpar.server.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addFileSource
import com.sksamuel.hoplite.addResourceSource
import java.io.File

/**
 * Server configuration (HOCON via Hoplite). Reworked for 2.0: grouped into
 * server / repo / limits / publish / metrics / tls / cli sections.
 */
data class TulparConfig(
    val server: ServerConfig = ServerConfig(),
    val repo: RepoConfig = RepoConfig(),
    val limits: LimitsConfig = LimitsConfig(),
    val publish: PublishConfig = PublishConfig(),
    val metrics: MetricsConfig = MetricsConfig(),
    val cli: CliConfig = CliConfig(),
)

data class ServerConfig(
    val address: String = "0.0.0.0",
    val port: Int = 8080,
    val runInBackground: Boolean = false,
    val httpsRedirect: Boolean = false,
    val tls: TlsConfig = TlsConfig(),
    /** Trust X-Forwarded-For (only enable behind a known reverse proxy). */
    val behindProxy: Boolean = false,
)

data class TlsConfig(
    val enabled: Boolean = false,
    val port: Int = 8443,
    val keyStorePath: String = "",
    val keyStorePassword: String = "",
    val keyAlias: String = "tulpar",
    val privateKeyPassword: String = "",
)

data class RepoConfig(
    /** Root directory holding pool/ and the generated repodata.json. */
    val root: String = "./repo-data",
    val defaultChannel: String = "main",
    /** Reindex the pool on startup. */
    val reindexOnStart: Boolean = true,
)

data class LimitsConfig(
    /** Max requests per IP within [windowMillis] before an automatic ban. */
    val maxRequestsPerWindow: Int = 120,
    val windowMillis: Long = 60_000,
    /** How long an automatically/manually banned IP stays blocked. */
    val banDurationMillis: Long = 60_000,
    /** Max concurrent downloads per IP (HTTP 429 beyond this). */
    val maxDownloadsPerIP: Int = 4,
    /** Max download throughput per IP in bytes/sec (0 = unlimited). */
    val maxDownloadSpeed: Long = 0,
    /** Stream buffer size in bytes. */
    val bufferSize: Int = 64 * 1024,
    /** Loopback addresses bypass rate limiting and bans. */
    val exemptLoopback: Boolean = true,
)

data class PublishConfig(
    val enabled: Boolean = false,
    /** Bearer tokens accepted for publish/delete. */
    val tokens: List<String> = emptyList(),
    /** Reject packages whose validation produces errors. */
    val validate: Boolean = true,
    /** Require a detached .sig alongside uploaded packages. */
    val requireSignature: Boolean = false,
    /**
     * Directory of trusted signing keys for signature verification
     * (libAPG keyring format: `*.key` files holding 32 raw Ed25519 public
     * key bytes). Required when [requireSignature] is true; when set, any
     * accompanying .sig is verified even if signatures are optional.
     */
    val keyringDir: String = "",
    /** Allow overwriting an existing package build. */
    val allowOverwrite: Boolean = false,
    /** Hard cap on a single uploaded .apg payload, in bytes. */
    val maxUploadBytes: Long = 4L * 1024 * 1024 * 1024,
    /** Hard cap on an uploaded .sig payload, in bytes. */
    val maxSignatureBytes: Long = 4096,
)

data class MetricsConfig(
    val enabled: Boolean = true,
    val intervalMillis: Long = 300_000,
)

data class CliConfig(
    val color: String = "#cccccc",
    val hello: List<String> = emptyList(),
)

object ConfigFactory {
    fun load(configFile: File): TulparConfig {
        val builder = ConfigLoaderBuilder.default()
        if (configFile.exists()) {
            builder.addFileSource(configFile)
        }
        builder.addResourceSource("/application.conf")
        return builder.build().loadConfigOrThrow<TulparConfig>()
    }
}

/**
 * Startup-time validation: refuses configurations that cannot serve safely
 * and reports every problem at once. Warnings (insecure-but-working setups)
 * are returned separately so the CLI can print them loudly without refusing.
 */
object ConfigValidation {

    /** Minimum accepted publish token length. */
    const val MIN_TOKEN_LENGTH = 32

    /** Keystore passwords that ship with tooling defaults and must be flagged. */
    private val DEFAULT_KEYSTORE_PASSWORDS = setOf("changeit", "changeme", "")

    data class Result(val errors: List<String>, val warnings: List<String>) {
        val valid: Boolean get() = errors.isEmpty()
    }

    fun validate(config: TulparConfig): Result {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        with(config.server) {
            if (port !in 1..65535) errors.add("server.port out of range (1..65535): $port")
            if (tls.enabled) {
                if (tls.port !in 1..65535) errors.add("server.tls.port out of range: ${tls.port}")
                if (tls.keyStorePath.isBlank()) errors.add("server.tls.enabled but keyStorePath is empty")
                if (tls.keyStorePassword in DEFAULT_KEYSTORE_PASSWORDS) {
                    warnings.add("server.tls uses a default/empty keystore password — set a strong one")
                }
                if (tls.privateKeyPassword in DEFAULT_KEYSTORE_PASSWORDS) {
                    warnings.add("server.tls uses a default/empty private-key password — set a strong one")
                }
            }
            if (httpsRedirect && !tls.enabled) {
                errors.add("server.httpsRedirect=true requires server.tls.enabled=true")
            }
        }

        with(config.repo) {
            if (root.isBlank()) errors.add("repo.root is empty")
        }

        with(config.limits) {
            if (maxRequestsPerWindow < 1) errors.add("limits.maxRequestsPerWindow must be >= 1")
            if (windowMillis < 1) errors.add("limits.windowMillis must be >= 1")
            if (banDurationMillis < 0) errors.add("limits.banDurationMillis must be >= 0")
            if (maxDownloadsPerIP < 1) errors.add("limits.maxDownloadsPerIP must be >= 1")
            if (maxDownloadSpeed < 0) errors.add("limits.maxDownloadSpeed must be >= 0")
            if (bufferSize < 1024) errors.add("limits.bufferSize must be >= 1024")
        }

        with(config.publish) {
            if (enabled) {
                if (tokens.isEmpty()) {
                    errors.add("publish.enabled=true but no tokens are configured — publishing would be wide open; set publish.tokens (env substitution like \${TULPAR_PUBLISH_TOKEN} is supported)")
                }
                for ((i, token) in tokens.withIndex()) {
                    if (token.isNotBlank() && token.length < MIN_TOKEN_LENGTH) {
                        errors.add("publish.tokens[$i] is shorter than $MIN_TOKEN_LENGTH characters")
                    }
                }
                if (requireSignature && keyringDir.isBlank()) {
                    errors.add("publish.requireSignature=true requires publish.keyringDir (a directory of *.key files)")
                }
            }
            if (maxUploadBytes < 1) errors.add("publish.maxUploadBytes must be >= 1")
            if (maxSignatureBytes < 64) errors.add("publish.maxSignatureBytes must be >= 64")
        }

        with(config.metrics) {
            if (enabled && intervalMillis < 1000) errors.add("metrics.intervalMillis must be >= 1000 when metrics are enabled")
        }

        return Result(errors, warnings)
    }
}
