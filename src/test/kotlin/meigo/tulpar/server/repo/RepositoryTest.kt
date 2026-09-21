package meigo.tulpar.server.repo

import meigo.tulpar.server.apg.ApgTestFixtures
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {

    private val tmp: File = Files.createTempDirectory("tulpar-repo-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    /** Place a fixture .apg at its canonical pool path. */
    private fun place(name: String, version: String, arch: String?, channel: String = "main", withSig: Boolean = false) {
        val bytes = ApgTestFixtures.validV2Package(name, version, arch)
        val archToken = arch ?: "noarch"
        val dir = File(tmp, "pool/$channel/$name/$archToken")
        dir.mkdirs()
        val apg = File(dir, "$name-$version-$archToken.apg")
        apg.writeBytes(bytes)
        if (withSig) File(dir, apg.name + ".sig").writeBytes("dummy-sig".toByteArray())
    }

    @Test
    fun `scans pool and indexes packages from archive metadata`() {
        place("apgexample", "0.0.0", null)
        place("curl", "7.85.0", "x86_64", withSig = true)

        val repo = Repository(tmp)
        val count = repo.reindex()
        assertEquals(2, count)

        val curl = repo.find("main", "curl", "7.85.0", "x86_64")
        assertNotNull(curl)
        assertEquals("x86_64", curl.arch)
        assertTrue(curl.signed)
        assertTrue(curl.size > 0)
        assertEquals(64, curl.sha256.length)

        val example = repo.find("main", "apgexample", "0.0.0", "noarch")
        assertNotNull(example)
        assertEquals(false, example.signed)
    }

    @Test
    fun `byName groups versions`() {
        place("curl", "7.85.0", "x86_64")
        place("curl", "7.86.0", "x86_64")
        val repo = Repository(tmp)
        repo.reindex()
        assertEquals(2, repo.byName("curl").size)
        assertTrue(repo.byName("missing").isEmpty())
    }

    @Test
    fun `builds are ordered latest-first by libAPG ver_compare`() {
        // The Tulpar client resolves to the FIRST satisfying build
        // (resolve.c:437-446), so the index must sort newest first.
        place("curl", "7.9.0", "x86_64")
        place("curl", "7.85.0", "x86_64")
        place("curl", "7.100.0", "x86_64")
        val repo = Repository(tmp)
        repo.reindex()
        assertEquals(
            listOf("7.100.0", "7.85.0", "7.9.0"),
            repo.byName("curl").map { it.version },
        )
    }

    @Test
    fun `epoch versions order above plain versions`() {
        place("pkg", "1:1.0", "x86_64")
        place("pkg", "0:9.9", "x86_64")
        place("pkg", "9.9", "x86_64")
        val repo = Repository(tmp)
        repo.reindex()
        val versions = repo.byName("pkg").map { it.version }
        // 1:1.0 (epoch 1) is newest; "0:9.9" and "9.9" are ver_compare-EQUAL
        // (epoch 0 == no epoch) and ordered deterministically by version text.
        assertEquals("1:1.0", versions[0])
        assertEquals(setOf("0:9.9", "9.9"), versions.subList(1, 3).toSet())
        assertEquals(listOf("0:9.9", "9.9"), versions.subList(1, 3))
    }

    @Test
    fun `libAPG-rejected pool contents are excluded with a warning not indexed`() {
        // Core invariant: tolerated-but-libAPG-rejected packages in a
        // pre-existing pool must never appear as installable.
        place("curl", "7.85.0", "x86_64")
        val bad = File(tmp, "pool/main/noDataDir/x86_64")
        bad.mkdirs()
        // metadata.json present but no data/ directory: parse_package succeeds,
        // install_data_dir would fail — the server's compat verdict rejects it.
        File(bad, "noDataDir-1-x86_64.apg").writeBytes(
            ApgTestFixtures.tarXz(
                linkedMapOf("metadata.json" to """{"name":"noDataDir","version":"1"}""".toByteArray()),
            ),
        )

        val repo = Repository(tmp)
        val count = repo.reindex()
        assertEquals(1, count)
        assertNull(repo.find("main", "noDataDir", "1", "x86_64"))
    }

    @Test
    fun `repodata json reflects index`() {
        place("curl", "7.85.0", "x86_64", withSig = true)
        val repo = Repository(tmp)
        repo.reindex()

        val data = repo.buildRepoData("Tulpar Server test")
        assertEquals(RepoData.FORMAT, data.meta.format)
        assertEquals(1, data.meta.package_count)
        val pkg = data.packages.single()
        assertEquals("curl", pkg.name)
        assertEquals("pool/main/curl/x86_64/curl-7.85.0-x86_64.apg", pkg.file)
        assertTrue(pkg.signed)

        // round-trips through JSON
        val json = data.toJson()
        assertTrue(json.contains("\"name\":\"curl\""))
        assertTrue(json.contains("tulpar-repodata/2"))
    }

    @Test
    fun `writeRepoData writes file to root`() {
        place("curl", "7.85.0", "x86_64")
        val repo = Repository(tmp)
        repo.reindex()
        val file = repo.writeRepoData("Tulpar Server test")
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("curl"))
    }

    @Test
    fun `malformed apg is skipped not fatal`() {
        place("curl", "7.85.0", "x86_64")
        val bad = File(tmp, "pool/main/broken/noarch")
        bad.mkdirs()
        File(bad, "broken-1-noarch.apg").writeBytes("not a real archive".toByteArray())

        val repo = Repository(tmp)
        val count = repo.reindex()
        assertEquals(1, count) // only the good one
        assertNull(repo.find("main", "broken", "1", "noarch"))
    }

    @Test
    fun `empty repo yields zero packages`() {
        val repo = Repository(tmp)
        assertEquals(0, repo.reindex())
        assertTrue(repo.entries().isEmpty())
    }
}

class RepodataReproducibilityTest {

    private val tmp: File = Files.createTempDirectory("tulpar-repro-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun place(name: String, version: String, arch: String?) {
        val bytes = ApgTestFixtures.validV2Package(name, version, arch)
        val archToken = arch ?: "noarch"
        val dir = File(tmp, "pool/main/$name/$archToken")
        dir.mkdirs()
        val apg = File(dir, "$name-$version-$archToken.apg")
        apg.writeBytes(bytes)
        apg.setLastModified(1_700_000_000_000L) // fixed mtime for determinism
    }

    @Test
    fun `repodata is byte-for-byte identical across reindexes of the same pool`() {
        place("curl", "7.85.0", "x86_64")
        place("curl", "7.86.0", "x86_64")
        place("tree", "2.2.1", "x86_64")

        val repo = Repository(tmp)
        repo.reindex()
        val first = repo.buildRepoData("Tulpar Server test").toJson()

        // Wall-clock time passes; a fresh reindex of the same pool state must
        // produce identical bytes.
        Thread.sleep(20)
        repo.reindex()
        val second = repo.buildRepoData("Tulpar Server test").toJson()

        assertEquals(first, second)
        // and generated_at is derived from package mtimes, not from now()
        assertTrue(first.contains("2023-11-14"), first.substring(0, minOf(300, first.length)))
    }

    @Test
    fun `empty pool yields a deterministic empty document`() {
        val repo = Repository(tmp)
        repo.reindex()
        assertEquals(repo.buildRepoData("s").toJson(), repo.buildRepoData("s").toJson())
    }
}
