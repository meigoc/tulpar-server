package meigo.tulpar.server.apg

import java.io.File

/** Outcome of validating an `.apg`: structural errors fail it; warnings don't. */
data class ApgValidationResult(
    val valid: Boolean,
    val detectedVersion: Int,          // 1 or 2 (0 if undetermined)
    val metadata: ApgMetadata?,
    val errors: List<String>,
    val warnings: List<String>,
) {
    val ok: Boolean get() = valid && errors.isEmpty()
}

/**
 * Validates an `.apg` package against the APG v1/v2 specifications, in the
 * spirit of NurOS `apgcheck`, but tolerant on the ecosystem's known
 * inconsistencies and with an explicit libAPG-compatibility check.
 *
 * Rules (from apgcheck README + apg-docs/apgv2.md):
 *   v1 requires: data/, md5sums, metadata.json
 *   v2 additionally: crc32sums
 *   v1 metadata requires: name, version, description, maintainer, homepage,
 *                         dependencies, conflicts, provides, replaces
 *   v2 additionally requires: type, tags, conf
 *
 * Tolerances we add on top:
 *   - accept `meta.json` as a metadata source (libAPG's internal name), but warn,
 *   - accept `sha256sums` (libAPG/apgbuild) in place of / alongside md5sums,
 *   - accept either checksum line order and either script-name style.
 *
 * Compatibility warning: libAPG (≤ 1.4) reads `meta.json` from the archive root,
 * while apgbuild/apgcheck/this server use `metadata.json`. A package carrying only
 * `metadata.json` validates fine here but would fail to parse under stock libAPG;
 * we surface that so publishers can ship both names if strict compat is required.
 */
class ApgValidator(private val verifyChecksums: Boolean = true) {

    fun validate(file: File): ApgValidationResult {
        val archive = try {
            ApgArchive.read(file)
        } catch (e: Exception) {
            return ApgValidationResult(false, 0, null, listOf("cannot read archive: ${e.message}"), emptyList())
        }
        return validate(archive)
    }

    fun validate(archive: ApgArchive): ApgValidationResult {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        // --- structure ---
        if (!archive.hasDataDir()) errors.add("required directory missing: 'data/'")

        val metaName = archive.metadataFileName()
        when (metaName) {
            null -> errors.add("required file missing: 'metadata.json'")
            "meta.json" -> warnings.add(
                "metadata stored as 'meta.json'; the spec and apgbuild use 'metadata.json'"
            )
        }
        if (metaName == "metadata.json" && !archive.has("meta.json")) {
            warnings.add(
                "no 'meta.json' in archive: stock libAPG (≤1.4) reads 'meta.json' and would " +
                    "fail to parse this package; consider shipping both names for strict compatibility"
            )
        }

        val hasMd5 = archive.has(ChecksumAlgo.MD5.fileName)
        val hasCrc32 = archive.has(ChecksumAlgo.CRC32.fileName)
        val hasSha256 = archive.has(ChecksumAlgo.SHA256.fileName)

        // Detect version: crc32sums present → v2, else v1. (sha256sums alone is a
        // libAPG/apgbuild extension; treat as v2-class but warn it's non-canonical.)
        val version = when {
            hasCrc32 -> 2
            hasMd5 -> 1
            hasSha256 -> 2
            else -> 0
        }

        if (!hasMd5 && !hasCrc32 && !hasSha256) {
            errors.add("no checksum file found (expected one of: sha256sums, crc32sums, md5sums)")
        } else if (!hasMd5 && (hasCrc32 || hasSha256)) {
            warnings.add("'md5sums' absent; spec lists md5sums as the v1/v2 baseline checksum file")
        }

        // --- checksum verification against data/ ---
        // apgcheck verifies every present sums file (both md5sums and crc32sums in
        // v2); we do the same so a tampered file is caught regardless of algorithm.
        if (verifyChecksums) {
            val dataFiles = archive.dataFiles().toSet()
            for (algo in ChecksumAlgo.byPriority) {
                val body = archive.bytes(algo.fileName) ?: continue
                val entries = Checksums.parse(body.decodeToString(), algo)
                if (entries.isEmpty()) {
                    warnings.add("${algo.fileName} present but contained no usable entries")
                }
                for (entry in entries) {
                    val rel = entry.relPath
                    val bytes = archive.bytes("data/$rel") ?: archive.bytes(rel)
                    if (bytes == null) {
                        if (rel !in dataFiles) {
                            errors.add("${algo.fileName}: file listed but missing from archive: $rel")
                        }
                        continue
                    }
                    val actual = algo.hex(bytes)
                    if (!actual.equals(entry.hash, ignoreCase = true)) {
                        errors.add("${algo.fileName} mismatch for $rel (expected ${entry.hash}, got $actual)")
                    }
                }
            }
        }

        // --- metadata fields ---
        val metadata = archive.metadata()
        if (metaName != null && metadata == null) {
            errors.add("$metaName is not valid JSON or is missing mandatory name/version")
        }
        if (metadata != null) {
            val missing = ArrayList<String>()
            // v1 baseline required fields
            if (metadata.description.isNullOrBlank()) missing.add("description")
            if (metadata.maintainer.isNullOrBlank()) missing.add("maintainer")
            if (metadata.homepage.isNullOrBlank()) missing.add("homepage")
            // dependencies/conflicts/provides/replaces are required *keys* (may be empty)
            // — our model defaults them to []; missing keys aren't distinguishable here,
            // so we don't fail on them. We do enforce the v2 presence of `type` when v2.
            if (version >= 2) {
                if (metadata.type.isNullOrBlank()) missing.add("type")
                // tags/conf may legitimately be empty arrays; only flag type.
            }
            if (missing.isNotEmpty()) {
                errors.add("metadata missing or empty required fields: $missing")
            }
        }

        return ApgValidationResult(
            valid = errors.isEmpty(),
            detectedVersion = version,
            metadata = metadata,
            errors = errors,
            warnings = warnings,
        )
    }
}
