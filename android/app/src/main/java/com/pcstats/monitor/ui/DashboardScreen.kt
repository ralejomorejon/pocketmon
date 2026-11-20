package com.pcstats.monitor.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcstats.monitor.burnin.burnInShift
import com.pcstats.monitor.burnin.rememberBurnInShift
import com.pcstats.monitor.data.AppSettings
import com.pcstats.monitor.data.DashboardStyle
import com.pcstats.monitor.net.ConnectionState
import com.pcstats.monitor.net.Transport
import com.pcstats.monitor.protocol.DiskStats
import com.pcstats.monitor.protocol.Snapshot
import com.pcstats.monitor.protocol.WelcomeMessage
import com.pcstats.monitor.ui.components.CoreBars
import com.pcstats.monitor.ui.components.NowPlaying
import com.pcstats.monitor.ui.components.FitText
import com.pcstats.monitor.ui.components.RingGauge
import com.pcstats.monitor.ui.components.TempGauge
import com.pcstats.monitor.ui.components.UsageBar
import com.pcstats.monitor.ui.components.heatColor
import com.pcstats.monitor.ui.components.valueUnit
import com.pcstats.monitor.util.DASH
import com.pcstats.monitor.util.Format
import com.pcstats.monitor.util.SessionStats
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

private const val OVERLAY_HIDE_MS = 5_000L

/** Reference layout sizes; the dashboard is laid out at (at least) this size and scaled to fit the screen. */
private val LandscapeDesign = Pair(860.dp, 380.dp)
private val PortraitDesign = Pair(400.dp, 860.dp)
private val ClassicLandscapeDesign = Pair(820.dp, 460.dp)
private val ClassicPortraitDesign = Pair(380.dp, 780.dp)

private const val CPU_MIN_C = 20.0
private const val CPU_MAX_C = 100.0
private const val GPU_MIN_C = 20.0
private const val GPU_MAX_C = 105.0

@Composable
fun DashboardScreen(
    state: ConnectionState,
    snapshot: Snapshot?,
    welcome: WelcomeMessage?,
    stats: SessionStats,
    settings: AppSettings,
    savedPcName: String?,
    httpBase: String?,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var overlayVisible by remember { mutableStateOf(false) }
    var overlayPokes by remember { mutableIntStateOf(0) }
    LaunchedEffect(overlayVisible, overlayPokes) {
        if (overlayVisible) {
            delay(OVERLAY_HIDE_MS)
            overlayVisible = false
        }
    }

    val shift = rememberBurnInShift(
        maxDp = settings.pixelShiftMaxDp.toFloat(),
        intervalMs = settings.pixelShiftIntervalSec * 1000L,
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    overlayVisible = !overlayVisible
                    overlayPokes++
                }
            },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // Just enough margin for the pixel shift so the drifting layout stays on screen.
                .padding(settings.pixelShiftMaxDp.dp)
                .burnInShift(shift),
        ) {
            val landscape = maxWidth > maxHeight
            if (settings.dashboardStyle == DashboardStyle.CLASSIC) {
                val (w, h) = if (landscape) ClassicLandscapeDesign else ClassicPortraitDesign
                FitToDesign(maxWidth, maxHeight, w, h) {
                    ClassicDashboard(state, snapshot, welcome, stats, settings, savedPcName, httpBase)
                }
                return@BoxWithConstraints
            }
            val (designW, designH) = if (landscape) LandscapeDesign else PortraitDesign
            FitToDesign(maxWidth, maxHeight, designW, designH) {
                val model = DashModel(snapshot, welcome, stats, settings, state, savedPcName, httpBase)
                if (landscape) LandscapeLayout(model) else PortraitLayout(model)
            }
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .burnInShift(shift),
        ) {
            Row(
                Modifier
                    .background(Palette.Black, RoundedCornerShape(14.dp))
                    .border(1.dp, Palette.Outline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { overlayVisible = false; onOpenSettings() },
                    border = BorderStroke(1.dp, Palette.Outline),
                ) { Text("Settings", color = Palette.Value) }
                OutlinedButton(
                    onClick = { overlayVisible = false; onDisconnect() },
                    border = BorderStroke(1.dp, Palette.Outline),
                ) { Text("Disconnect", color = Palette.Label) }
            }
        }
    }
}

/**
 * Scales content uniformly so a [designW]×[designH] layout always fits, then gives the content the full
 * available area (in scaled dp) so weights absorb any aspect-ratio difference. Ignores the system font
 * scale so text can't overflow the fixed layout.
 */
@Composable
private fun FitToDesign(availW: Dp, availH: Dp, designW: Dp, designH: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scale = min(availW / designW, availH / designH)
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, fontScale = 1f)) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

private class DashModel(
    val s: Snapshot?,
    val welcome: WelcomeMessage?,
    val stats: SessionStats,
    val settings: AppSettings,
    val state: ConnectionState,
    val savedPcName: String?,
    /** Agent HTTP root (e.g. "http://192.168.1.5:28555") for game logos. */
    val httpBase: String?,
) {
    val f get() = settings.useFahrenheit
    val deg get() = if (f) "°F" else "°C"
    fun t(c: Double?) = Format.tempNumber(c, f)
}

// ---------------------------------------------------------------- layouts

@Composable
private fun LandscapeLayout(m: DashModel) {
    Frame {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.95f).fillMaxHeight()) {
                Panel(Modifier.weight(1f)) { ClockPanel(m) }
                HDivider()
                Panel(Modifier.weight(1.1f)) { NetworkPanel(m) }
            }
            VDivider()
            Panel(Modifier.weight(1.45f).fillMaxHeight()) { CpuPanel(m, wide = false) }
            VDivider()
            Panel(Modifier.weight(1.45f).fillMaxHeight()) { GpuPanel(m, wide = false) }
            VDivider()
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Panel(Modifier.weight(0.8f)) { MemoryPanel(m) }
                HDivider()
                Panel(Modifier.weight(1.2f)) { StoragePanel(m) }
            }
        }
    }
}

@Composable
private fun PortraitLayout(m: DashModel) {
    Frame {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(0.85f).fillMaxWidth()) {
                Panel(Modifier.weight(1f).fillMaxHeight()) { ClockPanel(m) }
                VDivider()
                Panel(Modifier.weight(1f).fillMaxHeight()) { MemoryPanel(m) }
            }
            HDivider()
            Panel(Modifier.weight(1.3f).fillMaxWidth()) { CpuPanel(m, wide = true) }
            HDivider()
            Panel(Modifier.weight(1.3f).fillMaxWidth()) { GpuPanel(m, wide = true) }
            HDivider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Panel(Modifier.weight(1f).fillMaxHeight()) { NetworkPanel(m) }
                VDivider()
                Panel(Modifier.weight(1f).fillMaxHeight()) { StoragePanel(m) }
            }
        }
    }
}

@Composable
private fun Frame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().clipToBounds()) { content() }
}

@Composable
private fun Panel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clipToBounds().padding(horizontal = 14.dp, vertical = 12.dp), content = content)
}

@Composable
private fun HDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Divider))

@Composable
private fun RowScope.VDivider() = Box(Modifier.fillMaxHeight().width(1.dp).background(Palette.Divider))

// ---------------------------------------------------------------- building blocks

@Composable
private fun PanelHeader(title: String, right: String? = null, live: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (live) {
            Box(Modifier.size(5.dp).background(Palette.Live, CircleShape))
            Spacer(Modifier.width(7.dp))
        }
        Text(title.uppercase(), color = Palette.Label, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Spacer(Modifier.weight(1f))
        if (right != null) {
            Text(right, color = Palette.Value, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Larger stat for the column beside a gauge ("Load / 96%"); the value shrinks to fit the space it's given. */
@Composable
private fun Stat(label: String, value: AnnotatedString, color: Color = Palette.Value, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(label, color = Palette.Label, fontSize = 11.sp, maxLines = 1)
        FitText(value, 21.sp, color = color, weight = FontWeight.Light, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun LegendItem(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dashed) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                repeat(3) { Box(Modifier.size(1.5.dp, 6.dp).background(color)) }
            }
        } else {
            Box(Modifier.size(10.dp, 2.5.dp).background(color, RoundedCornerShape(1.dp)))
        }
        Spacer(Modifier.width(5.dp))
        Text(label, color = Palette.Label, fontSize = 9.sp)
    }
}

private fun pct(v: Double?) = valueUnit(v?.roundToInt()?.toString() ?: DASH, "%")
private fun temp(m: DashModel, c: Double?) = AnnotatedString(if (c == null) DASH else "${m.t(c)}°")

// ---------------------------------------------------------------- panels

@Composable
private fun ColumnScope.ClockPanel(m: DashModel) {
    val context = LocalContext.current
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val is24 = DateFormat.is24HourFormat(context)
    val locale = Locale.getDefault()
    Text(now.format(DateTimeFormatter.ofPattern("EEEE", locale)).uppercase(locale), color = Palette.Label, fontSize = 11.sp, letterSpacing = 2.sp)
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
        val big = min(maxWidth.value * 0.29f, maxHeight.value * 0.75f).sp
        Row(verticalAlignment = Alignment.Top) {
            Text(
                now.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm", locale)),
                color = Palette.Value, fontSize = big, fontWeight = FontWeight.Thin, maxLines = 1, softWrap = false,
            )
            Text(
                now.format(DateTimeFormatter.ofPattern("ss", locale)),
                color = Palette.Label, fontSize = big * 0.28f, fontWeight = FontWeight.Light,
                modifier = Modifier.padding(start = 4.dp, top = (big.value * 0.18f).dp),
            )
        }
    }
    FitText(now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale)), 17.sp, weight = FontWeight.Light)
    Spacer(Modifier.height(6.dp))
    ConnectionLine(m)
}

@Composable
private fun ConnectionLine(m: DashModel) {
    val (badge, note) = when (val st = m.state) {
        is ConnectionState.Connected -> (if (st.transport == Transport.USB) "USB" else "Wi-Fi") to null
        is ConnectionState.Connecting -> null to "Connecting…"
        is ConnectionState.Error -> null to st.message
        is ConnectionState.NeedsPairing -> null to "Needs pairing"
        ConnectionState.Disconnected -> null to "Disconnected"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (badge != null) {
            Text(
                badge, color = Palette.Accent, fontSize = 9.sp,
                modifier = Modifier.border(1.dp, Palette.Accent.copy(alpha = 0.4f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        val text = note ?: (m.state as? ConnectionState.Connected)?.pcName ?: m.savedPcName ?: ""
        Text(
            text, color = if (note != null) Palette.Warm else Palette.Label, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ColumnScope.NetworkPanel(m: DashModel) {
    val n = m.s?.net
    PanelHeader("Network", n?.adapter)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NetRing(Modifier.weight(1f), n?.downBps, m.stats.down.max, Palette.Cool, "Download", "${Format.bytes(n?.receivedBytes)} received")
        NetRing(Modifier.weight(1f), n?.upBps, m.stats.up.max, Palette.Upload, "Upload", "${Format.bytes(n?.sentBytes)} sent")
    }
}

@Composable
private fun NetRing(modifier: Modifier, bps: Double?, peak: Double?, color: Color, label: String, total: String) {
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        val fraction = if (bps == null) null else (bps / (peak ?: bps).coerceAtLeast(125_000.0)).toFloat()
        RingGauge(fraction, color, Modifier.fillMaxWidth().weight(1f), strokeRatio = 0.06f, ticks = true) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(Format.mbps(bps), 19.sp, weight = FontWeight.Light, modifier = Modifier.padding(horizontal = 18.dp))
                Text("Mbps", color = Palette.Label, fontSize = 8.sp)
            }
        }
        FitText(AnnotatedString("Peak ${Format.mbps(peak)} Mbps"), 9.sp, color = Palette.Label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).background(color, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Palette.Value, fontSize = 9.sp, maxLines = 1)
        }
        FitText(AnnotatedString(total), 9.sp, color = Palette.Label)
    }
}

@Composable
private fun ColumnScope.CpuPanel(m: DashModel, wide: Boolean) {
    val c = m.s?.cpu
    PanelHeader("CPU", m.welcome?.cpuName)
    Spacer(Modifier.height(8.dp))

    val limit = c?.tempLimit
    // Keep the limit marker inside the scale (e.g. TjMax 105 °C → scale to 110 °C).
    val maxC = if (limit != null && limit + 5 > CPU_MAX_C) ceil((limit + 5) / 5) * 5 else CPU_MAX_C
    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(if (wide) 1.2f else 1.6f).fillMaxHeight()) {
            TempGauge(
                tempC = c?.temp, loadPercent = c?.usage,
                tempText = m.t(c?.temp), unitText = m.deg,
                subtitle = if (limit != null && c?.temp != null) "${(limit - c.temp).roundToInt().coerceAtLeast(0)}° below limit" else null,
                minLabel = "${m.t(CPU_MIN_C)}°", maxLabel = "${m.t(maxC)}°",
                minC = CPU_MIN_C, maxC = maxC,
                limitC = limit, limitLabel = limit?.let { "${m.t(it)}°" },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendItem(heatColor(c?.temp), "Temp")
                LegendItem(Palette.LoadArc, "Load")
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Stat("Load", pct(c?.usage), modifier = Modifier.weight(1f))
            Stat("Power", valueUnit(c?.powerW?.roundToInt()?.toString() ?: DASH, "W"), modifier = Modifier.weight(1f))
            Stat("Clock", valueUnit(Format.ghz(c?.clockMhz), "GHz"), modifier = Modifier.weight(1f))
        }
    }

    val loads = c?.coreLoads.orEmpty()
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Text("Per-core load", color = Palette.Label, fontSize = 10.sp, modifier = Modifier.weight(1f))
        if (loads.isNotEmpty()) Text("${loads.size} cores", color = Palette.Value, fontSize = 10.sp)
    }
    Spacer(Modifier.height(4.dp))
    CoreBars(loads, Modifier.fillMaxWidth().height(12.dp))
}

@Composable
private fun ColumnScope.GpuPanel(m: DashModel, wide: Boolean) {
    val g = m.s?.gpu
    PanelHeader("GPU", m.welcome?.gpuName)
    Spacer(Modifier.height(8.dp))

    val hs = g?.hotspotTemp
    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(if (wide) 1.2f else 1.6f).fillMaxHeight()) {
            TempGauge(
                tempC = g?.temp, loadPercent = g?.usage,
                tempText = m.t(g?.temp), unitText = m.deg,
                subtitle = if (hs != null && g?.temp != null) "Hotspot +${(hs - g.temp).roundToInt().coerceAtLeast(0)}°" else null,
                minLabel = "${m.t(GPU_MIN_C)}°", maxLabel = "${m.t(GPU_MAX_C)}°",
                minC = GPU_MIN_C, maxC = GPU_MAX_C,
                hotspotC = hs,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            FlowRow(
                Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                LegendItem(heatColor(g?.temp), "Temp")
                if (hs != null) LegendItem(heatColor(hs), "Hotspot", dashed = true)
                LegendItem(Palette.LoadArc, "Load")
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (hs != null) Stat("Hotspot", temp(m, hs), color = heatColor(hs), modifier = Modifier.weight(1f))
            Stat("Load", pct(g?.usage), modifier = Modifier.weight(1f))
            Stat("Power", valueUnit(g?.powerW?.roundToInt()?.toString() ?: DASH, "W"), modifier = Modifier.weight(1f))
            Stat("Clock", valueUnit(Format.ghz(g?.clockMhz), "GHz"), modifier = Modifier.weight(1f))
        }
    }

    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Text("VRAM", color = Palette.Label, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(Format.memPair(g?.vramUsedMb, g?.vramTotalMb), color = Palette.Value, fontSize = 10.sp, maxLines = 1)
    }
    Spacer(Modifier.height(5.dp))
    UsageBar(Format.fraction(g?.vramUsedMb, g?.vramTotalMb), Modifier.fillMaxWidth().height(4.dp))
}

@Composable
private fun ColumnScope.MemoryPanel(m: DashModel) {
    val r = m.s?.ram
    val frac = Format.fraction(r?.usedMb, r?.totalMb)
    PanelHeader("Memory")
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        RingGauge(frac, Palette.Amber, Modifier.weight(1f).fillMaxHeight(), strokeRatio = 0.075f) {
            Row(verticalAlignment = Alignment.Bottom) {
                FitText(frac?.let { (it * 100).roundToInt().toString() } ?: DASH, 26.sp, color = Palette.Amber, weight = FontWeight.Light)
                if (frac != null) Text("%", color = Palette.Amber, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1.05f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Text("In use", color = Palette.Label, fontSize = 10.sp)
                FitText(valueUnit(Format.memGb(r?.usedMb), "/ ${Format.memGb(r?.totalMb)} GB"), 16.sp)
            }
            Column {
                Text("Free", color = Palette.Label, fontSize = 10.sp)
                val free = if (r?.usedMb != null && r.totalMb != null) r.totalMb - r.usedMb else null
                FitText(valueUnit(Format.memGb(free), "GB"), 16.sp)
            }
        }
    }
}

@Composable
private fun ColumnScope.StoragePanel(m: DashModel) {
    val fps = m.s?.fps
    if (fps != null) {
        // While a game is running this panel belongs to it: the logo gets the whole space; drives return afterwards.
        PanelHeader("Now playing", live = true)
        NowPlaying(fps, m.httpBase, Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp))
        return
    }
    PanelHeader("Storage", live = true)
    Spacer(Modifier.height(8.dp))
    val disks = m.s?.disks.orEmpty()
    if (disks.isEmpty()) {
        Text(DASH, color = Palette.Label, fontSize = 14.sp)
    } else {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Show as many drives as fit comfortably (rows are ~44 dp).
            disks.take(4).forEach { d -> DiskRow(m, d, Modifier) }
        }
    }
}

@Composable
private fun DiskRow(m: DashModel, d: DiskStats, modifier: Modifier) {
    val used = if (d.freeMb != null && d.totalMb != null && d.totalMb > 0) 1.0 - d.freeMb / d.totalMb else null
    val color = when {
        used == null -> Palette.Label
        used >= 0.9 -> Palette.Heat
        used >= 0.75 -> Palette.Amber
        else -> Palette.Cool
    }
    Row(modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        RingGauge(used?.toFloat(), color, Modifier.size(38.dp), strokeRatio = 0.09f) {
            Text(used?.let { (it * 100).roundToInt().toString() } ?: DASH, color = Palette.Value, fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(d.name, d.model).joinToString(" "),
                color = Palette.Value, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FitText(
                    AnnotatedString("${Format.size(d.freeMb)} free of ${Format.size(d.totalMb)}"), 9.5.sp,
                    color = Palette.Label, modifier = Modifier.weight(1f),
                )
                if (d.temp != null) Text("${m.t(d.temp)}°", color = Palette.Label, fontSize = 9.5.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}