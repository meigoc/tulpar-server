package meigo.tulpar.server.web

import io.ktor.utils.io.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import meigo.tulpar.server.apg.ApgTestFixtures
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct unit tests for [StreamingMultipart]: hand-built multipart bodies
 * (well-formed and adversarial) fed through a ByteReadChannel, asserting part
 * contents, caps, and strict error handling.
 */
class StreamingMultipartTest {

    private val boundary = "----TulparTestBoundary7MA4YWxkTrZu0gW"

    private data class Collected(
        val files: MutableList<Pair<String, ByteArray>> = mutableListOf(),
        val fields: MutableList<Pair<String, String>> = mutableListOf(),
    )

    private fun run(body: ByteArray, maxFile: Long = 1L shl 30, maxField: Long = 64 * 1024): Collected {
        val out = Collected()
        val parser = StreamingMultipart(
            ByteReadChannel(body),
            boundary,
            maxFileBytes = maxFile,
            maxFieldBytes = maxField,
            maxTotalBytes = body.size.toLong() + 1024,
        )
        runBlocking {
            parser.consume(
                onFile = { part ->
                    val buf = ByteArrayOutputStream()
                    object : java.io.OutputStream() {
                        override fun write(b: Int) = buf.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
                        override fun close() { out.files.add(part.name to buf.toByteArray()) }
                    }
                },
                onField = { f -> out.fields.add(f.name to f.value) },
            )
        }
        return out
    }

    private fun body(vararg parts: String): ByteArray {
        val sb = StringBuilder()
        for (p in parts) sb.append("--").append(boundary).append("\r\n").append(p)
        sb.append("--").append(boundary).append("--\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun filePart(name: String, filename: String, content: String) =
        "Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n$content\r\n"

    private fun fieldPart(name: String, value: String) =
        "Content-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"

    @Test
    fun `parses a file part and a field part`() {
        val collected = run(
            body(
                fieldPart("channel", "main"),
                filePart("apg", "pkg.apg", "ARCHIVE-BYTES"),
            ),
        )
        assertEquals(listOf("channel" to "main"), collected.fields)
        assertEquals(1, collected.files.size)
        assertEquals("apg", collected.files[0].first)
        assertContentEquals("ARCHIVE-BYTES".toByteArray(), collected.files[0].second)
    }

    @Test
    fun `binary file content with CRLF and boundary-like bytes survives intact`() {
        // Body contains \r\n sequences and a near-miss of the boundary.
        val raw = byteArrayOf(0, 1, 2, 13, 10, 0xFF.toByte(), '-'.code.toByte(), '-'.code.toByte())
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"apg\"; filename=\"x.apg\"\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1)
        val collected = run(prefix + raw + suffix)
        assertContentEquals(raw, collected.files.single().second)
    }

    @Test
    fun `delimiter split across reads is matched`() {
        // Feed the body 3 bytes at a time through a ByteChannel so every
        // delimiter straddles read boundaries.
        val full = body(filePart("apg", "p.apg", "payload-that-is-long-enough-to-span-several-reads-0123456789"))
        val out = Collected()
        runBlocking {
            val ch = ByteChannel(autoFlush = false)
            val writer = launch {
                var i = 0
                while (i < full.size) {
                    val n = minOf(3, full.size - i)
                    ch.writeFully(full, i, i + n)
                    ch.flush()
                    yield()
                    i += n
                }
                ch.close()
            }
            val parser = StreamingMultipart(
                ch,
                boundary,
                maxFileBytes = 1 shl 20,
                maxFieldBytes = 1024,
                maxTotalBytes = full.size.toLong() + 64,
            )
            parser.consume(
                onFile = { part ->
                    val buf = ByteArrayOutputStream()
                    object : java.io.OutputStream() {
                        override fun write(b: Int) = buf.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
                        override fun close() { out.files.add(part.name to buf.toByteArray()) }
                    }
                },
                onField = {},
            )
            writer.join()
        }
        assertContentEquals(
            "payload-that-is-long-enough-to-span-several-reads-0123456789".toByteArray(),
            out.files.single().second,
        )
    }

    @Test
    fun `file part over the cap throws MultipartLimitException`() {
        val big = "x".repeat(200)
        val e = assertFailsWith<MultipartLimitException> {
            run(body(filePart("apg", "p.apg", big)), maxFile = 100)
        }
        assertTrue(e.message!!.contains("exceeds"))
    }

    @Test
    fun `form field over the cap throws MultipartLimitException`() {
        val big = "y".repeat(200)
        assertFailsWith<MultipartLimitException> {
            run(body(fieldPart("channel", big)), maxField = 100)
        }
    }

    @Test
    fun `truncated stream (no closing boundary) is a 400-class error`() {
        val partial = ("--$boundary\r\nContent-Disposition: form-data; name=\"apg\"; filename=\"p.apg\"\r\n\r\nabc")
            .toByteArray(Charsets.ISO_8859_1)
        assertFailsWith<BadRequestUploadException> { run(partial) }
    }

    @Test
    fun `missing Content-Disposition is rejected`() {
        val b = ("--$boundary\r\nContent-Type: text/plain\r\n\r\nabc\r\n--$boundary--\r\n")
            .toByteArray(Charsets.ISO_8859_1)
        assertFailsWith<BadRequestUploadException> { run(b) }
    }

    @Test
    fun `Content-Transfer-Encoding parts are rejected not guessed`() {
        val b = ("--$boundary\r\nContent-Disposition: form-data; name=\"apg\"; filename=\"p\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\nYWJj\r\n--$boundary--\r\n").toByteArray(Charsets.ISO_8859_1)
        assertFailsWith<BadRequestUploadException> { run(b) }
    }

    @Test
    fun `junk before the first boundary is ignored (preamble)`() {
        val b = ("This is a preamble.\r\n--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"f\"; filename=\"f\"\r\n\r\nv\r\n--$boundary--\r\n")
            .toByteArray(Charsets.ISO_8859_1)
        val collected = run(b)
        assertContentEquals("v".toByteArray(), collected.files.single().second)
    }

    @Test
    fun `empty body (boundaries only) yields zero parts`() {
        val b = ("--$boundary--\r\n").toByteArray(Charsets.ISO_8859_1)
        val collected = run(b)
        assertEquals(0, collected.files.size)
        assertEquals(0, collected.fields.size)
    }

    @Test
    fun `epilogue after the closing boundary is ignored`() {
        val b = ("--$boundary\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f\"\r\n\r\nv\r\n" +
            "--$boundary--\r\ntrailing junk that must be ignored\r\n").toByteArray(Charsets.ISO_8859_1)
        val collected = run(b)
        assertContentEquals("v".toByteArray(), collected.files.single().second)
    }

    @Test
    fun `multiple file parts in order with fields interleaved`() {
        val collected = run(
            body(
                filePart("apg", "a.apg", "AAA"),
                fieldPart("channel", "testing"),
                filePart("sig", "a.apg.sig", "SIGSIG"),
            ),
        )
        assertEquals(listOf("apg" to "AAA".toByteArray(), "sig" to "SIGSIG".toByteArray()).map { it.first }, collected.files.map { it.first })
        assertContentEquals("AAA".toByteArray(), collected.files[0].second)
        assertContentEquals("SIGSIG".toByteArray(), collected.files[1].second)
        assertEquals(listOf("channel" to "testing"), collected.fields)
    }

    @Test
    fun `a real package fixture round-trips byte-identically`() {
        val pkg = ApgTestFixtures.validV2Package("parser", "1.0", "x86_64")
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"apg\"; filename=\"p.apg\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1)
        val collected = run(prefix + pkg + suffix)
        assertContentEquals(pkg, collected.files.single().second)
    }

    @Test
    fun `total cap is enforced across parts`() {
        val full = body(
            filePart("apg", "a.apg", "x".repeat(500)),
            fieldPart("channel", "y".repeat(500)),
        )
        val parser = StreamingMultipart(
            ByteReadChannel(full), boundary,
            maxFileBytes = 1 shl 20, maxFieldBytes = 1 shl 20, maxTotalBytes = 200,
        )
        assertFailsWith<MultipartLimitException> {
            runBlocking { parser.consume(onFile = { ByteArrayOutputStream() }, onField = {}) }
        }
    }
}
