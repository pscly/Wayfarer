package com.wayfarer.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface LifeEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: LifeEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(entity: LifeEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnoreAll(entities: List<LifeEventEntity>): List<Long>

    @Update
    fun update(entity: LifeEventEntity): Int

    @Query(
        "SELECT * FROM life_events_local " +
            "WHERE user_id = :userId AND event_type = :eventType AND end_at IS NULL " +
            "ORDER BY start_at DESC LIMIT 1",
    )
    fun latestActiveRangeByUser(userId: String, eventType: String = LifeEventEntity.EventType.MARK_RANGE): LifeEventEntity?

    @Query(
        "SELECT * FROM life_events_local " +
            "WHERE user_id = :userId AND end_at IS NOT NULL AND sync_status IN (0, 1, 2) " +
            "ORDER BY start_at ASC LIMIT :limit",
    )
    fun listPendingSync(userId: String, limit: Int): List<LifeEventEntity>

    @Query(
        "UPDATE life_events_local SET user_id = :newUserId " +
            "WHERE user_id = :oldUserId",
    )
    fun reassignUser(oldUserId: String, newUserId: String): Int

    @Query(
        "UPDATE life_events_local SET sync_status = :status, last_sync_error = :error, last_synced_at = :syncedAtUtc, updated_at = :updatedAtUtc " +
            "WHERE user_id = :userId AND event_id IN (:eventIds)",
    )
    fun markSyncStatusByEventIds(
        userId: String,
        eventIds: List<String>,
        status: Int,
        error: String?,
        syncedAtUtc: String?,
        updatedAtUtc: String,
    ): Int

    @Query(
        "UPDATE life_events_local SET sync_status = :status, last_sync_error = :error, updated_at = :updatedAtUtc " +
            "WHERE user_id = :userId AND sync_status = 2 AND event_id IN (:eventIds)",
    )
    fun markSyncStatusByEventIdsIfUploading(
        userId: String,
        eventIds: List<String>,
        status: Int,
        error: String?,
        updatedAtUtc: String,
    ): Int

    @Query(
        "SELECT * FROM life_events_local " +
            "WHERE user_id = :userId AND start_at <= :endUtc " +
            "AND (end_at IS NULL OR end_at >= :startUtc) " +
            "ORDER BY start_at ASC LIMIT :limit",
    )
    fun rangeOverlappingByUser(userId: String, startUtc: String, endUtc: String, limit: Int): List<LifeEventEntity>

    @Query(
        "SELECT * FROM life_events_local " +
            "WHERE user_id = :userId AND event_id = :eventId " +
            "LIMIT 1",
    )
    fun getByEventId(userId: String, eventId: String): LifeEventEntity?

    @Query(
        "DELETE FROM life_events_local " +
            "WHERE user_id = :userId AND event_id = :eventId",
    )
    fun deleteByEventId(userId: String, eventId: String): Int

    @Query("DELETE FROM life_events_local")
    fun deleteAll(): Int
}
