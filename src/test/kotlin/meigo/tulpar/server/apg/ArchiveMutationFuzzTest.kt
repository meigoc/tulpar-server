package meigo.tulpar.server.apg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Bounded, deterministic mutation testing for the archive reader.
 *
 * Every run uses a fixed seed, so failures reproduce exactly. Mutations
 * target the compression header, tar structure and payload of valid
 * packages; the reader must either parse the result or fail with a clean
 * [ApgFormatException]/[IllegalArgumentException] — never crash the JVM,
 * hang, or succeed with corrupted state.
 *
 * The same mutated bytes also pass through the validator: its verdict must
 * stay internally consistent (a rejection always carries reasons).
 */
class ArchiveMutationFuzzTest {

    private val seed = 0x5EEDL
    private val iterations = 300

    private fun basePackages(): List<ByteArray> = listOf(
        ApgTestFixtures.validV2Package(),
        ApgTestFixtures.tarZstd(
            linkedMapOf(
                "metadata.json" to ApgTestFixtures.metadataJson("z", "1.0"),
                "data/usr/bin/z" to ByteArray(2048) { (it % 251).toByte() },
            ),
        ),
        ApgTestFixtures.tarGz(
            linkedMapOf(
                "metadata.json" to ApgTestFixtures.metadataJson("g", "1.0"),
                "data/usr/bin/g" to "payload".toByteArray(),
            ),
        ),
        ApgTestFixtures.tarRaw(
            linkedMapOf(
                "metadata.json" to ApgTestFixtures.metadataJson("r", "1.0"),
                "data/usr/bin/r" to "payload".toByteArray(),
            ),
        ),
    )

    private fun mutate(bytes: ByteArray, rnd: Random): ByteArray {
        val out = bytes.copyOf()
        when (rnd.nextInt(6)) {
            // Flip bits in the compression/stream header.
            0 -> repeat(1 + rnd.nextInt(4)) {
                val i = rnd.nextInt(minOf(16, out.size))
                out[i] = (out[i].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            }
            // Flip bits at a random position.
            1 -> repeat(1 + rnd.nextInt(8)) {
                val i = rnd.nextInt(out.size)
                out[i] = (out[i].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            }
            // Truncate.
            2 -> return out.copyOfRange(0, rnd.nextInt(out.size + 1))
            // Append garbage.
            3 -> {
                val extra = ByteArray(1 + rnd.nextInt(64)).also { rnd.nextBytes(it) }
                return out + extra
            }
            // Overwrite a random window with random bytes.
            4 -> {
                val from = rnd.nextInt(out.size)
                val len = minOf(rnd.nextInt(48), out.size - from)
                rnd.nextBytes(out, from, len)
                return out
            }
            // Byte-swap two positions.
            else -> {
                if (out.size >= 2) {
                    val i = rnd.nextInt(out.size)
                    val j = rnd.nextInt(out.size)
                    val t = out[i]; out[i] = out[j]; out[j] = t
                }
            }
        }
        return out
    }

    private fun Random.nextBytes(dst: ByteArray, offset: Int, len: Int) {
        for (i in offset until offset + len) dst[i] = nextInt(256).toByte()
    }

    @Test
    fun `mutated archives are rejected cleanly or parse without corruption`() {
        val bases = basePackages()
        val rnd = Random(seed)
        var parsed = 0
        var rejected = 0
        for (i in 0 until iterations) {
            val base = bases[i % bases.size]
            val mutated = mutate(base, rnd)
            try {
                val archive = ApgArchive.read(ByteArrayInputStream(mutated))
                parsed++
                // If it parsed, the structural invariants must hold.
                for (entry in archive.entries) {
                    if (entry.path.split('/').any { it == ".." }) {
                        fail("mutation $i: reader accepted a '..' segment: ${entry.path}")
                    }
                    assertTrue(entry.path.length <= 4095, "mutation $i: path length cap violated")
                }
                // Metadata, when present, must strictly parse (or be absent).
                archive.metadataBytes()?.let { ApgMetadata.parseObject(it) }
                // Checksum digests, when captured, must be self-consistent.
                val withDigests = ApgArchive.read(
                    ByteArrayInputStream(mutated),
                    ApgReadLimits(captureDataDigests = true),
                )
                for (f in withDigests.dataFiles()) {
                    val d = withDigests.digests(f) ?: fail("mutation $i: data file $f has no digest")
                    assertTrue(d.md5.length == 32 && d.crc32.length == 8 && d.sha256.length == 64)
                }
            } catch (e: ApgFormatException) {
                rejected++
            } catch (e: IllegalArgumentException) {
                rejected++
            } catch (e: java.io.IOException) {
                rejected++
            } catch (e: ArrayIndexOutOfBoundsException) {
                fail("mutation $i leaked an AIOOBE (should be a clean format error): $e")
            }
            // The validator must never throw on mutated input — it converts
            // every read failure into a rejection verdict.
            val verdict = ApgValidator(verifyChecksums = true).validateBytes(mutated)
            if (!verdict.libapgCompatible) {
                assertTrue(verdict.rejectionReasons.isNotEmpty(), "mutation $i: rejection without reasons")
            }
        }
        // Sanity: the corpus must exercise both outcomes.
        assertTrue(parsed > 0, "no mutation ever parsed — mutation strategy broken?")
        assertTrue(rejected > iterations / 2, "suspiciously few rejections: $rejected/$iterations")
    }

    @Test
    fun `decompression bomb is capped by the decompressed-size limit`() {
        // Highly compressible payload: small archive, huge decompressed size.
        val payload = ByteArray(8 * 1024 * 1024) // 8 MiB of zeros compresses to ~KBs
        val archive = ApgTestFixtures.tarZstd(
            linkedMapOf(
                "metadata.json" to ApgTestFixtures.metadataJson("bomb", "1.0"),
                "data/big" to payload,
            ),
        )
        assertTrue(archive.size < payload.size / 10, "fixture must be much smaller than its payload")

        val limits = ApgReadLimits(maxTotalBytes = 1024 * 1024) // 1 MiB cap
        val e = kotlin.runCatching { ApgArchive.read(ByteArrayInputStream(archive), limits) }
        assertTrue(e.isFailure, "bomb must be rejected under a 1 MiB decompressed cap")
        assertTrue(e.exceptionOrNull() is ApgFormatException, "expected ApgFormatException, got ${e.exceptionOrNull()}")
    }

    @Test
    fun `entry count limit is enforced`() {
        val files = linkedMapOf<String, ByteArray>()
        files["metadata.json"] = ApgTestFixtures.metadataJson("many", "1.0")
        for (i in 0 until 60) files["data/f$i"] = "x".toByteArray()
        val archive = ApgTestFixtures.tarXz(files)

        val limits = ApgReadLimits(maxEntries = 10)
        val e = kotlin.runCatching { ApgArchive.read(ByteArrayInputStream(archive), limits) }
        assertTrue(e.isFailure)
        assertTrue(e.exceptionOrNull() is ApgFormatException)
    }

    @Test
    fun `metadata size limit is enforced`() {
        val hugeMeta = """{"name":"x","version":"1.0","description":"""" +
            "a".repeat(70 * 1024) + "\"}"
        val archive = ApgTestFixtures.tarXz(
            linkedMapOf(
                "metadata.json" to hugeMeta.toByteArray(),
                "data/x" to "y".toByteArray(),
            ),
        )
        val limits = ApgReadLimits(maxMetaBytes = 64 * 1024)
        val e = kotlin.runCatching { ApgArchive.read(ByteArrayInputStream(archive), limits) }
        assertTrue(e.isFailure)
        assertTrue(e.exceptionOrNull() is ApgFormatException)
    }
}
