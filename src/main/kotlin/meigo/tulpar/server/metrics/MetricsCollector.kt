package meigo.tulpar.server.metrics

import com.sun.management.OperatingSystemMXBean
import meigo.tulpar.server.ServerContext
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** A point-in-time metrics snapshot. */
@kotlinx.serialization.Serializable
data class MetricsSnapshot(
    val cpuLoadPercent: Double,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val nonHeapUsedBytes: Long,
    val bannedIps: Int,
    val blockedRequests: Long,
    val activeDownloads: Int,
    val totalDownloads: Long,
    val packages: Int,
    val totalRequests: Long,
) {
    fun format(): String {
        val template = "CPU %.1f%%; Heap %s/%s; Non-Heap %s; Banned IPs %d; Blocked %d; " +
            "Downloads active=%d total=%d; Packages %d; Requests %d"
        return template.format(
            cpuLoadPercent,
            human(heapUsedBytes), human(heapMaxBytes), human(nonHeapUsedBytes),
            bannedIps, blockedRequests, activeDownloads, totalDownloads, packages, totalRequests,
        )
    }

    companion object {
        fun human(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = listOf("KB", "MB", "GB", "TB", "PB")
            var v = bytes.toDouble() / 1024
            var i = 0
            while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
            return "%.1f %s".format(v, units[i])
        }
    }
}

/**
 * Periodically samples and logs server metrics. Ported from the legacy
 * MetricsCollector but sourcing live counters from the security guards,
 * repository, and request log via [ServerContext] plus a request-count supplier.
 */
class MetricsCollector(
    private val ctx: ServerContext,
    private val totalRequests: () -> Long,
) {
    private val log = LoggerFactory.getLogger("meigo.tulpar.metrics")
    private val osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean::class.java)
    private val memBean = ManagementFactory.getMemoryMXBean()
    private var scheduler: ScheduledExecutorService? = null

    /** Current snapshot. */
    fun snapshot(): MetricsSnapshot {
        val heap = memBean.heapMemoryUsage
        val nonHeap = memBean.nonHeapMemoryUsage
        val cpu = (osBean.processCpuLoad.takeIf { it >= 0 } ?: 0.0) * 100.0
        return MetricsSnapshot(
            cpuLoadPercent = cpu,
            heapUsedBytes = heap.used,
            heapMaxBytes = heap.max,
            nonHeapUsedBytes = nonHeap.used,
            bannedIps = ctx.ipGuard.banList().size,
            blockedRequests = ctx.ipGuard.blockedRequests(),
            activeDownloads = ctx.downloadLimiter.activeDownloads(),
            totalDownloads = ctx.downloadLimiter.totalDownloads(),
            packages = ctx.repository.entries().size,
            totalRequests = totalRequests(),
        )
    }

    /** Begin periodic logging at the configured interval. */
    fun start() {
        if (!ctx.config.metrics.enabled) return
        val interval = ctx.config.metrics.intervalMillis
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tulpar-metrics").apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate({
                runCatching { log.info(snapshot().format()) }
                    .onFailure { e -> log.warn("metrics sampling failed: {}", e.message) }
            }, interval, interval, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }
}
