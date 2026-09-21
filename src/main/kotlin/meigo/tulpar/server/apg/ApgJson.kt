package meigo.tulpar.server.apg

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strict JSON reader matching yyjson with default flags (`yyjson_read(.., 0)`),
 * the parser libAPG uses for `metadata.json` (libAPG src/json.c).
 *
 * Enforced, exactly like yyjson strict mode:
 *  - RFC 8259 only: no comments, no trailing commas, no unquoted keys,
 *    no NaN/Infinity, no leading zeros in numbers, no trailing garbage;
 *  - a leading UTF-8 BOM is rejected (yyjson: "BOM is not supported");
 *  - raw control characters (< 0x20) inside strings are rejected;
 *  - `\u` escapes with unpaired surrogates are rejected;
 *  - duplicate object keys keep the FIRST value (`yyjson_obj_get` semantics).
 *
 * Recursion depth is capped at [MAX_DEPTH]; yyjson itself is iterative, and a
 * pathological document must not exhaust the server's stack (stricter, documented).
 */
object ApgJson {

    const val MAX_DEPTH = 256

    class ParseException(message: String) : IllegalArgumentException(message)

    /** Parse UTF-8 [bytes] into a [JsonElement] tree, or throw [ParseException]. */
    fun parse(bytes: ByteArray): JsonElement {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            throw ParseException("UTF-8 byte order mark (BOM) is not supported")
        }
        val text = bytes.decodeToString() // strict UTF-8 (invalid sequences throw)
        val p = Reader(text)
        p.skipWs()
        val root = p.readValue(0)
        p.skipWs()
        if (!p.eof()) throw ParseException("trailing characters after JSON value")
        return root
    }

    /** Decode a UTF-16 surrogate pair produced by two consecutive \u escapes. */
    private fun surrogatePair(high: Int, low: Int): String =
        String(Character.toChars(((high - 0xD800) shl 10) + (low - 0xDC00) + 0x10000))

    private class Reader(private val s: String) {
        private var i = 0

        fun eof() = i >= s.length

        fun skipWs() {
            while (i < s.length) {
                when (s[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): JsonElement {
            if (depth > MAX_DEPTH) throw ParseException("maximum nesting depth exceeded")
            skipWs()
            if (eof()) throw ParseException("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> JsonPrimitive(readString())
                't' -> readLiteral("true", JsonPrimitive(true))
                'f' -> readLiteral("false", JsonPrimitive(false))
                'n' -> readLiteral("null", JsonNull)
                '-', in '0'..'9' -> readNumber()
                else -> throw ParseException("unexpected character '$c'")
            }
        }

        private fun readLiteral(word: String, value: JsonElement): JsonElement {
            if (!s.startsWith(word, i)) throw ParseException("invalid literal at $i")
            i += word.length
            return value
        }

        private fun readObject(depth: Int): JsonObject {
            expect('{')
            // Linked map preserving insertion order; first occurrence of a
            // duplicate key wins (later duplicates are parsed and dropped).
            val map = LinkedHashMap<String, JsonElement>()
            skipWs()
            if (peek() == '}') { i++; return JsonObject(map) }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                val value = readValue(depth + 1)
                map.putIfAbsent(key, value)
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return JsonObject(map)
                    else -> throw ParseException("expected ',' or '}' but got '$c'")
                }
            }
        }

        private fun readArray(depth: Int): JsonArray {
            expect('[')
            val items = ArrayList<JsonElement>()
            skipWs()
            if (peek() == ']') { i++; return JsonArray(items) }
            while (true) {
                items.add(readValue(depth + 1))
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return JsonArray(items)
                    else -> throw ParseException("expected ',' or ']' but got '$c'")
                }
            }
        }

        private fun readNumber(): JsonElement {
            val start = i
            if (peek() == '-') i++
            if (eof()) throw ParseException("unterminated number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                throw ParseException("invalid number at $start")
            }
            if (i < s.length && s[i] == '.') {
                i++
                val fracStart = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == fracStart) throw ParseException("number with empty fraction at $start")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val expStart = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == expStart) throw ParseException("number with empty exponent at $start")
            }
            // A numeric primitive (isString == false): the metadata reader
            // distinguishes JSON strings from numbers exactly like yyjson_is_str.
            val text = s.substring(start, i)
            return JsonPrimitive(text.toLongOrNull() ?: text.toDouble())
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw ParseException("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> appendEscape(sb)
                    c.code < 0x20 -> throw ParseException("unescaped control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun appendEscape(sb: StringBuilder) {
            if (eof()) throw ParseException("unterminated escape")
            when (val c = s[i++]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> appendUnicodeEscape(sb)
                else -> throw ParseException("invalid escape '\\$c'")
            }
        }

        private fun appendUnicodeEscape(sb: StringBuilder) {
            val cp = readHex4()
            if (Character.isHighSurrogate(cp.toChar())) {
                // yyjson rejects an unpaired surrogate; a high surrogate must
                // be followed immediately by \uDC00-\uDFFF.
                if (i + 1 < s.length && s[i] == '\\' && s[i + 1] == 'u') {
                    val save = i
                    i += 2
                    val low = readHex4()
                    if (Character.isLowSurrogate(low.toChar())) {
                        sb.append(surrogatePair(cp, low))
                        return
                    }
                    i = save
                }
                throw ParseException("unpaired high surrogate in string")
            }
            if (Character.isLowSurrogate(cp.toChar())) {
                throw ParseException("unpaired low surrogate in string")
            }
            sb.append(cp.toChar())
        }

        private fun readHex4(): Int {
            if (i + 4 > s.length) throw ParseException("truncated \\u escape")
            val hex = s.substring(i, i + 4)
            // Character.digit per char: toIntOrNull(16) would also accept a
            // leading '+'/'-' sign, which is not a valid \u escape.
            var value = 0
            for (c in hex) {
                val d = Character.digit(c, 16)
                if (d < 0) throw ParseException("invalid \\u escape '$hex'")
                value = (value shl 4) or d
            }
            i += 4
            return value
        }

        private fun peek(): Char =
            if (eof()) throw ParseException("unexpected end of input") else s[i]

        private fun next(): Char =
            if (eof()) throw ParseException("unexpected end of input") else s[i++]

        private fun expect(c: Char) {
            if (next() != c) throw ParseException("expected '$c'")
        }
    }
}
