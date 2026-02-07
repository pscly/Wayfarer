package com.wayfarer.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wayfarer.android.api.AuthStore
import com.wayfarer.android.db.LifeEventEntity
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.steps.SensorStepsRepository
import com.wayfarer.android.sync.WayfarerSyncManager
import com.wayfarer.android.tracking.LifeEventRepository
import com.wayfarer.android.tracking.TrackingServiceController
import com.wayfarer.android.tracking.TrackPointRepository
import com.wayfarer.android.tracking.TrackingStatusStore
import com.wayfarer.android.ui.components.WfCard
import com.wayfarer.android.ui.components.WfDimens
import com.wayfarer.android.ui.components.WfEmptyState
import com.wayfarer.android.ui.components.WfKpiCard
import com.wayfarer.android.ui.components.WfSectionHeader
import com.wayfarer.android.ui.components.wfSnackbarHostStateOrThrow
import com.wayfarer.android.ui.onboarding.OnboardingStore
import com.wayfarer.android.ui.records.MarkDetailScreen
import com.wayfarer.android.ui.records.MarkLabelHistoryStore
import com.wayfarer.android.ui.records.MarkCard
import com.wayfarer.android.ui.records.MarksListScreen
import com.wayfarer.android.ui.records.MarksRange
import com.wayfarer.android.ui.records.QuickMarkBottomSheet
import com.wayfarer.android.ui.sync.rememberSyncSnapshot
import com.wayfarer.android.ui.util.computeActiveMinutes
import com.wayfarer.android.ui.util.computeDistanceMeters
import com.wayfarer.android.ui.util.formatActiveMinutes
import com.wayfarer.android.ui.util.formatDistance
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RecordSessionSummary(
    val sessionId: String,
    val startUtc: String,
    val endUtc: String,
    val pointCount: Int,
    val durationSec: Long,
    val distanceM: Double,
)

private data class MarkerCoords(
    val latitudeWgs84: Double?,
    val longitudeWgs84: Double?,
    val latitudeGcj02: Double?,
    val longitudeGcj02: Double?,
)

private enum class RecordsRoute {
    HOME,
    MARKS_LIST,
}

@Composable
fun RecordsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val syncSnapshot = rememberSyncSnapshot(context)

    var isTracking by rememberSaveable { mutableStateOf(TrackingStatusStore.readIsTracking(context)) }
    val locationGranted = hasLocationPermission(context)
    val activityGranted =
        context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    val gpsEnabled = isGpsEnabled(context)

    val repository = remember { TrackPointRepository(context) }
    val lifeEventRepository = remember { LifeEventRepository(context) }
    val sensorStepsRepository = remember { SensorStepsRepository(context) }
    val syncManager = remember { WayfarerSyncManager(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = wfSnackbarHostStateOrThrow()
    val zone = remember { ZoneId.systemDefault() }

    var route by rememberSaveable { mutableStateOf(RecordsRoute.HOME) }

    var showQuickMarkSheet by rememberSaveable { mutableStateOf(false) }
    var recentMarkLabels by remember { mutableStateOf(MarkLabelHistoryStore.read(context)) }

    var activeRangeMark by remember { mutableStateOf<LifeEventEntity?>(null) }
    var markBusy by remember { mutableStateOf(false) }
    var markError by remember { mutableStateOf<String?>(null) }

    // Today preview (KPI + markers).
    var todayPointsLoading by remember { mutableStateOf(false) }
    var todayPointsError by remember { mutableStateOf<String?>(null) }
    var todayPoints by remember { mutableStateOf<List<TrackPointEntity>>(emptyList()) }

    // System all-day steps (domestic phones): Sensor.TYPE_STEP_COUNTER + local accumulator.
    var stepSensorAvailable by remember { mutableStateOf(sensorStepsRepository.isSensorAvailable()) }
    var stepsPermissionGranted by remember { mutableStateOf(activityGranted) }
    var todayStepsLoading by remember { mutableStateOf(false) }
    var todayStepsError by remember { mutableStateOf<String?>(null) }
    var todaySteps by remember { mutableStateOf<Long?>(null) }
    var todayStepsLastSampleUtcMs by remember { mutableStateOf<Long?>(null) }
    var showSystemStepsOnboarding by rememberSaveable { mutableStateOf(false) }

    var marksTodayLoading by remember { mutableStateOf(false) }
    var marksTodayError by remember { mutableStateOf<String?>(null) }
    var marksToday by remember { mutableStateOf<List<LifeEventEntity>>(emptyList()) }

    // Marks list route.
    var marksRange by rememberSaveable { mutableStateOf(MarksRange.TODAY) }
    var marksListLoading by remember { mutableStateOf(false) }
    var marksListError by remember { mutableStateOf<String?>(null) }
    var marksList by remember { mutableStateOf<List<LifeEventEntity>>(emptyList()) }

    var selectedMarkId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMarkLoading by remember { mutableStateOf(false) }
    var selectedMarkError by remember { mutableStateOf<String?>(null) }
    var selectedMark by remember { mutableStateOf<LifeEventEntity?>(null) }

    // Local stats (counts, latest timestamp)
    var statsLoading by remember { mutableStateOf(false) }
    var statsError by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<TrackPointRepository.TrackPointStats?>(null) }

    var showSystemStatus by rememberSaveable { mutableStateOf(false) }

    // Session list
    var sessionsLoading by remember { mutableStateOf(false) }
    var sessionsError by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<List<RecordSessionSummary>>(emptyList()) }
    var sessionsLoadSeq by remember { mutableStateOf(0) }

    // 自定义对象不要用 rememberSaveable（否则旋转/进程恢复可能崩）。
    var selectedSession by remember { mutableStateOf<RecordSessionSummary?>(null) }
    var detailPointsLoading by remember { mutableStateOf(false) }
    var detailPointsError by remember { mutableStateOf<String?>(null) }
    var detailPoints by remember { mutableStateOf<List<TrackPointEntity>>(emptyList()) }

    fun refreshStats() {
        statsLoading = true
        statsError = null
        repository.statsAsync(
            onResult = {
                stats = it
                statsLoading = false
            },
            onError = {
                statsError = it.message ?: it.toString()
                statsLoading = false
            },
        )
    }

    fun hasAuth(): Boolean {
        val access = AuthStore.readAccessToken(context)
        val refresh = AuthStore.readRefreshToken(context)
        return !access.isNullOrBlank() || !refresh.isNullOrBlank()
    }

    fun toSystemStepsError(t: Throwable): String {
        return when (t) {
            is SecurityException -> "未授权系统步数（请点击下方“开启系统步数”）"
            else -> t.message ?: t.toString()
        }
    }

    fun resolveTodayRangeUtc(): Pair<String, String> {
        val now = Instant.now()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        return start.toString() to now.toString()
    }

    fun resolveMarksRangeUtc(range: MarksRange): Pair<String, String> {
        val now = Instant.now()
        val days =
            when (range) {
                MarksRange.TODAY -> 1
                MarksRange.LAST_7D -> 7
                MarksRange.LAST_30D -> 30
            }
        val startDay = LocalDate.now(zone).minusDays((days - 1).toLong())
        val start = startDay.atStartOfDay(zone).toInstant()
        return start.toString() to now.toString()
    }

    fun refreshTodayPoints() {
        val (startUtc, endUtc) = resolveTodayRangeUtc()
        todayPointsLoading = true
        todayPointsError = null
        repository.rangePointsAsync(
            startUtc = startUtc,
            endUtc = endUtc,
            limit = 20_000,
            onResult = {
                todayPoints = it
                todayPointsLoading = false
            },
            onError = {
                todayPointsError = it.message ?: it.toString()
                todayPointsLoading = false
            },
        )
    }

    fun refreshTodayStepsSnapshot() {
        stepSensorAvailable = sensorStepsRepository.isSensorAvailable()
        stepsPermissionGranted =
            context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED

        val snapshot = sensorStepsRepository.readSnapshot(zone)
        todaySteps = snapshot.todaySteps(zone)
        todayStepsLastSampleUtcMs = snapshot.lastSampleUtcMs
    }

    fun sampleTodayStepsNow() {
        refreshTodayStepsSnapshot()
        if (!stepSensorAvailable) {
            todayStepsError = "设备不支持计步传感器（无法读取系统全天步数）"
            return
        }
        if (!stepsPermissionGranted) {
            todayStepsError = "未授权“活动识别”权限（系统步数不可用）"
            return
        }

        todayStepsLoading = true
        todayStepsError = null
        sensorStepsRepository.sampleNowAsync(
            zone = zone,
            onResult = { snapshot ->
                todaySteps = snapshot.todaySteps(zone)
                todayStepsLastSampleUtcMs = snapshot.lastSampleUtcMs
                todayStepsLoading = false
            },
            onError = { t ->
                todayStepsError = t.message ?: t.toString()
                todayStepsLoading = false
            },
        )
    }

    fun refreshMarksToday() {
        val (startUtc, endUtc) = resolveTodayRangeUtc()
        marksTodayLoading = true
        marksTodayError = null
        lifeEventRepository.rangeOverlappingAsync(
            startUtc = startUtc,
            endUtc = endUtc,
            limit = 2000,
            onResult = { items ->
                marksToday = items.sortedByDescending { it.startAtUtc }
                marksTodayLoading = false
            },
            onError = { t ->
                marksTodayError = t.message ?: t.toString()
                marksTodayLoading = false
            },
        )
    }

    fun refreshMarksList() {
        val (startUtc, endUtc) = resolveMarksRangeUtc(marksRange)
        marksListLoading = true
        marksListError = null
        lifeEventRepository.rangeOverlappingAsync(
            startUtc = startUtc,
            endUtc = endUtc,
            limit = 2000,
            onResult = { items ->
                // rangeOverlapping 返回 start_at ASC；列表更偏向“最近的在前”。
                marksList = items.sortedByDescending { it.startAtUtc }
                marksListLoading = false
            },
            onError = { t ->
                marksListError = t.message ?: t.toString()
                marksListLoading = false
            },
        )
    }

    fun refreshSessions() {
        val mySeq = sessionsLoadSeq + 1
        sessionsLoadSeq = mySeq
        sessionsLoading = true
        sessionsError = null
        repository.latestPointsAsync(
            limit = 5000,
            onResult = { pointsDesc ->
                if (sessionsLoadSeq != mySeq) return@latestPointsAsync
                scope.launch {
                    val computed =
                        withContext(Dispatchers.Default) {
                            val pointsAsc = pointsDesc.asReversed()
                            buildSessions(pointsAsc)
                        }
                    if (sessionsLoadSeq != mySeq) return@launch
                    sessions = computed
                    sessionsLoading = false
                }
            },
            onError = {
                if (sessionsLoadSeq != mySeq) return@latestPointsAsync
                sessionsError = it.message ?: it.toString()
                sessionsLoading = false
            },
        )
    }

    fun refreshActiveRangeMark() {
        lifeEventRepository.latestActiveRangeAsync(
            onResult = { activeRangeMark = it },
            onError = { _ -> },
        )
    }

    fun refreshAll() {
        refreshStats()
        refreshTodayPoints()
        refreshTodayStepsSnapshot()
        refreshMarksToday()
        refreshSessions()
        refreshActiveRangeMark()
    }

    LaunchedEffect(Unit) {
        refreshAll()
        if (stepSensorAvailable && stepsPermissionGranted) {
            sampleTodayStepsNow()
        }
    }

    // 首次进入：如果系统步数未开启（未授权活动识别），弹一次引导（仅一次，避免打扰）。
    LaunchedEffect(stepSensorAvailable, stepsPermissionGranted) {
        if (!stepSensorAvailable) return@LaunchedEffect
        if (stepsPermissionGranted) return@LaunchedEffect
        if (OnboardingStore.isSystemStepsIntroShown(context)) return@LaunchedEffect

        // 仅标记“展示过”，避免用户每次进入都被弹窗打断。
        OnboardingStore.markSystemStepsIntroShown(context)
        showSystemStepsOnboarding = true
    }

    // 从 Health Connect 授权/同步回来后，需要在 ON_RESUME 主动刷新（否则用户会误以为“点了没用”）。
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshTodayStepsSnapshot()
                    if (stepSensorAvailable && stepsPermissionGranted) {
                        sampleTodayStepsNow()
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 当后台同步拉取到新数据时，刷新界面，避免“登录后同步了但列表不更新”的体验。
    LaunchedEffect(syncSnapshot.lastPullAtMs) {
        refreshAll()
    }

    LaunchedEffect(isTracking) {
        // Start/stop often changes local data quickly.
        refreshAll()
    }

    LaunchedEffect(route, marksRange, syncSnapshot.lastPullAtMs) {
        if (route == RecordsRoute.MARKS_LIST) {
            refreshMarksList()
        }
    }

    LaunchedEffect(selectedMarkId, syncSnapshot.lastPullAtMs) {
        val id = selectedMarkId?.trim().orEmpty()
        if (id.isBlank()) {
            selectedMarkLoading = false
            selectedMarkError = null
            selectedMark = null
            return@LaunchedEffect
        }

        selectedMarkLoading = true
        selectedMarkError = null
        selectedMark = null
        lifeEventRepository.getByEventIdAsync(
            eventId = id,
            onResult = {
                if (selectedMarkId != id) return@getByEventIdAsync
                selectedMark = it
                selectedMarkLoading = false
            },
            onError = { t ->
                if (selectedMarkId != id) return@getByEventIdAsync
                selectedMarkError = t.message ?: t.toString()
                selectedMarkLoading = false
            },
        )
    }

    val requiredPermissions = remember {
        arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACTIVITY_RECOGNITION,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result: Map<String, Boolean> ->
        val permissionLocationGranted =
            (result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (result[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true)

        val permissionActivityGranted = (result[android.Manifest.permission.ACTIVITY_RECOGNITION] == true)
        if (permissionActivityGranted) {
            stepsPermissionGranted = true
            sampleTodayStepsNow()
        }

        if (permissionLocationGranted) {
            TrackingServiceController.start(context)
            TrackingStatusStore.markStarted(context)
            isTracking = true
        }
    }

    val activityPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        stepsPermissionGranted = granted
        if (granted) {
            sampleTodayStepsNow()
        } else {
            scope.launch { snackbarHostState.showSnackbar("未授权活动识别权限，无法读取系统步数") }
        }
    }

    if (showSystemStepsOnboarding) {
        val primaryLabel = if (stepsPermissionGranted) "立即采样" else "授权活动识别"
        AlertDialog(
            onDismissRequest = { showSystemStepsOnboarding = false },
            title = { Text("开启系统步数（推荐）") },
            text = {
                Text(
                    "Wayfarer 的“今日步数/统计步数”来自手机系统的全天步数（计步传感器采样），不会只统计你开启记录的那一小段。\n\n" +
                        "首次使用需要授权“活动识别”权限；授权后会自动采样一次，并在后台每 15 分钟 best-effort 更新。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSystemStepsOnboarding = false
                        if (stepsPermissionGranted) {
                            sampleTodayStepsNow()
                        } else {
                            activityPermissionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                    },
                ) {
                    Text(primaryLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSystemStepsOnboarding = false }) {
                    Text("稍后")
                }
            },
        )
    }

    fun resolvedUserId(): String {
        return AuthStore.readUserId(context) ?: "local"
    }

    fun lastKnownLocationBestEffort(): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        var best: Location? = null
        for (provider in candidates) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            val current = best
            if (current == null) {
                best = loc
                continue
            }

            if (loc.hasAccuracy()) {
                val bestAcc = if (current.hasAccuracy()) current.accuracy else Float.MAX_VALUE
                if (loc.accuracy < bestAcc) best = loc
            }
        }
        return best
    }

    fun resolveMarkerCoordsBestEffort(
        useLocation: Boolean,
        onResult: (MarkerCoords?) -> Unit,
    ) {
        if (!useLocation) {
            onResult(null)
            return
        }

        repository.latestPointsAsync(
            limit = 1,
            onResult = { pointsDesc ->
                val latest = pointsDesc.firstOrNull()
                val recentOk =
                    if (latest != null) {
                        val t = runCatching { Instant.parse(latest.recordedAtUtc) }.getOrNull()
                        t != null && Duration.between(t, Instant.now()).abs() <= Duration.ofMinutes(2)
                    } else {
                        false
                    }

                if (latest != null && recentOk) {
                    onResult(
                        MarkerCoords(
                            latitudeWgs84 = latest.latitudeWgs84,
                            longitudeWgs84 = latest.longitudeWgs84,
                            latitudeGcj02 = latest.latitudeGcj02,
                            longitudeGcj02 = latest.longitudeGcj02,
                        ),
                    )
                    return@latestPointsAsync
                }

                val loc = lastKnownLocationBestEffort()
                onResult(
                    MarkerCoords(
                        latitudeWgs84 = loc?.latitude,
                        longitudeWgs84 = loc?.longitude,
                        latitudeGcj02 = null,
                        longitudeGcj02 = null,
                    ),
                )
            },
            onError = {
                val loc = lastKnownLocationBestEffort()
                onResult(
                    MarkerCoords(
                        latitudeWgs84 = loc?.latitude,
                        longitudeWgs84 = loc?.longitude,
                        latitudeGcj02 = null,
                        longitudeGcj02 = null,
                    ),
                )
            },
        )
    }

    fun createMark(
        type: String,
        label: String,
        note: String?,
        useLocation: Boolean,
    ) {
        val normalizedLabel = label.trim()
        if (normalizedLabel.isBlank()) {
            markError = "请先输入标签（例如：出门买东西）"
            return
        }
        if (type == LifeEventEntity.EventType.MARK_RANGE && activeRangeMark != null) {
            markError = "已有正在进行的区间标记，请先结束。"
            return
        }

        markBusy = true
        markError = null
        resolveMarkerCoordsBestEffort(useLocation = useLocation) { coords ->
            val now = Instant.now()
            val startAt = now.toString()
            val endAt =
                if (type == LifeEventEntity.EventType.MARK_POINT) {
                    now.plusSeconds(1).toString()
                } else {
                    null
                }

            val payloadJson =
                runCatching {
                    JSONObject()
                        .put("source", "ANDROID_LOCAL")
                        .put("created_at", startAt)
                        .toString()
                }.getOrNull()

            val entity = LifeEventEntity(
                userId = resolvedUserId(),
                eventId = UUID.randomUUID().toString(),
                eventType = type,
                startAtUtc = startAt,
                endAtUtc = endAt,
                label = normalizedLabel,
                note = note?.trim()?.takeIf { it.isNotBlank() },
                latitudeWgs84 = coords?.latitudeWgs84,
                longitudeWgs84 = coords?.longitudeWgs84,
                latitudeGcj02 = coords?.latitudeGcj02,
                longitudeGcj02 = coords?.longitudeGcj02,
                payloadJson = payloadJson,
                syncStatus = LifeEventEntity.SyncStatus.NEW,
                createdAtUtc = startAt,
                updatedAtUtc = startAt,
            )

            lifeEventRepository.insertAsync(
                entity = entity,
                onDone = {
                    markBusy = false
                    showQuickMarkSheet = false
                    markError = null

                    MarkLabelHistoryStore.add(context, normalizedLabel)
                    recentMarkLabels = MarkLabelHistoryStore.read(context)

                    refreshMarksToday()
                    refreshActiveRangeMark()

                    val msg =
                        if (type == LifeEventEntity.EventType.MARK_RANGE) {
                            "已开始区间标记：$normalizedLabel"
                        } else {
                            "已创建标记：$normalizedLabel"
                        }
                    scope.launch { snackbarHostState.showSnackbar(message = msg) }
                },
                onError = { err ->
                    markBusy = false
                    markError = err.message ?: err.toString()
                },
            )
        }
    }

    fun endActiveRangeMark() {
        val active = activeRangeMark ?: return
        markBusy = true
        markError = null

        resolveMarkerCoordsBestEffort(useLocation = true) { coords ->
            val startInstant = runCatching { Instant.parse(active.startAtUtc) }.getOrNull()
            val nowInstant = Instant.now()
            val endInstant =
                if (startInstant != null && !nowInstant.isAfter(startInstant)) {
                    startInstant.plusSeconds(1)
                } else {
                    nowInstant
                }
            val endAt = endInstant.toString()

            val payload =
                runCatching {
                    val base =
                        if (!active.payloadJson.isNullOrBlank()) {
                            JSONObject(active.payloadJson)
                        } else {
                            JSONObject()
                        }
                    base
                        .put("ended_at", endAt)
                        .put(
                            "end",
                            JSONObject()
                                .put("latitude", coords?.latitudeWgs84)
                                .put("longitude", coords?.longitudeWgs84),
                        )
                }.getOrDefault(JSONObject().put("ended_at", endAt))

            val updated =
                active.copy(
                    endAtUtc = endAt,
                    payloadJson = payload.toString(),
                    syncStatus = LifeEventEntity.SyncStatus.NEW,
                    updatedAtUtc = endAt,
                )

            lifeEventRepository.updateAsync(
                entity = updated,
                onDone = {
                    markBusy = false
                    markError = null
                    refreshActiveRangeMark()
                    refreshMarksToday()
                    scope.launch { snackbarHostState.showSnackbar(message = "已结束区间标记：${active.label}") }
                },
                onError = { err ->
                    markBusy = false
                    markError = err.message ?: err.toString()
                },
            )
        }
    }

    QuickMarkBottomSheet(
        visible = showQuickMarkSheet,
        activeRangeMark = activeRangeMark,
        recentLabels = recentMarkLabels,
        busy = markBusy,
        error = markError,
        onDismiss = {
            if (!markBusy) {
                showQuickMarkSheet = false
                markError = null
            }
        },
        onCreate = { type, label, note, useLocation ->
            createMark(
                type = type,
                label = label,
                note = note,
                useLocation = useLocation,
            )
        },
        onEndActiveRange = { endActiveRangeMark() },
    )

    if (selectedMarkId != null) {
        val mark = selectedMark
        if (selectedMarkLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(WfDimens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(onClick = { selectedMarkId = null }) { Text("返回") }
                Text(
                    text = "读取标记中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        if (mark == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(WfDimens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(onClick = { selectedMarkId = null }) { Text("返回") }
                Text(
                    text = selectedMarkError ?: "标记不存在或已被删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return
        }

        val requiresRemote = mark.syncStatus == LifeEventEntity.SyncStatus.ACKED
        val canRemote = hasAuth()

        fun updateLocal(label: String, note: String?, markRemoteSyncedAt: String?) {
            val now = Instant.now().toString()
            val updated =
                mark.copy(
                    label = label,
                    note = note,
                    updatedAtUtc = now,
                    lastSyncedAtUtc = markRemoteSyncedAt ?: mark.lastSyncedAtUtc,
                    syncStatus =
                        if (requiresRemote) {
                            LifeEventEntity.SyncStatus.ACKED
                        } else {
                            mark.syncStatus
                        },
                )
            lifeEventRepository.updateAsync(
                entity = updated,
                onDone = {
                    markBusy = false
                    markError = null
                    selectedMark = updated
                    refreshMarksToday()
                    if (route == RecordsRoute.MARKS_LIST) refreshMarksList()
                    scope.launch { snackbarHostState.showSnackbar(message = "已保存") }
                },
                onError = { t ->
                    markBusy = false
                    markError = t.message ?: t.toString()
                },
            )
        }

        fun saveMark(label: String, note: String?) {
            val normalized = label.trim()
            if (normalized.isBlank()) {
                scope.launch { snackbarHostState.showSnackbar(message = "标签不能为空") }
                return
            }

            if (requiresRemote && !canRemote) {
                scope.launch { snackbarHostState.showSnackbar(message = "需要登录后才能修改已同步的标记") }
                return
            }

            markBusy = true
            markError = null

            if (requiresRemote) {
                val payload = JSONObject()
                    .put("location_name", normalized)
                    .put("manual_note", note ?: JSONObject.NULL)

                val now = Instant.now().toString()
                syncManager.lifeEventUpdateAsync(
                    eventId = mark.eventId,
                    payload = payload,
                    onDone = { _ ->
                        updateLocal(normalized, note, markRemoteSyncedAt = now)
                    },
                    onError = { t ->
                        markBusy = false
                        markError = t.message ?: t.toString()
                    },
                )
            } else {
                updateLocal(normalized, note, markRemoteSyncedAt = null)
            }
        }

        fun deleteMark() {
            if (requiresRemote && !canRemote) {
                scope.launch { snackbarHostState.showSnackbar(message = "需要登录后才能删除已同步的标记") }
                return
            }

            markBusy = true
            markError = null

            fun deleteLocal() {
                lifeEventRepository.deleteByEventIdAsync(
                    eventId = mark.eventId,
                    onDone = {
                        markBusy = false
                        selectedMarkId = null
                        selectedMark = null
                        refreshMarksToday()
                        if (route == RecordsRoute.MARKS_LIST) refreshMarksList()
                        refreshActiveRangeMark()
                        scope.launch { snackbarHostState.showSnackbar(message = "已删除") }
                    },
                    onError = { t ->
                        markBusy = false
                        markError = t.message ?: t.toString()
                    },
                )
            }

            if (requiresRemote) {
                syncManager.lifeEventDeleteAsync(
                    eventId = mark.eventId,
                    onDone = { _ -> deleteLocal() },
                    onError = { t ->
                        markBusy = false
                        markError = t.message ?: t.toString()
                    },
                )
            } else {
                deleteLocal()
            }
        }

        fun endRange() {
            val active = activeRangeMark
            if (active == null || active.eventId != mark.eventId) {
                scope.launch { snackbarHostState.showSnackbar(message = "该区间标记已不在进行中") }
                return
            }
            endActiveRangeMark()
        }

        MarkDetailScreen(
            mark = mark,
            busy = markBusy,
            error = markError,
            onBack = {
                selectedMarkId = null
                selectedMark = null
                markError = null
            },
            onSave = { label, note -> saveMark(label, note) },
            onDelete = { deleteMark() },
            onEndRange = { endRange() },
        )
        return
    }

    if (route == RecordsRoute.MARKS_LIST) {
        MarksListScreen(
            range = marksRange,
            loading = marksListLoading,
            error = marksListError,
            marks = marksList,
            onRangeChange = { marksRange = it },
            onBack = { route = RecordsRoute.HOME },
            onRefresh = { refreshMarksList() },
            onSelect = { selectedMarkId = it.eventId },
        )
        return
    }

    if (selectedSession != null) {
        val session = selectedSession!!

        LaunchedEffect(session.sessionId) {
            detailPointsLoading = true
            detailPointsError = null
            detailPoints = emptyList()
            repository.rangePointsAsync(
                startUtc = session.startUtc,
                endUtc = session.endUtc,
                limit = 5000,
                onResult = {
                    detailPoints = it
                    detailPointsLoading = false
                },
                onError = {
                    detailPointsError = it.message ?: it.toString()
                    detailPointsLoading = false
                },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { selectedSession = null }) {
                        Text(stringResource(com.wayfarer.android.R.string.records_back))
                    }
                    Column {
                        Text(
                            text = formatSessionTitle(session),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = formatSessionSubtitle(session),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                OutlinedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(com.wayfarer.android.R.string.records_view_detail),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        StatusRow(
                            label = stringResource(com.wayfarer.android.R.string.records_session_points),
                            value = session.pointCount.toString(),
                            valueTone = StatusTone.Muted,
                        )
                        StatusRow(
                            label = stringResource(com.wayfarer.android.R.string.records_session_duration),
                            value = formatDuration(session.durationSec),
                            valueTone = StatusTone.Muted,
                        )
                        StatusRow(
                            label = stringResource(com.wayfarer.android.R.string.records_session_distance),
                            value = formatDistance(session.distanceM),
                            valueTone = StatusTone.Muted,
                        )
                    }
                }
            }

            item {
                OutlinedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "点位预览",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        when {
                            detailPointsLoading -> {
                                Text(
                                    text = stringResource(com.wayfarer.android.R.string.track_stats_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            detailPointsError != null -> {
                                Text(
                                    text = detailPointsError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            detailPoints.isEmpty() -> {
                                Text(
                                    text = stringResource(com.wayfarer.android.R.string.map_no_points),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            else -> {
                                val first = detailPoints.first()
                                val last = detailPoints.last()
                                StatusRow(
                                    label = "起点",
                                    value = "${first.latitudeWgs84.format6()}, ${first.longitudeWgs84.format6()}",
                                    valueTone = StatusTone.Muted,
                                )
                                StatusRow(
                                    label = "终点",
                                    value = "${last.latitudeWgs84.format6()}, ${last.longitudeWgs84.format6()}",
                                    valueTone = StatusTone.Muted,
                                )
                            }
                        }
                    }
                }
            }
        }

        return
    }

    val todayStepsText = todaySteps?.toString() ?: "--"
    val todayDistanceM = remember(todayPoints) { computeDistanceMeters(todayPoints) }
    val todayActiveMinutes = remember(todayPoints) { computeActiveMinutes(todayPoints, zone) }

    val lastSampleText =
        todayStepsLastSampleUtcMs?.let { ms ->
            val zdt = Instant.ofEpochMilli(ms).atZone(zone)
            "%02d:%02d".format(zdt.hour, zdt.minute)
        } ?: "-"

    val stepsHelper =
        when {
            !stepSensorAvailable -> "传感器不可用"
            !stepsPermissionGranted -> "需授权"
            todayStepsLoading -> "采样中…"
            !todayStepsError.isNullOrBlank() -> "采样失败"
            todayStepsLastSampleUtcMs == null -> "系统（未采样）"
            else -> "系统（$lastSampleText）"
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(WfDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(WfDimens.ItemSpacing),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WfKpiCard(
                        label = "今日步数",
                        value = todayStepsText,
                        helper = stepsHelper,
                        modifier = Modifier.weight(1f),
                    )
                    WfKpiCard(
                        label = "今日距离",
                        value = formatDistance(todayDistanceM),
                        helper = "Wayfarer 记录",
                        modifier = Modifier.weight(1f),
                    )
                }
                WfKpiCard(
                    label = "今日活跃时长",
                    value = formatActiveMinutes(todayActiveMinutes),
                    helper = "Wayfarer 估算（按分钟：步数>0 或 speed≥0.8m/s）",
                    modifier = Modifier.fillMaxWidth(),
                )

                when {
                    !stepSensorAvailable -> {
                        WfEmptyState(
                            title = "系统步数不可用",
                            body = "当前设备不支持计步传感器，无法读取系统全天步数。",
                        )
                    }

                    !stepsPermissionGranted -> {
                        WfEmptyState(
                            title = "开启系统步数",
                            body = "授权后，“今日步数/统计步数”将显示系统全天步数（与你手机计步一致）。",
                            action = {
                                FilledTonalButton(
                                    onClick = { activityPermissionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION) },
                                ) {
                                    Text("授权活动识别")
                                }
                            },
                        )
                    }

                    todayStepsLoading -> {
                        WfEmptyState(
                            title = "系统步数采样中",
                            body = "正在读取计步传感器…",
                        )
                    }

                    !todayStepsError.isNullOrBlank() -> {
                        WfEmptyState(
                            title = "系统步数读取失败",
                            body = todayStepsError ?: "未知错误",
                            action = {
                                FilledTonalButton(
                                    onClick = { sampleTodayStepsNow() },
                                    enabled = !todayStepsLoading,
                                ) {
                                    Text("重试采样")
                                }
                            },
                        )
                    }

                    todayStepsLastSampleUtcMs == null -> {
                        WfEmptyState(
                            title = "系统步数未就绪",
                            body = "尚未采样到系统步数，点击下方按钮采样一次即可展示。",
                            action = {
                                FilledTonalButton(
                                    onClick = { sampleTodayStepsNow() },
                                    enabled = !todayStepsLoading,
                                ) {
                                    Text(if (todayStepsLoading) "采样中…" else "立即采样一次")
                                }
                            },
                        )
                    }
                }

                if (!todayPointsError.isNullOrBlank()) {
                    Text(
                        text = "今日数据读取失败：${todayPointsError ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!todayStepsError.isNullOrBlank()) {
                    Text(
                        text = "系统步数读取失败：${todayStepsError ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item {
            WfCard {
                Column(
                    modifier = Modifier.padding(WfDimens.CardPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val statusTitle = if (isTracking) "正在记录" else "未开始记录"
                    WfSectionHeader(
                        title = "记录",
                        subtitle = statusTitle,
                        trailing = {
                            FilledTonalButton(enabled = !markBusy, onClick = { showQuickMarkSheet = true }) {
                                Text("快速标记")
                            }
                        },
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                if (hasLocationPermission(context)) {
                                    TrackingServiceController.start(context)
                                    TrackingStatusStore.markStarted(context)
                                    isTracking = true
                                } else {
                                    permissionLauncher.launch(requiredPermissions)
                                }
                            },
                            enabled = !isTracking,
                        ) {
                            Text(
                                text = if (locationGranted) {
                                    stringResource(com.wayfarer.android.R.string.tracking_start)
                                } else {
                                    stringResource(com.wayfarer.android.R.string.tracking_request_permission)
                                },
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                TrackingServiceController.stop(context)
                                TrackingStatusStore.markStopped(context)
                                isTracking = false
                            },
                            enabled = isTracking,
                        ) {
                            Text(stringResource(com.wayfarer.android.R.string.tracking_stop))
                        }
                    }

                    if (!locationGranted || !activityGranted || !gpsEnabled) {
                        val hint = buildString {
                            append("提示：")
                            if (!locationGranted) append("未授权定位；")
                            if (!activityGranted) append("未授权活动识别；")
                            if (!gpsEnabled) append("GPS 未开启；")
                            append("可在“设置 → 权限”中查看。")
                        }
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val active = activeRangeMark
                    if (active != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "区间标记中：${active.label}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FilledTonalButton(
                                enabled = !markBusy,
                                onClick = { endActiveRangeMark() },
                            ) {
                                Text("结束区间")
                            }
                        }
                        Text(
                            text = "开始：${formatRecordedAt(active.startAtUtc)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WfSectionHeader(
                    title = "今日标记",
                    subtitle =
                        when {
                            marksTodayLoading -> "读取中…"
                            marksTodayError != null -> "加载失败"
                            else -> "共 ${marksToday.size} 条"
                        },
                    trailing = {
                        FilledTonalButton(
                            onClick = {
                                marksRange = MarksRange.TODAY
                                route = RecordsRoute.MARKS_LIST
                            },
                        ) {
                            Text("全部")
                        }
                    },
                )

                when {
                    marksTodayLoading -> {
                        Text(
                            text = stringResource(com.wayfarer.android.R.string.track_stats_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    marksTodayError != null -> {
                        Text(
                            text = marksTodayError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    marksToday.isEmpty() -> {
                        WfEmptyState(
                            title = "暂无标记",
                            body = "用「快速标记」随手记录时间点或区间，后续在统计里可下钻查看。",
                        )
                    }

                    else -> {
                        val preview = marksToday.take(3)
                        for (m in preview) {
                            MarkCard(
                                mark = m,
                                onClick = { selectedMarkId = m.eventId },
                            )
                        }

                        if (marksToday.size > preview.size) {
                            FilledTonalButton(
                                onClick = {
                                    marksRange = MarksRange.TODAY
                                    route = RecordsRoute.MARKS_LIST
                                },
                            ) {
                                Text("查看更多（${marksToday.size}）")
                            }
                        }
                    }
                }
            }
        }

        item {
            WfSectionHeader(
                title = "状态与权限（排障）",
                subtitle = if (showSystemStatus) "已展开" else "已折叠",
                trailing = {
                    FilledTonalButton(onClick = { showSystemStatus = !showSystemStatus }) {
                        Text(if (showSystemStatus) "收起" else "展开")
                    }
                },
            )
        }

        if (showSystemStatus) {
            item {
                OutlinedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(com.wayfarer.android.R.string.tracking_status_card_title),
                            style = MaterialTheme.typography.titleMedium,
                        )

                        StatusRow(
                            label = stringResource(com.wayfarer.android.R.string.tracking_status_service),
                            value =
                                if (isTracking) {
                                    stringResource(com.wayfarer.android.R.string.tracking_service_running)
                                } else {
                                    stringResource(com.wayfarer.android.R.string.tracking_service_stopped)
                                },
                            valueTone = if (isTracking) StatusTone.Good else StatusTone.Muted,
                        )

                        StatusRow(
                            label = stringResource(com.wayfarer.android.R.string.tracking_status_location_permission),
                            value =
                                if (locationGranted) {
                                    stringResource(com.wayfarer.android.R.string.status_granted)
                                } else {
                                    stringResource(com.wayfarer.android.R.string.status_denied)
                                },
                            valueTone = if (locationGranted) StatusTone.Good else StatusTone.Bad,
                        )

                        StatusRow(
                            label = stringResource(com.wayfarer.android.R.string.tracking_status_activity_permission),
                            value =
                                if (activityGranted) {
                                    stringResource(com.wayfarer.android.R.string.status_granted)
                                } else {
                                    stringResource(com.wayfarer.android.R.string.status_denied)
                                },
                            valueTone = if (activityGranted) StatusTone.Good else StatusTone.Bad,
                        )

                        StatusRow(
                            label = stringResource(com.wayfarer.android.R.string.tracking_status_gps),
                            value =
                                if (gpsEnabled) {
                                    stringResource(com.wayfarer.android.R.string.status_enabled)
                                } else {
                                    stringResource(com.wayfarer.android.R.string.status_disabled)
                                },
                            valueTone = if (gpsEnabled) StatusTone.Good else StatusTone.Bad,
                        )
                    }
                }
            }

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
                                text = stringResource(com.wayfarer.android.R.string.track_stats_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Button(onClick = { refreshAll() }) {
                                Text(stringResource(com.wayfarer.android.R.string.track_stats_refresh))
                            }
                        }

                        when {
                            statsLoading -> {
                                Text(
                                    text = stringResource(com.wayfarer.android.R.string.track_stats_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            statsError != null -> {
                                Text(
                                    text = statsError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            else -> {
                                val resolved = stats
                                if (resolved == null || resolved.totalCount <= 0L) {
                                    Text(
                                        text = stringResource(com.wayfarer.android.R.string.track_stats_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    StatusRow(
                                        label = stringResource(com.wayfarer.android.R.string.track_stats_total),
                                        value = resolved.totalCount.toString(),
                                        valueTone = StatusTone.Muted,
                                    )
                                    StatusRow(
                                        label = stringResource(com.wayfarer.android.R.string.track_stats_pending_sync),
                                        value = resolved.pendingSyncCount.toString(),
                                        valueTone =
                                            if (resolved.pendingSyncCount == 0L) {
                                                StatusTone.Good
                                            } else {
                                                StatusTone.Muted
                                            },
                                    )
                                    StatusRow(
                                        label = stringResource(com.wayfarer.android.R.string.track_stats_latest),
                                        value = formatRecordedAt(resolved.latestRecordedAtUtc),
                                        valueTone = StatusTone.Muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            WfSectionHeader(
                title = "记录会话",
                subtitle = "点击卡片查看详情",
                trailing = {
                    Button(onClick = { refreshSessions() }) {
                        Text("刷新")
                    }
                },
            )
        }

        if (sessionsLoading) {
            item {
                Text(
                    text = stringResource(com.wayfarer.android.R.string.track_stats_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (sessionsError != null) {
            item {
                Text(
                    text = sessionsError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (sessions.isEmpty()) {
            item {
                OutlinedCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(com.wayfarer.android.R.string.records_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            val grouped =
                sessions.groupBy { s ->
                    runCatching {
                        val end = Instant.parse(s.endUtc)
                        end.atZone(zone).toLocalDate()
                    }.getOrNull()
                }
            val dayKeys = grouped.keys.filterNotNull().sortedDescending()

            for (day in dayKeys) {
                item {
                    Text(
                        text = day.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                val daySessions = grouped[day].orEmpty()
                items(
                    items = daySessions,
                    key = { it.sessionId },
                ) { session ->
                    SessionCard(
                        session = session,
                        onClick = { selectedSession = session },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: RecordSessionSummary,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = formatSessionTitle(session),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${stringResource(com.wayfarer.android.R.string.records_session_points)}：${session.pointCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDistance(session.distanceM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatSessionSubtitle(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(com.wayfarer.android.R.string.records_view_detail),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun buildSessions(pointsAsc: List<TrackPointEntity>): List<RecordSessionSummary> {
    if (pointsAsc.isEmpty()) return emptyList()

    val sessions = mutableListOf<MutableList<TrackPointEntity>>()
    var current = mutableListOf<TrackPointEntity>()

    fun flush() {
        if (current.isNotEmpty()) {
            sessions.add(current)
            current = mutableListOf()
        }
    }

    // Simple segmentation: new session when the gap between points is large.
    val gapThresholdSec = 10 * 60L
    var prevInstant: Instant? = null

    for (p in pointsAsc) {
        val instant = runCatching { Instant.parse(p.recordedAtUtc) }.getOrNull()
        if (instant != null && prevInstant != null) {
            val gap = Duration.between(prevInstant, instant).seconds
            if (gap >= gapThresholdSec) {
                flush()
            }
        }

        current.add(p)
        prevInstant = instant
    }
    flush()

    val summaries = sessions.mapNotNull { points ->
        val first = points.firstOrNull() ?: return@mapNotNull null
        val last = points.lastOrNull() ?: return@mapNotNull null
        val start = first.recordedAtUtc
        val end = last.recordedAtUtc
        val durationSec =
            runCatching {
                Duration.between(Instant.parse(start), Instant.parse(end)).seconds
            }.getOrDefault(0L)

        RecordSessionSummary(
            sessionId = "${start}_${end}_${points.size}",
            startUtc = start,
            endUtc = end,
            pointCount = points.size,
            durationSec = durationSec.coerceAtLeast(0L),
            distanceM = computeDistanceMeters(points),
        )
    }

    // Newest first.
    return summaries.sortedByDescending { it.endUtc }
}

private fun Double.format6(): String = String.format("%.6f", this)

private fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "${h}小时${m}分"
        m > 0 -> "${m}分${sec}秒"
        else -> "${sec}秒"
    }
}

private fun formatSessionTitle(session: RecordSessionSummary): String {
    val start = formatRecordedAt(session.startUtc)
    val end = formatRecordedAt(session.endUtc)
    return "$start → $end"
}

private fun formatSessionSubtitle(session: RecordSessionSummary): String {
    return "时长：${formatDuration(session.durationSec)}"
}

private fun hasLocationPermission(context: Context): Boolean {
    return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
}

private val RECORDED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatRecordedAt(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    return runCatching {
        val instant = Instant.parse(value)
        val zdt = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
        RECORDED_AT_FORMAT.format(zdt)
    }.getOrDefault(value)
}

private enum class StatusTone {
    Good,
    Bad,
    Muted,
}

@Composable
private fun StatusRow(label: String, value: String, valueTone: StatusTone) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor =
        when (valueTone) {
            StatusTone.Good -> MaterialTheme.colorScheme.primary
            StatusTone.Bad -> MaterialTheme.colorScheme.error
            StatusTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

private fun isGpsEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return runCatching {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
}
