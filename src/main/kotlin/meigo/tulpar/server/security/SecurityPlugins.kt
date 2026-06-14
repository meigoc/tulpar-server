package meigo.tulpar.server.security

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.util.RequestRecord

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

/**
 * Record every request to the bounded in-memory [ServerContext.requestLog] once
 * the response status is known (drives the `log` admin command and metrics).
 */
fun Application.installRequestLog(ctx: ServerContext) {
    val behindProxy = ctx.config.server.behindProxy
    sendPipeline.intercept(io.ktor.server.response.ApplicationSendPipeline.After) {
        val status = call.response.status()?.value ?: 0
        ctx.requestLog.record(
            RequestRecord(
                epochMillis = System.currentTimeMillis(),
                ip = call.clientIp(behindProxy),
                method = call.request.httpMethod.value,
                uri = call.request.uri,
                status = status,
            ),
        )
    }
}
