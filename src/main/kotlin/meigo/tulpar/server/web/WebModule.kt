package meigo.tulpar.server.web

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.Version
import meigo.tulpar.server.metrics.MetricsCollector
import meigo.tulpar.server.security.installRateLimiting
import meigo.tulpar.server.security.installRequestLog
import java.io.File

/**
 * Root Ktor module for Tulpar Server 2.0. Installs JSON content negotiation and
 * status pages, then mounts the v2 REST routes, downloads, and static content.
 *
 * Security plugins (rate limiting, download limiting) are installed by
 * [meigo.tulpar.server.security.installSecurity] which M4 wires in here.
 */
fun Application.tulparModule(ctx: ServerContext) {
    if (ctx.config.server.httpsRedirect) {
        install(io.ktor.server.plugins.httpsredirect.HttpsRedirect) {
            sslPort = ctx.config.server.tls.port
            permanentRedirect = true
        }
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            encodeDefaults = true
            explicitNulls = true
        })
    }

    // HEAD for every GET, byte-range resume for file downloads, and
    // conditional GET (ETag/If-None-Match → 304) for cacheable responses.
    install(io.ktor.server.plugins.autohead.AutoHeadResponse)
    install(io.ktor.server.plugins.partialcontent.PartialContent)
    install(io.ktor.server.plugins.conditionalheaders.ConditionalHeaders)

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", cause.message),
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            // Serve a custom error page if present, else a JSON 404.
            val page = File("errors/404.html")
            if (page.isFile) call.respondFile(page)
            else call.respond(status, ErrorResponse("not_found", "resource not found"))
        }
    }

    // M4 inserts security plugins here.
    securityHook(ctx)

    routing {
        get("/") {
            val index = File("static/index.html")
            if (index.isFile) call.respondFile(index)
            else call.respondText("Welcome to ${Version.SERVER_NAME}", ContentType.Text.Html)
        }

        get("/favicon.ico") {
            val favicon = File("favicon.ico")
            if (favicon.isFile) call.respondFile(favicon) else call.respond(HttpStatusCode.NotFound)
        }

        get("/api/v2/health") {
            val ready = ctx.repository.isReady()
            val channels = ctx.repository.channels()
            val status = if (ready) "ok" else "starting"
            val code = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(code, HealthResponse(status, ready, ctx.repository.entries().size, channels))
        }

        get("/api/v2/version") {
            call.respond(
                VersionResponse(
                    server = Version.SERVER_NAME,
                    version = Version.VALUE,
                    api = "v2",
                    format = meigo.tulpar.server.repo.RepoData.FORMAT,
                    libapgTarget = Version.LIBAPG_TARGET,
                ),
            )
        }

        get("/metrics") {
            val snap = MetricsCollector(ctx) { ctx.requestLog.totalCount() }.snapshot()
            call.respond(snap)
        }

        staticFiles("/static", File("static"))

        packageRoutes(ctx)
        downloadRoutes(ctx)
        publishRoutes(ctx)
    }
}

/**
 * Install security plugins: per-IP rate limiting / ban guard as an early
 * intercept. Download concurrency + throughput limiting is applied per-request
 * inside the download routes via [ServerContext.downloadLimiter].
 */
internal fun Application.securityHook(ctx: ServerContext) {
    installRateLimiting(ctx)
    installRequestLog(ctx)
}
