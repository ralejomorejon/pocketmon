package com.pcstats.monitor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcstats.monitor.data.AppSettings
import com.pcstats.monitor.net.ConnectionState
import com.pcstats.monitor.net.Transport
import com.pcstats.monitor.protocol.Snapshot
import com.pcstats.monitor.protocol.WelcomeMessage
import com.pcstats.monitor.ui.components.ArcGauge
import com.pcstats.monitor.ui.components.Sparkline
import com.pcstats.monitor.util.DASH
import com.pcstats.monitor.util.Format
import com.pcstats.monitor.util.SampleSeries
import com.pcstats.monitor.util.SessionStats

/** The original tile dashboard: outline arc gauges, sparklines and a status line. */
@Composable
fun ClassicDashboard(
    state: ConnectionState,
    snapshot: Snapshot?,
    welcome: WelcomeMessage?,
    stats: SessionStats,
    settings: AppSettings,
    savedPcName: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        StatusLine(state, savedPcName)
        Spacer(Modifier.height(6.dp))
        TileGrid(snapshot, welcome, stats, settings, Modifier.weight(1f))
    }
}

@Composable
private fun StatusLine(state: ConnectionState, savedPcName: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val (badge, name, note) = when (state) {
            is ConnectionState.Connected -> Triple(if (state.transport == Transport.USB) "USB" else "Wi-Fi", state.pcName, null)
            is ConnectionState.Connecting -> Triple(null, savedPcName, "Connecting…")
            is ConnectionState.Error -> Triple(null, savedPcName, state.message + (state.retryInMs?.let { " · retry in ${it / 1000}s" } ?: ""))
            is ConnectionState.NeedsPairing -> Triple(null, savedPcName, "Needs pairing")
            ConnectionState.Disconnected -> Triple(null, savedPcName, "Disconnected")
        }
        if (badge != null) {
            Text(
                badge,
                color = Palette.Accent,
                fontSize = 10.sp,
                modifier = Modifier
                    .border(1.dp, Palette.Accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(name ?: "PC", color = Palette.Label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        if (note != null) Text(note, color = Palette.Warm.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
    }
}

private enum class TileKind { CPU, GPU, RAM, NET, FANS, FPS }

@Composable
private fun TileGrid(s: Snapshot?, welcome: WelcomeMessage?, stats: SessionStats, settings: AppSettings, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val landscape = maxWidth > maxHeight
        val columns = if (landscape) 3 else 2
        val tiles = buildList {
            addAll(listOf(TileKind.CPU, TileKind.GPU, TileKind.RAM, TileKind.NET, TileKind.FANS))
            if (s?.fps != null) add(TileKind.FPS)
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { kind ->
                        Tile(Modifier.weight(1f).fillMaxHeight()) {
                            when (kind) {
                                TileKind.CPU -> CpuTile(s, welcome?.cpuName, stats.cpuSeries, settings.useFahrenheit)
                                TileKind.GPU -> GpuTile(s, welcome?.gpuName, stats.gpuSeries, settings.useFahrenheit)
                                TileKind.RAM -> RamTile(s, stats.ramSeries)
                                TileKind.NET -> NetTile(s, stats)
                                TileKind.FANS -> FansTile(s)
                                TileKind.FPS -> FpsTile(s, stats.fpsSeries)
                            }
                        }
                    }
                    // Keep tile widths equal on a partially filled last row.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .border(1.dp, Palette.Outline, RoundedCornerShape(12.dp))
            .clipToBounds()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
private fun TileHeader(title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, color = Palette.Label, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) {
            Spacer(Modifier.width(6.dp))
            Text(subtitle, color = Palette.LabelDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color = Palette.Value) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Label, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, color = if (value == DASH) Palette.Label else color, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun GaugeValue(fraction: Float?, text: String, color: Color = Palette.Accent) {
    ArcGauge(fraction, size = 62.dp, color = color) {
        Text(text, color = if (text == DASH) Palette.Label else Palette.Value, fontSize = 15.sp)
    }
}

/** Gauge on the left, one prominent labelled value on the right. */
@Composable
private fun GaugeRow(fraction: Float?, gaugeText: String, label: String, value: String, valueColor: Color = Palette.Value) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        GaugeValue(fraction, gaugeText)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(label, color = Palette.Label, fontSize = 11.sp, maxLines = 1)
            Text(
                value,
                color = if (value == DASH) Palette.Label else valueColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ColumnScope.SparkArea(series: SampleSeries, fixedMax: Float? = null, color: Color = Palette.Accent.copy(alpha = 0.7f)) {
    Spacer(Modifier.height(6.dp))
    Sparkline(series, Modifier.fillMaxWidth().weight(1f, fill = true).padding(vertical = 2.dp), color = color, fixedMax = fixedMax)
}

@Composable
private fun ColumnScope.CpuTile(s: Snapshot?, name: String?, series: SampleSeries, fahrenheit: Boolean) {
    val c = s?.cpu
    TileHeader("CPU", name)
    Spacer(Modifier.height(6.dp))
    GaugeRow(
        c?.usage?.let { (it / 100).toFloat() }, Format.percent(c?.usage),
        "Temp", Format.temp(c?.temp, fahrenheit), Palette.temp(Format.tempLevel(c?.temp)),
    )
    Spacer(Modifier.height(6.dp))
    Metric("Clock", Format.clock(c?.clockMhz))
    Metric("Power", Format.power(c?.powerW))
    SparkArea(series, fixedMax = 100f)
}

@Composable
private fun ColumnScope.GpuTile(s: Snapshot?, name: String?, series: SampleSeries, fahrenheit: Boolean) {
    val g = s?.gpu
    TileHeader("GPU", name)
    Spacer(Modifier.height(6.dp))
    GaugeRow(
        g?.usage?.let { (it / 100).toFloat() }, Format.percent(g?.usage),
        "Temp", Format.temp(g?.temp, fahrenheit), Palette.temp(Format.tempLevel(g?.temp)),
    )
    Spacer(Modifier.height(6.dp))
    Metric("Clock", Format.clock(g?.clockMhz))
    Metric("Power", Format.power(g?.powerW))
    Metric("VRAM", Format.memPair(g?.vramUsedMb, g?.vramTotalMb))
    SparkArea(series, fixedMax = 100f)
}

@Composable
private fun ColumnScope.RamTile(s: Snapshot?, series: SampleSeries) {
    val r = s?.ram
    val frac = Format.fraction(r?.usedMb, r?.totalMb)
    TileHeader("RAM")
    Spacer(Modifier.height(6.dp))
    GaugeRow(frac, if (frac == null) DASH else Format.percent(frac * 100.0), "Used", Format.mem(r?.usedMb))
    Spacer(Modifier.height(6.dp))
    Metric("Total", Format.mem(r?.totalMb))
    SparkArea(series, fixedMax = 100f)
}

@Composable
private fun ColumnScope.NetTile(s: Snapshot?, stats: SessionStats) {
    val n = s?.net
    TileHeader("NETWORK", n?.adapter)
    Spacer(Modifier.height(6.dp))
    Metric("↑ Up", Format.rate(n?.upBps))
    Sparkline(stats.upSeries, Modifier.fillMaxWidth().weight(1f).padding(vertical = 3.dp), color = Palette.Warm.copy(alpha = 0.6f))
    Metric("↓ Down", Format.rate(n?.downBps))
    Sparkline(stats.downSeries, Modifier.fillMaxWidth().weight(1f).padding(vertical = 3.dp), color = Palette.Accent.copy(alpha = 0.7f))
}

@Composable
private fun ColumnScope.FansTile(s: Snapshot?) {
    TileHeader("FANS")
    Spacer(Modifier.height(6.dp))
    val fans = s?.fans.orEmpty()
    if (fans.isEmpty()) {
        Text(DASH, color = Palette.Label, fontSize = 14.sp)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            fans.forEach { f -> Metric(f.name ?: "Fan", Format.rpm(f.rpm)) }
        }
    }
}

@Composable
private fun ColumnScope.FpsTile(s: Snapshot?, series: SampleSeries) {
    val f = s?.fps ?: return
    TileHeader("FPS", f.app)
    Text(
        Format.fps(f.value),
        color = if (f.value == null) Palette.Label else Palette.Value,
        fontSize = 44.sp,
        fontWeight = FontWeight.Light,
    )
    SparkArea(series)
}
