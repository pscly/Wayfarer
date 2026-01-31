package com.wayfarer.android.db

import android.content.Context
import androidx.room.Room

object WayfarerDatabaseProvider {
    @Volatile
    private var instance: WayfarerDatabase? = null

    fun get(context: Context): WayfarerDatabase {
        val existing = instance
        if (existing != null) return existing

        return synchronized(this) {
            val again = instance
            if (again != null) return again

            val db = Room.databaseBuilder(
                context.applicationContext,
                WayfarerDatabase::class.java,
                "wayfarer.db",
            )
                // This DB is local-first and currently not schema-migrated.
                .fallbackToDestructiveMigration()
                .build()
            instance = db
            db
        }
    }
}
