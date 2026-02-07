package com.wayfarer.android.ui.util

import com.wayfarer.android.db.TrackPointEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun computeSteps(points: List<TrackPointEntity>): Long {
    var sum = 0L
    for (p in points) {
        sum += (p.stepDelta ?: 0L).coerceAtLeast(0L)
    }
    return sum
}

fun computeDistanceMeters(points: List<TrackPointEntity>): Double {
    if (points.size < 2) return 0.0

    var sum = 0.0
    var prev = points.first()
    for (i in 1 until points.size) {
        val cur = points[i]
        sum += haversineMeters(
            prev.latitudeWgs84,
            prev.longitudeWgs84,
            cur.latitudeWgs84,
            cur.longitudeWgs84,
        )
        prev = cur
    }
    return sum
}

fun computeActiveMinutes(
    points: List<TrackPointEntity>,
    zone: ZoneId,
    speedThresholdMps: Double = 0.8,
): Int {
    if (points.isEmpty()) return 0

    // 口径：某分钟内 step_delta > 0 记为活跃；若缺少步数，则用 speed >= threshold 兜底。
    val activeMinuteKeys = HashSet<Long>(512)
    for (p in points) {
        val instant = runCatching { Instant.parse(p.recordedAtUtc) }.getOrNull() ?: continue
        val zdt = ZonedDateTime.ofInstant(instant, zone)
        val minuteKey =
            (zdt.year.toLong() * 10_000L + zdt.monthValue.toLong() * 100L + zdt.dayOfMonth.toLong()) * 10_000L +
                zdt.hour.toLong() * 100L +
                zdt.minute.toLong()

        val steps = (p.stepDelta ?: 0L)
        val speed = p.speedMps
        val active = steps > 0L || (speed != null && speed >= speedThresholdMps)
        if (active) activeMinuteKeys.add(minuteKey)
    }
    return activeMinuteKeys.size
}

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

fun formatDistance(meters: Double): String {
    val m = meters.coerceAtLeast(0.0)
    return if (m < 1000.0) {
        "${m.toInt()} m"
    } else {
        val km = m / 1000.0
        String.format("%.2f km", km)
    }
}

fun formatActiveMinutes(minutes: Int): String {
    val m = minutes.coerceAtLeast(0)
    val h = m / 60
    val mm = m % 60
    return when {
        h > 0 && mm > 0 -> "${h}小时${mm}分"
        h > 0 -> "${h}小时"
        else -> "${mm}分"
    }
}

