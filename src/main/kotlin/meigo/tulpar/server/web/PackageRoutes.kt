package meigo.tulpar.server.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.repo.PackageEntry
import meigo.tulpar.server.security.PathSafety

/**
 * Clean v2 REST surface for browsing and downloading packages.
 *
 *   GET /api/v2/packages?arch=&channel=&type=&q=     list/filter/search
 *   GET /api/v2/packages/{name}                       all builds of a name
 *   GET /api/v2/download/{channel}/{name}/{version}/{arch}        .apg
 *   GET /api/v2/download/{channel}/{name}/{version}/{arch}.sig    signature
 *   GET /repodata.json , GET /api/v2/repodata         full index
 */
fun Route.packageRoutes(ctx: ServerContext) {
    val repo = ctx.repository

    route("/api/v2") {

        get("/packages") {
            val arch = call.request.queryParameters["arch"]
            val channel = call.request.queryParameters["channel"]
            val type = call.request.queryParameters["type"]
            val q = call.request.queryParameters["q"]?.lowercase()

            val results = repo.entries().asSequence()
                .filter { arch == null || it.metadata.archToken == arch || it.metadata.architecture == arch }
                .filter { channel == null || it.channel == channel }
                .filter { type == null || it.metadata.type == type }
                .filter { e -> q == null || matchesQuery(e, q) }
                .map { PackageSummary.from(it) }
                .toList()

            call.respond(PackageListResponse(results.size, results))
        }

        get("/packages/{name}") {
            val name = call.parameters["name"] ?: return@get call.badRequest("name required")
            if (!PathSafety.isSafeSegment(name)) return@get call.badRequest("invalid name")
            val builds = repo.byName(name)
            if (builds.isEmpty()) return@get call.notFound("package not found: $name")
            call.respond(PackageDetail(name, builds.map { PackageBuild.from(it) }))
        }

        get("/repodata") { call.respondRepoData(ctx) }
    }

    // Convenience top-level alias for the static index file consumers expect.
    get("/repodata.json") { call.respondRepoData(ctx) }
}

private fun matchesQuery(e: PackageEntry, q: String): Boolean {
    val m = e.metadata
    if (m.name.lowercase().contains(q)) return true
    if (m.description?.lowercase()?.contains(q) == true) return true
    return m.tags.any { it.lowercase().contains(q) }
}

private suspend fun ApplicationCall.respondRepoData(ctx: ServerContext) {
    respondText(
        ctx.repository.buildRepoData(meigo.tulpar.server.Version.SERVER_NAME).toJson(pretty = false),
        contentType = ContentType.Application.Json,
    )
}

internal suspend fun ApplicationCall.badRequest(msg: String) =
    respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", msg))

internal suspend fun ApplicationCall.notFound(msg: String) =
    respond(HttpStatusCode.NotFound, ErrorResponse("not_found", msg))
