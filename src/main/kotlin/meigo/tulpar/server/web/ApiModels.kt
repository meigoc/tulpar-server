package meigo.tulpar.server.web

import kotlinx.serialization.Serializable
import meigo.tulpar.server.repo.PackageEntry

/** REST response payloads for the v2 API. */

@Serializable
data class ErrorResponse(val error: String, val detail: String? = null)

@Serializable
data class HealthResponse(val status: String, val packages: Int, val channels: List<String>)

@Serializable
data class VersionResponse(val server: String, val version: String, val format: String)

/** Summary of a package build in list responses. */
@Serializable
data class PackageSummary(
    val name: String,
    val version: String,
    val architecture: String?,
    val channel: String,
    val type: String?,
    val description: String?,
    val size: Long,
    val signed: Boolean,
    val download: String,
) {
    companion object {
        fun from(e: PackageEntry): PackageSummary = PackageSummary(
            name = e.name,
            version = e.version,
            architecture = e.metadata.architecture,
            channel = e.channel,
            type = e.metadata.type,
            description = e.metadata.description,
            size = e.size,
            signed = e.signed,
            download = "/api/v2/download/${e.channel}/${e.name}/${e.version}/${e.arch}",
        )
    }
}

@Serializable
data class PackageListResponse(
    val count: Int,
    val packages: List<PackageSummary>,
)

/** Detailed view: all builds of one package name across versions/arches. */
@Serializable
data class PackageDetail(
    val name: String,
    val builds: List<PackageBuild>,
)

@Serializable
data class PackageBuild(
    val version: String,
    val architecture: String?,
    val channel: String,
    val type: String?,
    val description: String?,
    val maintainer: String?,
    val license: String?,
    val homepage: String?,
    val tags: List<String>,
    val dependencies: List<String>,
    val conflicts: List<String>,
    val provides: List<String>,
    val replaces: List<String>,
    val conf: List<String>,
    val size: Long,
    val sha256: String,
    val signed: Boolean,
    val download: String,
    val signature: String?,
) {
    companion object {
        fun from(e: PackageEntry): PackageBuild {
            val m = e.metadata
            val base = "/api/v2/download/${e.channel}/${e.name}/${e.version}/${e.arch}"
            return PackageBuild(
                version = m.version,
                architecture = m.architecture,
                channel = e.channel,
                type = m.type,
                description = m.description,
                maintainer = m.maintainer,
                license = m.license,
                homepage = m.homepage,
                tags = m.tags,
                dependencies = m.dependencies,
                conflicts = m.conflicts,
                provides = m.provides,
                replaces = m.replaces,
                conf = m.conf,
                size = e.size,
                sha256 = e.sha256,
                signed = e.signed,
                download = base,
                signature = if (e.signed) "$base.sig" else null,
            )
        }
    }
}
