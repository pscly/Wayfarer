package com.wayfarer.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.wayfarer.android.amap.AmapApiKey
import com.wayfarer.android.amap.AmapMapView
import com.wayfarer.android.tracking.TrackingServiceController
import com.wayfarer.android.tracking.TrackPointRepository
import com.wayfarer.android.tracking.TrackingStatusStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun TracksScreen() {
    val context = LocalContext.current
    val amapKeyRaw = remember { AmapApiKey.readFromManifest(context) }
    val amapKeyPresent = remember(amapKeyRaw) { AmapApiKey.isPresent(amapKeyRaw) }

    var isTracking by rememberSaveable { mutableStateOf(TrackingStatusStore.readIsTracking(context)) }

    val locationGranted = hasLocationPermission(context)
    val activityGranted =
        context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    val gpsEnabled = isGpsEnabled(context)

    val repository = remember { TrackPointRepository(context) }
    var statsLoading by remember { mutableStateOf(false) }
    var statsError by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<TrackPointRepository.TrackPointStats?>(null) }

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

    LaunchedEffect(Unit) {
        refreshStats()
    }

    LaunchedEffect(isTracking) {
        // Refresh after start/stop to show new points quickly.
        refreshStats()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                        BuildConfig.WAYFARER_API_BASE_URL,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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
                    Button(onClick = { refreshStats() }) {
                        Text(stringResource(com.wayfarer.android.R.string.track_stats_refresh))
                    }
                }

                if (statsLoading) {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.track_stats_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (statsError != null) {
                    Text(
                        text = statsError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
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
                            valueTone = if (resolved.pendingSyncCount == 0L) StatusTone.Good else StatusTone.Muted,
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

        OutlinedCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isTracking) {
                        stringResource(com.wayfarer.android.R.string.tracking_status_tracking)
                    } else {
                        stringResource(com.wayfarer.android.R.string.tracking_status_idle)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(com.wayfarer.android.R.string.tracking_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
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
                            text = if (hasLocationPermission(context)) {
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
            }
        }

        if (!amapKeyPresent) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.amap_key_missing_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.amap_key_missing_setup),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (amapKeyRaw.isNullOrBlank()) {
                            stringResource(com.wayfarer.android.R.string.amap_key_missing_manifest_blank)
                        } else {
                            stringResource(com.wayfarer.android.R.string.amap_key_missing_manifest_invalid)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    AmapMapView(modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (amapKeyPresent) {
            Spacer(modifier = Modifier.height(4.dp))
        } else {
            // Keep layout stable when map is absent.
            Spacer(modifier = Modifier.weight(1f))
        }
    }
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
