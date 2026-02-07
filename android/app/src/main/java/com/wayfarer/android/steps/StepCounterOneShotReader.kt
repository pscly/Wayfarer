package com.wayfarer.android.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.floor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 一次性读取 Step Counter（Sensor.TYPE_STEP_COUNTER）的累积值。
 *
 * 约束：
 * - 这是“系统计步”的底层原始数据（通常与小米健康同源），不依赖 Google/Health Connect。
 * - 读取该传感器通常需要 `ACTIVITY_RECOGNITION` 运行时权限（Android 10+）。
 */
object StepCounterOneShotReader {
    fun isAvailable(context: Context): Boolean {
        val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    fun readCumulativeStepsBlocking(
        context: Context,
        timeoutMs: Long = 2500L,
    ): Long? {
        val appContext = context.applicationContext
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        val thread = HandlerThread("wayfarer-step-counter-reader").apply { start() }
        val handler = Handler(thread.looper)

        val latch = CountDownLatch(1)
        var latest: Float? = null

        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val v = event?.values?.firstOrNull() ?: return
                    latest = v
                    latch.countDown()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    // no-op
                }
            }

        val registered =
            runCatching {
                // 使用 handler 绑定 looper，避免 Worker 线程无 looper 导致不回调。
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
                true
            }.getOrDefault(false)

        if (!registered) {
            runCatching { thread.quitSafely() }
            return null
        }

        runCatching {
            latch.await(timeoutMs.coerceAtLeast(200L), TimeUnit.MILLISECONDS)
        }

        runCatching { sm.unregisterListener(listener) }
        runCatching { thread.quitSafely() }

        val v = latest ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return floor(v.toDouble()).toLong().coerceAtLeast(0L)
    }
}
