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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wayfarer.android.api.AuthStore
import com.wayfarer.android.db.LifeEventEntity
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.tracking.LifeEventRepository
import com.wayfarer.android.tracking.TrackingServiceController
import com.wayfarer.android.tracking.TrackPointRepository
import com.wayfarer.android.tracking.TrackingStatusStore
import com.wayfarer.android.ui.sync.rememberSyncSnapshot
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

@Composable
fun RecordsScreen() {
    val context = LocalContext.current
    val syncSnapshot = rememberSyncSnapshot(context)

    var isTracking by rememberSaveable { mutableStateOf(TrackingStatusStore.readIsTracking(context)) }
    val locationGranted = hasLocationPermission(context)
    val activityGranted =
        context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    val gpsEnabled = isGpsEnabled(context)

    val repository = remember { TrackPointRepository(context) }
    val lifeEventRepository = remember { LifeEventRepository(context) }
    val scope = rememberCoroutineScope()

    var activeRangeMark by remember { mutableStateOf<LifeEventEntity?>(null) }

    var showMarkDialog by rememberSaveable { mutableStateOf(false) }
    var markType by rememberSaveable { mutableStateOf(LifeEventEntity.EventType.MARK_POINT) }
    var markLabel by rememberSaveable { mutableStateOf("") }
    var markNote by rememberSaveable { mutableStateOf("") }
    var markUseLocation by rememberSaveable { mutableStateOf(true) }
    var markBusy by remember { mutableStateOf(false) }
    var markError by remember { mutableStateOf<String?>(null) }

    // Local stats (counts, latest timestamp)
    var statsLoading by remember { mutableStateOf(false) }
    var statsError by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<TrackPointRepository.TrackPointStats?>(null) }

    // Session list
    var sessionsLoading by remember { mutableStateOf(false) }
    var sessionsError by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<List<RecordSessionSummary>>(emptyList()) }
    var sessionsLoadSeq by remember { mutableStateOf(0) }

    var selectedSession by rememberSaveable { mutableStateOf<RecordSessionSummary?>(null) }
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
        refreshSessions()
        refreshActiveRangeMark()
    }

    LaunchedEffect(Unit) {
        refreshAll()
    }

    // 当后台同步拉取到新数据时，刷新界面，避免“登录后同步了但列表不更新”的体验。
    LaunchedEffect(syncSnapshot.lastPullAtMs) {
        refreshAll()
    }

    LaunchedEffect(isTracking) {
        // Start/stop often changes local data quickly.
        refreshAll()
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

        if (permissionLocationGranted) {
            TrackingServiceController.start(context)
            TrackingStatusStore.markStarted(context)
            isTracking = true
        }
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
            if (best == null || (loc.hasAccuracy() && loc.accuracy < (best?.accuracy ?: Float.MAX_VALUE))) {
                best = loc
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

    fun createMark() {
        val label = markLabel.trim()
        if (label.isBlank()) {
            markError = "请先输入标签（例如：出门买东西）"
            return
        }
        if (markType == LifeEventEntity.EventType.MARK_RANGE && activeRangeMark != null) {
            markError = "已有正在进行的区间标记，请先结束。"
            return
        }

        markBusy = true
        markError = null
        resolveMarkerCoordsBestEffort(useLocation = markUseLocation) { coords ->
            val now = Instant.now()
            val startAt = now.toString()
            val endAt =
                if (markType == LifeEventEntity.EventType.MARK_POINT) {
                    now.plusSeconds(1).toString()
                } else {
                    null
                }
            val note = markNote.trim().takeIf { it.isNotBlank() }

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
                eventType = markType,
                startAtUtc = startAt,
                endAtUtc = endAt,
                label = label,
                note = note,
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
                    showMarkDialog = false
                    markLabel = ""
                    markNote = ""
                    refreshActiveRangeMark()
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
                    refreshActiveRangeMark()
                },
                onError = { err ->
                    markBusy = false
                    markError = err.message ?: err.toString()
                },
            )
        }
    }

    if (showMarkDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!markBusy) {
                    showMarkDialog = false
                    markError = null
                }
            },
            title = { Text("新建标记") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = !markBusy,
                            onClick = { markType = LifeEventEntity.EventType.MARK_POINT },
                        ) {
                            Text(
                                text = if (markType == LifeEventEntity.EventType.MARK_POINT) "时间点 ✓" else "时间点",
                            )
                        }

                        Button(
                            enabled = !markBusy && activeRangeMark == null,
                            onClick = { markType = LifeEventEntity.EventType.MARK_RANGE },
                        ) {
                            Text(
                                text = if (markType == LifeEventEntity.EventType.MARK_RANGE) "区间（开始） ✓" else "区间（开始）",
                            )
                        }
                    }

                    OutlinedTextField(
                        value = markLabel,
                        onValueChange = { markLabel = it },
                        label = { Text("标签（必填）") },
                        placeholder = { Text("例如：出门买东西") },
                        singleLine = true,
                        enabled = !markBusy,
                    )
                    OutlinedTextField(
                        value = markNote,
                        onValueChange = { markNote = it },
                        label = { Text("备注（可选）") },
                        singleLine = true,
                        enabled = !markBusy,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "记录当前位置",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Checkbox(
                            checked = markUseLocation,
                            onCheckedChange = { markUseLocation = it },
                            enabled = !markBusy,
                        )
                    }

                    if (markError != null) {
                        Text(
                            text = markError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !markBusy,
                    onClick = { createMark() },
                ) {
                    Text(if (markBusy) "处理中…" else "确定")
                }
            },
            dismissButton = {
                FilledTonalButton(
                    enabled = !markBusy,
                    onClick = {
                        showMarkDialog = false
                        markError = null
                    },
                ) {
                    Text("取消")
                }
            },
        )
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.tracking_subtitle),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
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

                        Spacer(modifier = Modifier.width(12.dp))

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

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = !markBusy,
                            onClick = { showMarkDialog = true },
                        ) {
                            Text("标记")
                        }

                        val active = activeRangeMark
                        if (active != null) {
                            FilledTonalButton(
                                enabled = !markBusy,
                                onClick = { endActiveRangeMark() },
                            ) {
                                Text("结束标记")
                            }
                        }
                    }

                    val active = activeRangeMark
                    if (active != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "标记中：${active.label}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "开始：${formatRecordedAt(active.startAtUtc)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (markError != null && !showMarkDialog) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = markError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (!locationGranted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(com.wayfarer.android.R.string.tracking_permission_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

        item {
            Text(
                text = stringResource(com.wayfarer.android.R.string.records_sessions),
                style = MaterialTheme.typography.titleMedium,
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
            items(
                items = sessions,
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

private fun Double.format6(): String = String.format("%.6f", this)

private fun formatDistance(meters: Double): String {
    val m = meters.coerceAtLeast(0.0)
    return if (m < 1000.0) {
        "${m.toInt()} m"
    } else {
        val km = m / 1000.0
        String.format("%.2f km", km)
    }
}

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
