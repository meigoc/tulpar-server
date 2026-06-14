package meigo.tulpar.server.web

import meigo.tulpar.server.ServerContext
import meigo.tulpar.server.apg.ApgTestFixtures
import meigo.tulpar.server.config.TulparConfig
import meigo.tulpar.server.repo.Repository
import java.io.File
import java.nio.file.Files

/** Shared helpers for Ktor integration tests. */
object WebTestSupport {

    fun tempRepo(): File = Files.createTempDirectory("tulpar-web-test").toFile()

    fun place(root: File, name: String, version: String, arch: String?, channel: String = "main", withSig: Boolean = false) {
        val bytes = ApgTestFixtures.validV2Package(name, version, arch)
        val archToken = arch ?: "noarch"
        val dir = File(root, "pool/$channel/$name/$archToken")
        dir.mkdirs()
        val apg = File(dir, "$name-$version-$archToken.apg")
        apg.writeBytes(bytes)
        if (withSig) File(dir, apg.name + ".sig").writeBytes("sig".toByteArray())
    }

    fun context(root: File, config: TulparConfig = TulparConfig()): ServerContext {
        val repo = Repository(root)
        repo.reindex()
        return ServerContext(config.copy(repo = config.repo.copy(root = root.path)), repo)
    }
}
