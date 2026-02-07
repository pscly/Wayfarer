package com.wayfarer.android.steps

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 纯 Kotlin 的“按天步数累计器”，用于把 Step Counter（累积值）转换为：
 * - 本地日（LocalDate）维度的累计步数
 * - 近似的本地小时桶（把 delta 归属到采样发生的小时）
 *
 * 设计目标：
 * - 不依赖 Android Context，便于单元测试
 * - 作为 SharedPreferences 持久化层的核心算法
 */
object DailyStepsAccumulator {
    data class State(
        val lastSampleUtcMs: Long?,
        val lastCumulativeSteps: Long?,
        val dailyTotals: Map<LocalDate, Long>,
        val hourlyBuckets: Map<LocalDate, LongArray>,
    )

    fun applySample(
        prev: State,
        sampleTimeUtcMs: Long,
        cumulativeSteps: Long,
        zone: ZoneId,
        keepDays: Int = 120,
    ): State {
        val current = cumulativeSteps.coerceAtLeast(0L)
        val last = prev.lastCumulativeSteps
        val delta =
            when {
                last == null -> 0L
                current < last -> 0L // reboot/reset
                else -> (current - last).coerceAtLeast(0L)
            }

        val zdt = Instant.ofEpochMilli(sampleTimeUtcMs).atZone(zone)
        val day = zdt.toLocalDate()
        val hour = zdt.hour.coerceIn(0, 23)

        // Copy-on-write to keep this function pure and safe.
        val totals = HashMap(prev.dailyTotals)
        val hourly = HashMap<LocalDate, LongArray>()
        for ((d, arr) in prev.hourlyBuckets) {
            hourly[d] = arr.copyOf()
        }

        // Ensure day key exists once we have a sample (even delta=0).
        totals[day] = (totals[day] ?: 0L) + delta

        val bucket = hourly[day] ?: LongArray(24) { 0L }
        bucket[hour] = bucket[hour] + delta
        hourly[day] = bucket

        // Prune history by local day.
        val keep = keepDays.coerceAtLeast(1)
        val earliest = day.minusDays((keep - 1).toLong())
        totals.keys.removeIf { it.isBefore(earliest) }
        hourly.keys.removeIf { it.isBefore(earliest) }

        return State(
            lastSampleUtcMs = sampleTimeUtcMs,
            lastCumulativeSteps = current,
            dailyTotals = totals,
            hourlyBuckets = hourly,
        )
    }
}

