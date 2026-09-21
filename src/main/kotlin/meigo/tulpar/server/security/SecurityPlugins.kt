package meigo.tulpar.server.security

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.util.RequestRecord

/** Resolved client address: the value plus whether it came from a proxy header. */
data class ClientAddress(val ip: String, val fromProxyHeader: Boolean)

/**
 * Resolve the client IP for an [ApplicationCall].
 *
 * Without `behindProxy`, X-Forwarded-For is ignored entirely — the TCP peer is
 * the only trustworthy source and a client could otherwise spoof its address
 * to dodge bans.
 *
 * With `behindProxy`, the RIGHTMOST entry of the full X-Forwarded-For chain is
 * used. Some proxies append a separate XFF header line rather than merging
 * into one comma-list, so ALL XFF lines are joined first; a correct proxy
 * appends the TCP peer it saw as the last value, which is the one the trusted
 * proxy vouches for. Everything left of it is client-claimed and spoofable.
 *
 * When `behindProxy` is set but no XFF header is present (a misconfigured
 * proxy), the TCP peer is used and marked proxy-derived, so the loopback
 * exemption does NOT apply — a localhost proxy cannot make all traffic
 * limit-exempt.
 *
 * Loopback exemption is only honored for TCP-derived addresses.
 */
fun ApplicationCall.clientAddress(behindProxy: Boolean): ClientAddress {
    if (behindProxy) {
        val xff = request.headers.getAll("X-Forwarded-For")
            ?.joinToString(",")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.lastOrNull()
        // A proxied request with no usable XFF still counts as proxy-supplied
        // so the loopback exemption cannot be claimed for proxied traffic.
        return ClientAddress(xff ?: request.local.remoteHost, fromProxyHeader = true)
    }
    return ClientAddress(request.local.remoteHost, fromProxyHeader = false)
}

/**
 * Install the request rate limiter / IP ban guard as an early intercept.
 *
 * Rejected requests get HTTP 429 with the uniform JSON error model.
 */
fun Application.installRateLimiting(ctx: ServerContext) {
    val behindProxy = ctx.config.server.behindProxy
    intercept(ApplicationCallPipeline.Plugins) {
        val addr = call.clientAddress(behindProxy)
        if (!ctx.ipGuard.allow(addr.ip, proxySupplied = addr.fromProxyHeader)) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                meigo.tulpar.server.web.ErrorResponse(
                    "rate_limited",
                    "too many requests — IP temporarily blocked",
                ),
            )
            finish()
        }
    }
}

/**
 * Record every request to the bounded in-memory [ServerContext.requestLog] once
 * the response status is known (drives the `log` admin command and metrics).
 *
 * The URI is user-controlled and lands in logs: control characters are
 * stripped so a crafted request line cannot forge log entries (log injection).
 */
fun Application.installRequestLog(ctx: ServerContext) {
    val behindProxy = ctx.config.server.behindProxy
    sendPipeline.intercept(io.ktor.server.response.ApplicationSendPipeline.After) {
        val status = call.response.status()?.value ?: 0
        val addr = call.clientAddress(behindProxy)
        ctx.requestLog.record(
            RequestRecord(
                epochMillis = System.currentTimeMillis(),
                ip = sanitizeForLog(addr.ip),
                method = call.request.httpMethod.value,
                uri = sanitizeForLog(call.request.uri),
                status = status,
            ),
        )
    }
}

/** Strip CR/LF and other control characters from user-controlled log fields. */
fun sanitizeForLog(value: String): String {
    val sb = StringBuilder(value.length)
    for (c in value) {
        if (c.code < 0x20 || c.code == 0x7F) sb.append('_') else sb.append(c)
    }
    return sb.toString()
}
