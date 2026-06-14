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
import meigo.tulpar.server.security.installRateLimiting
import java.io.File

/**
 * Root Ktor module for Tulpar Server 2.0. Installs JSON content negotiation and
 * status pages, then mounts the v2 REST routes, downloads, and static content.
 *
 * Security plugins (rate limiting, download limiting) are installed by
 * [meigo.tulpar.server.security.installSecurity] which M4 wires in here.
 */
fun Application.tulparModule(ctx: ServerContext) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            encodeDefaults = true
            explicitNulls = true
        })
    }

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
            val channels = ctx.repository.channels()
            call.respond(HealthResponse("ok", ctx.repository.entries().size, channels))
        }

        get("/api/v2/version") {
            call.respond(VersionResponse(Version.SERVER_NAME, Version.VALUE, meigo.tulpar.server.repo.RepoData.FORMAT))
        }

        staticFiles("/static", File("static"))

        packageRoutes(ctx)
        downloadRoutes(ctx)
    }
}

/**
 * Install security plugins: per-IP rate limiting / ban guard as an early
 * intercept. Download concurrency + throughput limiting is applied per-request
 * inside the download routes via [ServerContext.downloadLimiter].
 */
internal fun Application.securityHook(ctx: ServerContext) {
    installRateLimiting(ctx)
}
