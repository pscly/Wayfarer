package com.wayfarer.android.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wayfarer.android.db.LifeEventEntity
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.steps.SensorStepsRepository
import com.wayfarer.android.tracking.LifeEventRepository
import com.wayfarer.android.tracking.TrackPointRepository
import com.wayfarer.android.ui.components.wfSnackbarHostStateOrThrow
import com.wayfarer.android.ui.sync.rememberSyncSnapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch

private data class DayStat(
    val day: LocalDate,
    val pointCount: Int,
    val distanceM: Double,
    val steps: Long?,
    val activeMinutes: Int,
)

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = wfSnackbarHostStateOrThrow()
    val scope = rememberCoroutineScope()
    val syncSnapshot = rememberSyncSnapshot(context)
    val repository = remember { TrackPointRepository(context) }
    val lifeEventRepository = remember { LifeEventRepository(context) }
    val sensorStepsRepository = remember { SensorStepsRepository(context) }
    val zone = remember { ZoneId.systemDefault() }

    var windowDays by rememberSaveable { mutableStateOf(7) }
    var selectedDayIso by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<List<DayStat>>(emptyList()) }
    var pointsAsc by remember { mutableStateOf<List<TrackPointEntity>>(emptyList()) }

    // 系统全天步数（国内手机优先）：Sensor.TYPE_STEP_COUNTER + 本地累计。
    var stepSensorAvailable by remember { mutableStateOf(sensorStepsRepository.isSensorAvailable()) }
    var stepsPermissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var stepsLoading by remember { mutableStateOf(false) }
    var stepsError by remember { mutableStateOf<String?>(null) }
    var stepsSnapshot by remember { mutableStateOf(sensorStepsRepository.readSnapshot(zone)) }

    var marksLoading by remember { mutableStateOf(false) }
    var marksError by remember { mutableStateOf<String?>(null) }
    var marks by remember { mutableStateOf<List<LifeEventEntity>>(emptyList()) }

    var dailyLoadSeq by remember { mutableStateOf(0) }
    var hourlyLoadSeq by remember { mutableStateOf(0) }

    fun refreshStepsSnapshot() {
        stepSensorAvailable = sensorStepsRepository.isSensorAvailable()
        stepsPermissionGranted =
            context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        stepsSnapshot = sensorStepsRepository.readSnapshot(zone)
    }

    fun sampleStepsNow() {
        refreshStepsSnapshot()
        if (!stepSensorAvailable) {
            stepsError = "设备不支持计步传感器（无法读取系统全天步数）"
            return
        }
        if (!stepsPermissionGranted) {
            stepsError = "未授权“活动识别”权限（系统步数不可用）"
            return
        }

        stepsLoading = true
        stepsError = null
        sensorStepsRepository.sampleNowAsync(
            zone = zone,
            onResult = { snapshot ->
                stepsSnapshot = snapshot
                stepsLoading = false
                // 仅覆盖步数，其他指标（距离/点数/活跃时长）仍使用本地数据。
                stats = stats.map { s -> s.copy(steps = snapshot.dailyTotals[s.day]) }
            },
            onError = { t ->
                stepsError = t.message ?: t.toString()
                stepsLoading = false
            },
        )
    }

    val activityPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        stepsPermissionGranted = granted
        if (granted) {
            sampleStepsNow()
        } else {
            scope.launch { snackbarHostState.showSnackbar("未授权活动识别权限，无法读取系统步数") }
        }
    }

    fun refresh() {
        val mySeq = dailyLoadSeq + 1
        dailyLoadSeq = mySeq

        loading = true
        error = null
        refreshStepsSnapshot()

        // Pull a generous amount; compute window in-memory.
        repository.latestPointsAsync(
            limit = 20_000,
            onResult = { pointsDesc ->
                if (dailyLoadSeq != mySeq) return@latestPointsAsync

                val asc = pointsDesc.asReversed()
                pointsAsc = asc
                val localStats = computeDailyStats(asc, windowDays, zone)
                // 仅覆盖步数，其他指标（距离/点数/活跃时长）仍使用本地数据。
                val snapshot = sensorStepsRepository.readSnapshot(zone)
                stepsSnapshot = snapshot
                stats = localStats.map { s ->
                    val sys = snapshot.dailyTotals[s.day]
                    if (sys == null) s else s.copy(steps = sys)
                }
                loading = false
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

    LaunchedEffect(Unit) {
        refreshStepsSnapshot()
        if (stepSensorAvailable && stepsPermissionGranted) {
            sampleStepsNow()
        }
    }

    // 国内 ROM / 权限变化后，ON_RESUME 主动刷新一次（避免用户误以为“没生效”）。
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshStepsSnapshot()
                    if (stepSensorAvailable && stepsPermissionGranted) {
                        sampleStepsNow()
                    }
                    refresh()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(selectedDayIso, syncSnapshot.lastPullAtMs) {
        val dayIso = selectedDayIso ?: run {
            hourlyLoadSeq = hourlyLoadSeq + 1
            marks = emptyList()
            marksError = null
            marksLoading = false
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
        refreshStepsSnapshot()
    }

    val totalPoints = stats.sumOf { it.pointCount }
    val totalDistance = stats.sumOf { it.distanceM }
    val stepsAvailableDays = stats.count { it.steps != null }
    val stepsMissingDays = stats.size - stepsAvailableDays
    val totalStepsKnown = stats.sumOf { it.steps ?: 0L }
    val totalStepsText = if (stepsAvailableDays == 0) "--" else totalStepsKnown.toString()
    val totalStepsHelper =
        when {
            stepsAvailableDays == 0 -> "未读取到系统步数（计步传感器）"
            stepsMissingDays > 0 -> "$windowDays 天窗口（缺失 $stepsMissingDays 天）"
            else -> "$windowDays 天窗口"
        }
    val totalActiveMinutes = stats.sumOf { it.activeMinutes }
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
        val stepsByHour = day?.let { stepsSnapshot.hourlyBuckets[it] }
        val distanceByHourM = computeHourlyDistanceMeters(dayPoints, zone)
        val dayStepsText =
            stepsByHour?.sum()?.toString()
                ?: stats.firstOrNull { it.day == day }?.steps?.toString()
                ?: "--"
        val dayDistance = computeDistanceMeters(dayPoints)
        val dayActiveMinutes = com.wayfarer.android.ui.util.computeActiveMinutes(dayPoints, zone)

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
                            text = "步数：$dayStepsText  ·  距离：${formatDistance(dayDistance)}  ·  活跃：${com.wayfarer.android.ui.util.formatActiveMinutes(dayActiveMinutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when {
                            stepsLoading -> {
                                Text(
                                    text = "系统步数采样中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            !stepsError.isNullOrBlank() -> {
                                Text(
                                    text = "系统步数采样失败：${stepsError ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            !stepSensorAvailable -> {
                                Text(
                                    text = "设备不支持计步传感器，无法读取系统步数。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            !stepsPermissionGranted -> {
                                Text(
                                    text = "未授权活动识别权限，无法读取系统步数。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            else -> {
                                Text(
                                    text = "最近采样：${stepsSnapshot.lastSampleLocalTimeText(zone)}（按小时步数为采样近似值）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                        Button(
                            onClick = {
                                if (stepSensorAvailable && stepsPermissionGranted) sampleStepsNow()
                                refresh()
                            },
                        ) {
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
                        WindowChip(
                            selected = windowDays == 90,
                            label = "近 90 天",
                            onClick = { windowDays = 90 },
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
                    } else {
                        when {
                            !stepSensorAvailable -> {
                                Text(
                                    text = "系统步数不可用（设备不支持计步传感器）。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            !stepsPermissionGranted -> {
                                Text(
                                    text = "未授权活动识别权限：授权后步数将与手机系统计步一致（全天步数）。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilledTonalButton(
                                    onClick = { activityPermissionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION) },
                                ) {
                                    Text("授权活动识别")
                                }
                            }

                            stepsLoading -> {
                                Text(
                                    text = "系统步数采样中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            !stepsError.isNullOrBlank() -> {
                                Text(
                                    text = "系统步数采样失败：${stepsError ?: ""}（步数暂不展示）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                FilledTonalButton(
                                    onClick = { sampleStepsNow() },
                                ) {
                                    Text("重试采样")
                                }
                            }

                            stepsSnapshot.lastSampleUtcMs == null -> {
                                Text(
                                    text = "系统步数尚未采样，点击下方按钮采样一次即可展示。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilledTonalButton(
                                    onClick = { sampleStepsNow() },
                                ) {
                                    Text("立即采样一次")
                                }
                            }

                            else -> {
                                Text(
                                    text = "步数来自系统（计步传感器 · 全天步数）；距离/点数来自 Wayfarer 记录",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "最近采样：${stepsSnapshot.lastSampleLocalTimeText(zone)} · 时区 ${zone.id}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (!loading && error == null) {
                        Text(
                            text = "活跃时长口径：按分钟统计（步数>0 或 speed≥0.8m/s）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.wayfarer.android.ui.components.WfKpiCard(
                        label = "总步数",
                        value = totalStepsText,
                        helper = totalStepsHelper,
                        valueTone =
                            if (stepsAvailableDays == 0) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        modifier = Modifier.weight(1f),
                    )
                    com.wayfarer.android.ui.components.WfKpiCard(
                        label = "总距离",
                        value = formatDistance(totalDistance),
                        helper = "Wayfarer 记录",
                        modifier = Modifier.weight(1f),
                    )
                }
                com.wayfarer.android.ui.components.WfKpiCard(
                    label = "总活跃时长",
                    value = com.wayfarer.android.ui.util.formatActiveMinutes(totalActiveMinutes),
                    helper = "本机估算 · 活跃天数 $activeDays · 点数 $totalPoints",
                    modifier = Modifier.fillMaxWidth(),
                )
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
    val max = days.mapNotNull { it.steps }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val accent = Color(0xFF34D399) // green-ish

    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var chartWidthPx by remember { mutableStateOf(0f) }

    LaunchedEffect(days.size) {
        if (selectedIndex != null && selectedIndex !in days.indices) selectedIndex = null
    }

    val selected = selectedIndex?.let { idx -> days.getOrNull(idx) }
    if (days.isEmpty() || days.all { it.steps == null }) {
        com.wayfarer.android.ui.components.WfEmptyState(
            title = "暂无系统步数",
            body = "统计页的步数来自系统（计步传感器 · 全天步数）。请先授权“活动识别”权限，并等待采样后再查看。",
        )
        return
    }
    Text(
        text =
            selected?.let {
                val stepsText = it.steps?.toString() ?: "--"
                "${it.day.format(DAY_LABEL)}：$stepsText 步"
            } ?: "点击柱子查看具体步数",
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
                val steps = d.steps
                if (steps == null) {
                    val placeholderH = 10f
                    drawRoundRect(
                        color = accent.copy(alpha = 0.18f),
                        topLeft = Offset(x, size.height - placeholderH),
                        size = Size(barWidth, placeholderH),
                        cornerRadius = CornerRadius(10f, 10f),
                        style = Stroke(width = 2f),
                    )
                } else {
                    val h = (steps.toDouble() / max.toDouble()).toFloat() * size.height
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
    stepsByHour: LongArray?,
    distanceByHourM: DoubleArray,
) {
    if (stepsByHour == null) {
        Text(
            text = "系统按小时步数未就绪（未授权/未采样/暂不可用）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        com.wayfarer.android.ui.components.WfEmptyState(
            title = "无法展示按小时步数",
            body = "按小时步数来自计步传感器的后台采样（近似值）。请先授权“活动识别”权限，并在有采样后再查看。",
        )
        return
    }
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
        val activeMinutes = com.wayfarer.android.ui.util.computeActiveMinutes(pts, zone)
        out.add(
            DayStat(
                day = d,
                pointCount = pts.size,
                distanceM = dist,
                steps = null,
                activeMinutes = activeMinutes,
            ),
        )
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
    val stepsText = stat.steps?.toString() ?: "--"
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
                    text = "步数：$stepsText",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color =
                        if (stat.steps == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
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
                text = "活跃：${com.wayfarer.android.ui.util.formatActiveMinutes(stat.activeMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
