package com.wayfarer.android.steps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * “系统全天步数”（国内手机优先）的本地存储：
 * - 数据源：Sensor.TYPE_STEP_COUNTER（累积值）
 * - 持久化：SharedPreferences（轻量、无需 Room 迁移）
 *
 * 说明：
 * - 我们无法拿到每一步的精确时间戳，因此“按小时桶”是近似值（按采样时刻归属）。
 * - 为了尽量接近系统步数，配合 WorkManager 15 分钟后台采样 + App 回前台的一次采样。
 */
object DailyStepsStore {
    private const val PREFS_NAME = "wayfarer_daily_steps"

    private const val KEY_LAST_SAMPLE_UTC_MS = "last_sample_utc_ms"
    private const val KEY_LAST_CUMULATIVE_STEPS = "last_cumulative_steps"
    private const val KEY_DAILY_TOTALS_JSON = "daily_totals_json"
    private const val KEY_DAILY_HOURLY_JSON = "daily_hourly_json"

    private const val KEEP_DAYS = 120

    data class Snapshot(
        val lastSampleUtcMs: Long?,
        val lastCumulativeSteps: Long?,
        val dailyTotals: Map<LocalDate, Long>,
        val hourlyBuckets: Map<LocalDate, LongArray>,
    ) {
        fun todaySteps(zone: ZoneId): Long? {
            val day = LocalDate.now(zone)
            return dailyTotals[day]
        }

        fun todayHourlyBuckets(zone: ZoneId): LongArray? {
            val day = LocalDate.now(zone)
            return hourlyBuckets[day]
        }

        fun lastSampleLocalTimeText(zone: ZoneId): String {
            val ms = lastSampleUtcMs ?: return "-"
            val zdt = Instant.ofEpochMilli(ms).atZone(zone)
            return "%02d:%02d".format(zdt.hour, zdt.minute)
        }
    }

    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSnapshot(
        context: Context,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Snapshot {
        synchronized(lock) {
            val p = prefs(context)
            val lastSample = p.getLong(KEY_LAST_SAMPLE_UTC_MS, -1L).takeIf { it > 0L }
            val lastCumulative = p.getLong(KEY_LAST_CUMULATIVE_STEPS, -1L).takeIf { it >= 0L }

            val totals = readDailyTotals(p.getString(KEY_DAILY_TOTALS_JSON, null))
            val hourly = readHourlyBuckets(p.getString(KEY_DAILY_HOURLY_JSON, null))

            // Minor prune on read (timezone may change).
            val today = LocalDate.now(zone)
            val earliest = today.minusDays((KEEP_DAYS - 1).toLong())
            totals.keys.removeIf { it.isBefore(earliest) }
            hourly.keys.removeIf { it.isBefore(earliest) }

            return Snapshot(
                lastSampleUtcMs = lastSample,
                lastCumulativeSteps = lastCumulative,
                dailyTotals = totals,
                hourlyBuckets = hourly,
            )
        }
    }

    fun applySample(
        context: Context,
        sampleTimeUtcMs: Long,
        cumulativeSteps: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Snapshot {
        synchronized(lock) {
            val p = prefs(context)

            val prev = readSnapshot(context, zone)
            val next =
                DailyStepsAccumulator.applySample(
                    prev =
                        DailyStepsAccumulator.State(
                            lastSampleUtcMs = prev.lastSampleUtcMs,
                            lastCumulativeSteps = prev.lastCumulativeSteps,
                            dailyTotals = prev.dailyTotals,
                            hourlyBuckets = prev.hourlyBuckets,
                        ),
                    sampleTimeUtcMs = sampleTimeUtcMs,
                    cumulativeSteps = cumulativeSteps,
                    zone = zone,
                    keepDays = KEEP_DAYS,
                )

            val totalsJson = writeDailyTotals(next.dailyTotals)
            val hourlyJson = writeHourlyBuckets(next.hourlyBuckets)

            p.edit()
                .putLong(KEY_LAST_SAMPLE_UTC_MS, next.lastSampleUtcMs ?: -1L)
                .putLong(KEY_LAST_CUMULATIVE_STEPS, next.lastCumulativeSteps ?: -1L)
                .putString(KEY_DAILY_TOTALS_JSON, totalsJson)
                .putString(KEY_DAILY_HOURLY_JSON, hourlyJson)
                .apply()

            return Snapshot(
                lastSampleUtcMs = next.lastSampleUtcMs,
                lastCumulativeSteps = next.lastCumulativeSteps,
                dailyTotals = next.dailyTotals,
                hourlyBuckets = next.hourlyBuckets,
            )
        }
    }

    private fun readDailyTotals(json: String?): MutableMap<LocalDate, Long> {
        val out = HashMap<LocalDate, Long>(256)
        if (json.isNullOrBlank()) return out
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return out
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next() ?: continue
            val day = runCatching { LocalDate.parse(k) }.getOrNull() ?: continue
            val value = runCatching { obj.getLong(k) }.getOrNull() ?: continue
            out[day] = value.coerceAtLeast(0L)
        }
        return out
    }

    private fun writeDailyTotals(map: Map<LocalDate, Long>): String {
        val obj = JSONObject()
        for ((day, steps) in map) {
            obj.put(day.toString(), steps.coerceAtLeast(0L))
        }
        return obj.toString()
    }

    private fun readHourlyBuckets(json: String?): MutableMap<LocalDate, LongArray> {
        val out = HashMap<LocalDate, LongArray>(256)
        if (json.isNullOrBlank()) return out
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return out
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next() ?: continue
            val day = runCatching { LocalDate.parse(k) }.getOrNull() ?: continue
            val arr = runCatching { obj.getJSONArray(k) }.getOrNull() ?: continue
            val outArr = LongArray(24) { 0L }
            val n = minOf(arr.length(), 24)
            for (i in 0 until n) {
                outArr[i] = runCatching { arr.getLong(i) }.getOrDefault(0L).coerceAtLeast(0L)
            }
            out[day] = outArr
        }
        return out
    }

    private fun writeHourlyBuckets(map: Map<LocalDate, LongArray>): String {
        val obj = JSONObject()
        for ((day, arr) in map) {
            val outArr = JSONArray()
            for (i in 0 until 24) {
                outArr.put((arr.getOrNull(i) ?: 0L).coerceAtLeast(0L))
            }
            obj.put(day.toString(), outArr)
        }
        return obj.toString()
    }
}

