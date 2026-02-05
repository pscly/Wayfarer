package com.wayfarer.android.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wayfarer.android.api.ApiException
import com.wayfarer.android.api.AuthStore
import com.wayfarer.android.api.toUserMessageZh
import com.wayfarer.android.ui.UiErrorFormatter
import com.wayfarer.android.ui.auth.AuthGateStore
import java.io.IOException

class WayfarerSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        var result: Result = Result.success()
        val ran =
            SyncRunLock.tryRunExclusive {
                result = runOnce()
            }
        return if (ran) result else Result.success()
    }

    private fun runOnce(): Result {
        // Not logged in => no-op.
        val userId = AuthStore.readUserId(applicationContext)
        val refresh = AuthStore.readRefreshToken(applicationContext)
        if (userId.isNullOrBlank() || refresh.isNullOrBlank()) {
            return Result.success()
        }

        val action = inputData.getString(KEY_ACTION) ?: ACTION_SYNC
        val manager = WayfarerSyncManager(applicationContext)

        return try {
            when (action) {
                ACTION_UPLOAD -> {
                    manager.uploadPendingBlocking()
                }

                ACTION_PULL_24H -> {
                    manager.pullLast24hBlocking()
                }

                ACTION_PULL_INCREMENTAL -> {
                    manager.pullIncrementalBlocking()
                }

                else -> {
                    manager.uploadPendingBlocking()
                    manager.pullIncrementalBlocking()
                }
            }
            Result.success()
        } catch (e: Throwable) {
            val msg = formatSyncError(action = action, err = e)
            SyncStateStore.markError(applicationContext, msg)

            if (shouldLogout(err = e)) {
                AuthStore.clear(applicationContext)
                SyncStateStore.clear(applicationContext)
                AuthGateStore.reset(applicationContext)
                WayfarerSyncScheduler.cancelPeriodicSync(applicationContext)
                return Result.success()
            }

            if (isTransient(err = e)) Result.retry() else Result.failure()
        }
    }

    private fun formatSyncError(action: String, err: Throwable): String {
        val prefix =
            when (action) {
                ACTION_UPLOAD -> "同步上传失败"
                ACTION_PULL_24H -> "同步拉取失败"
                ACTION_PULL_INCREMENTAL -> "同步拉取失败"
                else -> "同步失败"
            }

        if (err is ApiException) {
            val core = err.toUserMessageZh()
            val trace = err.traceId?.trim().takeIf { !it.isNullOrBlank() }
            return if (trace == null) "$prefix：$core" else "$prefix：$core (trace_id=$trace)"
        }

        val core = UiErrorFormatter.format(err).message
        return "$prefix：$core"
    }

    private fun shouldLogout(err: Throwable): Boolean {
        if (err is ApiException && err.statusCode == 401) {
            val code = err.apiCode?.trim().orEmpty()
            if (code == "AUTH_TOKEN_EXPIRED") return true
            if (code == "AUTH_TOKEN_INVALID") return true
            if (code == "AUTH_REFRESH_REUSED") return true
        }
        if (err is IllegalStateException) {
            val msg = err.message?.trim().orEmpty()
            if (msg.contains("Missing refresh token")) return true
            if (msg.contains("Not logged in")) return true
        }
        return false
    }

    private fun isTransient(err: Throwable): Boolean {
        if (err is IOException) return true
        if (err is ApiException) {
            if (err.statusCode == 429) return true
            if (err.statusCode in 500..599) return true
        }
        return false
    }

    companion object {
        const val KEY_ACTION = "action"

        const val ACTION_SYNC = "sync"
        const val ACTION_UPLOAD = "upload"
        const val ACTION_PULL_24H = "pull_24h"
        const val ACTION_PULL_INCREMENTAL = "pull_incremental"
    }
}

