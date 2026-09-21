package meigo.tulpar.server.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** A malformed multipart body; maps to HTTP 400. */
class BadRequestUploadException(message: String) : Exception(message)

/** A multipart size cap was exceeded; maps to HTTP 413. */
class MultipartLimitException(message: String) : Exception(message)

/**
 * Minimal streaming multipart/form-data reader (RFC 2046 / RFC 7578) for the
 * publish endpoint.
 *
 * Why not Ktor's `receiveMultipart`: it enforces ONE per-part limit
 * (`formFieldLimit`) on every part — a file part larger than the limit fails
 * the boundary scan before the route sees it, while a small limit that
 * protects form fields rejects every real package (both verified empirically
 * against Ktor 3.3.3). This reader decouples the two:
 *  - file parts stream into a per-part sink bounded by [maxFileBytes]
 *    (heap use is O(read buffer), not O(part));
 *  - non-file parts accumulate in memory bounded by [maxFieldBytes];
 *  - every byte read counts against [maxTotalBytes].
 *
 * Supported surface is exactly what publish accepts: file parts with a
 * Content-Disposition name and optional filename, plus small text fields.
 * Parts with Content-Transfer-Encoding are rejected rather than guessed. All
 * protocol violations raise [BadRequestUploadException]; all cap breaches
 * raise [MultipartLimitException].
 *
 * Structure: one rolling window of unconsumed bytes and a five-state machine
 * (PREAMBLE → HEADERS → BODY → BOUNDARY → done). The window holds at most the
 * unflushable tail of a part body (`crlfDelim.size - 1` bytes that could
 * begin a delimiter split across reads) plus buffered headers, so memory
 * stays bounded. Behavior is pinned by StreamingMultipartTest (hand-built
 * bodies incl. split delimiters, preambles, epilogues, truncation) and by the
 * publish integration tests (real Ktor-client bodies).
 */
class StreamingMultipart(
    private val channel: ByteReadChannel,
    boundary: String,
    private val maxFileBytes: Long,
    private val maxFieldBytes: Long,
    private val maxTotalBytes: Long,
) {
    data class Field(val name: String, val value: String)
    data class FilePart(val name: String, val filename: String?, val headers: Headers)

    private val delim: ByteArray = "--$boundary".toByteArray(Charsets.US_ASCII)
    private val crlfDelim: ByteArray = byteArrayOf(CR, LF) + delim
    private val readBuf = ByteArray(READ_BUFFER)
    private var totalRead = 0L

    private enum class State { PREAMBLE, HEADERS, BODY, BOUNDARY }

    /**
     * Consume the entire body. [onFile] returns the sink for each file part
     * (closed here on every path); [onField] receives each completed field.
     * Returns only after the closing boundary (a zero-part body returns with
     * nothing emitted); throws [BadRequestUploadException] on malformed input
     * or a truncated stream, [MultipartLimitException] past any cap.
     */
    suspend fun consume(onFile: (FilePart) -> OutputStream, onField: (Field) -> Unit) {
        var window = ByteArray(0)
        var state = State.PREAMBLE
        var meta: PartMeta? = null
        var sink: OutputStream? = null
        var fieldBuf: ByteArrayOutputStream? = null
        var written = 0L
        var eof = false

        fun closeSink() {
            runCatching { sink?.close() }
            sink = null
        }

        try {
            while (true) {
                // Fill the window until the current state can make progress.
                while (!eof && !canProgress(state, window)) {
                    val n = channel.readAvailable(readBuf, 0, readBuf.size)
                    if (n <= 0) {
                        eof = true
                    } else {
                        totalRead += n
                        if (totalRead > maxTotalBytes) {
                            throw MultipartLimitException("multipart body exceeds $maxTotalBytes bytes")
                        }
                        window = if (window.isEmpty()) readBuf.copyOf(n) else window + readBuf.copyOf(n)
                    }
                }

                when (state) {
                    State.PREAMBLE -> {
                        // First boundary: delim at offset 0, or CRLF+delim.
                        val bare = indexOf(window, delim, 0)
                        val withCrlf = indexOf(window, crlfDelim, 0)
                        val (start, len) = when {
                            bare == 0 -> 0 to delim.size
                            withCrlf >= 0 && (bare < 0 || withCrlf < bare) -> withCrlf to crlfDelim.size
                            bare >= 0 -> bare to delim.size
                            else -> {
                                if (eof) throw BadRequestUploadException("stream ended before the first multipart boundary")
                                if (window.size > HEADER_CAP) {
                                    throw BadRequestUploadException("multipart preamble exceeds $HEADER_CAP bytes")
                                }
                                continue // need more input
                            }
                        }
                        state = State.BOUNDARY
                        window = window.copyOfRange(start + len, window.size)
                    }

                    State.HEADERS -> {
                        if (window.size > HEADER_CAP && indexOfCrlfCrlf(window) < 0) {
                            throw BadRequestUploadException("multipart part headers exceed $HEADER_CAP bytes")
                        }
                        val end = indexOfCrlfCrlf(window)
                        if (end < 0) {
                            if (eof) throw BadRequestUploadException("stream ended inside part headers")
                            continue
                        }
                        meta = parsePartHeaders(window.copyOfRange(0, end).toString(Charsets.UTF_8))
                        window = window.copyOfRange(end + 4, window.size)
                        written = 0
                        if (meta!!.filename != null) {
                            sink = onFile(FilePart(meta!!.name, meta!!.filename, meta!!.headers))
                            fieldBuf = null
                        } else {
                            sink = null
                            fieldBuf = ByteArrayOutputStream()
                        }
                        state = State.BODY
                    }

                    State.BODY -> {
                        val idx = indexOf(window, crlfDelim, 0)
                        val flushTo = if (idx >= 0) idx else (window.size - (crlfDelim.size - 1)).coerceAtLeast(0)
                        if (flushTo > 0) {
                            written += flushTo
                            val isFile = sink != null
                            val cap = if (isFile) maxFileBytes else maxFieldBytes
                            if (written > cap) {
                                throw MultipartLimitException(
                                    if (isFile) "file part '${meta!!.name}' exceeds $cap bytes"
                                    else "form field '${meta!!.name}' exceeds $cap bytes",
                                )
                            }
                            if (isFile) sink!!.write(window, 0, flushTo)
                            else fieldBuf!!.write(window, 0, flushTo)
                            window = window.copyOfRange(flushTo, window.size)
                        }
                        if (idx >= 0) {
                            // After the flush the window starts AT the delimiter.
                            window = window.copyOfRange(crlfDelim.size, window.size)
                            state = State.BOUNDARY
                        } else if (eof) {
                            throw BadRequestUploadException("stream ended inside a part body")
                        }
                        // else: window now holds only a possible partial delimiter; read more.
                    }

                    State.BOUNDARY -> {
                        // After a delimiter: "--" closes the body, CRLF starts
                        // the next part's headers.
                        if (window.size < 2) {
                            if (eof) {
                                // Trailing delimiter at EOF with no "--": treat a
                                // fully-emitted close as end only if "--" absent.
                                if (window.isEmpty()) {
                                    finishPart(fieldBuf, meta, onField, ::closeSink)
                                    return
                                }
                                throw BadRequestUploadException("stream ended at a multipart boundary")
                            }
                            continue
                        }
                        if (window[0] == DASH && window[1] == DASH) {
                            finishPart(fieldBuf, meta, onField, ::closeSink)
                            return // closing boundary; epilogue ignored
                        }
                        if (window[0] == CR && window[1] == LF) {
                            finishPart(fieldBuf, meta, onField, ::closeSink)
                            window = window.copyOfRange(2, window.size)
                            meta = null
                            fieldBuf = null
                            state = State.HEADERS
                        } else {
                            throw BadRequestUploadException("malformed multipart: CRLF or -- expected after boundary")
                        }
                    }
                }
            }
        } finally {
            closeSink()
        }
    }

    /** Whether the current state can act on the window without more input. */
    private fun canProgress(state: State, window: ByteArray): Boolean = when (state) {
        State.PREAMBLE -> indexOf(window, delim, 0) >= 0 || window.size > HEADER_CAP
        State.HEADERS -> indexOfCrlfCrlf(window) >= 0 || window.size > HEADER_CAP
        State.BODY -> indexOf(window, crlfDelim, 0) >= 0 || window.size > crlfDelim.size - 1
        State.BOUNDARY -> window.size >= 2
    }

    /** Emit a completed non-file part; close and reset the file sink. */
    private fun finishPart(
        fieldBuf: ByteArrayOutputStream?,
        meta: PartMeta?,
        onField: (Field) -> Unit,
        closeSink: () -> Unit,
    ) {
        closeSink()
        if (meta != null && meta.filename == null && fieldBuf != null) {
            if (fieldBuf.size() > maxFieldBytes) {
                throw MultipartLimitException("form field '${meta.name}' exceeds $maxFieldBytes bytes")
            }
            onField(Field(meta.name, fieldBuf.toString(Charsets.UTF_8)))
        }
    }

    private data class PartMeta(val name: String, val filename: String?, val headers: Headers)

    private fun parsePartHeaders(block: String): PartMeta {
        val headers = HeadersBuilder()
        for (line in block.split("\r\n")) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw BadRequestUploadException("malformed multipart part header line")
            headers.append(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
        }
        if (headers["Content-Transfer-Encoding"] != null) {
            throw BadRequestUploadException("Content-Transfer-Encoding parts are not supported")
        }
        val cd = headers[HttpHeaders.ContentDisposition]
            ?: throw BadRequestUploadException("multipart part without Content-Disposition")
        val disposition = runCatching { ContentDisposition.parse(cd) }
            .getOrElse { throw BadRequestUploadException("unparseable multipart Content-Disposition") }
        val name = disposition.parameter(ContentDisposition.Parameters.Name)
            ?: throw BadRequestUploadException("multipart part without a name parameter")
        val filename = disposition.parameter(ContentDisposition.Parameters.FileName)
        return PartMeta(name, filename, headers.build())
    }

    private companion object {
        const val READ_BUFFER = 64 * 1024
        const val HEADER_CAP = 64 * 1024
        val CR = '\r'.code.toByte()
        val LF = '\n'.code.toByte()
        val DASH = '-'.code.toByte()

        fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
            if (needle.isEmpty() || hay.size - from < needle.size) return -1
            outer@ for (i in from..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }

        fun indexOfCrlfCrlf(b: ByteArray): Int {
            for (i in 0..b.size - 4) {
                if (b[i] == CR && b[i + 1] == LF && b[i + 2] == CR && b[i + 3] == LF) return i
            }
            return -1
        }
    }
}

/** The multipart boundary from a form-data Content-Type, or null. */
fun ApplicationCall.multipartBoundary(): String? {
    val ct = request.contentType()
    if (ct.contentType.lowercase() != "multipart" || ct.contentSubtype.lowercase() != "form-data") return null
    return ct.parameter("boundary")?.trim('"')?.takeIf { it.isNotEmpty() }
}
