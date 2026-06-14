package meigo.tulpar.server.security

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import meigo.tulpar.server.ServerContext

/**
 * Resolve the client IP for an [ApplicationCall].
 *
 * When the server is configured to sit behind a known reverse proxy, trust the
 * first hop of X-Forwarded-For; otherwise use the socket's remote host so a
 * client cannot spoof its address to dodge bans.
 */
fun ApplicationCall.clientIp(behindProxy: Boolean): String {
    if (behindProxy) {
        request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    return request.local.remoteHost
}

/**
 * Install the request rate limiter / IP ban guard as an early intercept.
 *
 * Rejected requests get HTTP 429 with a short plain-text reason — matching the
 * legacy server's behaviour but with a correct status code (the old server
 * returned 200 with a body).
 */
fun Application.installRateLimiting(ctx: ServerContext) {
    val behindProxy = ctx.config.server.behindProxy
    intercept(ApplicationCallPipeline.Plugins) {
        val ip = call.clientIp(behindProxy)
        if (!ctx.ipGuard.allow(ip)) {
            call.respondText(
                "Too many requests — IP temporarily blocked.",
                status = HttpStatusCode.TooManyRequests,
            )
            finish()
        }
    }
}
