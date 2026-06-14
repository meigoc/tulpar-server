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
