package meigo.tulpar.server.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.repo.PackageEntry
import meigo.tulpar.server.security.PathSafety

/**
 * Package download endpoints. Throughput/concurrency limiting is layered on in
 * M4 via the DownloadLimiter plugin; here we resolve the entry safely and stream
 * the file (or its detached signature).
 */
fun Route.downloadRoutes(ctx: ServerContext) {
    val repo = ctx.repository

    // .sig must be matched before the bare .apg route (more specific path).
    get("/api/v2/download/{channel}/{name}/{version}/{arch}.sig") {
        val entry = resolveEntry(ctx) ?: return@get call.notFound("package not found")
        val sig = repo.signatureFor(entry) ?: return@get call.notFound("signature not available")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, sig.name).toString(),
        )
        call.respondFile(sig)
    }

    get("/api/v2/download/{channel}/{name}/{version}/{arch}") {
        val entry = resolveEntry(ctx) ?: return@get call.notFound("package not found")
        val file = repo.fileFor(entry)
        if (!file.isFile) return@get call.notFound("package file missing")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, entry.coordinates.fileName).toString(),
        )
        call.respondFile(file)
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
