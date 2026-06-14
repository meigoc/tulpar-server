package meigo.tulpar.server.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.repo.PackageEntry
import meigo.tulpar.server.security.DownloadLimiter
import meigo.tulpar.server.security.clientIp
import meigo.tulpar.server.security.PathSafety
import java.io.File

/**
 * Package download endpoints, with per-IP concurrency caps (HTTP 429) and
 * optional per-IP throughput throttling (token bucket).
 */
fun Route.downloadRoutes(ctx: ServerContext) {
    val repo = ctx.repository
    val limiter = ctx.downloadLimiter
    val behindProxy = ctx.config.server.behindProxy

    // .sig must be matched before the bare .apg route (more specific path).
    get("/api/v2/download/{channel}/{name}/{version}/{arch}.sig") {
        val entry = resolveEntry(ctx) ?: return@get call.notFound("package not found")
        val sig = repo.signatureFor(entry) ?: return@get call.notFound("signature not available")
        call.attachmentHeader(sig.name)
        call.respondFile(sig) // signatures are tiny; no throttling needed
    }

    get("/api/v2/download/{channel}/{name}/{version}/{arch}") {
        val entry = resolveEntry(ctx) ?: return@get call.notFound("package not found")
        val file = repo.fileFor(entry)
        if (!file.isFile) return@get call.notFound("package file missing")

        val ip = call.clientIp(behindProxy)
        if (!limiter.tryAcquire(ip)) {
            return@get call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse("too_many_downloads", "concurrent download limit reached for your IP"),
            )
        }
        try {
            call.attachmentHeader(entry.coordinates.fileName)
            if (limiter.throttled(ip)) {
                call.respondThrottled(file, limiter, ip)
            } else {
                call.respondFile(file)
            }
        } finally {
            limiter.release(ip)
        }
    }
}

private fun ApplicationCall.attachmentHeader(fileName: String) {
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment
            .withParameter(ContentDisposition.Parameters.FileName, fileName).toString(),
    )
}

/** Stream [file] honouring a per-IP token-bucket rate limit. */
private suspend fun ApplicationCall.respondThrottled(file: File, limiter: DownloadLimiter, ip: String) {
    val length = file.length()
    val bucket = limiter.bucketFor(ip)
    val bufSize = limiter.bufferSize.coerceAtLeast(1024)
    respondBytesWriter(ContentType.Application.OctetStream, HttpStatusCode.OK, contentLength = length) {
        file.inputStream().use { input ->
            val buf = ByteArray(bufSize)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                val wait = bucket.reserve(read)
                if (wait > 0) delay(wait)
                writeFully(buf, 0, read)
                flush()
            }
        }
    }
}

/**
 * Resolve a download request to a known PackageEntry, enforcing segment safety.
 * Returns null (caller emits 404) on any unsafe segment or unknown package.
 */
internal fun RoutingContext.resolveEntry(ctx: ServerContext): PackageEntry? {
    val channel = call.parameters["channel"] ?: return null
    val name = call.parameters["name"] ?: return null
    val version = call.parameters["version"] ?: return null
    var arch = call.parameters["arch"] ?: return null
    if (arch.endsWith(".sig")) arch = arch.removeSuffix(".sig")
    if (!PathSafety.allSafe(channel, name, version, arch)) return null
    return ctx.repository.find(channel, name, version, arch)
}
