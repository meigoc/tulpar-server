package meigo.tulpar.server.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsSnapshotTest {

    @Test
    fun `human formats byte units`() {
        assertEquals("512 B", MetricsSnapshot.human(512))
        assertEquals("1.0 KB", MetricsSnapshot.human(1024))
        assertEquals("1.0 MB", MetricsSnapshot.human(1024L * 1024))
        assertEquals("1.0 GB", MetricsSnapshot.human(1024L * 1024 * 1024))
    }

    @Test
    fun `format contains key fields`() {
        val s = MetricsSnapshot(
            cpuLoadPercent = 12.3,
            heapUsedBytes = 1024L * 1024 * 100,
            heapMaxBytes = 1024L * 1024 * 512,
            nonHeapUsedBytes = 1024L * 1024 * 64,
            bannedIps = 2,
            blockedRequests = 5,
            activeDownloads = 1,
            totalDownloads = 9,
            packages = 3,
            totalRequests = 42,
        )
        val text = s.format()
        assertTrue(text.contains("CPU 12.3%"))
        assertTrue(text.contains("Banned IPs 2"))
        assertTrue(text.contains("Packages 3"))
        assertTrue(text.contains("Requests 42"))
    }
}
