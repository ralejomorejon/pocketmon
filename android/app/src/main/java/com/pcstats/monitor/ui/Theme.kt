package com.pcstats.monitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pcstats.monitor.util.TempLevel

object Palette {
    val Black = Color(0xFF000000)
    /** Live values: near-white, not pure white. */
    val Value = Color(0xFFE2E6EB)
    val Accent = Color(0xFF8FB3C9)
    /** Static labels. */
    val Label = Color(0xFF858D97)
    val LabelDim = Color(0xFF555B63)
    val Outline = Color(0xFF1A1E24)
    val Track = Color(0xFF181C22)
    val Tick = Color(0xFF2A3038)
    val Warm = Color(0xFFC9A86A)
    val Hot = Color(0xFFC0766E)
    val Surface = Color(0xFF0A0A0A)

    // Dashboard accents (from the reference design).
    val Live = Color(0xFF3FB950)
    val Cool = Color(0xFF4FB0F5)
    val Amber = Color(0xFFE2AA5E)
    val AmberDeep = Color(0xFFA77A3E)
    val Heat = Color(0xFFFF5B3A)
    val HeatDeep = Color(0xFFD9342B)
    val Alert = Color(0xFFFF4D4D)
    val LoadArc = Color(0xFF6E7681)
    val Upload = Color(0xFF9AA4AF)
    val BarIdle = Color(0xFF232A33)
    val Divider = Color(0xFF15191F)

    fun temp(level: TempLevel?): Color = when (level) {
        TempLevel.HOT -> Hot
        TempLevel.WARM -> Warm
        TempLevel.NORMAL -> Value
        null -> Label
    }
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Black,
    secondary = Palette.Accent,
    background = Palette.Black,
    onBackground = Palette.Value,
    surface = Palette.Black,
    onSurface = Palette.Value,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.Label,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface,
    surfaceContainerHighest = Palette.Surface,
    surfaceContainerLow = Palette.Black,
    surfaceContainerLowest = Palette.Black,
    outline = Color(0xFF333333),
    outlineVariant = Palette.Outline,
    error = Palette.Hot,
)

@Composable
fun PcStatsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
