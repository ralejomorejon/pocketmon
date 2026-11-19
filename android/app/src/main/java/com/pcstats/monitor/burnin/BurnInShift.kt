package com.pcstats.monitor.burnin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val DEFAULT_SHIFT_MAX_DP = 8f
const val DEFAULT_SHIFT_INTERVAL_MS = 60_000L
const val DEFAULT_SHIFT_ANIMATION_MS = 2_000

/** Animated pixel-shift offset (in dp) driven by a [PixelShiftWalker]. Share it to move several layers together. */
@Stable
class BurnInShiftState internal constructor(private val x: Animatable<Float, *>, private val y: Animatable<Float, *>) {
    val offset: State<ShiftOffset> = derivedStateOf { ShiftOffset(x.value, y.value) }
}

@Composable
fun rememberBurnInShift(
    maxDp: Float = DEFAULT_SHIFT_MAX_DP,
    intervalMs: Long = DEFAULT_SHIFT_INTERVAL_MS,
    animationMs: Int = DEFAULT_SHIFT_ANIMATION_MS,
): BurnInShiftState {
    val walker = remember { PixelShiftWalker(maxDp) }
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    val state = remember { BurnInShiftState(x, y) }
    LaunchedEffect(maxDp, intervalMs, animationMs) {
        walker.maxDp = maxDp
        while (true) {
            val target = walker.next()
            launch { x.animateTo(target.x, tween(animationMs, easing = FastOutSlowInEasing)) }
            launch { y.animateTo(target.y, tween(animationMs, easing = FastOutSlowInEasing)) }
            delay(intervalMs.coerceAtLeast(animationMs.toLong()))
        }
    }
    return state
}

/** Offsets this element by a shared [BurnInShiftState]. */
fun Modifier.burnInShift(state: BurnInShiftState): Modifier = offset {
    val o = state.offset.value
    IntOffset((o.x * density).roundToInt(), (o.y * density).roundToInt())
}

/** Self-contained pixel shift: slow random walk within ±[maxDp], stepping every [intervalMs]. */
fun Modifier.burnInShift(
    maxDp: Float = DEFAULT_SHIFT_MAX_DP,
    intervalMs: Long = DEFAULT_SHIFT_INTERVAL_MS,
    animationMs: Int = DEFAULT_SHIFT_ANIMATION_MS,
): Modifier = composed {
    val state = rememberBurnInShift(maxDp, intervalMs, animationMs)
    burnInShift(state)
}
