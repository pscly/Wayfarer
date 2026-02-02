package com.wayfarer.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: TrackPointEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(entity: TrackPointEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnoreAll(entities: List<TrackPointEntity>): List<Long>

    @Update
    fun update(entity: TrackPointEntity): Int

    @Update
    fun updateAll(entities: List<TrackPointEntity>): Int

    @Query("SELECT COUNT(*) FROM track_points_local")
    fun countAll(): Long

    @Query("SELECT COUNT(*) FROM track_points_local WHERE user_id = :userId")
    fun countAllByUser(userId: String): Long

    @Query("SELECT COUNT(*) FROM track_points_local WHERE sync_status IN (0, 1, 2)")
    fun countPendingSync(): Long

    @Query("SELECT COUNT(*) FROM track_points_local WHERE user_id = :userId AND sync_status IN (0, 1, 2)")
    fun countPendingSyncByUser(userId: String): Long

    @Query(
        "SELECT * FROM track_points_local " +
            "WHERE user_id = :userId AND sync_status IN (0, 1, 2) " +
            "ORDER BY recorded_at ASC LIMIT :limit",
    )
    fun listPendingSync(userId: String, limit: Int): List<TrackPointEntity>

    @Query(
        "UPDATE track_points_local SET user_id = :newUserId " +
            "WHERE user_id = :oldUserId",
    )
    fun reassignUser(oldUserId: String, newUserId: String): Int

    @Query(
        "UPDATE track_points_local SET sync_status = :status, last_sync_error = :error, last_synced_at = :syncedAtUtc, updated_at = :updatedAtUtc " +
            "WHERE user_id = :userId AND client_point_id IN (:clientPointIds)",
    )
    fun markSyncStatusByClientIds(
        userId: String,
        clientPointIds: List<String>,
        status: Int,
        error: String?,
        syncedAtUtc: String?,
        updatedAtUtc: String,
    ): Int

    @Query(
        "UPDATE track_points_local SET sync_status = :status, last_sync_error = :error, updated_at = :updatedAtUtc " +
            "WHERE user_id = :userId AND sync_status = 2 AND client_point_id IN (:clientPointIds)",
    )
    fun markSyncStatusByClientIdsIfUploading(
        userId: String,
        clientPointIds: List<String>,
        status: Int,
        error: String?,
        updatedAtUtc: String,
    ): Int

    @Query(
        "SELECT * FROM track_points_local " +
            "WHERE recorded_at >= :startUtc AND recorded_at <= :endUtc " +
            "ORDER BY recorded_at ASC LIMIT :limit",
    )
    fun range(startUtc: String, endUtc: String, limit: Int): List<TrackPointEntity>

    @Query(
        "SELECT * FROM track_points_local " +
            "WHERE user_id = :userId AND recorded_at >= :startUtc AND recorded_at <= :endUtc " +
            "ORDER BY recorded_at ASC LIMIT :limit",
    )
    fun rangeByUser(userId: String, startUtc: String, endUtc: String, limit: Int): List<TrackPointEntity>

    @Query("DELETE FROM track_points_local")
    fun deleteAll(): Int

    @Query("SELECT * FROM track_points_local ORDER BY recorded_at DESC LIMIT :limit")
    fun latest(limit: Int): List<TrackPointEntity>

    @Query("SELECT * FROM track_points_local WHERE user_id = :userId ORDER BY recorded_at DESC LIMIT :limit")
    fun latestByUser(userId: String, limit: Int): List<TrackPointEntity>
}
