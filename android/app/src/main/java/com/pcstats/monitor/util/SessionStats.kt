package com.pcstats.monitor.util

import com.pcstats.monitor.protocol.Snapshot

/** Range of values seen since the session started; nulls are ignored. */
data class MinMax(val min: Double? = null, val max: Double? = null) {
    fun plus(v: Double?): MinMax = if (v == null) this else MinMax(
        min = min?.coerceAtMost(v) ?: v,
        max = max?.coerceAtLeast(v) ?: v,
    )
}

/** Immutable fixed-size series of the last [capacity] samples, oldest first. Nulls are gaps. */
class SampleSeries private constructor(val capacity: Int, val values: List<Float?>) {
    constructor(capacity: Int = 60) : this(capacity, emptyList())

    fun plus(v: Double?): SampleSeries {
        val next = if (values.size >= capacity) values.drop(values.size - capacity + 1) else values
        return SampleSeries(capacity, next + v?.toFloat())
    }

    val max: Float? get() = values.filterNotNull().maxOrNull()
}

/** Per-connection history: network peaks (modern design) and the last 60 samples for sparklines (classic design). */
data class SessionStats(
    val up: MinMax = MinMax(),
    val down: MinMax = MinMax(),
    val cpuSeries: SampleSeries = SampleSeries(),
    val gpuSeries: SampleSeries = SampleSeries(),
    val ramSeries: SampleSeries = SampleSeries(),
    val upSeries: SampleSeries = SampleSeries(),
    val downSeries: SampleSeries = SampleSeries(),
    val fpsSeries: SampleSeries = SampleSeries(),
) {
    fun plus(s: Snapshot): SessionStats {
        val ramPct = s.ram?.let { r -> if (r.usedMb != null && r.totalMb != null && r.totalMb > 0) r.usedMb / r.totalMb * 100 else null }
        return SessionStats(
            up = up.plus(s.net?.upBps),
            down = down.plus(s.net?.downBps),
            cpuSeries = cpuSeries.plus(s.cpu?.usage),
            gpuSeries = gpuSeries.plus(s.gpu?.usage),
            ramSeries = ramSeries.plus(ramPct),
            upSeries = upSeries.plus(s.net?.upBps),
            downSeries = downSeries.plus(s.net?.downBps),
            fpsSeries = fpsSeries.plus(s.fps?.value),
        )
    }
}
