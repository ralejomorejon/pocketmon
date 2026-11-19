package com.pcstats.monitor.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

const val PROTOCOL_VERSION = 1
const val WS_PORT = 28555
const val DISCOVERY_PORT = 28556
const val USB_HOST = "127.0.0.1"
const val MIN_INTERVAL_MS = 200
const val MAX_INTERVAL_MS = 5000

object ErrorCodes {
    const val UNAUTHORIZED = "unauthorized"
    const val BAD_CODE = "bad_code"
    const val BAD_REQUEST = "bad_request"
}

// ---------- Client -> server ----------

@Serializable
data class HelloMessage(
    val type: String = "hello",
    val v: Int = PROTOCOL_VERSION,
    val token: String? = null,
    val clientName: String? = null,
)

@Serializable
data class PairMessage(
    val type: String = "pair",
    val v: Int = PROTOCOL_VERSION,
    val code: String,
    val clientName: String? = null,
)

@Serializable
data class SetIntervalMessage(
    val type: String = "setInterval",
    val intervalMs: Int,
)

// ---------- Server -> client ----------

sealed interface ServerMessage

@Serializable
data class WelcomeMessage(
    val type: String = "welcome",
    val v: Int? = null,
    val pcName: String? = null,
    val pcId: String? = null,
    val token: String? = null,
    val cpuName: String? = null,
    val gpuName: String? = null,
    val intervalMs: Int? = null,
) : ServerMessage

@Serializable
data class ErrorMessage(
    val type: String = "error",
    val code: String? = null,
    val message: String? = null,
) : ServerMessage

@Serializable
data class Snapshot(
    val type: String = "snapshot",
    val ts: Long? = null,
    val cpu: CpuStats? = null,
    val gpu: GpuStats? = null,
    val ram: RamStats? = null,
    val net: NetStats? = null,
    val fans: List<FanStats>? = null,
    val disks: List<DiskStats>? = null,
    val fps: FpsStats? = null,
) : ServerMessage

@Serializable
data class CpuStats(
    val usage: Double? = null,
    val temp: Double? = null,
    val tempLimit: Double? = null,
    val clockMhz: Double? = null,
    val powerW: Double? = null,
    val voltage: Double? = null,
    val fanRpm: Double? = null,
    val coreLoads: List<Double>? = null,
)

@Serializable
data class GpuStats(
    val usage: Double? = null,
    val temp: Double? = null,
    val hotspotTemp: Double? = null,
    val clockMhz: Double? = null,
    val powerW: Double? = null,
    val voltage: Double? = null,
    val fanRpm: Double? = null,
    val vramUsedMb: Double? = null,
    val vramTotalMb: Double? = null,
)

@Serializable
data class RamStats(
    val usedMb: Double? = null,
    val totalMb: Double? = null,
)

@Serializable
data class NetStats(
    val upBps: Double? = null,
    val downBps: Double? = null,
    val adapter: String? = null,
    val sentBytes: Double? = null,
    val receivedBytes: Double? = null,
)

@Serializable
data class DiskStats(
    val name: String? = null,
    val model: String? = null,
    val freeMb: Double? = null,
    val totalMb: Double? = null,
    val temp: Double? = null,
)

@Serializable
data class FanStats(
    val name: String? = null,
    val rpm: Double? = null,
)

@Serializable
data class FpsStats(
    val app: String? = null,
    val value: Double? = null,
)

/** A server message type this client does not understand; ignored. */
data class UnknownMessage(val type: String?) : ServerMessage

// ---------- Discovery ----------

@Serializable
data class DiscoveryBeacon(
    val app: String? = null,
    val v: Int? = null,
    val name: String? = null,
    val port: Int? = null,
    val id: String? = null,
)

object Protocol {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encodeHello(token: String?, clientName: String?): String =
        json.encodeToString(HelloMessage.serializer(), HelloMessage(token = token, clientName = clientName))

    fun encodePair(code: String, clientName: String?): String =
        json.encodeToString(PairMessage.serializer(), PairMessage(code = code, clientName = clientName))

    fun encodeSetInterval(intervalMs: Int): String =
        json.encodeToString(
            SetIntervalMessage.serializer(),
            SetIntervalMessage(intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)),
        )

    /** Parses one server text frame. Returns null if it is not valid protocol JSON. */
    fun decodeServerMessage(text: String): ServerMessage? = try {
        val obj = json.parseToJsonElement(text) as? JsonObject
        when (val type = obj?.get("type")?.jsonPrimitive?.contentOrNull) {
            null -> null
            "snapshot" -> json.decodeFromJsonElement(Snapshot.serializer(), obj)
            "welcome" -> json.decodeFromJsonElement(WelcomeMessage.serializer(), obj)
            "error" -> json.decodeFromJsonElement(ErrorMessage.serializer(), obj)
            else -> UnknownMessage(type)
        }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Parses a UDP beacon; returns null unless it is a valid pcstats beacon. */
    fun decodeBeacon(text: String): DiscoveryBeacon? = try {
        val b = json.decodeFromString(DiscoveryBeacon.serializer(), text)
        if (b.app == "pcstats" && !b.id.isNullOrBlank()) b else null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
