package com.pcstats.monitor.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val DASH = "—"

enum class TempLevel { NORMAL, WARM, HOT }

object Format {
    fun tempLevel(celsius: Double?): TempLevel? = when {
        celsius == null -> null
        celsius > 80.0 -> TempLevel.HOT
        celsius >= 60.0 -> TempLevel.WARM
        else -> TempLevel.NORMAL
    }

    fun temp(celsius: Double?, fahrenheit: Boolean): String {
        if (celsius == null) return DASH
        val v = if (fahrenheit) celsius * 9.0 / 5.0 + 32.0 else celsius
        return "${v.roundToInt()}°${if (fahrenheit) "F" else "C"}"
    }

    fun percent(v: Double?): String = if (v == null) DASH else "${v.roundToInt()}%"

    fun clock(mhz: Double?): String = when {
        mhz == null -> DASH
        mhz >= 1000 -> String.format(Locale.US, "%.2f GHz", mhz / 1000.0)
        else -> "${mhz.roundToInt()} MHz"
    }

    fun power(w: Double?): String = when {
        w == null -> DASH
        abs(w) < 10 -> String.format(Locale.US, "%.1f W", w)
        else -> "${w.roundToInt()} W"
    }

    fun rpm(v: Double?): String = if (v == null) DASH else "${v.roundToInt()} rpm"

    fun fps(v: Double?): String = if (v == null) DASH else v.roundToInt().toString()

    /** Megabytes (MiB as reported by the agent) to a compact GB string. */
    fun memGb(mb: Double?): String = if (mb == null) DASH else String.format(Locale.US, "%.1f", mb / 1024.0)

    fun memPair(usedMb: Double?, totalMb: Double?): String =
        if (usedMb == null && totalMb == null) DASH else "${memGb(usedMb)} / ${memGb(totalMb)} GB"

    fun mem(mb: Double?): String = if (mb == null) DASH else "${memGb(mb)} GB"

    /** Bytes per second as megabits per second, the unit the dashboard network rings use. */
    fun mbps(bytesPerSec: Double?): String {
        if (bytesPerSec == null) return DASH
        val v = bytesPerSec.coerceAtLeast(0.0) * 8 / 1_000_000
        return when {
            v < 100 -> String.format(Locale.US, "%.1f", v)
            else -> v.roundToInt().toString()
        }
    }

    /** Megabytes to "719 GB" / "1.8 TB". */
    fun size(mb: Double?): String = when {
        mb == null -> DASH
        mb >= 1024.0 * 1024 -> String.format(Locale.US, "%.1f TB", mb / (1024.0 * 1024))
        mb >= 1024 -> "${(mb / 1024).roundToInt()} GB"
        else -> "${mb.roundToInt()} MB"
    }

    /** Bytes to "18.2 GB" / "1.8 GB" / "640 MB". */
    fun bytes(b: Double?): String = when {
        b == null -> DASH
        b >= 1024.0 * 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f TB", b / (1024.0 * 1024 * 1024 * 1024))
        b >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024))
        else -> "${(b / (1024.0 * 1024)).roundToInt()} MB"
    }

    /** Temperature number only (no unit), converted if needed. */
    fun tempNumber(celsius: Double?, fahrenheit: Boolean): String {
        if (celsius == null) return DASH
        val v = if (fahrenheit) celsius * 9.0 / 5.0 + 32.0 else celsius
        return v.roundToInt().toString()
    }

    fun grouped(v: Double?): String = if (v == null) DASH else String.format(Locale.US, "%,d", v.roundToInt())

    fun ghz(mhz: Double?): String = if (mhz == null) DASH else String.format(Locale.US, "%.2f", mhz / 1000.0)

    fun volts(v: Double?): String = if (v == null) DASH else String.format(Locale.US, "%.3f", v)

    /** Bytes per second with auto-scaled units (B/s, KB/s, MB/s, GB/s; 1024 base). */
    fun rate(bytesPerSec: Double?): String {
        if (bytesPerSec == null) return DASH
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = bytesPerSec.coerceAtLeast(0.0)
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return when {
            i == 0 -> "${v.roundToInt()} ${units[i]}"
            v < 10 -> String.format(Locale.US, "%.2f %s", v, units[i])
            v < 100 -> String.format(Locale.US, "%.1f %s", v, units[i])
            else -> "${v.roundToInt()} ${units[i]}"
        }
    }

    fun fraction(used: Double?, total: Double?): Float? =
        if (used == null || total == null || total <= 0) null else (used / total).toFloat().coerceIn(0f, 1f)
}
