package meigo.tulpar.server.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentifiersTest {

    @Test
    fun `production identifiers pass`() {
        // Every name/version/arch observed in the live pool must be accepted.
        for (name in listOf(
            "iana-etc", "procps-ng", "linux-headers", "libxcrypt", "syslog-ng",
            "ca-certificates", "editline", "dosfstools", "efibootmgr", "libfastjson",
            "linux-firmware", "openssh", "sudo", "nano", "tzdata", "filesystem",
            "tree", "easyfetch", "libapg", "openssl", "glibc", "gcc", "cmake",
            "python", "perl", "binutils", "doas", "bzip2", "readline", "lmdb",
        )) {
            assertTrue(Identifiers.isSafeName(name), "name rejected: $name")
        }
        for (version in listOf(
            "20260904-1", "4.0.7-1", "7.2.3-1", "4.5.2-1", "8.2608.0-1",
            "20260816-1", "20260512-3.1-1", "4.2-1", "18-1", "1.2304.0-1",
            "20260810-1", "10.5p1-1", "1.9.17p2-1", "9.2-2", "2026c-1",
            "2026.09.07-2", "2.2.1-2", "1-1", "2.3.3-1", "4.0.2-1", "2.44-3",
            "16.2.0-1", "3.31.5-1", "3.14.7-1", "5.44.0-2", "2.47-1",
            "1:2.3", "0:1.0",
        )) {
            assertTrue(Identifiers.isSafeVersion(version), "version rejected: $version")
        }
        for (arch in listOf("x86_64", "aarch64", "all", "noarch", "i686", "riscv64")) {
            assertTrue(Identifiers.isSafeArch(arch), "arch rejected: $arch")
        }
        assertTrue(Identifiers.isSafeChannel("main"))
        assertTrue(Identifiers.isSafeChannel("testing"))
    }

    @Test
    fun `traversal and separators are rejected`() {
        assertFalse(Identifiers.isSafeName("../etc"))
        assertFalse(Identifiers.isSafeName("a/b"))
        assertFalse(Identifiers.isSafeName("a\\b"))
        assertFalse(Identifiers.isSafeName(".."))
        assertFalse(Identifiers.isSafeName("."))
        assertFalse(Identifiers.isSafeName("a/../b"))
        assertFalse(Identifiers.isSafeVersion("1.0/2"))
        assertFalse(Identifiers.isSafeVersion("../1.0"))
        assertFalse(Identifiers.isSafeArch("x86_64/../all"))
        assertFalse(Identifiers.isSafeChannel("ma/in"))
    }

    @Test
    fun `control characters and whitespace are rejected`() {
        assertFalse(Identifiers.isSafeName("bad\nname"))
        assertFalse(Identifiers.isSafeName("bad\rname"))
        assertFalse(Identifiers.isSafeName("bad\u0000name"))
        assertFalse(Identifiers.isSafeName("bad name"))
        assertFalse(Identifiers.isSafeName("bad\u001b[31mname"))
        assertFalse(Identifiers.isSafeVersion("1.0 2"))
    }

    @Test
    fun `leading and trailing edge cases are rejected`() {
        assertFalse(Identifiers.isSafeName(""))
        assertFalse(Identifiers.isSafeName("-leading"))
        assertFalse(Identifiers.isSafeName(".leading"))
        assertFalse(Identifiers.isSafeName("trailing."))
        assertFalse(Identifiers.isSafeName("trailing "))
        assertFalse(Identifiers.isSafeName("a".repeat(Identifiers.MAX_LENGTH + 1)))
        assertTrue(Identifiers.isSafeName("a".repeat(Identifiers.MAX_LENGTH)))
    }

    @Test
    fun `windows reserved names are rejected`() {
        assertFalse(Identifiers.isSafeName("CON"))
        assertFalse(Identifiers.isSafeName("con"))
        assertFalse(Identifiers.isSafeName("nul.apg"))
        assertFalse(Identifiers.isSafeName("COM1"))
        assertFalse(Identifiers.isSafeArch("LPT9"))
        // "console" is not reserved (only the exact stems)
        assertTrue(Identifiers.isSafeName("console"))
        assertTrue(Identifiers.isSafeName("contoso"))
    }

    @Test
    fun `case-insensitive collisions map to the same canonical key`() {
        // On case-insensitive filesystems Package and PACKAGE are the same file.
        assertTrue(Identifiers.canonical("Package") == Identifiers.canonical("PACKAGE"))
        assertFalse(Identifiers.canonical("Package") == Identifiers.canonical("Pakcage"))
    }

    @Test
    fun `unicode homoglyphs are rejected by the ascii allowlist`() {
        assertFalse(Identifiers.isSafeName("раckage")) // Cyrillic 'р','а'
        assertFalse(Identifiers.isSafeName("pkg\u2024")) // one-dot leader
    }
}
