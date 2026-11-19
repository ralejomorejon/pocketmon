package com.pcstats.monitor.net

import com.pcstats.monitor.data.AppSettings
import com.pcstats.monitor.data.PcStore
import com.pcstats.monitor.data.SavedPc
import com.pcstats.monitor.protocol.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises ConnectionManager against a local fake PC agent speaking PROTOCOL.md. */
class ConnectionManagerTest {
    private class FakeStore(pc: SavedPc? = null, intervalMs: Int = 750) : PcStore {
        val pc = MutableStateFlow(pc)
        val s = MutableStateFlow(AppSettings(intervalMs = intervalMs))
        override val settings: Flow<AppSettings> = s
        override val savedPc: Flow<SavedPc?> = this.pc
        override suspend fun savePc(pc: SavedPc) { this.pc.value = pc }
        override suspend fun updateLastHost(pcId: String, host: String) { pc.value = pc.value?.copy(lastHost = host) }
        override suspend fun updatePcName(pcId: String, name: String) { pc.value = pc.value?.copy(name = name) }
        override suspend fun clearToken() { pc.value = pc.value?.copy(token = null) }
        override suspend fun forgetPc() { pc.value = null }
    }

    private val received = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host get() = "127.0.0.1:${server.port}"

    private val agent = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            val o = Json.parseToJsonElement(text).jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.content
            when (str("type")) {
                "pair" -> if (str("code") == "123456") {
                    webSocket.send("""{"type":"welcome","v":1,"pcName":"MOCK-PC","pcId":"pc-1","token":"tok","cpuName":"CPU","gpuName":"GPU","intervalMs":1000}""")
                    webSocket.send(SNAPSHOT)
                } else {
                    webSocket.send("""{"type":"error","code":"bad_code","message":"wrong"}""")
                    webSocket.close(1000, null)
                }
                "hello" -> if (str("token") == "tok") {
                    webSocket.send("""{"type":"welcome","v":1,"pcName":"MOCK-PC","pcId":"pc-1","intervalMs":1000}""")
                    webSocket.send(SNAPSHOT)
                } else {
                    webSocket.send("""{"type":"error","code":"unauthorized","message":"bad token"}""")
                    webSocket.close(1000, null)
                }
            }
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().webSocketUpgrade(agent).build()
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun manager(store: FakeStore, usbHost: String = host) = ConnectionManager(
        scope = scope,
        repo = store,
        discovered = MutableStateFlow(emptyMap()),
        clientName = "Test Phone",
        clock = { System.currentTimeMillis() },
        usbHost = usbHost,
    )

    @Test
    fun pairSavesTokenStreamsSnapshotsAndSendsInterval() = runBlocking {
        val store = FakeStore()
        val m = manager(store)
        m.pair(host, "123456")

        val connected = withTimeout(10_000) { m.state.first { it is ConnectionState.Connected } } as ConnectionState.Connected
        assertEquals(Transport.USB, connected.transport)
        assertEquals("MOCK-PC", connected.pcName)
        assertEquals("tok", store.pc.value?.token)
        assertEquals("pc-1", store.pc.value?.pcId)

        val snap: Snapshot = withTimeout(5_000) { m.snapshot.first { it != null } }!!
        assertEquals(23.5, snap.cpu?.usage!!, 0.0)
        assertNull(snap.fps)
        assertTrue(m.lastSnapshotAt.value != null)

        withTimeout(5_000) { while (received.none { it.contains("setInterval") }) kotlinx.coroutines.delay(20) }
        val pairMsg = Json.parseToJsonElement(received.first()).jsonObject
        assertEquals("pair", pairMsg["type"]?.jsonPrimitive?.content)
        assertEquals("Test Phone", pairMsg["clientName"]?.jsonPrimitive?.content)
        val interval = Json.parseToJsonElement(received.first { it.contains("setInterval") }).jsonObject
        assertEquals("750", interval["intervalMs"]?.jsonPrimitive?.content)
        m.stop()
    }

    @Test
    fun wrongCodeStaysOnPairing() = runBlocking {
        val m = manager(FakeStore())
        m.pair(host, "000000")
        val s = withTimeout(10_000) {
            m.state.first { it is ConnectionState.NeedsPairing && it.message != null }
        } as ConnectionState.NeedsPairing
        assertTrue(s.message!!.contains("Wrong pairing code"))
    }

    @Test
    fun helloWithSavedTokenFallsBackFromUnreachableUsbToSavedHost() = runBlocking {
        val store = FakeStore(SavedPc("pc-1", "Old name", host, "tok"))
        val m = manager(store, usbHost = "127.0.0.1:1")
        m.start()
        withTimeout(10_000) { m.state.first { it is ConnectionState.Connected } }
        val hello = Json.parseToJsonElement(received.first()).jsonObject
        assertEquals("hello", hello["type"]?.jsonPrimitive?.content)
        assertEquals("tok", hello["token"]?.jsonPrimitive?.content)
        withTimeout(5_000) { store.pc.map { it?.name }.first { it == "MOCK-PC" } }
        m.stop()
    }

    @Test
    fun unauthorizedDropsToPairingAndClearsToken() = runBlocking {
        val store = FakeStore(SavedPc("pc-1", "MOCK-PC", null, "revoked"))
        val m = manager(store)
        m.start()
        withTimeout(10_000) { m.state.first { it is ConnectionState.NeedsPairing } }
        assertNull(store.pc.value?.token)
    }

    private companion object {
        const val SNAPSHOT = """{"type":"snapshot","ts":1,"cpu":{"usage":23.5,"temp":null,"clockMhz":4850,"powerW":null},"gpu":null,"ram":{"usedMb":1,"totalMb":2},"net":{"upBps":1,"downBps":2},"fans":[],"fps":null}"""
    }
}
