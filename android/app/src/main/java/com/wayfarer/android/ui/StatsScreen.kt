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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.wayfarer.android.db.LifeEventEntity
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.tracking.LifeEventRepository
import com.wayfarer.android.tracking.TrackPointRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private data class DayStat(
    val day: LocalDate,
    val pointCount: Int,
    val distanceM: Double,
    val steps: Long,
)

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val repository = remember { TrackPointRepository(context) }
    val lifeEventRepository = remember { LifeEventRepository(context) }
    val zone = remember { ZoneId.systemDefault() }

    var windowDays by rememberSaveable { mutableStateOf(7) }
    var selectedDayIso by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<List<DayStat>>(emptyList()) }
    var pointsAsc by remember { mutableStateOf<List<TrackPointEntity>>(emptyList()) }

    var marksLoading by remember { mutableStateOf(false) }
    var marksError by remember { mutableStateOf<String?>(null) }
    var marks by remember { mutableStateOf<List<LifeEventEntity>>(emptyList()) }

    fun refresh() {
        loading = true
        error = null
        // Pull a generous amount; compute window in-memory.
        repository.latestPointsAsync(
            limit = 20_000,
            onResult = { pointsDesc ->
                val asc = pointsDesc.asReversed()
                pointsAsc = asc
                stats = computeDailyStats(asc, windowDays)
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

    LaunchedEffect(selectedDayIso) {
        val dayIso = selectedDayIso ?: run {
            marks = emptyList()
            marksError = null
            marksLoading = false
            return@LaunchedEffect
        }

        val day = runCatching { LocalDate.parse(dayIso) }.getOrNull() ?: return@LaunchedEffect
        val start = day.atStartOfDay(zone).toInstant()
        val endExclusive = day.plusDays(1).atStartOfDay(zone).toInstant()
        val endInclusive = endExclusive.minusMillis(1)

        marksLoading = true
        marksError = null
        lifeEventRepository.rangeOverlappingAsync(
            startUtc = start.toString(),
            endUtc = endInclusive.toString(),
            limit = 2000,
            onResult = {
                marks = it
                marksLoading = false
            },
            onError = {
                marksError = it.message ?: it.toString()
                marksLoading = false
            },
        )
    }

    val totalPoints = stats.sumOf { it.pointCount }
    val totalDistance = stats.sumOf { it.distanceM }
    val totalSteps = stats.sumOf { it.steps }
    val activeDays = stats.count { it.pointCount > 0 }

    val selected = selectedDayIso
    if (selected != null) {
        val day = runCatching { LocalDate.parse(selected) }.getOrNull()
        val dayPoints =
            if (day == null) {
                emptyList()
            } else {
                pointsAsc.filter { p ->
                    val instant = runCatching { Instant.parse(p.recordedAtUtc) }.getOrNull() ?: return@filter false
                    instant.atZone(zone).toLocalDate() == day
                }
            }
        val stepsByHour = computeHourlySteps(dayPoints, zone)
        val daySteps = stepsByHour.sum()
        val dayDistance = computeDistanceMeters(dayPoints)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { selectedDayIso = null }) {
                        Text("返回")
                    }
                    Column {
                        Text(
                            text = day?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) ?: selected,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "步数：$daySteps  ·  距离：${formatDistance(dayDistance)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "按小时步数",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        HourlyStepsBarChart(stepsByHour)
                    }
                }
            }

            item {
                Text(
                    text = "标记",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            when {
                marksLoading -> {
                    item {
                        Text(
                            text = stringResource(com.wayfarer.android.R.string.track_stats_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                marksError != null -> {
                    item {
                        Text(
                            text = marksError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                marks.isEmpty() -> {
                    item {
                        OutlinedCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "当天没有标记。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                else -> {
                    items(
                        items = marks,
                        key = { it.localId },
                    ) { m ->
                        MarkCard(mark = m, zone = zone)
                    }
                }
            }
        }

        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
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
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricRow(
                        label = "步数",
                        value = totalSteps.toString(),
                    )
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
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "步数趋势",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StepsBarChart(stats)
                }
            }
        }

        item {
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
        }

        item {
            Text(
                text = "按天明细（点击查看）",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        items(
            items = stats,
            key = { it.day.toString() },
        ) { d ->
            DayStatCard(
                stat = d,
                onClick = { selectedDayIso = d.day.toString() },
            )
        }
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

@Composable
private fun StepsBarChart(stats: List<DayStat>) {
    val days = stats.takeLast(14) // keep chart readable
    val max = days.maxOfOrNull { it.steps }?.coerceAtLeast(1L) ?: 1L
    val accent = Color(0xFF34D399) // green-ish

    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = days.size.coerceAtLeast(1)
            val gap = 10f
            val totalGap = gap * (barCount - 1)
            val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(6f)

            var x = 0f
            for (d in days) {
                val h = (d.steps.toDouble() / max.toDouble()).toFloat() * size.height
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

@Composable
private fun HourlyStepsBarChart(stepsByHour: LongArray) {
    val max = stepsByHour.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val accent = Color(0xFF34D399)

    Box(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = 24
            val gap = 6f
            val totalGap = gap * (barCount - 1)
            val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(4f)

            var x = 0f
            for (hour in 0 until barCount) {
                val steps = stepsByHour.getOrNull(hour) ?: 0L
                val h = (steps.toDouble() / max.toDouble()).toFloat() * size.height
                drawRoundRect(
                    color = accent.copy(alpha = 0.9f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(8f, 8f),
                )
                x += barWidth + gap
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "0  6  12  18  23",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")
private val LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

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
        val steps = pts.sumOf { it.stepDelta ?: 0L }
        out.add(DayStat(day = d, pointCount = pts.size, distanceM = dist, steps = steps))
        d = d.plusDays(1)
    }
    return out
}

private fun computeHourlySteps(points: List<TrackPointEntity>, zone: ZoneId): LongArray {
    val out = LongArray(24)
    for (p in points) {
        val instant = runCatching { Instant.parse(p.recordedAtUtc) }.getOrNull() ?: continue
        val hour = instant.atZone(zone).hour
        val delta = p.stepDelta ?: 0L
        if (hour in 0..23) out[hour] = out[hour] + delta
    }
    return out
}

@Composable
private fun DayStatCard(
    stat: DayStat,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick = onClick) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stat.day.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "步数：${stat.steps}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "距离：${formatDistance(stat.distanceM)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "点：${stat.pointCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "查看详情",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MarkCard(
    mark: LifeEventEntity,
    zone: ZoneId,
) {
    val typeLabel =
        when (mark.eventType) {
            LifeEventEntity.EventType.MARK_POINT -> "时间点"
            LifeEventEntity.EventType.MARK_RANGE -> "区间"
            else -> mark.eventType
        }

    val startText = formatLocalDateTime(mark.startAtUtc, zone)
    val endText = formatLocalDateTime(mark.endAtUtc, zone)
    val subtitle =
        if (mark.eventType == LifeEventEntity.EventType.MARK_RANGE) {
            if (mark.endAtUtc.isNullOrBlank()) "$typeLabel：$startText → 进行中" else "$typeLabel：$startText → $endText"
        } else {
            "$typeLabel：$startText"
        }

    OutlinedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = mark.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val lat = mark.latitudeWgs84
            val lon = mark.longitudeWgs84
            if (lat != null && lon != null) {
                Text(
                    text = "GPS：${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!mark.note.isNullOrBlank()) {
                Text(
                    text = "备注：${mark.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatLocalDateTime(utcIso: String?, zone: ZoneId): String {
    if (utcIso.isNullOrBlank()) return "-"
    return runCatching {
        val instant = Instant.parse(utcIso)
        val zdt = ZonedDateTime.ofInstant(instant, zone)
        LOCAL_DATE_TIME.format(zdt)
    }.getOrDefault(utcIso)
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
