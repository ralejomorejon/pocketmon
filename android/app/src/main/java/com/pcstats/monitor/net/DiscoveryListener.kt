package com.pcstats.monitor.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.pcstats.monitor.protocol.DISCOVERY_PORT
import com.pcstats.monitor.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class DiscoveredPc(
    val id: String,
    val name: String,
    /** Host string usable with [Hosts.wsUrl] (IP, plus port if non-default). */
    val host: String,
    val lastSeenMs: Long,
)

/** Listens for UDP beacons broadcast by the PC agent on [DISCOVERY_PORT]. */
class DiscoveryListener(
    context: Context,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    private val _pcs = MutableStateFlow<Map<String, DiscoveredPc>>(emptyMap())
    val pcs: StateFlow<Map<String, DiscoveredPc>> = _pcs.asStateFlow()

    private var job: Job? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        acquireLock()
        job = scope.launch(Dispatchers.IO) {
            launch { pruneLoop() }
            while (isActive) {
                try {
                    receiveLoop()
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "Discovery socket error: ${e.message}")
                    delay(2000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        releaseLock()
    }

    private suspend fun pruneLoop() {
        while (true) {
            delay(2000)
            val now = clock()
            _pcs.update { m -> m.filterValues { now - it.lastSeenMs < STALE_MS } }
        }
    }

    private suspend fun receiveLoop() {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 1000
            socket.bind(InetSocketAddress(DISCOVERY_PORT))
            val buf = ByteArray(2048)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val beacon = Protocol.decodeBeacon(text) ?: continue
                val ip = packet.address?.hostAddress ?: continue
                val pc = DiscoveredPc(
                    id = beacon.id!!,
                    name = beacon.name ?: ip,
                    host = Hosts.withPort(ip, beacon.port),
                    lastSeenMs = clock(),
                )
                _pcs.update { it + (pc.id to pc) }
            }
        }
    }

    private fun acquireLock() {
        try {
            val l = lock ?: wifi?.createMulticastLock("pcstats-discovery")?.apply { setReferenceCounted(false) }
            l?.acquire()
            lock = l
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock unavailable: ${e.message}")
        }
    }

    private fun releaseLock() {
        try {
            lock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "Discovery"
        const val STALE_MS = 10_000L
    }
}
