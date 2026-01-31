package com.wayfarer.android.tracking

import android.content.Context
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

    fun insertAsync(entity: TrackPointEntity) {
        ioExecutor.execute {
            runCatching {
                dao.insert(entity)
            }
        }
    }
}
