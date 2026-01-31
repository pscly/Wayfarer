package com.wayfarer.android.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_points_local",
    indices = [
        Index(value = ["user_id", "client_point_id"], unique = true),
        Index(value = ["user_id", "recorded_at"]),
        Index(value = ["user_id", "sync_status", "recorded_at"]),
    ],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_id")
    val localId: Long = 0,

    @ColumnInfo(name = "user_id")
    val userId: String,

    // Idempotency key (client-generated UUID)
    @ColumnInfo(name = "client_point_id")
    val clientPointId: String,

    // recorded_at(UTC) in ISO8601, e.g. 2026-01-30T12:34:56Z
    @ColumnInfo(name = "recorded_at")
    val recordedAtUtc: String,

    // WGS84 coordinates
    @ColumnInfo(name = "latitude")
    val latitudeWgs84: Double,
    @ColumnInfo(name = "longitude")
    val longitudeWgs84: Double,

    // Optional GCJ-02 coordinates
    @ColumnInfo(name = "gcj02_latitude")
    val latitudeGcj02: Double? = null,
    @ColumnInfo(name = "gcj02_longitude")
    val longitudeGcj02: Double? = null,

    // Coordinate metadata
    @ColumnInfo(name = "coord_source")
    val coordSource: String,
    @ColumnInfo(name = "coord_transform_status")
    val coordTransformStatus: String,

    // Location quality & motion
    @ColumnInfo(name = "accuracy")
    val accuracyM: Double? = null,
    @ColumnInfo(name = "altitude")
    val altitudeM: Double? = null,
    @ColumnInfo(name = "speed")
    val speedMps: Double? = null,
    @ColumnInfo(name = "bearing")
    val bearingDeg: Double? = null,

    // Weak dedupe helper
    @ColumnInfo(name = "geom_hash")
    val geomHash: String,

    // Mirror/compat fields
    @ColumnInfo(name = "weather_snapshot_json")
    val weatherSnapshotJson: String? = null,
    @ColumnInfo(name = "server_track_point_id")
    val serverTrackPointId: Long? = null,

    // Per-row sync state
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
    object CoordSource {
        const val GPS = "GPS"
        const val AMAP = "AMap"
        const val UNKNOWN = "Unknown"
    }

    object CoordTransformStatus {
        const val OK = "OK"
        const val OUTSIDE_CN = "OUTSIDE_CN"
        const val BYPASS = "BYPASS"
        const val FAILED = "FAILED"
    }

    // Matches backend sync spec: 0=NEW, 1=QUEUED, 2=UPLOADING, 3=ACKED, 4=FAILED.
    object SyncStatus {
        const val NEW = 0
        const val QUEUED = 1
        const val UPLOADING = 2
        const val ACKED = 3
        const val FAILED = 4
    }
}
