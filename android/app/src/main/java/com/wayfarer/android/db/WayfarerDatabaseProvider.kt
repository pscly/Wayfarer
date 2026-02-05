package com.wayfarer.android.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
                .addMigrations(MIGRATION_1_2)
                // This DB is local-first and currently not schema-migrated.
                .fallbackToDestructiveMigration()
                .build()
            instance = db
            db
        }
    }

    private val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points_local ADD COLUMN step_count INTEGER")
                db.execSQL("ALTER TABLE track_points_local ADD COLUMN step_delta INTEGER")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS life_events_local (" +
                        "local_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "user_id TEXT NOT NULL, " +
                        "event_id TEXT NOT NULL, " +
                        "event_type TEXT NOT NULL, " +
                        "start_at TEXT NOT NULL, " +
                        "end_at TEXT, " +
                        "label TEXT NOT NULL, " +
                        "note TEXT, " +
                        "latitude REAL, " +
                        "longitude REAL, " +
                        "gcj02_latitude REAL, " +
                        "gcj02_longitude REAL, " +
                        "payload_json TEXT, " +
                        "sync_status INTEGER NOT NULL DEFAULT 0, " +
                        "last_sync_error TEXT, " +
                        "last_synced_at TEXT, " +
                        "created_at TEXT NOT NULL, " +
                        "updated_at TEXT NOT NULL" +
                        ")",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_life_events_local_user_id_event_id " +
                        "ON life_events_local(user_id, event_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_life_events_local_user_id_start_at " +
                        "ON life_events_local(user_id, start_at)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_life_events_local_user_id_sync_status_start_at " +
                        "ON life_events_local(user_id, sync_status, start_at)",
                )
            }
        }
}
