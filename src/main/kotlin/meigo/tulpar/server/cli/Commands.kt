package meigo.tulpar.server.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.Version
import meigo.tulpar.server.config.ConfigFactory
import meigo.tulpar.server.config.TulparConfig
import meigo.tulpar.server.repo.Repository
import meigo.tulpar.server.web.tulparModule
import org.slf4j.LoggerFactory
import java.io.File

internal val terminal = Terminal()
internal val logger = LoggerFactory.getLogger("meigo.tulpar.server")

/** Root command — does nothing on its own; dispatches to subcommands. */
class TulparCommand : CliktCommand(name = "tulpar-server") {
    override fun run() = Unit
}

class VersionCommand : CliktCommand(name = "version") {
    override fun help(context: Context) = "Show version info"
    override fun run() {
        terminal.println("${Version.SERVER_NAME} (repodata ${meigo.tulpar.server.repo.RepoData.FORMAT})")
    }
}

class StartCommand : CliktCommand(name = "start") {
    override fun help(context: Context) = "Start the Tulpar Server"

    private val port by option("-p", "--port", help = "Port to bind to").int()
    private val configPath by option("-c", "--config", help = "Path to application.conf")
        .file(mustExist = false, canBeDir = false)
        .default(File("application.conf"))
    private val daemon by option("-d", "--daemon", help = "Run without blocking (detached)").flag()

    override fun run() {
        try {
            val config = ConfigFactory.load(configPath)
            val ctx = buildContext(config)

            val finalPort = port ?: config.server.port
            val finalHost = config.server.address
            printBanner(config)

            if (config.repo.reindexOnStart) {
                val n = ctx.repository.reindex()
                logger.info("startup index: {} package(s) in {}", n, ctx.repository.root)
            }

            logger.info("starting server on {}:{}", finalHost, finalPort)
            val server = embeddedServer(Netty, port = finalPort, host = finalHost) {
                tulparModule(ctx)
            }
            server.start(wait = !daemon || !config.server.runInBackground)
        } catch (e: Exception) {
            terminal.println(TextColors.red("Fatal Error: ${e.message}"))
            logger.error("failed to start server", e)
        }
    }

    private fun printBanner(config: TulparConfig) {
        config.cli.hello.forEach { line -> terminal.println(TextColors.rgb(config.cli.color)(line)) }
        terminal.println(TextStyles.bold(Version.SERVER_NAME))
        terminal.println(TextColors.gray("OS: ${System.getProperty("os.name")} | Arch: ${System.getProperty("os.arch")}"))
        terminal.println(TextColors.gray("=".repeat(terminal.size.width.coerceAtMost(60))))
    }
}

/** Build the shared runtime context (config + repository) from a config. */
internal fun buildContext(config: TulparConfig): ServerContext {
    val repo = Repository(File(config.repo.root))
    return ServerContext(config, repo)
}
