package meigo.tulpar.server.repo

import meigo.tulpar.server.apg.ApgMetadata

/**
 * Location of a package build within the repository.
 *
 * On-disk layout (apger-inspired):
 *   pool/<channel>/<name>/<arch>/<name>-<version>-<arch>.apg
 * where <arch> is the metadata architecture, or "noarch" when it is null.
 */
data class PackageCoordinates(
    val name: String,
    val version: String,
    val arch: String,   // "noarch" for architecture-independent packages
    val channel: String,
) {
    /** Canonical package file name: `<name>-<version>-<arch>.apg`. */
    val fileName: String get() = "$name-$version-$arch.apg"

    /** Repository-relative path to the `.apg`, using forward slashes. */
    val relativePath: String get() = "pool/$channel/$name/$arch/$fileName"

    /** Repository-relative path to the detached signature. */
    val signaturePath: String get() = "$relativePath.sig"

    companion object {
        fun of(meta: ApgMetadata, channel: String): PackageCoordinates =
            PackageCoordinates(meta.name, meta.version, meta.archToken, channel)
    }
}

/**
 * A fully resolved package known to the repository: its metadata (read from the
 * `.apg` itself — the source of truth libAPG sees), plus repository facts.
 */
data class PackageEntry(
    val coordinates: PackageCoordinates,
    val metadata: ApgMetadata,
    val size: Long,
    val sha256: String,
    val signed: Boolean,
    val updatedEpochMillis: Long,
) {
    val name: String get() = coordinates.name
    val version: String get() = coordinates.version
    val arch: String get() = coordinates.arch
    val channel: String get() = coordinates.channel
}
