package meigo.tulpar.server.security

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathSafetyTest {

    @Test
    fun `clean segments are safe`() {
        assertTrue(PathSafety.isSafeSegment("curl"))
        assertTrue(PathSafety.isSafeSegment("7.85.0-1"))
        assertTrue(PathSafety.isSafeSegment("x86_64"))
        assertTrue(PathSafety.allSafe("main", "curl", "7.85.0", "x86_64"))
    }

    @Test
    fun `traversal separators and dot segments are unsafe`() {
        assertFalse(PathSafety.isSafeSegment(".."))
        assertFalse(PathSafety.isSafeSegment("."))
        assertFalse(PathSafety.isSafeSegment("a/b"))
        assertFalse(PathSafety.isSafeSegment("a\\b"))
        assertFalse(PathSafety.isSafeSegment("a/../b"))
        assertFalse(PathSafety.isSafeSegment("   "))
        assertFalse(PathSafety.isSafeSegment(""))
    }

    @Test
    fun `NUL bytes are unsafe`() {
        assertFalse(PathSafety.isSafeSegment("bad\u0000name"))
        assertFalse(PathSafety.isSafeSegment("\u0000"))
    }

    @Test
    fun `resolveContained keeps paths inside the base`() {
        val base = Files.createTempDirectory("tulpar-pathsafety").toFile()
        try {
            val inside = PathSafety.resolveContained(base, "pool/main/curl/x86_64/curl-1-x86_64.apg")
            assertTrue(inside != null && inside.path.startsWith(base.canonicalFile.path))

            assertNull(PathSafety.resolveContained(base, "../escape.apg"))
            assertNull(PathSafety.resolveContained(base, "pool/../../escape.apg"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `resolveContained rejects absolute escapes`() {
        val base = Files.createTempDirectory("tulpar-pathsafety").toFile()
        try {
            // File(base, "/etc/passwd") resolves to /etc/passwd on POSIX —
            // canonicalization must catch it.
            val resolved = PathSafety.resolveContained(base, "/etc/passwd")
            assertTrue(resolved == null || resolved.path.startsWith(base.canonicalFile.path))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `base itself resolves to base`() {
        val base = Files.createTempDirectory("tulpar-pathsafety").toFile()
        try {
            assertEquals(base.canonicalFile.path, PathSafety.resolveContained(base, ".")?.path)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `symlinked base is canonicalized`() {
        val base = Files.createTempDirectory("tulpar-pathsafety").toFile()
        try {
            File(base, "pool").mkdirs()
            val link = File(base.parentFile, "link-to-base")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), base.toPath())
                val resolved = PathSafety.resolveContained(link, "pool/x.apg")
                assertTrue(resolved != null)
                assertTrue(resolved.path.startsWith(base.canonicalFile.path))
            } catch (e: Exception) {
                when (e) {
                    // Platforms/configurations without symlink support
                    // (Windows without developer mode, read-only FS): nothing
                    // to assert.
                    is UnsupportedOperationException, is java.io.IOException -> Unit
                    else -> throw e
                }
            } finally {
                link.delete()
            }
        } finally {
            base.deleteRecursively()
        }
    }
}
