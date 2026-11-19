package com.pcstats.monitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class DashboardStyle { MODERN, CLASSIC }

data class AppSettings(
    val intervalMs: Int = 1000,
    val useFahrenheit: Boolean = false,
    val pixelShiftIntervalSec: Int = 60,
    val pixelShiftMaxDp: Int = 8,
    val dashboardStyle: DashboardStyle = DashboardStyle.MODERN,
)

data class SavedPc(
    val pcId: String,
    val name: String,
    val lastHost: String?,
    val token: String?,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pcstats")

/** Persistence the connection manager needs; implemented by [SettingsRepository] (and fakes in tests). */
interface PcStore {
    val settings: Flow<AppSettings>
    val savedPc: Flow<SavedPc?>
    suspend fun savePc(pc: SavedPc)
    suspend fun updateLastHost(pcId: String, host: String)
    suspend fun updatePcName(pcId: String, name: String)
    suspend fun clearToken()
    suspend fun forgetPc()
}

class SettingsRepository(context: Context) : PcStore {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val interval = intPreferencesKey("interval_ms")
        val fahrenheit = booleanPreferencesKey("use_fahrenheit")
        val shiftInterval = intPreferencesKey("pixel_shift_interval_sec")
        val shiftMax = intPreferencesKey("pixel_shift_max_dp")
        val style = stringPreferencesKey("dashboard_style")
        val pcId = stringPreferencesKey("pc_id")
        val pcName = stringPreferencesKey("pc_name")
        val pcHost = stringPreferencesKey("pc_last_host")
        val pcToken = stringPreferencesKey("pc_token")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            intervalMs = this[Keys.interval] ?: d.intervalMs,
            useFahrenheit = this[Keys.fahrenheit] ?: d.useFahrenheit,
            pixelShiftIntervalSec = this[Keys.shiftInterval] ?: d.pixelShiftIntervalSec,
            pixelShiftMaxDp = this[Keys.shiftMax] ?: d.pixelShiftMaxDp,
            dashboardStyle = DashboardStyle.entries.firstOrNull { it.name == this[Keys.style] } ?: d.dashboardStyle,
        )
    }

    override val settings: Flow<AppSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    override val savedPc: Flow<SavedPc?> = store.data.map { p ->
        val id = p[Keys.pcId] ?: return@map null
        SavedPc(id, p[Keys.pcName] ?: "PC", p[Keys.pcHost], p[Keys.pcToken])
    }.distinctUntilChanged()

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val n = transform(p.toSettings())
            p[Keys.interval] = n.intervalMs
            p[Keys.fahrenheit] = n.useFahrenheit
            p[Keys.shiftInterval] = n.pixelShiftIntervalSec
            p[Keys.shiftMax] = n.pixelShiftMaxDp
            p[Keys.style] = n.dashboardStyle.name
        }
    }

    override suspend fun savePc(pc: SavedPc) {
        store.edit { p ->
            p[Keys.pcId] = pc.pcId
            p[Keys.pcName] = pc.name
            if (pc.lastHost != null) p[Keys.pcHost] = pc.lastHost else p.remove(Keys.pcHost)
            if (pc.token != null) p[Keys.pcToken] = pc.token else p.remove(Keys.pcToken)
        }
    }

    override suspend fun updateLastHost(pcId: String, host: String) {
        store.edit { p -> if (p[Keys.pcId] == pcId) p[Keys.pcHost] = host }
    }

    override suspend fun updatePcName(pcId: String, name: String) {
        store.edit { p -> if (p[Keys.pcId] == pcId) p[Keys.pcName] = name }
    }

    override suspend fun clearToken() {
        store.edit { p -> p.remove(Keys.pcToken) }
    }

    override suspend fun forgetPc() {
        store.edit { p ->
            p.remove(Keys.pcId); p.remove(Keys.pcName); p.remove(Keys.pcHost); p.remove(Keys.pcToken)
        }
    }
}
