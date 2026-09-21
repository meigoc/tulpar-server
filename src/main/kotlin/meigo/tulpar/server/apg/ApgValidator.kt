package meigo.tulpar.server.apg

import java.io.File

/**
 * Outcome of validating an `.apg`.
 *
 * Two verdicts are reported:
 *  - [libapgCompatible] — whether libAPG would accept the package (identical
 *    accept/reject to `parse_package` + the `data/` directory an install
 *    requires); this drives the `check` exit code;
 *  - [ok] — the server policy verdict ([libapgCompatible] plus no integrity or
 *    indexing errors); this drives publish validation (`publish.validate`).
 *
 * The server is allowed to be stricter than libAPG, never looser; every
 * stricter rule appears in [errors] and is listed in docs/libapg-compat.md.
 */
data class ApgValidationResult(
    val libapgCompatible: Boolean,
    val detectedVersion: Int,          // 1 or 2 (0 if no checksum file present)
    val metadata: ApgMetadata?,
    val rejectionReasons: List<String>, // why libAPG would not accept it
    val errors: List<String>,           // server-policy errors (integrity/indexing)
    val warnings: List<String>,
) {
    val valid: Boolean get() = libapgCompatible
    val ok: Boolean get() = libapgCompatible && errors.isEmpty()
}

/**
 * Validates an `.apg` package: the pass/fail verdict mirrors libAPG (the
 * reference implementation at the commit pinned in docs/libapg-compat.md),
 * while the APG v1/v2 spec extras (checksum files, recommended metadata
 * fields) are verified as server policy and reported as errors only for the
 * publish path, or as warnings for `check`.
 *
 * libAPG-derived rules (hard rejections):
 *   - compression filter must be none/gzip/xz/zstd (src/archive.c);
 *   - no `..` path segments, no device nodes, safe hardlinks (src/archive.c);
 *   - `metadata.json` at the archive root, strict JSON object (src/package.c,
 *     src/json.c); other names (`meta.json`) are NOT recognized;
 *   - a `data/` directory must exist (src/install/install.c install_data_dir).
 *
 * Server policy (errors on publish with validate=true; libAPG ignores these):
 *   - every present sums file must match the `data/` payloads;
 *   - `name` and `version` must be present JSON strings (the pool layout
 *     cannot address a package without them).
 *
 * Advisory warnings (never fail a verdict): missing sums files, missing
 * recommended metadata fields (description/maintainer/homepage/type per
 * apg-docs/apgv2.md), legacy `meta.json` present.
 */
class ApgValidator(private val verifyChecksums: Boolean = true) {

    fun validate(file: File): ApgValidationResult {
        val archive = try {
            ApgArchive.read(file, ApgReadLimits(captureDataDigests = verifyChecksums))
        } catch (e: Exception) {
            return unreadable(e)
        }
        return validate(archive)
    }

    /** Validate from raw bytes, as received through the publish endpoint. */
    fun validateBytes(bytes: ByteArray): ApgValidationResult {
        val archive = try {
            ApgArchive.read(bytes.inputStream(), ApgReadLimits(captureDataDigests = verifyChecksums))
        } catch (e: Exception) {
            return unreadable(e)
        }
        return validate(archive)
    }

    private fun unreadable(e: Exception): ApgValidationResult = ApgValidationResult(
        libapgCompatible = false,
        detectedVersion = 0,
        metadata = null,
        rejectionReasons = listOf("cannot read archive: ${e.message}"),
        errors = emptyList(),
        warnings = emptyList(),
    )

    fun validate(archive: ApgArchive): ApgValidationResult {
        val rejections = ArrayList<String>()
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        // --- libAPG acceptance rules ---
        if (!archive.hasDataDir()) {
            rejections.add("required directory missing: 'data/' (libAPG install_data_dir fails without it)")
        }

        val metaBytes = archive.metadataBytes()
        if (metaBytes == null) {
            rejections.add("required file missing: 'metadata.json'")
            if (archive.hasLegacyMetaJson()) {
                rejections.add(
                    "'meta.json' is present but libAPG (2.x) reads only 'metadata.json'; " +
                        "rename the file to be installable",
                )
            }
        }

        var metadata: ApgMetadata? = null
        if (metaBytes != null) {
            try {
                val root = ApgMetadata.parseObject(metaBytes)
                if (root == null) {
                    rejections.add("metadata.json root is not a JSON object")
                } else {
                    val raw = RawMetadata.from(root)
                    // Indexing requirement (server policy, stricter than libAPG):
                    // the pool path is derived from name/version, so they must be
                    // present JSON strings.
                    if (raw.name == null || raw.version == null) {
                        errors.add(
                            "metadata 'name' and 'version' must be present JSON strings " +
                                "(libAPG would accept this package but it cannot be indexed)",
                        )
                    }
                    metadata = ApgMetadata.parse(metaBytes)
                    if (raw.name != null && raw.version != null) {
                        for ((field, value) in listOf("description" to raw.description, "maintainer" to raw.maintainer, "homepage" to raw.homepage)) {
                            if (value.isNullOrBlank()) warnings.add("metadata field '$field' is empty (recommended by APGv2)")
                        }
                        if (raw.type.isNullOrBlank()) warnings.add("metadata field 'type' is missing (APGv2 packages carry one)")
                    }
                }
            } catch (e: IllegalArgumentException) {
                rejections.add("metadata.json is not valid strict JSON: ${e.message}")
            }
        }

        // --- checksum files: presence advisory, mismatch is an integrity error ---
        val hasMd5 = archive.has(ChecksumAlgo.MD5.fileName)
        val hasCrc32 = archive.has(ChecksumAlgo.CRC32.fileName)
        val hasSha256 = archive.has(ChecksumAlgo.SHA256.fileName)

        val version = when {
            hasCrc32 -> 2
            hasMd5 -> 1
            hasSha256 -> 2
            else -> 0
        }
        if (version == 0) {
            warnings.add("no checksum file found (expected one of: sha256sums, crc32sums, md5sums)")
        }

        if (verifyChecksums && !archive.capturedDigests) {
            warnings.add("checksum verification skipped: archive was read without digest capture")
        } else if (verifyChecksums) {
            for (algo in ChecksumAlgo.byPriority) {
                val body = archive.bytes(algo.fileName) ?: continue
                val entries = Checksums.parse(body.decodeToString(), algo)
                if (entries.isEmpty()) {
                    warnings.add("${algo.fileName} present but contained no usable entries")
                }
                for (entry in entries) {
                    val digests = archive.digests(entry.relPath)
                    if (digests == null) {
                        errors.add("${algo.fileName}: file listed but missing from archive: ${entry.relPath}")
                        continue
                    }
                    val actual = when (algo) {
                        ChecksumAlgo.SHA256 -> digests.sha256
                        ChecksumAlgo.MD5 -> digests.md5
                        ChecksumAlgo.CRC32 -> digests.crc32
                    }
                    if (!actual.equals(entry.hash, ignoreCase = true)) {
                        errors.add("${algo.fileName} mismatch for ${entry.relPath} (expected ${entry.hash}, got $actual)")
                    }
                }
            }
        }

        return ApgValidationResult(
            libapgCompatible = rejections.isEmpty(),
            detectedVersion = version,
            metadata = metadata,
            rejectionReasons = rejections,
            errors = errors,
            warnings = warnings,
        )
    }
}
