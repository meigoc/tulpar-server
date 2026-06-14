package meigo.tulpar.server

import meigo.tulpar.server.config.TulparConfig
import meigo.tulpar.server.repo.Repository

/** Server-wide version constant. */
object Version {
    const val VALUE = "2.0.0"
    const val SERVER_NAME = "Tulpar Server $VALUE"
}

/**
 * Shared runtime state passed into the Ktor module and CLI: the loaded config
 * and the live repository index.
 */
class ServerContext(
    val config: TulparConfig,
    val repository: Repository,
)
