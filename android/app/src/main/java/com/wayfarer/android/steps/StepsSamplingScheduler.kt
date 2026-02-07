package com.wayfarer.android.steps

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 计步后台采样调度器（国内手机优先）：
 * - 周期：WorkManager 最小 15 分钟
 * - 目标：尽量贴近系统全天步数（即使 Wayfarer 没在记录轨迹）
 */
object StepsSamplingScheduler {
    private const val UNIQUE_PERIODIC = "wayfarer_steps_sampling_periodic"
    private const val UNIQUE_ONE_TIME = "wayfarer_steps_sampling_one_time"

    fun ensurePeriodicScheduled(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

        val request =
            PeriodicWorkRequestBuilder<StepsSamplingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueSampleNow(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

        val request =
            OneTimeWorkRequestBuilder<StepsSamplingWorker>()
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_ONE_TIME, ExistingWorkPolicy.REPLACE, request)
    }
}

