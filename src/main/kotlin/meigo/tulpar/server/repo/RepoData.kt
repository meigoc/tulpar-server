package meigo.tulpar.server.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Canonical `repodata.json` schema served by Tulpar Server.
 *
 * Shape follows the NurOS listpkgs API reference (`{ packages: [...], meta: {...} }`),
 * and each package object carries the exact APGv2 metadata fields (so it is a
 * superset a libAPG-aware client can consume directly) plus repository facts
 * (channel, file path, size, sha256, signed, updated).
 */
@Serializable
data class RepoData(
    val meta: RepoMeta,
    val packages: List<RepoPackage>,
) {
    fun toJson(pretty: Boolean = false): String =
        (if (pretty) prettyJson else compactJson).encodeToString(this)

    companion object {
        const val FORMAT = "tulpar-repodata/2"
        private val compactJson = Json { encodeDefaults = true; explicitNulls = true }
        private val prettyJson = Json { encodeDefaults = true; explicitNulls = true; prettyPrint = true }
    }
}

@Serializable
data class RepoMeta(
    val format: String,
    val server: String,
    val generated_at: String,   // ISO-8601
    val package_count: Int,
    val channels: List<String>,
)

@Serializable
data class RepoPackage(
    // --- APGv2 metadata fields (verbatim) ---
    val name: String,
    val version: String,
    val type: String?,
    val architecture: String?,
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
    // --- repository facts ---
    val channel: String,
    val file: String,           // repo-relative path to the .apg
    val size: Long,
    val sha256: String,
    val signed: Boolean,
    val updated: String,        // ISO-8601
) {
    companion object {
        fun from(entry: PackageEntry, updatedIso: String): RepoPackage {
            val m = entry.metadata
            return RepoPackage(
                name = m.name,
                version = m.version,
                type = m.type,
                architecture = m.architecture,
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
                channel = entry.channel,
                file = entry.coordinates.relativePath,
                size = entry.size,
                sha256 = entry.sha256,
                signed = entry.signed,
                updated = updatedIso,
            )
        }
    }
}
