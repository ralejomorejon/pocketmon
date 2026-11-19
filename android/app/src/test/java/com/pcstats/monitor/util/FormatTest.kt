package com.pcstats.monitor.util

import com.pcstats.monitor.protocol.CpuStats
import com.pcstats.monitor.protocol.NetStats
import com.pcstats.monitor.protocol.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun nullsRenderAsDash() {
        assertEquals(DASH, Format.temp(null, false))
        assertEquals(DASH, Format.percent(null))
        assertEquals(DASH, Format.clock(null))
        assertEquals(DASH, Format.power(null))
        assertEquals(DASH, Format.rate(null))
        assertEquals(DASH, Format.memPair(null, null))
        assertEquals(DASH, Format.rpm(null))
    }

    @Test
    fun networkRateAutoUnits() {
        assertEquals("512 B/s", Format.rate(512.0))
        assertEquals("11.7 KB/s", Format.rate(12000.0))
        assertEquals("3.24 MB/s", Format.rate(3_400_000.0))
        assertEquals("1.00 GB/s", Format.rate(1024.0 * 1024 * 1024))
    }

    @Test
    fun temperatureUnitsAndLevels() {
        assertEquals("61°C", Format.temp(61.0, false))
        assertEquals("142°F", Format.temp(61.0, true))
        assertEquals(TempLevel.NORMAL, Format.tempLevel(59.9))
        assertEquals(TempLevel.WARM, Format.tempLevel(60.0))
        assertEquals(TempLevel.WARM, Format.tempLevel(80.0))
        assertEquals(TempLevel.HOT, Format.tempLevel(80.1))
    }

    @Test
    fun sessionStatsTrackPeaksAndSeries() {
        val snap = { usage: Double?, down: Double? -> Snapshot(cpu = CpuStats(usage = usage), net = NetStats(downBps = down)) }
        var s = SessionStats().plus(snap(61.0, 100.0)).plus(snap(null, null)).plus(snap(57.0, 900.0)).plus(snap(88.0, 50.0))
        assertEquals(MinMax(50.0, 900.0), s.down)
        assertEquals(listOf(61f, null, 57f, 88f), s.cpuSeries.values)
        repeat(75) { s = s.plus(snap(it.toDouble(), null)) }
        assertEquals(60, s.cpuSeries.values.size)
        assertEquals(74f, s.cpuSeries.values.last())
    }

    @Test
    fun dashboardUnits() {
        assertEquals("51.2", Format.mbps(6_400_000.0))
        assertEquals("120", Format.mbps(15_000_000.0))
        assertEquals("719 GB", Format.size(719.0 * 1024))
        assertEquals("1.8 TB", Format.size(1.8 * 1024 * 1024))
        assertEquals("18.2 GB", Format.bytes(18.2 * 1024 * 1024 * 1024))
        assertEquals("1,640", Format.grouped(1640.0))
        assertEquals("194", Format.tempNumber(90.0, true))
    }
}
