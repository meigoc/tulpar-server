package meigo.tulpar.server.web

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.repo.DeleteResult
import meigo.tulpar.server.repo.PublishResult
import meigo.tulpar.server.repo.PublishService
import meigo.tulpar.server.repo.UploadTooLargeException
import meigo.tulpar.server.security.Identifiers
import meigo.tulpar.server.security.PathSafety
import java.io.File
import java.io.OutputStream

@Serializable
data class PublishResponse(
    val status: String,
    val file: String,
    val signed: Boolean,
    val warnings: List<String>,
)

/**
 * Publish (upload) and delete (yank) endpoints, gated by Bearer-token auth.
 *
 *   POST   /api/v2/packages        multipart: apg=<file> [sig=<file>] [channel=<text>]
 *   DELETE /api/v2/packages/{channel}/{name}/{version}/{arch}
 *
 * Both require `Authorization: Bearer <token>` and publish.enabled=true.
 *
 * The .apg part streams directly into a staging temp file with a hard byte
 * cap (HTTP 413 on breach) — the payload never accumulates on the heap, so a
 * 4 GiB upload validates under the server's configured heap. The .sig part is
 * bounded to a few KiB and held in memory (it is exactly 64 bytes when valid).
 */
fun Route.publishRoutes(ctx: ServerContext) {
    val service = ctx.publishService

    route("/api/v2/packages") {

        post {
            if (!requireAuth(ctx)) return@post

            var staged: File? = null
            var sigBytes: ByteArray? = null
            var channel: String? = null
            try {
                val maxUpload = ctx.config.publish.maxUploadBytes
                val maxSig = ctx.config.publish.maxSignatureBytes.toInt()

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val isSig = part.name in setOf("sig", "signature") ||
                                (part.name !in setOf("apg", "package", "file") &&
                                    part.originalFileName?.endsWith(".sig") == true)
                            if (isSig) {
                                sigBytes = readBounded(part, maxSig)
                                    ?: throw UploadTooLargeException("signature part exceeds $maxSig bytes")
                            } else {
                                if (staged != null) {
                                    throw UploadTooLargeException("more than one package part in the upload")
                                }
                                staged = streamToStaging(part, service, maxUpload)
                            }
                        }
                        is PartData.FormItem -> if (part.name == "channel") channel = part.value
                        else -> {}
                    }
                    part.dispose()
                }

                val apgFile = staged
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("missing_file", "multipart field 'apg' (the .apg file) is required"),
                    )

                if (channel != null && (!PathSafety.isSafeSegment(channel!!) || !Identifiers.isSafeChannel(channel!!))) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_channel", "invalid channel"))
                }

                when (val result = service.publishStaged(apgFile, sigBytes, channel)) {
                    is PublishResult.Success -> {
                        staged = null // consumed by the successful move
                        call.respond(
                            HttpStatusCode.Created,
                            PublishResponse("published", result.coordinates.relativePath, result.signed, result.warnings),
                        )
                    }
                    is PublishResult.Rejected -> call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        ErrorResponse("rejected", (listOf(result.reason) + result.errors).joinToString("; ")),
                    )
                }
            } catch (e: UploadTooLargeException) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse("payload_too_large", e.message ?: "upload exceeds the configured limit"),
                )
            } catch (e: BadRequestUploadException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("bad_payload", e.message ?: "payload is not a recognizable .apg archive"),
                )
            } finally {
                staged?.delete()
            }
        }

        delete("{channel}/{name}/{version}/{arch}") {
            if (!requireAuth(ctx)) return@delete

            val channel = call.parameters["channel"]!!
            val name = call.parameters["name"]!!
            val version = call.parameters["version"]!!
            val arch = call.parameters["arch"]!!
            if (!PathSafety.allSafe(channel, name, version, arch)) {
                return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "invalid segment"))
            }

            when (service.delete(channel, name, version, arch)) {
                DeleteResult.Deleted -> call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
                DeleteResult.NotFound -> call.notFound("package not found")
            }
        }
    }
}

/**
 * Stream a file part into a fresh staging temp file, enforcing [maxBytes].
 * Throws [UploadTooLargeException] the moment the cap is crossed — before the
 * whole body is buffered — and a magic-byte preflight rejects payloads that
 * cannot be an archive (truncated or non-.apg uploads) with the same
 * exception type mapped to 413/400 by the caller.
 */
private suspend fun streamToStaging(part: PartData.FileItem, service: PublishService, maxBytes: Long): File {
    val tmp = File.createTempFile("upload-", ".apg.part", service.stagingDir())
    var written = 0L
    try {
        tmp.outputStream().buffered().use { out ->
            part.provider().copyAndClose(out, maxBytes) { written = it }
        }
        if (written > maxBytes) throw UploadTooLargeException("upload exceeds $maxBytes bytes")
        if (!hasArchiveMagic(tmp)) {
            throw BadRequestUploadException(
                "uploaded payload is not a recognizable .apg archive (expected xz, zstd, gzip or tar magic)",
            )
        }
        return tmp
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
}

/** Read a small part fully into memory, or return null if it exceeds [maxBytes]. */
private suspend fun readBounded(part: PartData.FileItem, maxBytes: Int): ByteArray? {
    val channel = part.provider()
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = channel.readAvailable(buf, 0, buf.size)
        if (read <= 0) break
        total += read
        if (total > maxBytes) return null
        out.write(buf, 0, read)
    }
    return out.toByteArray()
}

/** Compressed-tar magic (xz/zstd/gzip) or an uncompressed ustar header at 257. */
private fun hasArchiveMagic(file: File): Boolean {
    val head = ByteArray(263)
    val n = file.inputStream().use { it.read(head) }
    if (n < 4) return false
    fun startsWith(vararg bytes: Int) = (0 until bytes.size).all { head[it] == bytes[it].toByte() }
    return when {
        n >= 6 && startsWith(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> true // xz
        startsWith(0x28, 0xB5, 0x2F, 0xFD) -> true                       // zstd
        startsWith(0x1F, 0x8B) -> true                                    // gzip
        n >= 263 && head[257] == 'u'.code.toByte() && head[258] == 's'.code.toByte() &&
            head[259] == 't'.code.toByte() && head[260] == 'a'.code.toByte() &&
            head[261] == 'r'.code.toByte() -> true                        // raw tar
        else -> false
    }
}

/** Upload whose payload cannot be an archive at all → HTTP 400 (structural). */
class BadRequestUploadException(message: String) : Exception(message)

/** Copy this ByteReadChannel into [out], stopping with an exception past [maxBytes]. */
private suspend fun ByteReadChannel.copyAndClose(out: OutputStream, maxBytes: Long, onCount: (Long) -> Unit) {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    try {
        while (true) {
            val read = readAvailable(buf, 0, buf.size)
            if (read <= 0) break
            total += read
            if (total > maxBytes) throw UploadTooLargeException("upload exceeds $maxBytes bytes")
            out.write(buf, 0, read)
        }
    } finally {
        onCount(total)
    }
}

/**
 * Enforce publish auth. Responds with the right status and returns false when
 * the request must not proceed.
 */
private suspend fun RoutingContext.requireAuth(ctx: ServerContext): Boolean {
    if (!ctx.config.publish.enabled) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("publish_disabled", "publishing is disabled"))
        return false
    }
    if (!ctx.tokenAuth.authorize(call.request.headers[HttpHeaders.Authorization])) {
        call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "valid bearer token required"))
        return false
    }
    return true
}
