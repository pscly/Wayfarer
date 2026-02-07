package com.wayfarer.android.steps

import android.content.pm.PackageManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.ZoneId

/**
 * 15 分钟后台采样一次计步传感器，用于尽量贴近“系统全天步数”。
 *
 * 注意：在国内 ROM 上，后台调度可能被电池优化影响；此 Worker 仅做 best-effort。
 */
class StepsSamplingWorker(
    appContext: android.content.Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val granted =
            applicationContext.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return Result.success()

        val cumulative =
            runCatching { StepCounterOneShotReader.readCumulativeStepsBlocking(applicationContext) }.getOrNull()
                ?: return Result.success()

        runCatching {
            DailyStepsStore.applySample(
                context = applicationContext,
                sampleTimeUtcMs = System.currentTimeMillis(),
                cumulativeSteps = cumulative,
                zone = ZoneId.systemDefault(),
            )
        }

        return Result.success()
    }
}

