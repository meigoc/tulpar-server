package meigo.tulpar.server.apg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataTest {

    @Test
    fun `parses full v2 metadata`() {
        val json = """
            {
              "name": "curl", "version": "7.85.0", "type": "binary",
              "architecture": "x86_64", "description": "data transfer tool",
              "maintainer": "Team <t@e.org>", "license": "MIT",
              "homepage": "https://curl.se",
              "tags": ["net","http"],
              "dependencies": ["openssl >= 1.1.0","libc"],
              "conflicts": ["wget"], "provides": ["http-client"],
              "replaces": [], "conf": ["/etc/curl/config"]
            }
        """.trimIndent()
        val m = ApgMetadata.parse(json)!!
        assertEquals("curl", m.name)
        assertEquals("x86_64", m.architecture)
        assertEquals(listOf("net", "http"), m.tags)
        assertEquals(2, m.dependencies.size)
        assertEquals("curl@x86_64", m.key)
        assertEquals("x86_64", m.archToken)
    }

    @Test
    fun `null architecture and license become null and noarch token`() {
        val json = """
            {"name":"apgexample","version":"0.0.0","type":"misc",
             "architecture":null,"description":"x","maintainer":"y",
             "license":null,"homepage":"https://nuros.org",
             "tags":["a"],"dependencies":[],"conflicts":[],
             "provides":[],"replaces":[],"conf":[]}
        """.trimIndent()
        val m = ApgMetadata.parse(json)!!
        assertNull(m.architecture)
        assertNull(m.license)
        assertEquals("noarch", m.archToken)
        assertEquals("apgexample", m.key)
    }

    @Test
    fun `v1 metadata without type tags conf still parses`() {
        val json = """
            {"name":"old","version":"1.0","architecture":"aarch64",
             "description":"d","maintainer":"m","license":"GPL-3.0",
             "homepage":"https://x","dependencies":[],"conflicts":[],
             "provides":[],"replaces":[]}
        """.trimIndent()
        val m = ApgMetadata.parse(json)!!
        assertEquals("old", m.name)
        assertNull(m.type)
        assertTrue(m.tags.isEmpty())
        assertTrue(m.conf.isEmpty())
    }

    @Test
    fun `missing name or version yields null`() {
        assertNull(ApgMetadata.parse("""{"version":"1.0"}"""))
        assertNull(ApgMetadata.parse("""{"name":"x"}"""))
    }

    @Test
    fun `tolerates trailing comma and comments`() {
        val json = """
            {
              // a comment
              "name": "x", "version": "1.0",
            }
        """.trimIndent()
        val m = ApgMetadata.parse(json)
        assertEquals("x", m?.name)
    }

    @Test
    fun `parsed dependencies are structured`() {
        val m = ApgMetadata.parse("""{"name":"x","version":"1","dependencies":["a >= 2.0","b"]}""")!!
        val deps = m.parsedDependencies()
        assertEquals(VerOp.GE, deps[0].op)
        assertEquals("2.0", deps[0].version)
        assertEquals(VerOp.ANY, deps[1].op)
    }
}
