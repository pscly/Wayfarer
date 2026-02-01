package com.wayfarer.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: TrackPointEntity): Long

    @Query("SELECT COUNT(*) FROM track_points_local")
    fun countAll(): Long

    @Query("SELECT COUNT(*) FROM track_points_local WHERE sync_status != 3")
    fun countPendingSync(): Long

    @Query("SELECT * FROM track_points_local ORDER BY recorded_at DESC LIMIT :limit")
    fun latest(limit: Int): List<TrackPointEntity>
}
