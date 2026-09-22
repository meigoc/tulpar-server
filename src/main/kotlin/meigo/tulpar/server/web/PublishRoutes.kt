package meigo.tulpar.server.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.repo.DeleteResult
import meigo.tulpar.server.repo.PublishResult
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
 * The body is parsed by [StreamingMultipart], which applies separate caps:
 * the .apg part streams into a staging temp file bounded by
 * `publish.maxUploadBytes` (413 on breach — the payload never accumulates on
 * the heap), while non-file fields are bounded to [MAX_FORM_FIELD_BYTES] so a
 * huge `channel` value cannot exhaust memory either. The .sig part is capped
 * by `publish.maxSignatureBytes` and held in memory (64 bytes when valid).
 * A declared Content-Length is required (411 otherwise).
 */
fun Route.publishRoutes(ctx: ServerContext) {
    val service = ctx.publishService

    route("/api/v2/packages") {

        post {
            if (!requireAuth(ctx)) return@post

            val maxUpload = ctx.config.publish.maxUploadBytes
            val maxSig = ctx.config.publish.maxSignatureBytes

            // Publish requires a declared Content-Length: with a chunked body,
            // an attacker controls how long the parser keeps reading. Every
            // legitimate publisher (curl -F, the pkgdrop autopublish script)
            // declares the length.
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared == null) {
                call.respond(
                    HttpStatusCode.LengthRequired,
                    ErrorResponse("length_required", "publish requires a Content-Length header (chunked uploads are not accepted)"),
                )
                return@post
            }
            val totalCap = maxUpload + maxSig + MAX_FORM_FIELD_BYTES + MULTIPART_OVERHEAD_BYTES
            if (declared > totalCap) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse("payload_too_large", "upload exceeds $maxUpload bytes"),
                )
                return@post
            }

            val boundary = call.multipartBoundary()
            if (boundary == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("bad_request", "expected a multipart/form-data body"),
                )
                return@post
            }

            // Ownership: `staged` is the route-owned staging temp file. It is
            // nulled only after a successful publish (the file was atomically
            // moved into the pool); the finally-block deletes it on every other
            // path (rejections, parse errors, aborted streams).
            var staged: File? = null
            var sigBytes: ByteArray? = null
            var channel: String? = null
            var apgPartSeen = false
            var sigPartSeen = false
            try {
                val parser = StreamingMultipart(
                    call.request.receiveChannel(),
                    boundary,
                    maxFileBytes = maxUpload,
                    maxFieldBytes = MAX_FORM_FIELD_BYTES,
                    maxTotalBytes = totalCap,
                )
                run {
                    parser.consume(
                        onFile = { part ->
                            if (isSignaturePart(part.name, part.filename)) {
                                if (sigPartSeen) throw BadRequestUploadException("more than one signature part in the upload")
                                sigPartSeen = true
                                BoundedSink(maxSig) { bytes -> sigBytes = bytes }
                            } else {
                                if (apgPartSeen) throw BadRequestUploadException("more than one package part in the upload")
                                apgPartSeen = true
                                val tmp = File.createTempFile("upload-", ".apg.part", service.stagingDir())
                                staged = tmp
                                FileSink(tmp)
                            }
                        },
                        onField = { field ->
                            if (field.name == "channel") channel = field.value
                        },
                    )
                }

                val apgFile = staged
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("missing_file", "multipart field 'apg' (the .apg file) is required"),
                    )

                // Magic-byte preflight on the fully staged part: a truncated or
                // non-archive upload is rejected before the (heavier) archive
                // parse. Runs after consume() returns so it cannot mask a
                // parser limit/malformed error.
                if (!hasArchiveMagic(apgFile)) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("bad_payload", "uploaded payload is not a recognizable .apg archive (expected xz, zstd, gzip or tar magic)"),
                    )
                }

                if (channel != null && (!PathSafety.isSafeSegment(channel!!) || !Identifiers.isSafeChannel(channel!!))) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_channel", "invalid channel"))
                }

                when (val result = service.publishStaged(apgFile, sigBytes, channel)) {
                    is PublishResult.Success -> {
                        staged = null // consumed: atomically moved into the pool
                        call.respond(
                            HttpStatusCode.Created,
                            PublishResponse("published", result.coordinates.relativePath, result.signed, result.warnings),
                        )
                    }
                    is PublishResult.Rejected -> {
                        val status = if (result.conflict) HttpStatusCode.Conflict else HttpStatusCode.UnprocessableEntity
                        val code = if (result.conflict) "conflict" else "rejected"
                        call.respond(
                            status,
                            ErrorResponse(code, (listOf(result.reason) + result.errors).joinToString("; ")),
                        )
                    }
                }
            } catch (e: MultipartLimitException) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse("payload_too_large", e.message ?: "upload exceeds the configured limit"),
                )
            } catch (e: BadRequestUploadException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("bad_payload", e.message ?: "malformed multipart upload"),
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

/** True for the .sig multipart part, by field name or file extension. */
private fun isSignaturePart(name: String, filename: String?): Boolean =
    name in setOf("sig", "signature") ||
        (name !in setOf("apg", "package", "file") && filename?.endsWith(".sig") == true)

/**
 * An [OutputStream] collecting at most [limit] bytes, handing the result to
 * [onComplete] on close. Exceeding the limit throws [MultipartLimitException]
 * mid-write, so oversized parts abort before completion.
 */
private class BoundedSink(private val limit: Long, private val onComplete: (ByteArray) -> Unit) : OutputStream() {
    private val buf = java.io.ByteArrayOutputStream()
    private var failed = false

    override fun write(b: Int) {
        grow(1)
        buf.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        grow(len.toLong())
        buf.write(b, off, len)
    }

    private fun grow(n: Long) {
        if (buf.size() + n > limit) {
            failed = true
            throw MultipartLimitException("signature part exceeds $limit bytes")
        }
    }

    override fun close() {
        if (!failed) onComplete(buf.toByteArray())
    }
}

/** An [OutputStream] writing a part straight to a staging file. */
private class FileSink(file: File) : OutputStream() {
    private val out = file.outputStream().buffered()

    override fun write(b: Int) = out.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun flush() = out.flush()
    override fun close() = out.close()
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

/** Cap for non-file multipart fields (only `channel` is read). */
private const val MAX_FORM_FIELD_BYTES = 64L * 1024

/** Slack for boundary lines and part headers in the total-body cap. */
private const val MULTIPART_OVERHEAD_BYTES = 64L * 1024

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
