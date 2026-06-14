package meigo.tulpar.server.util

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** One logged HTTP request. */
data class RequestRecord(
    val epochMillis: Long,
    val ip: String,
    val method: String,
    val uri: String,
    val status: Int,
)

/**
 * Bounded in-memory request log. The legacy server kept an unbounded list that
 * grew forever; here the oldest entries are evicted once [capacity] is reached.
 */
class RequestLog(private val capacity: Int = 1000) {
    private val deque = ConcurrentLinkedDeque<RequestRecord>()
    private val size = AtomicLong(0)
    private val total = AtomicLong(0)

    fun record(record: RequestRecord) {
        deque.addLast(record)
        total.incrementAndGet()
        if (size.incrementAndGet() > capacity) {
            deque.pollFirst()
            size.decrementAndGet()
        }
    }

    /** Most recent [limit] records, newest last. */
    fun recent(limit: Int = capacity): List<RequestRecord> {
        val all = deque.toList()
        return if (all.size <= limit) all else all.subList(all.size - limit, all.size)
    }

    /** Total requests seen since startup (including evicted). */
    fun totalCount(): Long = total.get()
}
