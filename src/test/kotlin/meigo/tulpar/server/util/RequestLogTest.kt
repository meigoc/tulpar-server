package meigo.tulpar.server.util

import kotlin.test.Test
import kotlin.test.assertEquals

class RequestLogTest {

    @Test
    fun `evicts oldest beyond capacity`() {
        val log = RequestLog(capacity = 3)
        repeat(5) { log.record(RequestRecord(it.toLong(), "ip", "GET", "/$it", 200)) }
        val recent = log.recent()
        assertEquals(3, recent.size)
        // oldest two evicted; remaining are /2 /3 /4
        assertEquals(listOf("/2", "/3", "/4"), recent.map { it.uri })
    }

    @Test
    fun `total count includes evicted`() {
        val log = RequestLog(capacity = 2)
        repeat(10) { log.record(RequestRecord(it.toLong(), "ip", "GET", "/", 200)) }
        assertEquals(10, log.totalCount())
    }

    @Test
    fun `recent honours limit`() {
        val log = RequestLog(capacity = 100)
        repeat(10) { log.record(RequestRecord(it.toLong(), "ip", "GET", "/$it", 200)) }
        assertEquals(2, log.recent(2).size)
        assertEquals(listOf("/8", "/9"), log.recent(2).map { it.uri })
    }
}
