package com.pcstats.monitor.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcstats.monitor.R
import com.pcstats.monitor.protocol.FpsStats
import com.pcstats.monitor.ui.Palette
import com.pcstats.monitor.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Orbitron (OFL) — the display face for the FPS counter. Variable font: pick the weight via variation settings. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Orbitron = FontFamily(
    Font(R.font.orbitron, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
    Font(R.font.orbitron, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val ImageHttp = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
private val ImageCache = ConcurrentHashMap<String, ImageBitmap>()

/** Loads a PNG from the agent (cached in memory). Null while loading or on failure. */
@Composable
fun rememberRemoteImage(url: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(url?.let { ImageCache[it] }, url) {
        // produceState keeps the previous value when the key changes; reset it for the new URL first.
        value = url?.let { ImageCache[it] }
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null
                    else r.body.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
                }
            }.getOrNull()
        }?.also { ImageCache[url] = it }
    }
    return image
}

private val FontCache = ConcurrentHashMap<String, FontFamily>()

/** Downloads a font from the agent into the app cache and loads it. Null while loading, on failure or for a null url. */
@Composable
fun rememberRemoteFont(url: String?): FontFamily? {
    val dir = LocalContext.current.cacheDir
    val family by produceState<FontFamily?>(url?.let { FontCache[it] }, url) {
        value = url?.let { FontCache[it] }
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val file = java.io.File(dir, "font-" + url.substringAfterLast('/'))
                    file.writeBytes(r.body.bytes())
                    FontFamily(android.graphics.Typeface.createFromFile(file))
                }
            }.getOrNull()
        }?.also { FontCache[url] = it }
    }
    return family
}

/** Joins the agent root and a logo path from the snapshot; only agent-relative paths are accepted. */
fun logoUrl(httpBase: String?, logo: String?): String? =
    if (httpBase == null || logo == null || !logo.startsWith("/logos/")) null else httpBase + logo

/** Cinzel (OFL): Trajan-style capitals, the closest free match to the Ocarina of Time title lettering. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Cinzel = FontFamily(
    Font(R.font.cinzel, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
    Font(R.font.cinzel, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Parses "#RRGGBB" from the agent; anything else yields null. */
fun parseHexColor(hex: String?): Color? =
    hex?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }?.let { Color(0xFF000000 or it.substring(1).toLong(16)) }

/**
 * FPS number + "FPS" label. Default: Orbitron in near-white with a red label. Games can ask for their own
 * lettering ([font] "cinzel" for Zelda), drawn in a metallic gradient of [color] like the game's logo.
 */
@Composable
fun FpsCounter(
    value: Double?,
    size: TextUnit,
    modifier: Modifier = Modifier,
    font: String? = null,
    color: String? = null,
    httpBase: String? = null,
) {
    val tint = parseHexColor(color)
    // Fonts other than the bundled ones are installed on the PC and served by the agent (licence-restricted fonts
    // like Hylia Serif are never shipped in the app). Cinzel stands in while one loads.
    val custom = font?.takeIf { it != "cinzel" && it != "orbitron" && Regex("^[a-z0-9][a-z0-9-]{0,31}$").matches(it) }
    val customFamily = rememberRemoteFont(custom?.let { id -> httpBase?.let { "$it/fonts/$id" } })
    val cinzel = font != null && font != "orbitron"
    val family = customFamily ?: if (cinzel) Cinzel else Orbitron
    val weight = if (customFamily != null) FontWeight.Normal else FontWeight.Black
    val numberSize = if (cinzel) size * 1.12f else size
    val numberStyle = if (tint != null && cinzel) {
        TextStyle(
            brush = Brush.verticalGradient(listOf(Color(0xFFFFF6D8), tint, lerp(tint, Color.Black, 0.45f))),
            shadow = Shadow(lerp(tint, Color.Black, 0.65f), Offset(0f, 3f), 0f),
        )
    } else {
        TextStyle(color = tint ?: Palette.Value)
    }
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Format.fps(value), style = numberStyle, fontFamily = family, fontWeight = weight, fontSize = numberSize, maxLines = 1)
        Text(
            "FPS", color = tint ?: Color(0xFFFF5B3A), fontFamily = family,
            fontWeight = if (customFamily != null) FontWeight.Normal else FontWeight.Bold,
            fontSize = size * (if (cinzel) 0.32f else 0.28f), letterSpacing = (if (cinzel) 3 else 2).sp,
            modifier = Modifier.padding(bottom = (size.value * 0.12f).dp),
        )
    }
}
/** Game logo (or its name) with a large FPS counter: stacked when there's height, side by side when short. */
@Composable
fun NowPlaying(fps: FpsStats, httpBase: String?, modifier: Modifier = Modifier) {
    val logo = rememberRemoteImage(logoUrl(httpBase, fps.logo))
    val title = fps.game ?: fps.app?.removeSuffix(".exe") ?: ""
    val art: @Composable (Modifier) -> Unit = { m ->
        Box(m, contentAlignment = Alignment.Center) {
            if (logo != null) {
                Image(logo, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                FitText(title, 18.sp, color = Palette.Value, weight = FontWeight.Bold)
            }
        }
    }
    BoxWithConstraints(modifier) {
        if (maxHeight < 150.dp && maxWidth > maxHeight * 1.3f) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                art(Modifier.weight(1.6f).fillMaxHeight())
                FpsCounter(fps.value, 34.sp, font = fps.font, color = fps.color, httpBase = httpBase)
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                art(Modifier.fillMaxWidth().weight(1f))
                FpsCounter(fps.value, 44.sp, font = fps.font, color = fps.color, httpBase = httpBase)
            }
        }
    }
}
