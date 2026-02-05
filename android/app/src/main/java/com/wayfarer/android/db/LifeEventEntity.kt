package com.wayfarer.android.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "life_events_local",
    indices = [
        Index(value = ["user_id", "event_id"], unique = true),
        Index(value = ["user_id", "start_at"]),
        Index(value = ["user_id", "sync_status", "start_at"]),
    ],
)
data class LifeEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_id")
    val localId: Long = 0,

    @ColumnInfo(name = "user_id")
    val userId: String,

    // Client-generated UUID for idempotency in sync.
    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    // UTC ISO8601 time, e.g. 2026-01-30T12:34:56Z
    @ColumnInfo(name = "start_at")
    val startAtUtc: String,

    // Nullable for "正在进行中的区间标记"。
    @ColumnInfo(name = "end_at")
    val endAtUtc: String? = null,

    // 用户输入的标签（例如：出门买东西）
    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "note")
    val note: String? = null,

    // Optional coordinates (WGS84).
    @ColumnInfo(name = "latitude")
    val latitudeWgs84: Double? = null,
    @ColumnInfo(name = "longitude")
    val longitudeWgs84: Double? = null,

    // Optional GCJ-02.
    @ColumnInfo(name = "gcj02_latitude")
    val latitudeGcj02: Double? = null,
    @ColumnInfo(name = "gcj02_longitude")
    val longitudeGcj02: Double? = null,

    // JSON string for additional payload (best-effort, optional).
    @ColumnInfo(name = "payload_json")
    val payloadJson: String? = null,

    // Per-row sync state: 0=NEW, 1=QUEUED, 2=UPLOADING, 3=ACKED, 4=FAILED.
    @ColumnInfo(name = "sync_status")
    val syncStatus: Int = 0,
    @ColumnInfo(name = "last_sync_error")
    val lastSyncError: String? = null,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAtUtc: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAtUtc: String,
    @ColumnInfo(name = "updated_at")
    val updatedAtUtc: String,
) {
    object EventType {
        const val MARK_POINT = "MARK_POINT"
        const val MARK_RANGE = "MARK_RANGE"
    }

    object SyncStatus {
        const val NEW = 0
        const val QUEUED = 1
        const val UPLOADING = 2
        const val ACKED = 3
        const val FAILED = 4
    }
}

