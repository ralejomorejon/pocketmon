package com.pcstats.monitor.net

import com.pcstats.monitor.data.SavedPc
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPolicyTest {
    @Test
    fun candidateOrderIsUsbThenSavedHostThenDiscovered() {
        val pc = SavedPc("id-1", "MY-PC", "192.168.1.10", "tok")
        val discovered = mapOf(
            "id-1" to DiscoveredPc("id-1", "MY-PC", "192.168.1.22", 0),
            "other" to DiscoveredPc("other", "OTHER", "192.168.1.99", 0),
        )
        assertEquals(
            listOf("127.0.0.1", "192.168.1.10", "192.168.1.22"),
            ConnectionPolicy.candidates(pc, discovered),
        )
        assertEquals(listOf("127.0.0.1"), ConnectionPolicy.candidates(pc.copy(lastHost = null), emptyMap()))
        assertEquals(
            listOf("127.0.0.1", "192.168.1.10"),
            ConnectionPolicy.candidates(pc, mapOf("id-1" to DiscoveredPc("id-1", "MY-PC", "192.168.1.10", 0))),
        )
    }

    @Test
    fun backoffDoublesUpTo10s() {
        val seq = generateSequence(ConnectionPolicy.INITIAL_BACKOFF_MS) { ConnectionPolicy.nextBackoff(it) }.take(6).toList()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), seq)
    }

    @Test
    fun hostUrls() {
        assertEquals("ws://192.168.1.5:28555/ws", Hosts.wsUrl("192.168.1.5"))
        assertEquals("ws://192.168.1.5:9000/ws", Hosts.wsUrl("192.168.1.5:9000"))
        assertEquals("ws://127.0.0.1:28555/ws", Hosts.wsUrl("ws://127.0.0.1:28555/ws"))
        assertEquals("192.168.1.5", Hosts.normalize(" ws://192.168.1.5:28555/ws "))
        assertEquals("192.168.1.5:9000", Hosts.withPort("192.168.1.5", 9000))
        assertEquals("192.168.1.5", Hosts.withPort("192.168.1.5", 28555))
        assertEquals(Transport.USB, Hosts.transportOf("127.0.0.1"))
        assertEquals(Transport.WIFI, Hosts.transportOf("192.168.1.5"))
    }
}
