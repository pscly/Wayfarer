package com.wayfarer.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object WayfarerSyncScheduler {
    private const val UNIQUE_PERIODIC = "wayfarer_sync_periodic"
    private const val UNIQUE_ONE_TIME = "wayfarer_sync_one_time"
    private const val UNIQUE_BOOTSTRAP = "wayfarer_sync_bootstrap"
    private const val UNIQUE_BACKFILL = "wayfarer_sync_backfill"

    fun ensurePeriodicSyncScheduled(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val request =
            PeriodicWorkRequestBuilder<WayfarerSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        WayfarerSyncWorker.KEY_ACTION to WayfarerSyncWorker.ACTION_SYNC,
                    ),
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_PERIODIC)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(UNIQUE_PERIODIC)
        wm.cancelUniqueWork(UNIQUE_ONE_TIME)
        wm.cancelUniqueWork(UNIQUE_BOOTSTRAP)
        wm.cancelUniqueWork(UNIQUE_BACKFILL)
    }

    fun cancelBackfill(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_BACKFILL)
    }

    fun enqueueOneTimeSync(context: Context, action: String = WayfarerSyncWorker.ACTION_SYNC) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val request =
            OneTimeWorkRequestBuilder<WayfarerSyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        WayfarerSyncWorker.KEY_ACTION to action,
                    ),
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_ONE_TIME, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueBootstrapRecent(context: Context) {
        // New bootstrap implies a new backfill chain.
        cancelBackfill(context)

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val request =
            OneTimeWorkRequestBuilder<WayfarerSyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        WayfarerSyncWorker.KEY_ACTION to WayfarerSyncWorker.ACTION_BOOTSTRAP_RECENT,
                    ),
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_BOOTSTRAP, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueBackfillStep(context: Context, delayMinutes: Long = 2) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val request =
            OneTimeWorkRequestBuilder<WayfarerSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMinutes.coerceAtLeast(0), TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        WayfarerSyncWorker.KEY_ACTION to WayfarerSyncWorker.ACTION_BACKFILL_STEP,
                    ),
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_BACKFILL, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
