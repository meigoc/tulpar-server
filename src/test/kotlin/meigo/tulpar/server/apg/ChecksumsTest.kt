package meigo.tulpar.server.apg

import kotlin.test.Test
import kotlin.test.assertEquals

class ChecksumsTest {

    @Test
    fun `md5 hex matches known vector`() {
        // md5("") = d41d8cd98f00b204e9800998ecf8427e
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ChecksumAlgo.MD5.hex(ByteArray(0)))
    }

    @Test
    fun `sha256 hex matches known vector`() {
        // sha256("") = e3b0c442...
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ChecksumAlgo.SHA256.hex(ByteArray(0)),
        )
    }

    @Test
    fun `crc32 is 8 hex chars`() {
        val h = ChecksumAlgo.CRC32.hex("hello".toByteArray())
        assertEquals(8, h.length)
    }

    @Test
    fun `parses hash-first order (libAPG)`() {
        val body = "d41d8cd98f00b204e9800998ecf8427e  usr/bin/x\n"
        val e = Checksums.parse(body, ChecksumAlgo.MD5).single()
        assertEquals("usr/bin/x", e.relPath)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", e.hash)
    }

    @Test
    fun `parses path-first order (apgcheck and APGexample)`() {
        val body = "usr/bin/apgexample 3239ba88e19f7795694d563f3625f181\n"
        val e = Checksums.parse(body, ChecksumAlgo.MD5).single()
        assertEquals("usr/bin/apgexample", e.relPath)
        assertEquals("3239ba88e19f7795694d563f3625f181", e.hash)
    }

    @Test
    fun `skips comments and blank lines`() {
        val body = "\n# comment\nd41d8cd98f00b204e9800998ecf8427e  a\n\n"
        assertEquals(1, Checksums.parse(body, ChecksumAlgo.MD5).size)
    }

    @Test
    fun `render produces canonical hash-first two-space form`() {
        val rendered = Checksums.render(listOf(ChecksumEntry("usr/bin/x", "abc")))
        assertEquals("abc  usr/bin/x\n", rendered)
    }
}
