package com.pcstats.monitor.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val exampleSnapshot = """
        {
          "type":"snapshot",
          "ts":1731970000000,
          "cpu":{"usage":23.5,"temp":61.0,"clockMhz":4850,"powerW":45.2},
          "gpu":{"usage":97.0,"temp":70.0,"clockMhz":2610,"powerW":280.4,
                 "vramUsedMb":9120,"vramTotalMb":16376},
          "ram":{"usedMb":18234,"totalMb":32768},
          "net":{"upBps":12000,"downBps":3400000},
          "fans":[{"name":"CPU Fan","rpm":1200},{"name":"GPU Fan 1","rpm":1650}],
          "fps":{"app":"cyberpunk2077.exe","value":143.7}
        }
    """.trimIndent()

    @Test
    fun parsesProtocolExampleSnapshot() {
        val s = Protocol.decodeServerMessage(exampleSnapshot) as Snapshot
        val cpu = s.cpu!!
        val gpu = s.gpu!!
        val ram = s.ram!!
        val net = s.net!!
        val fans = s.fans!!
        val fps = s.fps!!
        assertEquals(1731970000000L, s.ts)
        assertEquals(23.5, cpu.usage!!, 0.0)
        assertEquals(61.0, cpu.temp!!, 0.0)
        assertEquals(4850.0, cpu.clockMhz!!, 0.0)
        assertEquals(45.2, cpu.powerW!!, 0.0)
        assertEquals(97.0, gpu.usage!!, 0.0)
        assertEquals(2610.0, gpu.clockMhz!!, 0.0)
        assertEquals(9120.0, gpu.vramUsedMb!!, 0.0)
        assertEquals(16376.0, gpu.vramTotalMb!!, 0.0)
        assertEquals(18234.0, ram.usedMb!!, 0.0)
        assertEquals(32768.0, ram.totalMb!!, 0.0)
        assertEquals(12000.0, net.upBps!!, 0.0)
        assertEquals(3400000.0, net.downBps!!, 0.0)
        assertEquals(listOf("CPU Fan", "GPU Fan 1"), fans.map { it.name })
        assertEquals(1650.0, fans[1].rpm!!, 0.0)
        assertEquals("cyberpunk2077.exe", fps.app)
        assertEquals(143.7, fps.value!!, 0.0)
    }

    @Test
    fun parsesNullFieldsAndNullFps() {
        val text = """
            {"type":"snapshot","ts":1,
             "cpu":{"usage":null,"temp":null,"clockMhz":4850,"powerW":null},
             "gpu":null,
             "ram":{"usedMb":1024,"totalMb":null},
             "net":{"upBps":null,"downBps":null},
             "fans":[{"name":"CPU Fan","rpm":null}],
             "fps":null,
             "futureField":{"x":1}}
        """.trimIndent()
        val s = Protocol.decodeServerMessage(text) as Snapshot
        val cpu = s.cpu!!
        assertNull(cpu.usage)
        assertNull(cpu.temp)
        assertEquals(4850.0, cpu.clockMhz!!, 0.0)
        assertNull(cpu.powerW)
        assertNull(s.gpu)
        assertEquals(1024.0, s.ram?.usedMb!!, 0.0)
        assertNull(s.ram.totalMb)
        assertNull(s.net?.upBps)
        assertEquals("CPU Fan", s.fans?.get(0)?.name)
        assertNull(s.fans?.get(0)?.rpm)
        assertNull(s.fps)
    }

    @Test
    fun parsesSnapshotWithMissingSections() {
        val s = Protocol.decodeServerMessage("""{"type":"snapshot"}""") as Snapshot
        assertNull(s.cpu)
        assertNull(s.fans)
        assertNull(s.fps)
    }

    @Test
    fun parsesWelcomeAndError() {
        val w = Protocol.decodeServerMessage(
            """{"type":"welcome","v":1,"pcName":"MY-PC","pcId":"abc","token":"tok",
               "cpuName":"AMD Ryzen 7 7800X3D","gpuName":"NVIDIA GeForce RTX 4080","intervalMs":1000}""",
        ) as WelcomeMessage
        assertEquals("MY-PC", w.pcName)
        assertEquals("abc", w.pcId)
        assertEquals("tok", w.token)
        assertEquals(1000, w.intervalMs)

        val noToken = Protocol.decodeServerMessage("""{"type":"welcome","v":1,"pcName":"MY-PC","pcId":"abc"}""") as WelcomeMessage
        assertNull(noToken.token)

        val e = Protocol.decodeServerMessage("""{"type":"error","code":"unauthorized","message":"nope"}""") as ErrorMessage
        assertEquals(ErrorCodes.UNAUTHORIZED, e.code)
        assertEquals("nope", e.message)
    }

    @Test
    fun unknownOrInvalidMessagesDoNotThrow() {
        assertTrue(Protocol.decodeServerMessage("""{"type":"somethingNew","a":1}""") is UnknownMessage)
        assertNull(Protocol.decodeServerMessage("not json"))
        assertNull(Protocol.decodeServerMessage("""{"no":"type"}"""))
        assertNull(Protocol.decodeServerMessage("""{"type":"snapshot","cpu":"bad"}"""))
    }

    @Test
    fun encodesClientMessages() {
        val hello = Json.parseToJsonElement(Protocol.encodeHello("tok", "Pixel 8")).jsonObject
        assertEquals("hello", hello.str("type"))
        assertEquals("1", hello.str("v"))
        assertEquals("tok", hello.str("token"))
        assertEquals("Pixel 8", hello.str("clientName"))

        val helloNoToken = Json.parseToJsonElement(Protocol.encodeHello(null, "Pixel 8")).jsonObject
        assertFalse("token" in helloNoToken)

        val pair = Json.parseToJsonElement(Protocol.encodePair("123456", "Pixel 8")).jsonObject
        assertEquals("pair", pair.str("type"))
        assertEquals("1", pair.str("v"))
        assertEquals("123456", pair.str("code"))

        val interval = Json.parseToJsonElement(Protocol.encodeSetInterval(500)).jsonObject
        assertEquals("setInterval", interval.str("type"))
        assertEquals("500", interval.str("intervalMs"))
        assertEquals("200", Json.parseToJsonElement(Protocol.encodeSetInterval(50)).jsonObject.str("intervalMs"))
        assertEquals("5000", Json.parseToJsonElement(Protocol.encodeSetInterval(99999)).jsonObject.str("intervalMs"))
    }

    @Test
    fun decodesDiscoveryBeacon() {
        val b = Protocol.decodeBeacon("""{"app":"pcstats","v":1,"name":"MY-PC","port":28555,"id":"guid-1"}""")!!
        assertEquals("MY-PC", b.name)
        assertEquals(28555, b.port)
        assertEquals("guid-1", b.id)
        assertNull(Protocol.decodeBeacon("""{"app":"other","id":"x"}"""))
        assertNull(Protocol.decodeBeacon("""{"app":"pcstats"}"""))
        assertNull(Protocol.decodeBeacon("garbage"))
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content
}
