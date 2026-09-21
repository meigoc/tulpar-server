package meigo.tulpar.server

import meigo.tulpar.server.config.TulparConfig
import meigo.tulpar.server.repo.PublishService
import meigo.tulpar.server.repo.Repository
import meigo.tulpar.server.security.DownloadLimiter
import meigo.tulpar.server.security.IpGuard
import meigo.tulpar.server.security.TokenAuth
import meigo.tulpar.server.util.RequestLog

/** Server-wide version constant. */
object Version {
    const val VALUE = "2.0.0"
    const val SERVER_NAME = "Tulpar Server $VALUE"

    /** libAPG commit this server's conformance surface targets (docs/libapg-compat.md). */
    const val LIBAPG_TARGET = "1ecebf7c7fb567740126bed483779f536b57c36a"
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
    val tokenAuth: TokenAuth = TokenAuth(config.publish),
    val requestLog: RequestLog = RequestLog(),
    /** Single instance so the publish write lock is shared by all routes. */
    val publishService: PublishService = PublishService(repository, config.publish, config.repo.defaultChannel),
)
