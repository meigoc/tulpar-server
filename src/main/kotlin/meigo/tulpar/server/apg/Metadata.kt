package meigo.tulpar.server.apg

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parsed `metadata.json` of an APG package.
 *
 * Parsing mirrors libAPG `src/json.c` (yyjson, strict default flags): the root
 * must be a JSON object; a field is populated only when its value is a JSON
 * string (numbers/booleans/null leave it unset); unknown fields are ignored;
 * string arrays keep only their string items. JSON that yyjson would reject
 * (BOM, comments, trailing commas, lone surrogates, control characters,
 * malformed numbers) is rejected here too — see [ApgJson].
 *
 * [ApgMetadata] itself additionally requires `name` and `version` to be
 * present strings. That is a server indexing requirement (a package cannot be
 * filed or addressed in the pool without them), not a libAPG acceptance rule;
 * [RawMetadata] preserves the distinction for the validator and conformance
 * tooling.
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

        /**
         * Strict parse of metadata JSON text. Returns null when the document is
         * not a JSON object, or when `name`/`version` are not present strings.
         * Throws [ApgJson.ParseException] (and [IllegalArgumentException] on
         * invalid UTF-8) when the JSON itself is invalid.
         */
        fun parse(jsonText: String): ApgMetadata? = parse(jsonText.toByteArray())

        /** Strict parse of metadata JSON bytes; see [parse] (String). */
        fun parse(bytes: ByteArray): ApgMetadata? {
            val root = parseObject(bytes) ?: return null
            val raw = RawMetadata.from(root)
            val name = raw.name ?: return null
            val version = raw.version ?: return null
            return ApgMetadata(
                name = name,
                version = version,
                type = raw.type,
                architecture = raw.architecture,
                description = raw.description,
                maintainer = raw.maintainer,
                license = raw.license,
                homepage = raw.homepage,
                tags = raw.tags,
                dependencies = raw.dependencies,
                conflicts = raw.conflicts,
                provides = raw.provides,
                replaces = raw.replaces,
                conf = raw.conf,
            )
        }

        /**
         * Parse bytes into a JSON object exactly as libAPG's
         * `package_metadata_from_file` would: strict JSON, root must be an
         * object. Returns null when the root is not an object; throws on
         * invalid JSON.
         */
        fun parseObject(bytes: ByteArray): JsonObject? =
            ApgJson.parse(bytes) as? JsonObject
    }
}

/**
 * Metadata with every field optional — the literal libAPG view of a parsed
 * `metadata.json` (fields absent unless their JSON value is a string).
 */
data class RawMetadata(
    val name: String? = null,
    val version: String? = null,
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
    companion object {
        fun from(root: JsonObject): RawMetadata = RawMetadata(
            name = root.str("name"),
            version = root.str("version"),
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

        /** Only JSON strings populate fields (yyjson_is_str guard in libAPG json.c). */
        private fun JsonObject.str(key: String): String? {
            val prim = this[key] as? JsonPrimitive ?: return null
            if (!prim.isString) return null
            return prim.content
        }

        /** String arrays keep only their string items; non-strings are dropped. */
        private fun JsonObject.strList(key: String): List<String> {
            val arr = this[key] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        }
    }
}
