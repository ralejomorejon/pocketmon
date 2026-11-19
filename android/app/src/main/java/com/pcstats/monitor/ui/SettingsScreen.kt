package com.pcstats.monitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcstats.monitor.data.AppSettings
import com.pcstats.monitor.data.DashboardStyle
import com.pcstats.monitor.data.SavedPc
import kotlin.math.roundToInt

private val intervalOptions = listOf(250, 500, 750, 1000, 1500, 2000, 3000, 5000)
private val shiftIntervalOptions = listOf(15, 30, 60, 120, 300)
private val shiftMaxOptions = listOf(2, 4, 6, 8, 12, 16)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    savedPc: SavedPc?,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Black)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Palette.Value, fontSize = 24.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("Done", color = Palette.Accent) }
            }

            OptionSlider("Refresh interval", intervalOptions, settings.intervalMs, ::formatMs) { v ->
                onChange { it.copy(intervalMs = v) }
            }

            Column {
                Text("Dashboard design", color = Palette.Value, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(DashboardStyle.MODERN to "Modern", DashboardStyle.CLASSIC to "Classic").forEach { (style, label) ->
                        val selected = settings.dashboardStyle == style
                        OutlinedButton(
                            onClick = { onChange { it.copy(dashboardStyle = style) } },
                            border = BorderStroke(1.dp, if (selected) Palette.Accent else Palette.Outline),
                            modifier = Modifier.weight(1f),
                        ) { Text(label, color = if (selected) Palette.Accent else Palette.Label) }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Temperature unit", color = Palette.Value, fontSize = 15.sp)
                    Text(if (settings.useFahrenheit) "°F" else "°C", color = Palette.Label, fontSize = 13.sp)
                }
                Switch(
                    checked = settings.useFahrenheit,
                    onCheckedChange = { f -> onChange { it.copy(useFahrenheit = f) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.Accent,
                        checkedTrackColor = Palette.Black,
                        checkedBorderColor = Palette.Accent,
                        uncheckedThumbColor = Palette.Label,
                        uncheckedTrackColor = Palette.Black,
                        uncheckedBorderColor = Palette.Outline,
                    ),
                )
            }

            SectionHeader("Burn-in protection")
            OptionSlider("Pixel shift interval", shiftIntervalOptions, settings.pixelShiftIntervalSec, ::formatSec) { v ->
                onChange { it.copy(pixelShiftIntervalSec = v) }
            }
            OptionSlider("Pixel shift range", shiftMaxOptions, settings.pixelShiftMaxDp, { "±$it dp" }) { v ->
                onChange { it.copy(pixelShiftMaxDp = v) }
            }
            Text(
                "The screen stays on at normal brightness while the dashboard is open; the layout drifts slightly to spread pixel wear.",
                color = Palette.Label, fontSize = 13.sp,
            )

            SectionHeader("Paired PC")
            Text(savedPc?.let { "${it.name}${it.lastHost?.let { h -> " · $h" } ?: ""}" } ?: "None", color = Palette.Value, fontSize = 15.sp)
            OutlinedButton(
                onClick = onForget,
                enabled = savedPc != null,
                border = BorderStroke(1.dp, Palette.Hot.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Forget PC", color = Palette.Hot) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text.uppercase(), color = Palette.Label, fontSize = 11.sp, letterSpacing = 1.5.sp)
}

@Composable
private fun OptionSlider(
    title: String,
    options: List<Int>,
    value: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    val index = options.indexOf(value).takeIf { it >= 0 }
        ?: options.indices.minBy { kotlin.math.abs(options[it] - value) }
    var pos by remember(index) { mutableFloatStateOf(index.toFloat()) }
    Column {
        Row {
            Text(title, color = Palette.Value, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(format(options[pos.roundToInt().coerceIn(options.indices)]), color = Palette.Accent, fontSize = 14.sp)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onChange(options[pos.roundToInt().coerceIn(options.indices)]) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = Palette.Accent,
                activeTrackColor = Palette.Accent.copy(alpha = 0.6f),
                inactiveTrackColor = Palette.Track,
                activeTickColor = Palette.Black,
                inactiveTickColor = Palette.Outline,
            ),
        )
    }
}

private fun formatMs(ms: Int): String = if (ms < 1000) "$ms ms" else "${ms / 1000.0}".removeSuffix(".0") + " s"

private fun formatSec(s: Int): String = when {
    s < 60 -> "$s s"
    s % 60 == 0 -> "${s / 60} min"
    else -> "${s / 60} min ${s % 60} s"
}
