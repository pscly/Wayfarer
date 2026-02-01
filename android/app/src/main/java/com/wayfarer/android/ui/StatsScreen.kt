package com.wayfarer.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.tracking.TrackPointRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private data class DayStat(
    val day: LocalDate,
    val pointCount: Int,
    val distanceM: Double,
)

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val repository = remember { TrackPointRepository(context) }

    var windowDays by rememberSaveable { mutableStateOf(7) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<List<DayStat>>(emptyList()) }

    fun refresh() {
        loading = true
        error = null
        // Pull a generous amount; compute window in-memory.
        repository.latestPointsAsync(
            limit = 20_000,
            onResult = { pointsDesc ->
                val pointsAsc = pointsDesc.asReversed()
                stats = computeDailyStats(pointsAsc, windowDays)
                loading = false
            },
            onError = {
                error = it.message ?: it.toString()
                loading = false
            },
        )
    }

    LaunchedEffect(windowDays) {
        refresh()
    }

    val totalPoints = stats.sumOf { it.pointCount }
    val totalDistance = stats.sumOf { it.distanceM }
    val activeDays = stats.count { it.pointCount > 0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.stats_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Button(onClick = { refresh() }) {
                        Text(stringResource(com.wayfarer.android.R.string.track_stats_refresh))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WindowChip(
                        selected = windowDays == 7,
                        label = stringResource(com.wayfarer.android.R.string.stats_last_7d),
                        onClick = { windowDays = 7 },
                    )
                    WindowChip(
                        selected = windowDays == 30,
                        label = stringResource(com.wayfarer.android.R.string.stats_last_30d),
                        onClick = { windowDays = 30 },
                    )
                }

                if (loading) {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.track_stats_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (error != null) {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricRow(
                    label = stringResource(com.wayfarer.android.R.string.stats_distance),
                    value = formatDistance(totalDistance),
                )
                MetricRow(
                    label = stringResource(com.wayfarer.android.R.string.stats_points),
                    value = totalPoints.toString(),
                )
                MetricRow(
                    label = stringResource(com.wayfarer.android.R.string.stats_active_days),
                    value = activeDays.toString(),
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "距离趋势",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(10.dp))
                DistanceBarChart(stats)
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WindowChip(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        FilledTonalButton(onClick = onClick) {
            Text(label)
        }
    } else {
        Button(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun DistanceBarChart(stats: List<DayStat>) {
    val days = stats.takeLast(14) // keep chart readable
    val max = days.maxOfOrNull { it.distanceM }?.coerceAtLeast(1.0) ?: 1.0
    val accent = Color(0xFF38BDF8)

    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = days.size.coerceAtLeast(1)
            val gap = 10f
            val totalGap = gap * (barCount - 1)
            val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(6f)

            var x = 0f
            for (d in days) {
                val h = (d.distanceM / max).toFloat() * size.height
                drawRoundRect(
                    color = accent.copy(alpha = 0.9f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(10f, 10f),
                )
                x += barWidth + gap
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = days.joinToString("  ") { it.day.format(DAY_LABEL) },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")

private fun computeDailyStats(pointsAsc: List<TrackPointEntity>, windowDays: Int): List<DayStat> {
    if (pointsAsc.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val startDay = today.minusDays((windowDays - 1).toLong())

    // Bucket points by local date.
    val buckets = mutableMapOf<LocalDate, MutableList<TrackPointEntity>>()
    for (p in pointsAsc) {
        val instant = runCatching { Instant.parse(p.recordedAtUtc) }.getOrNull() ?: continue
        val day = instant.atZone(zone).toLocalDate()
        if (day.isBefore(startDay) || day.isAfter(today)) continue
        buckets.getOrPut(day) { mutableListOf() }.add(p)
    }

    val out = mutableListOf<DayStat>()
    var d = startDay
    while (!d.isAfter(today)) {
        val pts = buckets[d].orEmpty()
        val dist = computeDistanceMeters(pts)
        out.add(DayStat(day = d, pointCount = pts.size, distanceM = dist))
        d = d.plusDays(1)
    }
    return out
}

private fun computeDistanceMeters(points: List<TrackPointEntity>): Double {
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

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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

private fun formatDistance(meters: Double): String {
    val m = meters.coerceAtLeast(0.0)
    return if (m < 1000.0) {
        "${m.toInt()} m"
    } else {
        val km = m / 1000.0
        String.format("%.2f km", km)
    }
}
