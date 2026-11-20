package com.pcstats.monitor.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

/** Joins the agent root and a logo path from the snapshot; only agent-relative paths are accepted. */
fun logoUrl(httpBase: String?, logo: String?): String? =
    if (httpBase == null || logo == null || !logo.startsWith("/logos/")) null else httpBase + logo

@Composable
fun FpsCounter(value: Double?, size: TextUnit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Format.fps(value), color = Palette.Value, fontFamily = Orbitron, fontWeight = FontWeight.Black, fontSize = size, maxLines = 1)
        Text(
            "FPS", color = Color(0xFFFF5B3A), fontFamily = Orbitron, fontWeight = FontWeight.Bold,
            fontSize = size * 0.28f, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = (size.value * 0.12f).dp),
        )
    }
}

/** Game logo (or its name) above a large FPS counter. */
@Composable
fun NowPlaying(fps: FpsStats, httpBase: String?, modifier: Modifier = Modifier) {
    val logo = rememberRemoteImage(logoUrl(httpBase, fps.logo))
    val title = fps.game ?: fps.app?.removeSuffix(".exe") ?: ""
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (logo != null) {
                Image(logo, contentDescription = title, contentScale = ContentScale.Fit)
            } else {
                FitText(title, 18.sp, color = Palette.Value, weight = FontWeight.Bold)
            }
        }
        FpsCounter(fps.value, 44.sp)
    }
}
