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
import meigo.tulpar.server.security.PathSafety

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
 */
fun Route.publishRoutes(ctx: ServerContext) {
    val auth = ctx.tokenAuth
    val service = PublishService(ctx.repository, ctx.config.publish, ctx.config.repo.defaultChannel)

    route("/api/v2/packages") {

        post {
            if (!requireAuth(ctx)) return@post

            var apgBytes: ByteArray? = null
            var sigBytes: ByteArray? = null
            var channel: String? = null

            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val bytes = part.provider().toByteArray()
                        when (part.name) {
                            "apg", "package", "file" -> apgBytes = bytes
                            "sig", "signature" -> sigBytes = bytes
                            else -> if (part.originalFileName?.endsWith(".sig") == true) sigBytes = bytes
                                else if (part.originalFileName?.endsWith(".apg") == true) apgBytes = bytes
                        }
                    }
                    is PartData.FormItem -> if (part.name == "channel") channel = part.value
                    else -> {}
                }
                part.dispose()
            }

            val bytes = apgBytes
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_file", "multipart field 'apg' (the .apg file) is required"),
                )

            if (channel != null && !PathSafety.isSafeSegment(channel!!)) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_channel", "invalid channel"))
            }

            when (val result = service.publish(bytes, sigBytes, channel)) {
                is PublishResult.Success -> call.respond(
                    HttpStatusCode.Created,
                    PublishResponse("published", result.coordinates.relativePath, result.signed, result.warnings),
                )
                is PublishResult.Rejected -> call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ErrorResponse("rejected", (listOf(result.reason) + result.errors).joinToString("; ")),
                )
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
