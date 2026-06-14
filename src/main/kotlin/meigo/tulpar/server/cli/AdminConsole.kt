package meigo.tulpar.server.cli

import com.github.ajalt.mordant.rendering.TextColors
import io.ktor.server.engine.*
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.metrics.MetricsCollector
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Interactive admin console that runs on stdin while the server is up. Replaces
 * the legacy reflection-based command dispatch with an explicit registry.
 */
class AdminConsole(
    private val ctx: ServerContext,
    private val engine: EmbeddedServer<*, *>,
    private val metrics: MetricsCollector,
    private val onRestart: () -> Unit = {},
) {
    private data class Command(val help: String, val run: (List<String>) -> Unit)

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val commands = LinkedHashMap<String, Command>()

    init {
        register("help", "list commands") { printHelp() }
        register("packages", "show indexed package count and names") {
            val e = ctx.repository.entries()
            println("${e.size} package(s):")
            e.forEach { println("  ${it.channel}/${it.name} ${it.version} (${it.arch})${if (it.signed) " [signed]" else ""}") }
        }
        register("reindex", "rescan the pool directory") {
            val n = ctx.repository.reindex()
            println("reindexed: $n package(s)")
        }
        register("metrics", "print a metrics snapshot") { println(metrics.snapshot().format()) }
        register("log", "show recent requests (log [n])") { args ->
            val n = args.firstOrNull()?.toIntOrNull() ?: 50
            ctx.requestLog.recent(n).forEach {
                println("${timeFmt.format(Instant.ofEpochMilli(it.epochMillis))} ${it.ip} ${it.method} ${it.uri} -> ${it.status}")
            }
        }
        register("ban", "ban an IP (ban <ip>)") { args ->
            val ip = args.firstOrNull() ?: return@register println("usage: ban <ip>")
            ctx.ipGuard.ban(ip); println("banned $ip")
        }
        register("unban", "lift a ban (unban <ip>)") { args ->
            val ip = args.firstOrNull() ?: return@register println("usage: unban <ip>")
            println(if (ctx.ipGuard.unban(ip)) "unbanned $ip" else "$ip was not banned")
        }
        register("banlist", "list currently banned IPs") {
            val bans = ctx.ipGuard.banList()
            if (bans.isEmpty()) println("no banned IPs")
            else bans.forEach { (ip, until) -> println("  $ip until ${timeFmt.format(Instant.ofEpochMilli(until))}") }
        }
        register("version", "show server version") { println(meigo.tulpar.server.Version.SERVER_NAME) }
        register("restart", "restart the server") { onRestart() }
        register("stop", "stop the server and exit") { shutdown() }
    }

    private fun register(name: String, help: String, run: (List<String>) -> Unit) {
        commands[name] = Command(help, run)
    }

    private fun printHelp() {
        println("Commands:")
        commands.forEach { (name, cmd) -> println("  %-10s %s".format(name, cmd.help)) }
    }

    private fun shutdown() {
        println("stopping…")
        metrics.stop()
        engine.stop(500, 1500)
        Runtime.getRuntime().halt(0)
    }

    /** Read-eval loop over stdin; blocks until EOF or `stop`. */
    fun run() {
        printHelp()
        val reader = System.`in`.bufferedReader()
        while (true) {
            print(TextColors.gray("tulpar> "))
            System.out.flush()
            val line = reader.readLine() ?: break
            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val cmd = commands[parts[0].lowercase()]
            if (cmd == null) println("unknown command: ${parts[0]} (try 'help')")
            else runCatching { cmd.run(parts.drop(1)) }.onFailure { println("error: ${it.message}") }
        }
    }
}
