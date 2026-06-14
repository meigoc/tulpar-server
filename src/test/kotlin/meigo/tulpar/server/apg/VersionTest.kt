package meigo.tulpar.server.apg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {

    @Test
    fun `numeric segments compare numerically not lexically`() {
        assertTrue(ApgVersion.compare("1.10", "1.9") > 0)
        assertTrue(ApgVersion.compare("1.9", "1.10") < 0)
        assertEquals(0, ApgVersion.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `missing trailing segments treated as zero`() {
        assertEquals(0, ApgVersion.compare("1.0", "1.0.0"))
        assertTrue(ApgVersion.compare("1.0.1", "1.0") > 0)
    }

    @Test
    fun `null handling matches libAPG`() {
        assertEquals(0, ApgVersion.compare(null, null))
        assertEquals(-1, ApgVersion.compare(null, "1.0"))
        assertEquals(1, ApgVersion.compare("1.0", null))
    }

    @Test
    fun `non-numeric suffixes compare by char`() {
        // "1.0rc1" vs "1.0rc2": equal numeric prefix, then 'r' run is non-numeric
        assertTrue(ApgVersion.compare("1.0rc1", "1.0rc2") < 0)
    }

    @Test
    fun `parse plain name yields ANY`() {
        val d = Dependency.parse("libfoo")
        assertEquals("libfoo", d.name)
        assertEquals(VerOp.ANY, d.op)
        assertNull(d.version)
    }

    @Test
    fun `parse versioned constraint`() {
        val d = Dependency.parse("libfoo >= 2.0")
        assertEquals("libfoo", d.name)
        assertEquals(VerOp.GE, d.op)
        assertEquals("2.0", d.version)
    }

    @Test
    fun `two-char operators win over one-char prefixes`() {
        // ">=" must not be parsed as ">"
        assertEquals(VerOp.GE, Dependency.parse("x>=1").op)
        assertEquals(VerOp.LE, Dependency.parse("x<=1").op)
        assertEquals(VerOp.EQ, Dependency.parse("x==1").op)
        assertEquals(VerOp.NEQ, Dependency.parse("x!=1").op)
    }

    @Test
    fun `canonical string round-trips with single spaces`() {
        assertEquals("libfoo >= 2.0", Dependency.parse("libfoo>=2.0").toCanonicalString())
        assertEquals("libfoo", Dependency.parse("libfoo").toCanonicalString())
    }

    @Test
    fun `satisfaction semantics`() {
        assertTrue(Dependency.parse("x >= 2.0").isSatisfiedBy("2.0"))
        assertTrue(Dependency.parse("x >= 2.0").isSatisfiedBy("2.1"))
        assertFalse(Dependency.parse("x >= 2.0").isSatisfiedBy("1.9"))
        assertTrue(Dependency.parse("x").isSatisfiedBy("anything"))
        assertTrue(Dependency.parse("x != 1.0").isSatisfiedBy("1.1"))
        assertFalse(Dependency.parse("x != 1.0").isSatisfiedBy("1.0"))
    }
}
