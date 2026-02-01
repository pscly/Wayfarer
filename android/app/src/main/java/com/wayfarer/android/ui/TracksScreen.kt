package com.wayfarer.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wayfarer.android.BuildConfig
import com.wayfarer.android.amap.AmapApiKey
import com.wayfarer.android.amap.AmapMapView
import com.wayfarer.android.dev.DevInputMonitor
import com.wayfarer.android.dev.DeveloperModeGate
import com.wayfarer.android.dev.DeviceIdleWhitelist
import com.wayfarer.android.steps.StepDeltaCalculator
import com.wayfarer.android.tracking.TrackingServiceController

@Composable
fun TracksScreen() {
    val context = LocalContext.current
    val amapKeyRaw = remember { AmapApiKey.readFromManifest(context) }
    val amapKeyPresent = remember(amapKeyRaw) { AmapApiKey.isPresent(amapKeyRaw) }

    var isTracking by rememberSaveable { mutableStateOf(false) }

    val developerModeGate = remember { DeveloperModeGate() }
    var developerModeEnabled by rememberSaveable { mutableStateOf(false) }

    val stepCalc = remember { StepDeltaCalculator() }
    var stepSensorStatus by remember { mutableStateOf("IDLE") }
    var stepCount by remember { mutableStateOf<Long?>(null) }
    var stepDelta by remember { mutableStateOf<Long?>(null) }

    val inputMonitor = remember { DevInputMonitor() }
    var inputStatus by remember { mutableStateOf(inputMonitor.status()) }

    var rootStatus by remember { mutableStateOf("IDLE") }

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
        val locationGranted =
            (result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (result[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true)

        if (locationGranted) {
            TrackingServiceController.start(context)
            isTracking = true
        }
    }

    val vm: TracksViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TracksViewModel(
                    amapKeyRaw = amapKeyRaw,
                    amapKeyPresent = amapKeyPresent,
                )
            }
        },
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "Wayfarer",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .clickable {
                        developerModeEnabled = developerModeGate.tap()
                    },
            )

            // Verification hint: benchmark APK should show https://waf.pscly.cc here by default.
            Text(
                text = "api_base_url: ${BuildConfig.WAYFARER_API_BASE_URL}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (!developerModeEnabled) {
                Text(
                    text = "Tap title 7 times for developer tools",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (developerModeEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Developer tools",
                    style = MaterialTheme.typography.titleMedium,
                )

                // Best-effort step counter listener.
                DisposableEffect(Unit) {
                    stepCalc.reset()
                    stepCount = null
                    stepDelta = null

                    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                    if (sensorManager == null) {
                        stepSensorStatus = "ERR: SensorManager missing"
                        return@DisposableEffect onDispose { }
                    }

                    val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                    if (sensor == null) {
                        stepSensorStatus = "ERR: step counter sensor missing"
                        return@DisposableEffect onDispose { }
                    }

                    val listener =
                        object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent?) {
                                val value = event?.values?.firstOrNull() ?: return
                                val out = stepCalc.onSample(value)
                                stepCount = out.stepCount
                                stepDelta = out.stepDelta
                                stepSensorStatus = "OK"
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

                    stepSensorStatus = if (registered) "OK: listening" else "ERR: failed to register"

                    onDispose {
                        runCatching { sensorManager.unregisterListener(listener) }
                    }
                }

                Text(
                    text = "step_sensor: $stepSensorStatus",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "step_count: ${stepCount?.toString() ?: "(unknown)"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "step_delta: ${stepDelta?.toString() ?: "(unknown)"}",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            rootStatus = DeviceIdleWhitelist.tryWhitelist(context.packageName)
                        },
                    ) {
                        Text("Whitelist (root)")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            inputStatus = inputMonitor.probeOnce()
                        },
                    ) {
                        Text("Probe /dev/input")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            inputStatus = inputMonitor.start()
                        },
                    ) {
                        Text("Start /dev/input monitor")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            inputStatus = inputMonitor.stop()
                        },
                    ) {
                        Text("Stop /dev/input monitor")
                    }
                }

                Text(
                    text = "root: $rootStatus",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "input: $inputStatus",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row {
                Button(
                    onClick = {
                        if (hasLocationPermission(context)) {
                            TrackingServiceController.start(context)
                            isTracking = true
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    },
                    enabled = !isTracking,
                ) {
                    Text("Start tracking")
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        TrackingServiceController.stop(context)
                        isTracking = false
                    },
                    enabled = isTracking,
                ) {
                    Text("Stop tracking")
                }
            }

            if (!vm.uiState.amapKeyPresent) {
                Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
                    Text(
                        text = "AMap API key missing",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text =
                            "Set WAYFARER_AMAP_API_KEY via:\n" +
                                "1) Gradle property: -PWAYFARER_AMAP_API_KEY=...\n" +
                                "2) Environment variable: WAYFARER_AMAP_API_KEY\n" +
                                "3) android/local.properties (local-only; do not commit)",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text =
                            if (vm.uiState.amapKeyRaw.isNullOrBlank()) {
                                "Manifest meta-data com.amap.api.v2.apikey is missing/blank."
                            } else {
                                "Manifest meta-data com.amap.api.v2.apikey is set but looks invalid (placeholder/sentinel)."
                            },
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
                    AmapMapView(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
}
