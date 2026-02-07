package com.wayfarer.android.ui.util

import com.wayfarer.android.db.TrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.UUID

class TrackStatsMathTest {
    private fun tp(
        recordedAtUtc: String,
        stepDelta: Long? = null,
        speedMps: Double? = null,
    ): TrackPointEntity {
        return TrackPointEntity(
            userId = "u",
            clientPointId = UUID.randomUUID().toString(),
            recordedAtUtc = recordedAtUtc,
            latitudeWgs84 = 0.0,
            longitudeWgs84 = 0.0,
            coordSource = TrackPointEntity.CoordSource.UNKNOWN,
            coordTransformStatus = TrackPointEntity.CoordTransformStatus.BYPASS,
            accuracyM = 5.0,
            altitudeM = null,
            speedMps = speedMps,
            bearingDeg = null,
            stepCount = null,
            stepDelta = stepDelta,
            geomHash = "h",
            weatherSnapshotJson = null,
            serverTrackPointId = null,
            syncStatus = TrackPointEntity.SyncStatus.NEW,
            lastSyncError = null,
            lastSyncedAtUtc = null,
            createdAtUtc = recordedAtUtc,
            updatedAtUtc = recordedAtUtc,
        )
    }

    @Test
    fun computeSteps_sumsNonNegativeStepDelta() {
        val points = listOf(
            tp("2026-02-07T00:00:10Z", stepDelta = 5),
            tp("2026-02-07T00:00:20Z", stepDelta = -1),
            tp("2026-02-07T00:00:30Z", stepDelta = 0),
            tp("2026-02-07T00:00:40Z", stepDelta = null),
        )
        assertEquals(5L, computeSteps(points))
    }

    @Test
    fun computeActiveMinutes_countsOncePerMinute_bySteps() {
        val zone = ZoneId.of("UTC")
        val points = listOf(
            tp("2026-02-07T00:00:10Z", stepDelta = 3),
            tp("2026-02-07T00:00:50Z", stepDelta = 2),
            tp("2026-02-07T00:01:05Z", stepDelta = 1),
        )
        assertEquals(2, computeActiveMinutes(points, zone))
    }

    @Test
    fun computeActiveMinutes_fallsBackToSpeedThreshold() {
        val zone = ZoneId.of("UTC")
        val points = listOf(
            tp("2026-02-07T00:05:10Z", stepDelta = 0, speedMps = 0.9),
            tp("2026-02-07T00:06:10Z", stepDelta = 0, speedMps = 0.1),
        )
        assertEquals(1, computeActiveMinutes(points, zone))
    }

    @Test
    fun formatActiveMinutes_formatsHoursAndMinutes() {
        assertEquals("0分", formatActiveMinutes(0))
        assertEquals("59分", formatActiveMinutes(59))
        assertEquals("1小时", formatActiveMinutes(60))
        assertEquals("1小时1分", formatActiveMinutes(61))
    }
}

