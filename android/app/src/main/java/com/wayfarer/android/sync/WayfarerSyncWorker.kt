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
import java.time.Instant
import java.time.ZoneId

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
                ACTION_BOOTSTRAP_RECENT -> {
                    SyncStateStore.markBootstrapStarting(applicationContext)

                    // 先上传本地待同步数据，再拉取近期云端数据，避免“离线记录被覆盖/看不到”。
                    manager.uploadPendingBlocking()

                    val planner = BackfillPlanner()
                    val now = Instant.now()
                    val w = planner.bootstrapRecentRange(now)
                    manager.pullRangeBlocking(startUtc = w.startUtc.toString(), endUtc = w.endUtc.toString())

                    // Bootstrap 完成后，从 w.start 开始向过去回填。
                    val progress = "已同步到 ${fmtDayLocal(w.startUtc)}"
                    SyncStateStore.markBootstrapOk(
                        applicationContext,
                        backfillCursorEndUtc = w.startUtc.toString(),
                        progressText = progress,
                    )
                    SyncStateStore.markBackfillStarting(applicationContext, progressText = progress)

                    // 立即启动第一步回填（温和后台回填会在 worker 内自我调度后续步骤）。
                    WayfarerSyncScheduler.enqueueBackfillStep(applicationContext, delayMinutes = 0)
                }

                ACTION_BACKFILL_STEP -> {
                    if (!SyncStateStore.readBackfillEnabled(applicationContext)) {
                        SyncStateStore.markPaused(applicationContext, reason = "已暂停历史回填")
                        return Result.success()
                    }

                    SyncStateStore.markBackfillStarting(applicationContext)

                    val planner = BackfillPlanner()
                    val now = Instant.now()

                    val cursorEndRaw =
                        SyncStateStore.readBackfillCursorEndUtc(applicationContext)
                            ?: planner.bootstrapRecentRange(now).startUtc.toString()

                    val cursorEnd = runCatching { Instant.parse(cursorEndRaw) }.getOrNull() ?: now

                    val minUtc = AuthStore.readUserCreatedAtUtc(applicationContext)
                        ?.let { s -> runCatching { Instant.parse(s) }.getOrNull() }

                    val w = planner.nextBackfillWindow(cursorEndUtc = cursorEnd, minUtc = minUtc)
                    if (w == null) {
                        SyncStateStore.markBackfillDone(applicationContext)
                        return Result.success()
                    }

                    val pull = manager.pullRangeBlocking(startUtc = w.startUtc.toString(), endUtc = w.endUtc.toString())

                    val nextCursorEnd = w.startUtc
                    val progress = "已同步到 ${fmtDayLocal(nextCursorEnd)}"

                    var emptyStreak = SyncStateStore.readBackfillEmptyStreak(applicationContext)
                    val isEmptyWindow = pull.tracksFetched == 0 && pull.lifeEventsFetched == 0
                    emptyStreak =
                        if (minUtc == null) {
                            if (isEmptyWindow) (emptyStreak + 1) else 0
                        } else {
                            0
                        }

                    // 如果没有 created_at 下界，避免无限扫描：连续一段时间窗都为空时先暂停，允许用户手动继续。
                    if (minUtc == null && emptyStreak >= 12) {
                        SyncStateStore.setBackfillCursorEndUtc(applicationContext, nextCursorEnd.toString())
                        SyncStateStore.setBackfillEmptyStreak(applicationContext, emptyStreak)
                        SyncStateStore.markPaused(applicationContext, reason = "未发现更早数据（可在设置-同步中手动继续）")
                        return Result.success()
                    }

                    SyncStateStore.updateBackfillProgress(
                        applicationContext,
                        cursorEndUtc = nextCursorEnd.toString(),
                        progressText = progress,
                        emptyStreak = emptyStreak,
                    )

                    // 如果达到了下界，标记完成；否则继续温和调度下一步回填。
                    if (minUtc != null && !nextCursorEnd.isAfter(minUtc)) {
                        SyncStateStore.markBackfillDone(applicationContext)
                    } else {
                        WayfarerSyncScheduler.enqueueBackfillStep(applicationContext, delayMinutes = 2)
                    }
                }

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

            // Any successful run clears ERROR state (bootstrap/backfill will set a richer phase).
            if (SyncStateStore.readSnapshot(applicationContext).phase == SyncStateStore.SyncPhase.ERROR) {
                SyncStateStore.markIdle(applicationContext)
            }
            Result.success()
        } catch (e: Throwable) {
            val msg = formatSyncError(action = action, err = e)
            SyncStateStore.markError(applicationContext, msg)

            if (shouldLogout(err = e)) {
                AuthStore.clear(applicationContext)
                SyncStateStore.clear(applicationContext)
                AuthGateStore.reset(applicationContext)
                WayfarerSyncScheduler.cancelAll(applicationContext)
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
        const val ACTION_BOOTSTRAP_RECENT = "bootstrap_recent"
        const val ACTION_BACKFILL_STEP = "backfill_step"
        const val ACTION_UPLOAD = "upload"
        const val ACTION_PULL_24H = "pull_24h"
        const val ACTION_PULL_INCREMENTAL = "pull_incremental"
    }

    private fun fmtDayLocal(instant: Instant): String {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}
