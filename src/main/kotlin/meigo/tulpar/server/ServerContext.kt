package meigo.tulpar.server

import meigo.tulpar.server.config.TulparConfig
import meigo.tulpar.server.repo.Repository
import meigo.tulpar.server.security.DownloadLimiter
import meigo.tulpar.server.security.IpGuard

/** Server-wide version constant. */
object Version {
    const val VALUE = "2.0.0"
    const val SERVER_NAME = "Tulpar Server $VALUE"
}

/**
 * Shared runtime state passed into the Ktor module and CLI: the loaded config,
 * the live repository index, and the security guards.
 */
class ServerContext(
    val config: TulparConfig,
    val repository: Repository,
    val ipGuard: IpGuard = IpGuard(config.limits),
    val downloadLimiter: DownloadLimiter = DownloadLimiter(config.limits),
)
