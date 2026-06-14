package meigo.tulpar.server.cli

import io.ktor.server.engine.*
import meigo.tulpar.server.config.TlsConfig
import java.io.File
import java.security.KeyStore

/** Helpers for configuring HTTP/HTTPS connectors on the embedded engine. */
object TlsSupport {

    /**
     * Load the keystore referenced by [tls]. Supports JKS and PKCS12 (by file
     * extension). Throws with a clear message if the file or alias is missing.
     */
    fun loadKeyStore(tls: TlsConfig): KeyStore {
        val file = File(tls.keyStorePath)
        require(file.isFile) { "TLS keyStorePath does not exist: ${tls.keyStorePath}" }
        val type = if (file.extension.lowercase() in setOf("p12", "pfx")) "PKCS12" else "JKS"
        val ks = KeyStore.getInstance(type)
        file.inputStream().use { ks.load(it, tls.keyStorePassword.toCharArray()) }
        require(ks.containsAlias(tls.keyAlias)) { "TLS keyAlias '${tls.keyAlias}' not found in keystore" }
        return ks
    }

    /**
     * Build the engine connector list: always a plain HTTP connector, plus an
     * HTTPS connector when [tls] is enabled.
     */
    fun connectors(host: String, httpPort: Int, tls: TlsConfig): List<EngineConnectorConfig> {
        val list = mutableListOf<EngineConnectorConfig>()
        list += EngineConnectorBuilder().apply {
            this.host = host
            this.port = httpPort
        }
        if (tls.enabled) {
            val ks = loadKeyStore(tls)
            val privatePass = tls.privateKeyPassword.ifEmpty { tls.keyStorePassword }
            list += EngineSSLConnectorBuilder(
                keyStore = ks,
                keyAlias = tls.keyAlias,
                keyStorePassword = { tls.keyStorePassword.toCharArray() },
                privateKeyPassword = { privatePass.toCharArray() },
            ).apply {
                this.host = host
                this.port = tls.port
            }
        }
        return list
    }
}
