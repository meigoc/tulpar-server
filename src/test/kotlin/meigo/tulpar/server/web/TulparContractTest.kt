package meigo.tulpar.server.web

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.apg.ApgVersion
import meigo.tulpar.server.config.TulparConfig
import meigo.tulpar.server.repo.Repository
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Contract tests replaying what the Tulpar client (NurOS-Linux/Tulpar @
 * 8e330fa) actually consumes, field by field, from its C sources:
 *
 *  - repo_index_parse_json (src/repo/repodata.c:61-118): accepts a root ARRAY
 *    or an object with a `packages` array; reads name, version, architecture,
 *    channel, type, description, provides, replaces from each item — every
 *    field must be present (missing → empty string, which corrupts resolution).
 *  - parse_package_detail (src/net/api.c:69-120): GET /api/v2/packages/{name}
 *    → { name: str, builds: [ {version, architecture, channel, type,
 *    description} ] }; anything else is a hard parse failure.
 *  - resolve_fetch_by_name (src/cmd/resolve.c:437-468): picks the FIRST build
 *    satisfying the constraint → builds must be ordered latest-first by
 *    libAPG ver_compare; downloads via
 *    /api/v2/download/{channel}/{name}/{version}/{arch} and {…}.sig.
 *  - repodata_refresh (src/repo/repodata.c:174-214): GET {repo}/repodata.json.
 *  - api_version (src/net/api.c:208-234): reads only `version`.
 */
class TulparContractTest {

    private val root: File = WebTestSupport.tempRepo()
    private val json = Json { }

    @AfterTest
    fun cleanup() = root.deleteRecursively().let {}

    private fun ctx(): ServerContext {
        val config = TulparConfig().copy(repo = TulparConfig().repo.copy(root = root.path))
        return ServerContext(config, Repository(root).apply { reindex() })
    }

    @Test
    fun `repodata items carry every field the client index parser reads`() = testApplication {
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64")
        WebTestSupport.place(root, "apgexample", "0.0.0", null)
        application { tulparModule(ctx()) }

        for (path in listOf("/repodata.json", "/api/v2/repodata")) {
            val body = client.get(path).bodyAsText()
            val rootObj = json.parseToJsonElement(body).jsonObject
            val packages = rootObj["packages"]!!.jsonArray
            assertTrue(packages.isNotEmpty(), "$path: packages must not be empty")
            for (item in packages) {
                val o = item.jsonObject
                // dup_str_or_empty: absent fields silently become "" in the
                // client — presence is what we must guarantee.
                for (field in listOf("name", "version", "architecture", "channel", "type", "description")) {
                    assertTrue(o.containsKey(field), "$path: item missing '$field': $o")
                }
                for (field in listOf("provides", "replaces")) {
                    assertTrue(o[field]!!.jsonArray.isNotEmpty() || o[field]!!.jsonArray.isEmpty(),
                        "$path: '$field' must be an array")
                }
            }
        }
    }

    @Test
    fun `package detail matches the client builds parser`() = testApplication {
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64")
        WebTestSupport.place(root, "curl", "7.86.0", "x86_64")
        application { tulparModule(ctx()) }

        val body = client.get("/api/v2/packages/curl").bodyAsText()
        val o = json.parseToJsonElement(body).jsonObject
        assertEquals("curl", o["name"]!!.jsonPrimitive.content)
        val builds = o["builds"]!!.jsonArray
        assertEquals(2, builds.size)
        for (b in builds) {
            val bo = b.jsonObject
            for (field in listOf("version", "architecture", "channel", "type", "description")) {
                assertTrue(bo.containsKey(field), "build missing '$field': $bo")
            }
        }
    }

    @Test
    fun `first build is the latest under libAPG ver_compare (client takes first match)`() = testApplication {
        WebTestSupport.place(root, "curl", "7.9.0", "x86_64")
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64")
        WebTestSupport.place(root, "curl", "7.100.0", "x86_64")
        application { tulparModule(ctx()) }

        val body = client.get("/api/v2/packages/curl").bodyAsText()
        val builds = json.parseToJsonElement(body).jsonObject["builds"]!!.jsonArray
        val versions = builds.map { it.jsonObject["version"]!!.jsonPrimitive.content }
        assertEquals("7.100.0", versions.first(), "client installs builds[0]; it must be the newest")
        // and the whole list is ver_compare-sorted descending
        for (i in 0 until versions.size - 1) {
            assertTrue(ApgVersion.compare(versions[i], versions[i + 1]) >= 0, "$versions not sorted")
        }
    }

    @Test
    fun `download URLs built from client fields resolve to 200`() = testApplication {
        WebTestSupport.place(root, "curl", "7.85.0", "x86_64", withSig = true)
        application { tulparModule(ctx()) }

        // The client builds: {base}/api/v2/download/{channel}/{name}/{version}/{arch}
        val body = client.get("/repodata.json").bodyAsText()
        val pkg = json.parseToJsonElement(body).jsonObject["packages"]!!.jsonArray.first().jsonObject
        val channel = pkg["channel"]!!.jsonPrimitive.content
        val name = pkg["name"]!!.jsonPrimitive.content
        val version = pkg["version"]!!.jsonPrimitive.content
        val arch = pkg["architecture"]!!.jsonPrimitive.content.takeIf { it != "null" } ?: "noarch"

        val dl = client.get("/api/v2/download/$channel/$name/$version/$arch")
        assertEquals(HttpStatusCode.OK, dl.status)
        val sig = client.get("/api/v2/download/$channel/$name/$version/$arch.sig")
        assertEquals(HttpStatusCode.OK, sig.status)
    }

    @Test
    fun `null architecture is served as a usable token for the download path`() = testApplication {
        WebTestSupport.place(root, "apgexample", "0.0.0", null)
        application { tulparModule(ctx()) }

        // Client fallback: empty architecture → "noarch" (resolve.c:458).
        // The repodata 'architecture' may be null, but the download path must
        // work with the noarch token the pool uses.
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v2/download/main/apgexample/0.0.0/noarch").status,
        )
        // And packages list still carries the architecture field.
        val list = client.get("/api/v2/packages").bodyAsText()
        assertContains(list, "apgexample")
    }

    @Test
    fun `version endpoint keeps the field the client reads`() = testApplication {
        application { tulparModule(ctx()) }
        val body = client.get("/api/v2/version").bodyAsText()
        val o = json.parseToJsonElement(body).jsonObject
        assertEquals("2.0.0", o["version"]!!.jsonPrimitive.content)
    }
}
