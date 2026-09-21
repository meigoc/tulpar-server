package meigo.tulpar.server.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import meigo.tulpar.server.apg.ApgArchive
import meigo.tulpar.server.apg.ApgFormatException
import meigo.tulpar.server.apg.ApgReadLimits
import meigo.tulpar.server.apg.ApgValidator
import meigo.tulpar.server.apg.RawMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Hermetic conformance suite: replays the recorded verdicts of the libAPG C
 * oracle (`conformance/oracle/apg_oracle.c`, run against libAPG at the commit
 * pinned in docs/libapg-compat.md) over the committed corpus, without needing
 * libAPG at test time.
 *
 * The goldens under `src/test/resources/conformance/` are the source of truth:
 * they were produced by the oracle itself (see conformance/README.md). The
 * live-oracle re-verification lives in the `conformanceTest` Gradle task.
 *
 * Verdict semantics compared per case:
 *  - libAPG `parse_package` acceptance == server archive-read success
 *    (safety/filter rules) AND strict metadata parse to an object;
 *  - when accepted, every parsed metadata field must equal the oracle's
 *    (names, versions, lists — including libAPG's quirks: duplicate keys keep
 *    the first value, non-string array items are dropped, non-string scalars
 *    leave fields unset).
 */
class GoldenCorpusTest {

    private val json = Json { }

    private fun corpusResource(name: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("conformance/$name")
            ?: fail("missing conformance resource: $name")
        return stream.use { it.readBytes() }
    }

    private data class OracleCase(val file: String, val expected: JsonObject)

    private fun loadParseGoldens(): List<OracleCase> {
        val root = json.parseToJsonElement(corpusResource("goldens-parse.json").decodeToString())
        return root.jsonArray.map { item ->
            val obj = item.jsonObject
            OracleCase(
                file = obj["case"]!!.jsonPrimitive.content,
                expected = obj["oracle"]!!.jsonObject,
            )
        }
    }

    /** Server-side equivalent of the oracle's parse verdict. */
    private fun serverParse(file: String): ServerParseVerdict {
        val bytes = corpusResource("corpus/$file")
        val archive = try {
            ApgArchive.read(bytes.inputStream(), ApgReadLimits(captureDataDigests = true))
        } catch (e: ApgFormatException) {
            return ServerParseVerdict(accepted = false, raw = null)
        } catch (e: IllegalArgumentException) {
            return ServerParseVerdict(accepted = false, raw = null)
        }
        val metaBytes = archive.metadataBytes()
            ?: return ServerParseVerdict(accepted = false, raw = null)
        val root = try {
            meigo.tulpar.server.apg.ApgMetadata.parseObject(metaBytes)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return ServerParseVerdict(accepted = false, raw = null)
        return ServerParseVerdict(accepted = true, raw = RawMetadata.from(root))
    }

    private class ServerParseVerdict(val accepted: Boolean, val raw: RawMetadata?)

    private fun assertOracleField(
        expected: JsonObject,
        key: String,
        actual: String?,
        file: String,
    ) {
        val exp = expected[key]!!.jsonPrimitive
        val expValue = if (exp.isString) exp.content else null
        assertEquals(expValue, actual, "field '$key' mismatch in $file")
    }

    private fun assertOracleList(
        expected: JsonObject,
        key: String,
        actual: List<String>,
        file: String,
    ) {
        val exp = expected[key]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(exp, actual, "list '$key' mismatch in $file")
    }

    @Test
    fun `server verdicts match the recorded libAPG oracle verdicts for every corpus case`() {
        val failures = ArrayList<String>()
        for (case in loadParseGoldens()) {
            val expectedAccepted = case.expected["accepted"]!!.jsonPrimitive.boolean
            val actual = serverParse(case.file)
            if (actual.accepted != expectedAccepted) {
                failures.add("${case.file}: oracle accepted=$expectedAccepted server accepted=${actual.accepted}")
                continue
            }
            if (!expectedAccepted || actual.raw == null) continue

            val raw = actual.raw
            val e = case.expected
            assertOracleField(e, "name", raw.name, case.file)
            assertOracleField(e, "version", raw.version, case.file)
            assertOracleField(e, "type", raw.type, case.file)
            assertOracleField(e, "architecture", raw.architecture, case.file)
            assertOracleField(e, "description", raw.description, case.file)
            assertOracleField(e, "maintainer", raw.maintainer, case.file)
            assertOracleField(e, "license", raw.license, case.file)
            assertOracleField(e, "homepage", raw.homepage, case.file)
            assertOracleList(e, "tags", raw.tags, case.file)
            // The oracle reports dependencies in canonical dep_constraint form
            // (name op version); compare against the parsed constraints.
            val depsCanonical = raw.dependencies.map {
                meigo.tulpar.server.apg.Dependency.parse(it).toCanonicalString()
            }
            assertOracleList(e, "dependencies", depsCanonical, case.file)
            assertOracleList(e, "conflicts", raw.conflicts, case.file)
            assertOracleList(e, "provides", raw.provides, case.file)
            assertOracleList(e, "replaces", raw.replaces, case.file)
            assertOracleList(e, "conf", raw.conf, case.file)
        }
        if (failures.isNotEmpty()) {
            fail("conformance mismatches vs libAPG oracle goldens:\n  " + failures.joinToString("\n  "))
        }
    }

    @Test
    fun `version comparison matches the recorded libAPG oracle`() {
        val goldens = json.parseToJsonElement(corpusResource("goldens-vercmp.json").decodeToString())
            .jsonArray
            .map { it.jsonObject }
        val failures = ArrayList<String>()
        for (g in goldens) {
            val a = g["a"]!!.jsonPrimitive.content
            val b = g["b"]!!.jsonPrimitive.content
            val expected = g["cmp"]!!.jsonPrimitive.content.toInt()
            val actual = sign(meigo.tulpar.server.apg.ApgVersion.compare(a, b))
            if (actual != expected) failures.add("ver_compare(\"$a\",\"$b\"): oracle=$expected server=$actual")
        }
        if (failures.isNotEmpty()) {
            fail("version comparison mismatches vs libAPG oracle:\n  " + failures.take(40).joinToString("\n  ") +
                (if (failures.size > 40) "\n  ... and ${failures.size - 40} more" else ""))
        }
    }

    @Test
    fun `dependency parsing matches the recorded libAPG oracle`() {
        val goldens = json.parseToJsonElement(corpusResource("goldens-depparse.json").decodeToString())
            .jsonArray
            .map { it.jsonObject }
        val failures = ArrayList<String>()
        for (g in goldens) {
            val input = g["input"]!!.jsonPrimitive.content
            val expected = g["oracle"]!!.jsonObject
            val parsed = meigo.tulpar.server.apg.Dependency.parse(input)
            val expName = expected["name"]!!.jsonPrimitive.takeIf { it.isString }?.content
            val expVersion = expected["version"]!!.jsonPrimitive.takeIf { it.isString }?.content
            val expCanonical = expected["canonical"]!!.jsonPrimitive.takeIf { it.isString }?.content
            if (parsed.name != expName) failures.add("depparse(\"$input\") name: oracle=$expName server=${parsed.name}")
            if (parsed.version != expVersion) failures.add("depparse(\"$input\") version: oracle=$expVersion server=${parsed.version}")
            if (parsed.toCanonicalString() != expCanonical) {
                failures.add("depparse(\"$input\") canonical: oracle=$expCanonical server=${parsed.toCanonicalString()}")
            }
        }
        if (failures.isNotEmpty()) {
            fail("dependency parse mismatches vs libAPG oracle:\n  " + failures.joinToString("\n  "))
        }
    }

    @Test
    fun `check-cli verdict equals libAPG acceptance plus the data dir requirement`() {
        // The server's `check` verdict = libAPG parse acceptance AND data/
        // present (libAPG's install_data_dir fails without it). Replayed over
        // the same corpus using the committed goldens.
        val validator = ApgValidator(verifyChecksums = false)
        val failures = ArrayList<String>()
        for (case in loadParseGoldens()) {
            val expectedAccepted = case.expected["accepted"]!!.jsonPrimitive.boolean
            val bytes = corpusResource("corpus/${case.file}")
            val result = validator.validateBytes(bytes)
            // data/ presence, per the oracle corpus: every accepted case that
            // lacks data/ still parses, so `check` adds the requirement.
            val hasData = expectedAccepted && run {
                val a = ApgArchive.read(bytes.inputStream())
                a.hasDataDir()
            }
            val expectedCheck = expectedAccepted && hasData
            if (result.libapgCompatible != expectedCheck) {
                failures.add("${case.file}: expected check=${expectedCheck} got=${result.libapgCompatible} (${result.rejectionReasons})")
            }
        }
        if (failures.isNotEmpty()) fail("check verdict mismatches:\n  " + failures.joinToString("\n  "))
    }

    private fun sign(v: Int) = if (v > 0) 1 else if (v < 0) -1 else 0
}
