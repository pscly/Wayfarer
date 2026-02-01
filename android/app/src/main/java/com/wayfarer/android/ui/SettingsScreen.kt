package com.wayfarer.android.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.wayfarer.android.dev.DevInputMonitor
import com.wayfarer.android.dev.DeviceIdleWhitelist
import com.wayfarer.android.dev.DeveloperModeGate
import com.wayfarer.android.steps.StepDeltaCalculator
import com.wayfarer.android.tracking.TrackPointRepository

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val developerModeGate = remember { DeveloperModeGate() }
    var developerModeEnabled by rememberSaveable { mutableStateOf(false) }

    val repository = remember { TrackPointRepository(context) }
    var statsLoading by remember { mutableStateOf(false) }
    var statsError by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<TrackPointRepository.TrackPointStats?>(null) }

    var showClearDialog by rememberSaveable { mutableStateOf(false) }

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

    val locationGranted = hasLocationPermission(context)
    val activityGranted =
        context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    val gpsEnabled = isGpsEnabled(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = {
                    Text(stringResource(com.wayfarer.android.R.string.settings_clear_local_data_confirm_title))
                },
                text = {
                    Text(stringResource(com.wayfarer.android.R.string.settings_clear_local_data_confirm_body))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            repository.clearAllAsync(
                                onDone = { refreshStats() },
                                onError = { err -> statsError = err.message ?: err.toString() },
                            )
                        },
                    ) {
                        Text(stringResource(com.wayfarer.android.R.string.settings_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(stringResource(com.wayfarer.android.R.string.settings_cancel))
                    }
                },
            )
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(com.wayfarer.android.R.string.tab_settings),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        com.wayfarer.android.R.string.tracking_api_base_url,
                        BuildConfig.WAYFARER_API_BASE_URL,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
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
                    text = stringResource(com.wayfarer.android.R.string.settings_section_permissions),
                    style = MaterialTheme.typography.titleMedium,
                )

                SettingsStatusRow(
                    label = stringResource(com.wayfarer.android.R.string.tracking_status_location_permission),
                    value =
                        if (locationGranted) {
                            stringResource(com.wayfarer.android.R.string.status_granted)
                        } else {
                            stringResource(com.wayfarer.android.R.string.status_denied)
                        },
                )
                SettingsStatusRow(
                    label = stringResource(com.wayfarer.android.R.string.tracking_status_activity_permission),
                    value =
                        if (activityGranted) {
                            stringResource(com.wayfarer.android.R.string.status_granted)
                        } else {
                            stringResource(com.wayfarer.android.R.string.status_denied)
                        },
                )
                SettingsStatusRow(
                    label = stringResource(com.wayfarer.android.R.string.tracking_status_gps),
                    value =
                        if (gpsEnabled) {
                            stringResource(com.wayfarer.android.R.string.status_enabled)
                        } else {
                            stringResource(com.wayfarer.android.R.string.status_disabled)
                        },
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row {
                    FilledTonalButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                    ) {
                        Text(stringResource(com.wayfarer.android.R.string.settings_open_app_settings))
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                    ) {
                        Text(stringResource(com.wayfarer.android.R.string.settings_open_location_settings))
                    }
                }
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
                        text = stringResource(com.wayfarer.android.R.string.settings_section_data),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = { refreshStats() }) {
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
                        if (resolved != null) {
                            SettingsStatusRow(
                                label = stringResource(com.wayfarer.android.R.string.track_stats_total),
                                value = resolved.totalCount.toString(),
                                mono = true,
                            )
                            SettingsStatusRow(
                                label = stringResource(com.wayfarer.android.R.string.track_stats_pending_sync),
                                value = resolved.pendingSyncCount.toString(),
                                mono = true,
                            )
                            SettingsStatusRow(
                                label = stringResource(com.wayfarer.android.R.string.track_stats_latest),
                                value = resolved.latestRecordedAtUtc ?: "-",
                                mono = true,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { showClearDialog = true },
                ) {
                    Text(stringResource(com.wayfarer.android.R.string.settings_clear_local_data))
                }
            }
        }

        val amapKeyRaw = remember { AmapApiKey.readFromManifest(context) }
        val amapKeyPresent = remember(amapKeyRaw) { AmapApiKey.isPresent(amapKeyRaw) }

        OutlinedCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(com.wayfarer.android.R.string.settings_section_map),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (amapKeyPresent) {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.settings_amap_key_present),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.amap_key_missing_short),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.amap_key_missing_setup),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.clickable {
                developerModeEnabled = developerModeGate.tap()
            },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(com.wayfarer.android.R.string.settings_section_about),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        com.wayfarer.android.R.string.settings_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(com.wayfarer.android.R.string.dev_tools_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (developerModeEnabled) {
            DeveloperToolsCard(context = context)
        }
    }

    // Initialize stats lazily after first composition.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        refreshStats()
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun isGpsEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return runCatching {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
}

@Composable
private fun SettingsStatusRow(label: String, value: String, mono: Boolean = false) {
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
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun DeveloperToolsCard(context: Context) {
    val stepCalc = remember { StepDeltaCalculator() }
    var stepSensorStatus by remember { mutableStateOf("空闲") }
    var stepCount by remember { mutableStateOf<Long?>(null) }
    var stepDelta by remember { mutableStateOf<Long?>(null) }

    val inputMonitor = remember { DevInputMonitor() }
    var inputStatus by remember { mutableStateOf(inputMonitor.status()) }

    var rootStatus by remember { mutableStateOf("空闲") }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(com.wayfarer.android.R.string.dev_tools_title),
                style = MaterialTheme.typography.titleLarge,
            )

            // Best-effort step counter listener.
            DisposableEffect(Unit) {
                stepCalc.reset()
                stepCount = null
                stepDelta = null

                val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                if (sensorManager == null) {
                    stepSensorStatus = "错误：SensorManager 不可用"
                    return@DisposableEffect onDispose { }
                }

                val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                if (sensor == null) {
                    stepSensorStatus = "错误：设备不支持计步传感器"
                    return@DisposableEffect onDispose { }
                }

                val listener =
                    object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent?) {
                            val value = event?.values?.firstOrNull() ?: return
                            val out = stepCalc.onSample(value)
                            stepCount = out.stepCount
                            stepDelta = out.stepDelta
                            stepSensorStatus = "正常"
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                            // No-op for dev display.
                        }
                    }

                val registered =
                    runCatching {
                        sensorManager.registerListener(
                            listener,
                            sensor,
                            SensorManager.SENSOR_DELAY_NORMAL,
                        )
                    }.getOrDefault(false)

                stepSensorStatus = if (registered) "正常：监听中" else "错误：注册失败"

                onDispose {
                    runCatching { sensorManager.unregisterListener(listener) }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(com.wayfarer.android.R.string.dev_step_sensor, stepSensorStatus),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    com.wayfarer.android.R.string.dev_step_count,
                    stepCount?.toString() ?: "(未知)",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    com.wayfarer.android.R.string.dev_step_delta,
                    stepDelta?.toString() ?: "(未知)",
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(
                    onClick = {
                        rootStatus = DeviceIdleWhitelist.tryWhitelist(context.packageName)
                    },
                ) {
                    Text(stringResource(com.wayfarer.android.R.string.dev_whitelist_root))
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        inputStatus = inputMonitor.probeOnce()
                    },
                ) {
                    Text(stringResource(com.wayfarer.android.R.string.dev_probe_input))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(
                    onClick = {
                        inputStatus = inputMonitor.start()
                    },
                ) {
                    Text(stringResource(com.wayfarer.android.R.string.dev_start_input_monitor))
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        inputStatus = inputMonitor.stop()
                    },
                ) {
                    Text(stringResource(com.wayfarer.android.R.string.dev_stop_input_monitor))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(com.wayfarer.android.R.string.dev_root_status, rootStatus),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(com.wayfarer.android.R.string.dev_input_status, inputStatus),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
