package com.pcstats.monitor.burnin

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A point in dp, relative to the content's resting position. */
data class ShiftOffset(val x: Float, val y: Float) {
    companion object {
        val Zero = ShiftOffset(0f, 0f)
    }
}

/**
 * Bounded random walk used for pixel shifting. Each [next] moves a few dp in a random direction
 * and reflects off the ±[maxDp] box so the content never leaves its allowed area.
 */
class PixelShiftWalker(
    maxDp: Float,
    private val random: Random = Random.Default,
    private val minStepDp: Float = 2f,
    private val maxStepDp: Float = 4f,
) {
    var maxDp: Float = maxDp.coerceAtLeast(0f)
        set(value) {
            field = value.coerceAtLeast(0f)
            current = ShiftOffset(current.x.coerceIn(-field, field), current.y.coerceIn(-field, field))
        }

    var current: ShiftOffset = ShiftOffset.Zero
        private set

    fun next(): ShiftOffset {
        val m = maxDp
        if (m == 0f) {
            current = ShiftOffset.Zero
            return current
        }
        val angle = random.nextDouble(0.0, 2 * Math.PI)
        val step = (minStepDp + random.nextFloat() * (maxStepDp - minStepDp)).coerceAtMost(m)
        val x = reflect(current.x + (cos(angle) * step).toFloat(), m)
        val y = reflect(current.y + (sin(angle) * step).toFloat(), m)
        current = ShiftOffset(x, y)
        return current
    }

    private fun reflect(v: Float, m: Float): Float = when {
        v > m -> (2 * m - v).coerceIn(-m, m)
        v < -m -> (-2 * m - v).coerceIn(-m, m)
        else -> v
    }
}
