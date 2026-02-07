package com.wayfarer.android.steps

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 国内手机优先的“系统全天步数”仓库：
 * - 数据源：Sensor.TYPE_STEP_COUNTER（累积值）
 * - 存储：DailyStepsStore（按本地日累计）
 */
class SensorStepsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext

    private val ioExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isSensorAvailable(): Boolean = StepCounterOneShotReader.isAvailable(appContext)

    fun readSnapshot(zone: ZoneId = ZoneId.systemDefault()): DailyStepsStore.Snapshot {
        return DailyStepsStore.readSnapshot(appContext, zone)
    }

    fun sampleNowAsync(
        zone: ZoneId = ZoneId.systemDefault(),
        onResult: (DailyStepsStore.Snapshot) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                val cumulative = StepCounterOneShotReader.readCumulativeStepsBlocking(appContext)
                    ?: throw IllegalStateException("无法读取计步传感器（可能未授权或设备不支持）")
                DailyStepsStore.applySample(
                    context = appContext,
                    sampleTimeUtcMs = System.currentTimeMillis(),
                    cumulativeSteps = cumulative,
                    zone = zone,
                )
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }
}

