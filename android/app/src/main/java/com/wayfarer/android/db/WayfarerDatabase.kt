package com.wayfarer.android.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackPointEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class WayfarerDatabase : RoomDatabase() {
    abstract fun trackPointDao(): TrackPointDao
}
