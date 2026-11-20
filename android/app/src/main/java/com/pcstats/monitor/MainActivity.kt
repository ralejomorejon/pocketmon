package com.pcstats.monitor

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcstats.monitor.ui.ConnectScreen
import com.pcstats.monitor.ui.DashboardScreen
import com.pcstats.monitor.ui.Palette
import com.pcstats.monitor.ui.PcStatsTheme
import com.pcstats.monitor.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.BLACK)
        setContent {
            PcStatsTheme {
                Box(Modifier.fillMaxSize().background(Palette.Black)) { App(vm) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    @Composable
    private fun App(vm: MainViewModel) {
        val state by vm.state.collectAsStateWithLifecycle()
        val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
        val userStopped by vm.userStopped.collectAsStateWithLifecycle()
        val pairing by vm.pairing.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val savedPc by vm.savedPc.collectAsStateWithLifecycle()
        val screen = vm.screen(state, settingsOpen, userStopped, pairing)

        LaunchedEffect(screen) { applyWindow(screen) }

        when (screen) {
            Screen.CONNECT -> {
                val discovered by vm.discovered.collectAsStateWithLifecycle()
                ConnectScreen(
                    state = state,
                    discovered = discovered,
                    savedPc = savedPc,
                    onPair = vm::pair,
                    onReconnect = vm::reconnect,
                    onOpenSettings = vm::openSettings,
                )
            }
            Screen.SETTINGS -> {
                BackHandler { vm.closeSettings() }
                SettingsScreen(
                    settings = settings,
                    savedPc = savedPc,
                    onChange = vm::updateSettings,
                    onForget = vm::forgetPc,
                    onBack = vm::closeSettings,
                )
            }
            Screen.DASHBOARD -> {
                val snapshot by vm.snapshot.collectAsStateWithLifecycle()
                val welcome by vm.connection.welcome.collectAsStateWithLifecycle()
                val httpBase by vm.connection.httpBase.collectAsStateWithLifecycle()
                val stats by vm.stats.collectAsStateWithLifecycle()
                DashboardScreen(
                    state = state,
                    snapshot = snapshot,
                    welcome = welcome,
                    stats = stats,
                    settings = settings,
                    savedPcName = savedPc?.name,
                    httpBase = httpBase,
                    onOpenSettings = vm::openSettings,
                    onDisconnect = vm::disconnect,
                )
            }
        }
    }

    /** Dashboard: immersive and always on at the user's normal brightness (burn-in is handled by pixel shifting). */
    private fun applyWindow(screen: Screen) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (screen == Screen.DASHBOARD) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
