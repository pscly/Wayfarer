package com.wayfarer.android.tracking

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.db.WayfarerDatabaseProvider
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class TrackPointRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dao = WayfarerDatabaseProvider.get(appContext).trackPointDao()

    private val ioExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class TrackPointStats(
        val totalCount: Long,
        val pendingSyncCount: Long,
        val latestRecordedAtUtc: String?,
    )

    fun insertAsync(entity: TrackPointEntity) {
        ioExecutor.execute {
            runCatching {
                dao.insert(entity)
            }
        }
    }

    fun statsAsync(
        onResult: (TrackPointStats) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                val total = dao.countAll()
                val pending = dao.countPendingSync()
                val latest = dao.latest(1).firstOrNull()?.recordedAtUtc
                TrackPointStats(
                    totalCount = total,
                    pendingSyncCount = pending,
                    latestRecordedAtUtc = latest,
                )
            }

            mainHandler.post {
                result.fold(onResult, onError)
            }
        }
    }

    fun latestPointsAsync(
        limit: Int,
        onResult: (List<TrackPointEntity>) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                dao.latest(limit)
            }

            mainHandler.post {
                result.fold(onResult, onError)
            }
        }
    }

    fun rangePointsAsync(
        startUtc: String,
        endUtc: String,
        limit: Int,
        onResult: (List<TrackPointEntity>) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                dao.range(startUtc = startUtc, endUtc = endUtc, limit = limit)
            }

            mainHandler.post {
                result.fold(onResult, onError)
            }
        }
    }

    fun clearAllAsync(
        onDone: () -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                dao.deleteAll()
            }

            mainHandler.post {
                result.fold({ onDone() }, onError)
            }
        }
    }
}
