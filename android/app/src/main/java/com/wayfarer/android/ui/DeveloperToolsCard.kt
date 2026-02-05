package com.wayfarer.android.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wayfarer.android.R
import com.wayfarer.android.dev.DevInputMonitor
import com.wayfarer.android.dev.DeviceIdleWhitelist
import com.wayfarer.android.steps.StepDeltaCalculator

@Composable
fun DeveloperToolsCard(context: Context) {
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
                text = stringResource(R.string.dev_tools_title),
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
                text = stringResource(R.string.dev_step_sensor, stepSensorStatus),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.dev_step_count,
                    stepCount?.toString() ?: "(未知)",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.dev_step_delta,
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
                    Text(stringResource(R.string.dev_whitelist_root))
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        inputStatus = inputMonitor.probeOnce()
                    },
                ) {
                    Text(stringResource(R.string.dev_probe_input))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(
                    onClick = {
                        inputStatus = inputMonitor.start()
                    },
                ) {
                    Text(stringResource(R.string.dev_start_input_monitor))
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        inputStatus = inputMonitor.stop()
                    },
                ) {
                    Text(stringResource(R.string.dev_stop_input_monitor))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.dev_root_status, rootStatus),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(R.string.dev_input_status, inputStatus),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

