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
    /** Allow overwriting an existing package build. */
    val allowOverwrite: Boolean = false,
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
