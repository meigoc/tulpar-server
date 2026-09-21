package meigo.tulpar.server.security

/**
 * Strict allowlists for repository identifiers (channel, name, version, arch).
 *
 * libAPG itself places no character restrictions on metadata `name`,
 * `version` or `architecture`, but every identifier becomes a filesystem path
 * segment (`pool/<channel>/<name>/<arch>/<name>-<version>-<arch>.apg`) and a
 * URL segment, so the server must bound the character set. The allowlists are
 * derived from the identifiers that exist in the ecosystem (production pool
 * packages such as `procps-ng`, `linux-headers`, versions `1.9.17p2`,
 * `10.5p1`, `2026c-1`, `2026.09.07-2`, `1:2.3` epochs, archs `x86_64`,
 * `aarch64`, `all`, `noarch`) and from libAPG's own path rules (no `/`, no
 * `..`, component length < 256).
 *
 * These checks are deliberately stricter than libAPG (documented deviation in
 * docs/libapg-compat.md): a package whose identifiers they reject can still be
 * read by libAPG locally, but it cannot be safely stored or addressed in a
 * repository, so publishing it is refused and pre-existing pool copies are
 * flagged at index time.
 */
object Identifiers {

    /** A name/channel segment: alphanumeric start, then letters, digits, `.`, `_`, `+`, `-`. */
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")

    /** A version: like a name but additionally allows `:` (epoch) and `~`. */
    private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._+~:-]*$")

    /** An architecture token. */
    private val ARCH = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")

    /** Maximum identifier length (filesystem NAME_MAX minus layout overhead). */
    const val MAX_LENGTH = 128

    /**
     * Windows reserved device names. The server may run on any platform and
     * pools may be served from or copied to Windows filesystems, where these
     * names cannot exist as files regardless of extension.
     */
    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    fun isSafeName(value: String): Boolean =
        common(value) && NAME.matches(value) && !isWindowsReserved(value)

    fun isSafeChannel(value: String): Boolean = isSafeName(value)

    fun isSafeVersion(value: String): Boolean =
        common(value) && VERSION.matches(value) && !isWindowsReserved(value)

    fun isSafeArch(value: String): Boolean =
        common(value) && ARCH.matches(value) && !isWindowsReserved(value)

    /**
     * Canonical form used for case-insensitive collision detection: two
     * identifiers that differ only by case map to the same canonical key, and
     * on case-insensitive filesystems (Windows, default macOS) would collide
     * on disk.
     */
    fun canonical(value: String): String = value.lowercase()

    private fun common(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= MAX_LENGTH &&
            !value.endsWith('.') &&
            !value.endsWith(' ') &&
            !value.contains("//")

    private fun isWindowsReserved(value: String): Boolean {
        val stem = value.substringBefore('.').uppercase()
        return stem in WINDOWS_RESERVED
    }
}
