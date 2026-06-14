package meigo.tulpar.server.apg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed `metadata.json` of an APG package.
 *
 * The field set is exactly the APGv2 schema (see apg-docs/apgv2.md and the
 * `package_metadata` struct in libAPG). Parsing is deliberately *tolerant*:
 * the same model loads both APGv1 metadata (no `type`/`tags`/`conf`) and APGv2,
 * and accepts `architecture`/`license` being JSON null. Serialization back out
 * (for repodata.json) is *canonical* — APGv2 shape with stable field order.
 */
data class ApgMetadata(
    val name: String,
    val version: String,
    val type: String? = null,
    val architecture: String? = null,
    val description: String? = null,
    val maintainer: String? = null,
    val license: String? = null,
    val homepage: String? = null,
    val tags: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val provides: List<String> = emptyList(),
    val replaces: List<String> = emptyList(),
    val conf: List<String> = emptyList(),
) {
    /** Architecture token used in repository paths/keys; `null` → "noarch". */
    val archToken: String get() = architecture?.takeIf { it.isNotBlank() } ?: NOARCH

    /** Registry key `name@arch` (or just `name` when arch is absent), as in listpkgs. */
    val key: String get() = if (architecture.isNullOrBlank()) name else "$name@$architecture"

    /** Dependencies parsed into structured constraints (libAPG semantics). */
    fun parsedDependencies(): List<Dependency> = dependencies.map { Dependency.parse(it) }

    companion object {
        const val NOARCH = "noarch"

        /** Lenient reader: tolerates comments, trailing commas, unknown keys, lax types. */
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val lenient = Json {
            ignoreUnknownKeys = true
            isLenient = true
            allowTrailingComma = true
            allowComments = true
            coerceInputValues = true
        }

        /**
         * Parse metadata from a JSON string. Returns null on hard parse failure
         * or when the mandatory `name`/`version` fields are missing (these are the
         * only fields libAPG and the listpkgs aggregator both treat as required).
         */
        fun parse(jsonText: String): ApgMetadata? {
            val root = runCatching { lenient.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject
                ?: return null

            val name = root.str("name") ?: return null
            val version = root.str("version") ?: return null

            return ApgMetadata(
                name = name,
                version = version,
                type = root.str("type"),
                architecture = root.str("architecture"),
                description = root.str("description"),
                maintainer = root.str("maintainer"),
                license = root.str("license"),
                homepage = root.str("homepage"),
                tags = root.strList("tags"),
                dependencies = root.strList("dependencies"),
                conflicts = root.strList("conflicts"),
                provides = root.strList("provides"),
                replaces = root.strList("replaces"),
                conf = root.strList("conf"),
            )
        }

        private fun JsonObject.str(key: String): String? {
            val prim = (this[key] as? JsonPrimitive) ?: return null
            if (!prim.isString && prim.contentOrNull == "null") return null
            return prim.contentOrNull?.takeIf { it.isNotEmpty() }
        }

        private fun JsonObject.strList(key: String): List<String> {
            val arr = this[key] as? JsonArray ?: return emptyList()
            return arr.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }
    }
}
