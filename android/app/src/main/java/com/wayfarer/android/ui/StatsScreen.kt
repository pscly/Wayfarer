package com.wayfarer.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wayfarer.android.api.ApiException
import com.wayfarer.android.api.AuthStore
import com.wayfarer.android.api.WayfarerApiClient
import com.wayfarer.android.api.toUserMessageZh
import com.wayfarer.android.db.LifeEventEntity
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.tracking.LifeEventRepository
import com.wayfarer.android.tracking.StatsStepsRepository
import com.wayfarer.android.tracking.TrackPointRepository
import com.wayfarer.android.ui.sync.rememberSyncSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
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
    val syncSnapshot = rememberSyncSnapshot(context)
    val repository = remember { TrackPointRepository(context) }
    val lifeEventRepository = remember { LifeEventRepository(context) }
    val stepsRepository = remember { StatsStepsRepository(context) }
    val zone = remember { ZoneId.systemDefault() }

    var windowDays by rememberSaveable { mutableStateOf(7) }
    var selectedDayIso by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<List<DayStat>>(emptyList()) }
    var pointsAsc by remember { mutableStateOf<List<TrackPointEntity>>(emptyList()) }

    var remoteStepsLoading by remember { mutableStateOf(false) }
    var remoteStepsError by remember { mutableStateOf<String?>(null) }

    var marksLoading by remember { mutableStateOf(false) }
    var marksError by remember { mutableStateOf<String?>(null) }
    var marks by remember { mutableStateOf<List<LifeEventEntity>>(emptyList()) }

    var remoteHourlyLoading by remember { mutableStateOf(false) }
    var remoteHourlyError by remember { mutableStateOf<String?>(null) }
    var remoteHourlyBuckets by remember { mutableStateOf<LongArray?>(null) }

    var dailyLoadSeq by remember { mutableStateOf(0) }
    var hourlyLoadSeq by remember { mutableStateOf(0) }

    fun hasAuth(): Boolean {
        val access = AuthStore.readAccessToken(context)
        val refresh = AuthStore.readRefreshToken(context)
        return !access.isNullOrBlank() || !refresh.isNullOrBlank()
    }

    fun toUiError(t: Throwable): String {
        return when (t) {
            is ApiException -> t.toUserMessageZh()
            else -> t.message ?: t.toString()
        }
    }

    fun currentTzOffsetMinutes(): Int? {
        return runCatching {
            val seconds = zone.rules.getOffset(Instant.now()).totalSeconds
            seconds / 60
        }.getOrNull()
    }

    fun refresh() {
        val mySeq = dailyLoadSeq + 1
        dailyLoadSeq = mySeq

        loading = true
        error = null
        remoteStepsLoading = false
        remoteStepsError = null

        // Pull a generous amount; compute window in-memory.
        repository.latestPointsAsync(
            limit = 20_000,
            onResult = { pointsDesc ->
                if (dailyLoadSeq != mySeq) return@latestPointsAsync

                val asc = pointsDesc.asReversed()
                pointsAsc = asc
                val localStats = computeDailyStats(asc, windowDays, zone)
                stats = localStats
                loading = false

                if (!hasAuth()) return@latestPointsAsync

                val today = LocalDate.now(zone)
                val startDay = today.minusDays((windowDays - 1).toLong())
                val startUtc = startDay.atStartOfDay(zone).toInstant().toString()
                val endUtc = Instant.now().toString()
                val tz = zone.id

                remoteStepsLoading = true
                remoteStepsError = null
                stepsRepository.dailyAsync(
                    startUtc = startUtc,
                    endUtc = endUtc,
                    tz = tz,
                    tzOffsetMinutes = currentTzOffsetMinutes(),
                    onResult = { items ->
                        if (dailyLoadSeq != mySeq) return@dailyAsync

                        val stepsByDay = buildMap<LocalDate, Long> {
                            for (it in items) {
                                val day = runCatching { LocalDate.parse(it.day) }.getOrNull() ?: continue
                                put(day, it.steps)
                            }
                        }

                        // 仅覆盖步数，其他指标（距离/点数）仍使用本地数据。
                        stats = localStats.map { s ->
                            val remote = stepsByDay[s.day]
                            if (remote == null) s else s.copy(steps = remote)
                        }
                        remoteStepsLoading = false
                    },
                    onError = { t ->
                        if (dailyLoadSeq != mySeq) return@dailyAsync
                        remoteStepsError = toUiError(t)
                        remoteStepsLoading = false
                    },
                )
            },
            onError = {
                if (dailyLoadSeq != mySeq) return@latestPointsAsync
                error = it.message ?: it.toString()
                loading = false
            },
        )
    }

    LaunchedEffect(windowDays, syncSnapshot.lastPullAtMs) {
        refresh()
    }

    LaunchedEffect(selectedDayIso, syncSnapshot.lastPullAtMs) {
        val dayIso = selectedDayIso ?: run {
            hourlyLoadSeq = hourlyLoadSeq + 1
            marks = emptyList()
            marksError = null
            marksLoading = false
            remoteHourlyLoading = false
            remoteHourlyError = null
            remoteHourlyBuckets = null
            return@LaunchedEffect
        }

        val mySeq = hourlyLoadSeq + 1
        hourlyLoadSeq = mySeq

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
                if (hourlyLoadSeq != mySeq) return@rangeOverlappingAsync
                marks = it
                marksLoading = false
            },
            onError = {
                if (hourlyLoadSeq != mySeq) return@rangeOverlappingAsync
                marksError = it.message ?: it.toString()
                marksLoading = false
            },
        )

        if (!hasAuth()) {
            remoteHourlyLoading = false
            remoteHourlyError = null
            remoteHourlyBuckets = null
            return@LaunchedEffect
        }

        remoteHourlyLoading = true
        remoteHourlyError = null
        remoteHourlyBuckets = null
        stepsRepository.hourlyAsync(
            startUtc = start.toString(),
            endUtc = endInclusive.toString(),
            tz = zone.id,
            tzOffsetMinutes = currentTzOffsetMinutes(),
            onResult = { items ->
                if (hourlyLoadSeq != mySeq) return@hourlyAsync
                remoteHourlyBuckets = computeHourlyStepsFromRemote(items, zone)
                remoteHourlyLoading = false
            },
            onError = { t ->
                if (hourlyLoadSeq != mySeq) return@hourlyAsync
                remoteHourlyError = toUiError(t)
                remoteHourlyLoading = false
            },
        )
    }

    val totalPoints = stats.sumOf { it.pointCount }
    val totalDistance = stats.sumOf { it.distanceM }
    val totalSteps = stats.sumOf { it.steps }
    val activeDays = stats.count { it.pointCount > 0 }
    val statsDesc = remember(stats) { stats.sortedByDescending { it.day } }

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
        val localStepsByHour = computeHourlySteps(dayPoints, zone)
        val stepsByHour = remoteHourlyBuckets ?: localStepsByHour
        val distanceByHourM = computeHourlyDistanceMeters(dayPoints, zone)
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
                        if (remoteHourlyLoading) {
                            Text(
                                text = "云端步数加载中…（按本地时区 ${zone.id} 分桶）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (!remoteHourlyError.isNullOrBlank()) {
                            Text(
                                text = remoteHourlyError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
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
                        HourlyStepsBarChart(
                            stepsByHour = stepsByHour,
                            distanceByHourM = distanceByHourM,
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
                            text = "按小时距离",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        HourlyDistanceBarChart(distanceByHourM = distanceByHourM)
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
                    } else if (hasAuth() && remoteStepsLoading) {
                        Text(
                            text = "云端步数加载中…（按本地时区 ${zone.id} 分桶）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (hasAuth() && !remoteStepsError.isNullOrBlank()) {
                        Text(
                            text = "云端步数加载失败：${remoteStepsError ?: ""}（已回退到本地统计）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (hasAuth()) {
                        Text(
                            text = "步数来自云端聚合（本地日期口径）；距离/点数来自本机记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            items = statsDesc,
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

    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var chartWidthPx by remember { mutableStateOf(0f) }

    LaunchedEffect(days.size) {
        if (selectedIndex != null && selectedIndex !in days.indices) selectedIndex = null
    }

    val selected = selectedIndex?.let { idx -> days.getOrNull(idx) }
    Text(
        text = selected?.let { "${it.day.format(DAY_LABEL)}：${formatDistance(it.distanceM)}" } ?: "点击柱子查看具体距离",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))

    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { chartWidthPx = it.width.toFloat() }
                    .pointerInput(days.size, chartWidthPx) {
                        detectTapGestures { offset ->
                            val barCount = days.size.coerceAtLeast(1)
                            val gap = 10f
                            val totalGap = gap * (barCount - 1)
                            val w = chartWidthPx
                            if (w <= 0f) return@detectTapGestures

                            val barWidth = ((w - totalGap) / barCount).coerceAtLeast(6f)
                            val stride = barWidth + gap
                            val idx = (offset.x / stride).toInt()
                            if (idx !in 0 until barCount) {
                                selectedIndex = null
                                return@detectTapGestures
                            }
                            val xInStride = offset.x - idx * stride
                            selectedIndex = if (xInStride in 0f..barWidth) idx else null
                        }
                    },
        ) {
            val barCount = days.size.coerceAtLeast(1)
            val gap = 10f
            val totalGap = gap * (barCount - 1)
            val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(6f)

            var x = 0f
            for ((i, d) in days.withIndex()) {
                val h = (d.distanceM / max).toFloat() * size.height
                drawRoundRect(
                    color = accent.copy(alpha = if (selectedIndex == i) 0.95f else 0.55f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(10f, 10f),
                )
                if (selectedIndex == i) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(10f, 10f),
                        style = Stroke(width = 2f),
                    )
                }
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

    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var chartWidthPx by remember { mutableStateOf(0f) }

    LaunchedEffect(days.size) {
        if (selectedIndex != null && selectedIndex !in days.indices) selectedIndex = null
    }

    val selected = selectedIndex?.let { idx -> days.getOrNull(idx) }
    Text(
        text = selected?.let { "${it.day.format(DAY_LABEL)}：${it.steps} 步" } ?: "点击柱子查看具体步数",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))

    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { chartWidthPx = it.width.toFloat() }
                    .pointerInput(days.size, chartWidthPx) {
                        detectTapGestures { offset ->
                            val barCount = days.size.coerceAtLeast(1)
                            val gap = 10f
                            val totalGap = gap * (barCount - 1)
                            val w = chartWidthPx
                            if (w <= 0f) return@detectTapGestures

                            val barWidth = ((w - totalGap) / barCount).coerceAtLeast(6f)
                            val stride = barWidth + gap
                            val idx = (offset.x / stride).toInt()
                            if (idx !in 0 until barCount) {
                                selectedIndex = null
                                return@detectTapGestures
                            }
                            val xInStride = offset.x - idx * stride
                            selectedIndex = if (xInStride in 0f..barWidth) idx else null
                        }
                    },
        ) {
            val barCount = days.size.coerceAtLeast(1)
            val gap = 10f
            val totalGap = gap * (barCount - 1)
            val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(6f)

            var x = 0f
            for ((i, d) in days.withIndex()) {
                val h = (d.steps.toDouble() / max.toDouble()).toFloat() * size.height
                drawRoundRect(
                    color = accent.copy(alpha = if (selectedIndex == i) 0.95f else 0.55f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(10f, 10f),
                )
                if (selectedIndex == i) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(10f, 10f),
                        style = Stroke(width = 2f),
                    )
                }
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
private fun HourlyStepsBarChart(
    stepsByHour: LongArray,
    distanceByHourM: DoubleArray,
) {
    val max = stepsByHour.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val accent = Color(0xFF34D399)

    var selectedHour by rememberSaveable { mutableStateOf<Int?>(null) }
    var chartWidthPx by remember { mutableStateOf(0f) }

    val selectedSteps = selectedHour?.let { h -> stepsByHour.getOrNull(h) ?: 0L }
    val selectedDistance = selectedHour?.let { h -> distanceByHourM.getOrNull(h) ?: 0.0 }
    Text(
        text =
            selectedHour?.let { h ->
                "${String.format("%02d:00", h)}：${selectedSteps ?: 0L} 步  ·  ${formatDistance(selectedDistance ?: 0.0)}"
            } ?: "点击柱子查看具体步数/距离",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))

    Box(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { chartWidthPx = it.width.toFloat() }
                    .pointerInput(chartWidthPx) {
                        detectTapGestures { offset ->
                            val barCount = 24
                            val gap = 6f
                            val totalGap = gap * (barCount - 1)
                            val w = chartWidthPx
                            if (w <= 0f) return@detectTapGestures

                            val barWidth = ((w - totalGap) / barCount).coerceAtLeast(4f)
                            val stride = barWidth + gap
                            val idx = (offset.x / stride).toInt()
                            if (idx !in 0 until barCount) {
                                selectedHour = null
                                return@detectTapGestures
                            }
                            val xInStride = offset.x - idx * stride
                            selectedHour = if (xInStride in 0f..barWidth) idx else null
                        }
                    },
        ) {
            val barCount = 24
            val gap = 6f
            val totalGap = gap * (barCount - 1)
            val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(4f)

            var x = 0f
            for (hour in 0 until barCount) {
                val steps = stepsByHour.getOrNull(hour) ?: 0L
                val h = (steps.toDouble() / max.toDouble()).toFloat() * size.height
                drawRoundRect(
                    color = accent.copy(alpha = if (selectedHour == hour) 0.95f else 0.55f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(8f, 8f),
                )
                if (selectedHour == hour) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(8f, 8f),
                        style = Stroke(width = 2f),
                    )
                }
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

@Composable
private fun HourlyDistanceBarChart(distanceByHourM: DoubleArray) {
    val max = distanceByHourM.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val accent = Color(0xFF38BDF8)

    var selectedHour by rememberSaveable { mutableStateOf<Int?>(null) }
    var chartWidthPx by remember { mutableStateOf(0f) }

    val selectedDistance = selectedHour?.let { h -> distanceByHourM.getOrNull(h) ?: 0.0 }
    Text(
        text = selectedHour?.let { h -> "${String.format("%02d:00", h)}：${formatDistance(selectedDistance ?: 0.0)}" }
            ?: "点击柱子查看具体距离",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))

    Box(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { chartWidthPx = it.width.toFloat() }
                    .pointerInput(chartWidthPx) {
                        detectTapGestures { offset ->
                            val barCount = 24
                            val gap = 6f
                            val totalGap = gap * (barCount - 1)
                            val w = chartWidthPx
                            if (w <= 0f) return@detectTapGestures

                            val barWidth = ((w - totalGap) / barCount).coerceAtLeast(4f)
                            val stride = barWidth + gap
                            val idx = (offset.x / stride).toInt()
                            if (idx !in 0 until barCount) {
                                selectedHour = null
                                return@detectTapGestures
                            }
                            val xInStride = offset.x - idx * stride
                            selectedHour = if (xInStride in 0f..barWidth) idx else null
                        }
                    },
        ) {
            val barCount = 24
            val gap = 6f
            val totalGap = gap * (barCount - 1)
            val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(4f)

            var x = 0f
            for (hour in 0 until barCount) {
                val meters = distanceByHourM.getOrNull(hour) ?: 0.0
                val h = (meters / max).toFloat() * size.height
                drawRoundRect(
                    color = accent.copy(alpha = if (selectedHour == hour) 0.95f else 0.55f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(8f, 8f),
                )
                if (selectedHour == hour) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(8f, 8f),
                        style = Stroke(width = 2f),
                    )
                }
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

private fun computeDailyStats(pointsAsc: List<TrackPointEntity>, windowDays: Int, zone: ZoneId): List<DayStat> {
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

private fun computeHourlyStepsFromRemote(items: List<WayfarerApiClient.StepsHourlyItem>, zone: ZoneId): LongArray {
    val out = LongArray(24)
    for (it in items) {
        val t = runCatching { OffsetDateTime.parse(it.hourStart) }.getOrNull() ?: continue
        val hour = t.atZoneSameInstant(zone).hour
        if (hour in 0..23) out[hour] = out[hour] + it.steps
    }
    return out
}

private fun computeHourlyDistanceMeters(points: List<TrackPointEntity>, zone: ZoneId): DoubleArray {
    val out = DoubleArray(24)
    if (points.size < 2) return out

    var prev = points.first()
    var prevInstant = runCatching { Instant.parse(prev.recordedAtUtc) }.getOrNull()
    for (i in 1 until points.size) {
        val cur = points[i]
        val curInstant = runCatching { Instant.parse(cur.recordedAtUtc) }.getOrNull()
        if (prevInstant != null && curInstant != null) {
            // 将“上一个点 -> 当前点”的位移归到上一个点所在小时（足够直观且保证总和=全天距离）。
            val hour = prevInstant.atZone(zone).hour
            val dist =
                haversineMeters(
                    prev.latitudeWgs84,
                    prev.longitudeWgs84,
                    cur.latitudeWgs84,
                    cur.longitudeWgs84,
                )
            if (hour in 0..23) out[hour] = out[hour] + dist
        }
        prev = cur
        prevInstant = curInstant
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
