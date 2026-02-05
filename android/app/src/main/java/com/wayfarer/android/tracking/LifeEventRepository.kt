package com.wayfarer.android.tracking

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wayfarer.android.api.AuthStore
import com.wayfarer.android.db.LifeEventEntity
import com.wayfarer.android.db.WayfarerDatabaseProvider
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class LifeEventRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dao = WayfarerDatabaseProvider.get(appContext).lifeEventDao()

    private fun resolvedUserId(): String {
        return AuthStore.readUserId(appContext) ?: USER_ID_LOCAL
    }

    private val ioExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun insertAsync(
        entity: LifeEventEntity,
        onDone: (Long) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching { dao.insert(entity) }
            mainHandler.post { result.fold(onDone, onError) }
        }
    }

    fun updateAsync(
        entity: LifeEventEntity,
        onDone: (Int) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching { dao.update(entity) }
            mainHandler.post { result.fold(onDone, onError) }
        }
    }

    fun latestActiveRangeAsync(
        onResult: (LifeEventEntity?) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching { dao.latestActiveRangeByUser(resolvedUserId()) }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun rangeOverlappingAsync(
        startUtc: String,
        endUtc: String,
        limit: Int,
        onResult: (List<LifeEventEntity>) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                dao.rangeOverlappingByUser(
                    userId = resolvedUserId(),
                    startUtc = startUtc,
                    endUtc = endUtc,
                    limit = limit,
                )
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun clearAllAsync(
        onDone: () -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching { dao.deleteAll() }
            mainHandler.post { result.fold({ onDone() }, onError) }
        }
    }

    companion object {
        private const val USER_ID_LOCAL = "local"
    }
}

