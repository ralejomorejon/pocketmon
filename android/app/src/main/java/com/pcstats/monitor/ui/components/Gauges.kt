package com.pcstats.monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pcstats.monitor.ui.Palette
import com.pcstats.monitor.util.DASH
import com.pcstats.monitor.util.SampleSeries
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP = 270f

/** "118" + small gray " W"; a dash gets no unit. */
fun valueUnit(value: String, unit: String?, unitColor: Color = Palette.Label): AnnotatedString = buildAnnotatedString {
    append(value)
    if (value != DASH && !unit.isNullOrEmpty()) {
        withStyle(SpanStyle(fontSize = 0.58.em, color = unitColor, fontWeight = FontWeight.Normal)) { append(" $unit") }
    }
}

/** Single-line text that shrinks (never clips or wraps) when its box is too small. */
@Composable
fun FitText(
    text: AnnotatedString,
    maxSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Value,
    weight: FontWeight = FontWeight.Normal,
    minSize: TextUnit = 7.sp,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontWeight = weight, fontSize = maxSize),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 0.5.sp),
    )
}

@Composable
fun FitText(text: String, maxSize: TextUnit, modifier: Modifier = Modifier, color: Color = Palette.Value, weight: FontWeight = FontWeight.Normal) =
    FitText(AnnotatedString(text), maxSize, modifier, color, weight)

/** Temperature color: cool blue, amber when warm, red-orange when hot. */
fun heatColor(celsius: Double?): Color = when {
    celsius == null -> Palette.Label
    celsius < 50 -> Palette.Cool
    celsius < 70 -> Palette.Amber
    else -> Palette.Heat
}

/** Load color for small bars: dim at idle, amber mid, red-orange at full load. */
fun loadColor(percent: Double): Color {
    val f = (percent / 100.0).toFloat().coerceIn(0f, 1f)
    return if (f < 0.5f) lerp(Palette.BarIdle, Palette.Amber, f / 0.5f) else lerp(Palette.Amber, Palette.Heat, (f - 0.5f) / 0.5f)
}

private fun DrawScope.polar(center: Offset, radius: Float, angleDeg: Float): Offset {
    val a = Math.toRadians(angleDeg.toDouble())
    return Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
}

/**
 * The big temperature gauge: tick ring, thick temperature arc with end knob, thin inner load arc,
 * optional limit marker and (GPU) hotspot shown by lighting up the tick ring.
 */
@Composable
fun TempGauge(
    tempC: Double?,
    loadPercent: Double?,
    tempText: String,
    unitText: String,
    subtitle: String?,
    minLabel: String,
    maxLabel: String,
    minC: Double,
    maxC: Double,
    modifier: Modifier = Modifier,
    limitC: Double? = null,
    limitLabel: String? = null,
    hotspotC: Double? = null,
) {
    val measurer = rememberTextMeasurer()
    val color = heatColor(tempC)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side: Dp = min(maxWidth.value, maxHeight.value).dp
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = size.minDimension
                val c = center
                val half = s / 2f
                val stroke = s * 0.05f
                val progressR = half * 0.74f
                val tickIn = half * 0.86f
                val tickOut = half * 0.95f
                val loadR = half * 0.60f
                fun frac(v: Double?) = if (v == null) 0f else ((v - minC) / (maxC - minC)).toFloat().coerceIn(0f, 1f)

                // Tick ring; with a hotspot value, ticks up to it light up in the hotspot's heat color.
                val ticks = 54
                val hsFrac = if (hotspotC != null) frac(hotspotC) else -1f
                val hsColor = heatColor(hotspotC)
                for (i in 0..ticks) {
                    val f = i / ticks.toFloat()
                    val a = START_ANGLE + SWEEP * f
                    val lit = f <= hsFrac
                    val major = i % 9 == 0
                    drawLine(
                        color = if (lit) hsColor.copy(alpha = 0.9f) else Palette.Tick,
                        start = polar(c, if (lit || major) tickIn - half * 0.02f else tickIn, a),
                        end = polar(c, tickOut, a),
                        strokeWidth = if (lit) s * 0.012f else s * 0.006f,
                    )
                }

                val arcTopLeft = Offset(c.x - progressR, c.y - progressR)
                val arcSize = Size(progressR * 2, progressR * 2)
                drawArc(Palette.Track, START_ANGLE, SWEEP, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))

                val f = frac(tempC)
                if (tempC != null) {
                    // Rotate so the arc starts at 0°; the sweep gradient then has no seam inside the arc.
                    rotate(START_ANGLE, c) {
                        val brush = Brush.sweepGradient(
                            0f to lerp(color, Palette.HeatDeep, 0.45f),
                            0.75f to color,
                            1f to color,
                            center = c,
                        )
                        drawArc(brush, 0f, SWEEP * f.coerceAtLeast(0.005f), false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    val knob = polar(c, progressR, START_ANGLE + SWEEP * f)
                    drawCircle(Palette.Black, stroke * 0.85f, knob)
                    drawCircle(color, stroke * 0.85f, knob, style = Stroke(stroke * 0.38f))
                }

                // Inner load arc.
                val loadTopLeft = Offset(c.x - loadR, c.y - loadR)
                val loadSize = Size(loadR * 2, loadR * 2)
                val thin = s * 0.012f
                drawArc(Palette.Track, START_ANGLE, SWEEP, false, loadTopLeft, loadSize, style = Stroke(thin, cap = StrokeCap.Round))
                if (loadPercent != null && loadPercent > 0) {
                    drawArc(
                        Palette.LoadArc, START_ANGLE, SWEEP * (loadPercent / 100.0).toFloat().coerceIn(0f, 1f), false,
                        loadTopLeft, loadSize, style = Stroke(thin * 1.6f, cap = StrokeCap.Round),
                    )
                }

                val labelStyle = TextStyle(color = Palette.Label, fontSize = (s * 0.045f / density).sp)
                fun label(text: String, at: Offset, style: TextStyle) {
                    val m = measurer.measure(text, style)
                    drawText(m, topLeft = Offset(at.x - m.size.width / 2f, at.y - m.size.height / 2f))
                }
                label(minLabel, polar(c, progressR, START_ANGLE) + Offset(0f, stroke * 2.2f), labelStyle)
                label(maxLabel, polar(c, progressR, START_ANGLE + SWEEP) + Offset(0f, stroke * 2.2f), labelStyle)

                fun marker(valueC: Double, text: String?) {
                    val a = START_ANGLE + SWEEP * frac(valueC)
                    drawLine(Palette.Alert, polar(c, progressR - stroke * 0.9f, a), polar(c, progressR + stroke * 1.6f, a), strokeWidth = s * 0.011f, cap = StrokeCap.Round)
                    if (text != null) {
                        label(text, polar(c, progressR + stroke * 3.1f, a), labelStyle.copy(color = Palette.Alert))
                    }
                }
                if (limitC != null) marker(limitC, limitLabel)
            }

            // Center readout.
            val big = (side.value * 0.27f).sp
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Top) {
                    BasicText(tempText, style = TextStyle(color = if (tempC == null) Palette.Label else color, fontSize = big, fontWeight = FontWeight.Light))
                    if (tempC != null) {
                        BasicText(
                            unitText,
                            style = TextStyle(color = Palette.Label, fontSize = big * 0.3f, baselineShift = BaselineShift.Superscript),
                        )
                    }
                }
                if (subtitle != null) {
                    BasicText(subtitle, style = TextStyle(color = Palette.Label, fontSize = (side.value * 0.045f).sp))
                }
            }
        }
    }
}

/** 270° ring with round caps, optional outer ticks, content in the middle. */
@Composable
fun RingGauge(
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    strokeRatio: Float = 0.075f,
    ticks: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side: Dp = min(maxWidth.value, maxHeight.value).dp
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = size.minDimension
                val half = s / 2f
                val stroke = s * strokeRatio
                val r = if (ticks) half * 0.78f else half - stroke
                if (ticks) {
                    val n = 40
                    for (i in 0..n) {
                        val a = START_ANGLE + SWEEP * i / n
                        drawLine(Palette.Tick, polar(center, half * 0.9f, a), polar(center, half * 0.98f, a), strokeWidth = s * 0.008f)
                    }
                }
                val tl = Offset(center.x - r, center.y - r)
                val sz = Size(r * 2, r * 2)
                drawArc(Palette.Track, START_ANGLE, SWEEP, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction != null && fraction > 0f) {
                    drawArc(color, START_ANGLE, SWEEP * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            content()
        }
    }
}

/** One rounded bar per core, colored by load. */
@Composable
fun CoreBars(loads: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (loads.isEmpty()) {
            drawRoundRect(Palette.Track, cornerRadius = CornerRadius(3.dp.toPx()))
            return@Canvas
        }
        val gap = if (loads.size > 16) 1.5.dp.toPx() else 3.dp.toPx()
        val w = (size.width - gap * (loads.size - 1)) / loads.size
        loads.forEachIndexed { i, load ->
            drawRoundRect(
                color = loadColor(load),
                topLeft = Offset(i * (w + gap), 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** Thin horizontal usage bar with an amber gradient fill. */
@Composable
fun UsageBar(fraction: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Palette.Track, cornerRadius = r)
        if (fraction != null && fraction > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(Palette.AmberDeep, Palette.Amber), endX = size.width * fraction),
                size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
                cornerRadius = r,
            )
        }
    }
}

// ---------------------------------------------------------------- classic design

/** Thin outline arc gauge (270° sweep). [fraction] null draws only the track. */
@Composable
fun ArcGauge(
    fraction: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Dp = 3.dp,
    color: Color = Palette.Accent,
    content: @Composable () -> Unit = {},
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            val topLeft = Offset(inset, inset)
            drawArc(Palette.Track, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            if (fraction != null && fraction > 0f) {
                drawArc(color, START_ANGLE, SWEEP * fraction.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Thin line sparkline of the last samples; no fill. */
@Composable
fun Sparkline(
    series: SampleSeries,
    modifier: Modifier = Modifier,
    color: Color = Palette.Accent,
    fixedMax: Float? = null,
    stroke: Dp = 1.dp,
) {
    Canvas(modifier) {
        val values = series.values
        if (values.size < 2) return@Canvas
        val maxV = (fixedMax ?: series.max ?: 0f).coerceAtLeast(1e-3f)
        val stepX = size.width / (series.capacity - 1).coerceAtLeast(1)
        val startX = size.width - stepX * (values.size - 1)
        val path = Path()
        var penDown = false
        values.forEachIndexed { i, v ->
            if (v == null) {
                penDown = false
                return@forEachIndexed
            }
            val x = startX + i * stepX
            val y = size.height - (v / maxV).coerceIn(0f, 1f) * size.height
            if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
            penDown = true
        }
        drawPath(path, color, style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
    }
}
