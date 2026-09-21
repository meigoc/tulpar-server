package meigo.tulpar.server.apg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `comments are rejected (yyjson strict, as libAPG parses)`() {
        // libAPG reads metadata.json with yyjson default flags: comments are a
        // hard parse error there, so they must be one here (oracle corpus 21).
        assertFailsWith<ApgJson.ParseException> {
            ApgMetadata.parse("""{/*c*/"name":"x","version":"1.0"}""")
        }
    }

    @Test
    fun `trailing commas are rejected (yyjson strict)`() {
        assertFailsWith<ApgJson.ParseException> {
            ApgMetadata.parse("""{"name":"x","version":"1.0",}""")
        }
    }

    @Test
    fun `leading BOM is rejected (yyjson strict)`() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """{"name":"x","version":"1.0"}""".toByteArray()
        assertFailsWith<ApgJson.ParseException> { ApgMetadata.parse(withBom) }
    }

    @Test
    fun `unpaired surrogate escape is rejected (yyjson strict)`() {
        assertFailsWith<ApgJson.ParseException> {
            ApgMetadata.parse("""{"name":"x","version":"1.0","description":"\ud800"}""")
        }
    }

    @Test
    fun `raw control character in string is rejected (yyjson strict)`() {
        assertFailsWith<ApgJson.ParseException> {
            ApgMetadata.parse("{\"name\":\"x\",\"version\":\"1.0\",\"description\":\"tab\there\"}")
        }
    }

    @Test
    fun `numeric version is not a string so the package is not indexable`() {
        // libAPG accepts the file but leaves version NULL (yyjson_is_str guard,
        // oracle corpus 23); the server cannot index it.
        val raw = RawMetadata.from(ApgMetadata.parseObject("""{"name":"adv","version":1.0}""".toByteArray())!!)
        assertEquals("adv", raw.name)
        assertNull(raw.version)
        assertNull(ApgMetadata.parse("""{"name":"adv","version":1.0}"""))
    }

    @Test
    fun `empty object parses for libAPG but has no indexable fields`() {
        val raw = RawMetadata.from(ApgMetadata.parseObject("{}".toByteArray())!!)
        assertNull(raw.name)
        assertNull(raw.version)
        assertNull(ApgMetadata.parse("{}"))
    }

    @Test
    fun `duplicate keys keep the first value (yyjson_obj_get semantics)`() {
        val raw = RawMetadata.from(
            ApgMetadata.parseObject("""{"name":"first","name":"second","version":"1.0"}""".toByteArray())!!,
        )
        assertEquals("first", raw.name)
    }

    @Test
    fun `non-string array items are dropped (libAPG parse_str_array)`() {
        val raw = RawMetadata.from(
            ApgMetadata.parseObject(
                """{"name":"a","version":"1","tags":["x",5,null,"y"],"dependencies":["ok",7]}""".toByteArray(),
            )!!,
        )
        assertEquals(listOf("x", "y"), raw.tags)
        assertEquals(listOf("ok"), raw.dependencies)
    }

    @Test
    fun `empty strings stay empty strings (not null)`() {
        val raw = RawMetadata.from(
            ApgMetadata.parseObject("""{"name":"a","version":"1","description":"","architecture":""}""".toByteArray())!!,
        )
        assertEquals("", raw.description)
        assertEquals("", raw.architecture)
        // empty architecture still maps to the noarch token
        assertEquals("noarch", ApgMetadata.parse("""{"name":"a","version":"1","architecture":""}""")!!.archToken)
    }

    @Test
    fun `unknown fields are ignored (libAPG reads a fixed field set)`() {
        val m = ApgMetadata.parse(
            """{"name":"adv","version":"1.0","future_field":{"x":1},"zeta":"kept"}""",
        )!!
        assertEquals("adv", m.name)
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
