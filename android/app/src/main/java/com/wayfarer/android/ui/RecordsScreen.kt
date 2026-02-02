package com.wayfarer.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wayfarer.android.BuildConfig
import com.wayfarer.android.api.ServerConfigStore
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.tracking.TrackingServiceController
import com.wayfarer.android.tracking.TrackPointRepository
import com.wayfarer.android.tracking.TrackingStatusStore
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

@Composable
fun RecordsScreen() {
    val context = LocalContext.current

    var isTracking by rememberSaveable { mutableStateOf(TrackingStatusStore.readIsTracking(context)) }
    val locationGranted = hasLocationPermission(context)
    val activityGranted =
        context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    val gpsEnabled = isGpsEnabled(context)

    val repository = remember { TrackPointRepository(context) }

    // Local stats (counts, latest timestamp)
    var statsLoading by remember { mutableStateOf(false) }
    var statsError by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<TrackPointRepository.TrackPointStats?>(null) }

    // Session list
    var sessionsLoading by remember { mutableStateOf(false) }
    var sessionsError by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<List<RecordSessionSummary>>(emptyList()) }

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
        sessionsLoading = true
        sessionsError = null
        repository.latestPointsAsync(
            limit = 5000,
            onResult = { pointsDesc ->
                val pointsAsc = pointsDesc.asReversed()
                sessions = buildSessions(pointsAsc)
                sessionsLoading = false
            },
            onError = {
                sessionsError = it.message ?: it.toString()
                sessionsLoading = false
            },
        )
    }

    fun refreshAll() {
        refreshStats()
        refreshSessions()
    }

    LaunchedEffect(Unit) {
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
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            com.wayfarer.android.R.string.tracking_api_base_url,
                            ServerConfigStore.readBaseUrl(context),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
