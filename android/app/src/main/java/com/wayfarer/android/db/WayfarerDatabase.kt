package com.wayfarer.android.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackPointEntity::class,
        LifeEventEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class WayfarerDatabase : RoomDatabase() {
    abstract fun trackPointDao(): TrackPointDao
    abstract fun lifeEventDao(): LifeEventDao
}
