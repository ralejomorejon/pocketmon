package com.pcstats.monitor.net

import android.util.Log
import com.pcstats.monitor.data.SavedPc
import com.pcstats.monitor.data.PcStore
import com.pcstats.monitor.protocol.ErrorCodes
import com.pcstats.monitor.protocol.ErrorMessage
import com.pcstats.monitor.protocol.Protocol
import com.pcstats.monitor.protocol.Snapshot
import com.pcstats.monitor.protocol.USB_HOST
import com.pcstats.monitor.protocol.WelcomeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val host: String? = null) : ConnectionState
    data class Connected(val transport: Transport, val pcName: String) : ConnectionState
    data class NeedsPairing(val message: String? = null) : ConnectionState
    data class Error(val message: String, val retryInMs: Long? = null) : ConnectionState
}

/** Pure connection policy, kept separate so it can be unit-tested. */
object ConnectionPolicy {
    const val INITIAL_BACKOFF_MS = 1_000L
    const val MAX_BACKOFF_MS = 10_000L
    const val USB_REPROBE_MS = 15_000L

    fun nextBackoff(currentMs: Long): Long = (currentMs * 2).coerceAtMost(MAX_BACKOFF_MS)

    /** Order: USB (adb reverse) first, then the saved Wi-Fi host, then discovered hosts for this pcId. */
    fun candidates(pc: SavedPc, discovered: Map<String, DiscoveredPc>, usbHost: String = USB_HOST): List<String> {
        val list = mutableListOf(usbHost)
        pc.lastHost?.let { list += Hosts.normalize(it) }
        discovered[pc.pcId]?.let { list += Hosts.normalize(it.host) }
        return list.distinct()
    }
}

class ConnectionManager(
    private val scope: CoroutineScope,
    private val repo: PcStore,
    private val discovered: StateFlow<Map<String, DiscoveredPc>>,
    private val clientName: String,
    private val clock: () -> Long,
    private val client: OkHttpClient = defaultClient(),
    /** Host used for the USB (adb reverse) path; overridable for tests. */
    private val usbHost: String = USB_HOST,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    /** [clock] time of the last snapshot received, or null if none yet. */
    private val _lastSnapshotAt = MutableStateFlow<Long?>(null)
    val lastSnapshotAt: StateFlow<Long?> = _lastSnapshotAt.asStateFlow()

    private val _welcome = MutableStateFlow<WelcomeMessage?>(null)
    val welcome: StateFlow<WelcomeMessage?> = _welcome.asStateFlow()

    /** HTTP root of the currently connected agent (for game logos), or null when not connected. */
    private val _httpBase = MutableStateFlow<String?>(null)
    val httpBase: StateFlow<String?> = _httpBase.asStateFlow()

    private var loopJob: Job? = null
    private var hostTrackerJob: Job? = null

    private val intervalFlow: Flow<Int> = repo.settings.map { it.intervalMs }.distinctUntilChanged()

    val isRunning: Boolean get() = loopJob?.isActive == true

    fun start() {
        ensureHostTracker()
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop(null) }
    }

    fun pair(host: String, code: String) {
        ensureHostTracker()
        val previous = loopJob
        loopJob = scope.launch {
            previous?.cancelAndJoin()
            runLoop(PairRequest(Hosts.normalize(host), code.trim()))
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        hostTrackerJob?.cancel()
        hostTrackerJob = null
        _state.value = ConnectionState.Disconnected
        _snapshot.value = null
        _lastSnapshotAt.value = null
    }

    fun forget() {
        stop()
        scope.launch {
            repo.forgetPc()
            _state.value = ConnectionState.NeedsPairing()
        }
    }

    /** Keeps the saved PC's last host up to date when its beacon is seen from a new address. */
    private fun ensureHostTracker() {
        if (hostTrackerJob?.isActive == true) return
        hostTrackerJob = scope.launch {
            discovered.collect { pcs ->
                val pc = repo.savedPc.first() ?: return@collect
                val seen = pcs[pc.pcId] ?: return@collect
                if (Hosts.normalize(seen.host) != pc.lastHost) repo.updateLastHost(pc.pcId, Hosts.normalize(seen.host))
            }
        }
    }

    private data class PairRequest(val host: String, val code: String)

    private sealed interface Handshake {
        data class Ok(val session: WsSession, val welcome: WelcomeMessage) : Handshake
        data class Rejected(val code: String?, val message: String?) : Handshake
        data class Failed(val message: String) : Handshake
    }

    private sealed interface SessionEnd {
        data object Closed : SessionEnd
        data object Unauthorized : SessionEnd
        data class SwitchTo(val ok: Handshake.Ok) : SessionEnd
    }

    private suspend fun runLoop(pairRequest: PairRequest?) {
        var backoff = ConnectionPolicy.INITIAL_BACKOFF_MS
        var pending: Handshake.Ok? = null

        if (pairRequest != null) {
            _state.value = ConnectionState.Connecting(pairRequest.host)
            when (val r = handshake(pairRequest.host, Protocol.encodePair(pairRequest.code, clientName))) {
                is Handshake.Ok -> {
                    val token = r.welcome.token
                    val pcId = r.welcome.pcId
                    if (token.isNullOrBlank() || pcId.isNullOrBlank()) {
                        r.session.close()
                        _state.value = ConnectionState.NeedsPairing("The PC did not return a pairing token.")
                        return
                    }
                    val wifiHost = pairRequest.host.takeIf { Hosts.transportOf(it) == Transport.WIFI }
                        ?: discovered.value[pcId]?.host
                    repo.savePc(SavedPc(pcId, r.welcome.pcName ?: "PC", wifiHost, token))
                    pending = r
                }
                is Handshake.Rejected -> {
                    _state.value = ConnectionState.NeedsPairing(rejectionText(r))
                    return
                }
                is Handshake.Failed -> {
                    _state.value = ConnectionState.NeedsPairing("Couldn't reach ${pairRequest.host}: ${r.message}")
                    return
                }
            }
        }

        while (true) {
            val pc = repo.savedPc.first()
            val token = pc?.token
            if (pc == null || token.isNullOrBlank()) {
                pending?.session?.close()
                _state.value = ConnectionState.NeedsPairing()
                return
            }

            var ok = pending
            pending = null
            val tried = mutableSetOf<String>()
            if (ok == null) {
                for (host in ConnectionPolicy.candidates(pc, discovered.value, usbHost)) {
                    tried += host
                    _state.value = ConnectionState.Connecting(host)
                    when (val r = handshake(host, Protocol.encodeHello(token, clientName))) {
                        is Handshake.Ok -> { ok = r; break }
                        is Handshake.Rejected -> if (r.code == ErrorCodes.UNAUTHORIZED) {
                            repo.clearToken()
                            _state.value = ConnectionState.NeedsPairing("The PC no longer accepts this phone. Pair again.")
                            return
                        }
                        is Handshake.Failed -> Log.d(TAG, "Connect to $host failed: ${r.message}")
                    }
                }
            }

            if (ok != null) {
                backoff = ConnectionPolicy.INITIAL_BACKOFF_MS
                when (val end = runSession(ok, pc, token)) {
                    SessionEnd.Unauthorized -> {
                        repo.clearToken()
                        _state.value = ConnectionState.NeedsPairing("The PC no longer accepts this phone. Pair again.")
                        return
                    }
                    is SessionEnd.SwitchTo -> {
                        pending = end.ok
                        continue
                    }
                    SessionEnd.Closed -> {
                        _state.value = ConnectionState.Connecting()
                        delay(ConnectionPolicy.INITIAL_BACKOFF_MS)
                        continue
                    }
                }
            }

            _state.value = ConnectionState.Error("Can't reach ${pc.name}", backoff)
            // Wait for the backoff, but retry early if the PC's beacon shows up at a host we haven't tried.
            withTimeoutOrNull(backoff) {
                discovered.first { m -> m[pc.pcId]?.let { Hosts.normalize(it.host) !in tried } == true }
            }
            backoff = ConnectionPolicy.nextBackoff(backoff)
        }
    }

    private suspend fun runSession(ok: Handshake.Ok, pc: SavedPc, token: String): SessionEnd {
        val session = ok.session
        val welcome = ok.welcome
        val transport = Hosts.transportOf(session.host)
        val pcName = welcome.pcName ?: pc.name
        _welcome.value = welcome
        _httpBase.value = Hosts.httpBase(session.host)
        if (welcome.pcId == null || welcome.pcId == pc.pcId) {
            if (transport == Transport.WIFI && pc.lastHost != session.host) repo.updateLastHost(pc.pcId, session.host)
            if (welcome.pcName != null && welcome.pcName != pc.name) repo.updatePcName(pc.pcId, welcome.pcName)
        }
        _state.value = ConnectionState.Connected(transport, pcName)

        val switchTo = AtomicReference<Handshake.Ok?>(null)
        var unauthorized = false
        try {
            coroutineScope {
                val intervalJob = launch {
                    intervalFlow.collect { session.send(Protocol.encodeSetInterval(it)) }
                }
                val probeJob = if (transport == Transport.WIFI) launch {
                    while (true) {
                        delay(ConnectionPolicy.USB_REPROBE_MS)
                        val r = handshake(usbHost, Protocol.encodeHello(token, clientName))
                        if (r is Handshake.Ok) {
                            switchTo.set(r)
                            session.cancel()
                            break
                        }
                    }
                } else null

                for (ev in session.events) {
                    when (ev) {
                        is WsEvent.Text -> when (val m = Protocol.decodeServerMessage(ev.text)) {
                            is Snapshot -> {
                                _snapshot.value = m
                                _lastSnapshotAt.value = clock()
                            }
                            is ErrorMessage -> if (m.code == ErrorCodes.UNAUTHORIZED) unauthorized = true
                            else -> Unit
                        }
                        is WsEvent.Closed, is WsEvent.Failure -> break
                        WsEvent.Open -> Unit
                    }
                }
                intervalJob.cancel()
                probeJob?.cancelAndJoin()
            }
        } catch (e: CancellationException) {
            session.cancel()
            switchTo.get()?.session?.cancel()
            throw e
        }
        session.cancel()
        val next = switchTo.get()
        return when {
            unauthorized -> {
                next?.session?.cancel()
                SessionEnd.Unauthorized
            }
            next != null -> SessionEnd.SwitchTo(next)
            else -> SessionEnd.Closed
        }
    }

    private suspend fun handshake(host: String, firstMessage: String): Handshake {
        val session = WsSession.open(client, host)
        try {
            val result = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { awaitWelcome(session, firstMessage) }
                ?: Handshake.Failed("timed out")
            if (result !is Handshake.Ok) session.cancel()
            return result
        } catch (e: CancellationException) {
            session.cancel()
            throw e
        }
    }

    private suspend fun awaitWelcome(session: WsSession, firstMessage: String): Handshake {
        session.send(firstMessage)
        for (ev in session.events) {
            when (ev) {
                is WsEvent.Text -> when (val m = Protocol.decodeServerMessage(ev.text)) {
                    is WelcomeMessage -> return Handshake.Ok(session, m)
                    is ErrorMessage -> return Handshake.Rejected(m.code, m.message)
                    else -> Unit
                }
                is WsEvent.Closed -> return Handshake.Failed("closed (${ev.code})")
                is WsEvent.Failure -> return Handshake.Failed(ev.error.message ?: ev.error.javaClass.simpleName)
                WsEvent.Open -> Unit
            }
        }
        return Handshake.Failed("closed")
    }

    private fun rejectionText(r: Handshake.Rejected): String = when (r.code) {
        ErrorCodes.BAD_CODE -> "Wrong pairing code. Check the code in the PC tray."
        ErrorCodes.UNAUTHORIZED -> "The PC refused the pairing."
        ErrorCodes.BAD_REQUEST -> "The PC rejected the request${r.message?.let { ": $it" } ?: "."}"
        else -> r.message ?: "Pairing failed."
    }

    companion object {
        private const val TAG = "ConnectionManager"
        private const val HANDSHAKE_TIMEOUT_MS = 6_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .build()
    }
}
