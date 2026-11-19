package com.pcstats.monitor

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcstats.monitor.data.AppSettings
import com.pcstats.monitor.data.SavedPc
import com.pcstats.monitor.data.SettingsRepository
import com.pcstats.monitor.net.ConnectionManager
import com.pcstats.monitor.net.ConnectionState
import com.pcstats.monitor.net.DiscoveredPc
import com.pcstats.monitor.net.DiscoveryListener
import com.pcstats.monitor.protocol.Snapshot
import com.pcstats.monitor.util.SessionStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { CONNECT, DASHBOARD, SETTINGS }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }

    val repo = SettingsRepository(app)
    private val discovery = DiscoveryListener(app, clock)
    val connection = ConnectionManager(
        scope = viewModelScope,
        repo = repo,
        discovered = discovery.pcs,
        clientName = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .distinct().joinToString(" ").ifBlank { "Android" },
        clock = clock,
    )

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val savedPc: StateFlow<SavedPc?> = repo.savedPc.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val discovered: StateFlow<List<DiscoveredPc>> = discovery.pcs
        .map { m -> m.values.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val state: StateFlow<ConnectionState> get() = connection.state
    val snapshot: StateFlow<Snapshot?> get() = connection.snapshot

    private val _stats = MutableStateFlow(SessionStats())
    /** Low/high temperatures and network peaks since this connection started. */
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    /** True after the user pressed Disconnect; keeps us on the connect screen until they reconnect. */
    private val _userStopped = MutableStateFlow(false)
    val userStopped: StateFlow<Boolean> = _userStopped.asStateFlow()

    /** True while a pairing attempt is in flight; keeps the connect screen (and its form) visible. */
    private val _pairing = MutableStateFlow(false)
    val pairing: StateFlow<Boolean> = _pairing.asStateFlow()

    init {
        viewModelScope.launch {
            connection.snapshot.filterNotNull().collect { s -> _stats.update { it.plus(s) } }
        }
        viewModelScope.launch {
            connection.state.collect { st ->
                if (st is ConnectionState.Connected || st is ConnectionState.NeedsPairing) _pairing.value = false
            }
        }
    }

    fun screen(state: ConnectionState, settingsOpen: Boolean, userStopped: Boolean, pairing: Boolean = false): Screen = when {
        settingsOpen -> Screen.SETTINGS
        userStopped -> Screen.CONNECT
        pairing && state !is ConnectionState.Connected -> Screen.CONNECT
        state is ConnectionState.NeedsPairing || state is ConnectionState.Disconnected -> Screen.CONNECT
        else -> Screen.DASHBOARD
    }

    fun onForeground() {
        discovery.start(viewModelScope)
        if (!_userStopped.value) connection.start()
    }

    fun onBackground() {
        discovery.stop()
        connection.stop()
    }

    fun pair(host: String, code: String) {
        _userStopped.value = false
        _stats.value = SessionStats()
        _pairing.value = true
        connection.pair(host, code)
    }

    fun reconnect() {
        _userStopped.value = false
        connection.start()
    }

    fun disconnect() {
        _userStopped.value = true
        connection.stop()
        _stats.value = SessionStats()
    }

    fun openSettings() { _settingsOpen.value = true }
    fun closeSettings() { _settingsOpen.value = false }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.updateSettings(transform) }
    }

    fun forgetPc() {
        _settingsOpen.value = false
        _userStopped.value = false
        _stats.value = SessionStats()
        connection.forget()
    }

    override fun onCleared() {
        discovery.stop()
        connection.stop()
    }
}
