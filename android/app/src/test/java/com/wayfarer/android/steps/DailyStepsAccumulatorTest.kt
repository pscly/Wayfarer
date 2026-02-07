package com.wayfarer.android.steps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyStepsAccumulatorTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `first sample creates day key with zero`() {
        val day = LocalDate.of(2026, 2, 8)
        val t = ZonedDateTime.of(2026, 2, 8, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

        val state =
            DailyStepsAccumulator.applySample(
                prev =
                    DailyStepsAccumulator.State(
                        lastSampleUtcMs = null,
                        lastCumulativeSteps = null,
                        dailyTotals = emptyMap(),
                        hourlyBuckets = emptyMap(),
                    ),
                sampleTimeUtcMs = t,
                cumulativeSteps = 1234,
                zone = zone,
                keepDays = 120,
            )

        assertEquals(0L, state.dailyTotals[day])
        assertEquals(1234L, state.lastCumulativeSteps)
        assertEquals(t, state.lastSampleUtcMs)
        assertEquals(0L, state.hourlyBuckets[day]?.get(10))
    }

    @Test
    fun `second sample accumulates delta into day and hour`() {
        val day = LocalDate.of(2026, 2, 8)
        val t1 = ZonedDateTime.of(2026, 2, 8, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val t2 = ZonedDateTime.of(2026, 2, 8, 10, 15, 0, 0, zone).toInstant().toEpochMilli()

        val s1 =
            DailyStepsAccumulator.applySample(
                prev =
                    DailyStepsAccumulator.State(
                        lastSampleUtcMs = null,
                        lastCumulativeSteps = null,
                        dailyTotals = emptyMap(),
                        hourlyBuckets = emptyMap(),
                    ),
                sampleTimeUtcMs = t1,
                cumulativeSteps = 1000,
                zone = zone,
                keepDays = 120,
            )

        val s2 =
            DailyStepsAccumulator.applySample(
                prev = s1,
                sampleTimeUtcMs = t2,
                cumulativeSteps = 1100,
                zone = zone,
                keepDays = 120,
            )

        assertEquals(100L, s2.dailyTotals[day])
        assertEquals(100L, s2.hourlyBuckets[day]?.get(10))
    }

    @Test
    fun `sensor reset does not produce negative delta`() {
        val day = LocalDate.of(2026, 2, 8)
        val t = ZonedDateTime.of(2026, 2, 8, 11, 0, 0, 0, zone).toInstant().toEpochMilli()

        val prev =
            DailyStepsAccumulator.State(
                lastSampleUtcMs = t - 60_000,
                lastCumulativeSteps = 5000,
                dailyTotals = mapOf(day to 321L),
                hourlyBuckets = mapOf(day to LongArray(24) { 0L }.also { it[10] = 321L }),
            )

        val next =
            DailyStepsAccumulator.applySample(
                prev = prev,
                sampleTimeUtcMs = t,
                cumulativeSteps = 12, // reset/reboot
                zone = zone,
                keepDays = 120,
            )

        assertEquals(321L, next.dailyTotals[day])
        assertEquals(12L, next.lastCumulativeSteps)
    }

    @Test
    fun `prunes old days`() {
        val d1 = LocalDate.of(2026, 2, 6)
        val d2 = LocalDate.of(2026, 2, 7)
        val d3 = LocalDate.of(2026, 2, 8)

        val prev =
            DailyStepsAccumulator.State(
                lastSampleUtcMs = null,
                lastCumulativeSteps = 100,
                dailyTotals = mapOf(d1 to 1L, d2 to 2L),
                hourlyBuckets = mapOf(d1 to LongArray(24) { 0L }, d2 to LongArray(24) { 0L }),
            )

        val t3 = ZonedDateTime.of(2026, 2, 8, 0, 10, 0, 0, zone).toInstant().toEpochMilli()
        val next =
            DailyStepsAccumulator.applySample(
                prev = prev,
                sampleTimeUtcMs = t3,
                cumulativeSteps = 105,
                zone = zone,
                keepDays = 2,
            )

        assertNull(next.dailyTotals[d1])
        assertEquals(2L, next.dailyTotals[d2])
        assertEquals(5L, next.dailyTotals[d3])
    }
}

