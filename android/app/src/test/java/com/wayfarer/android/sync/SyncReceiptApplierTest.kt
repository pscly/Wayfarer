package com.wayfarer.android.sync

import com.wayfarer.android.db.TrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncReceiptApplierTest {
    @Test
    fun acceptedIds_setsAckedAndClearsError() {
        val p1 = tp(clientPointId = "a", syncStatus = TrackPointEntity.SyncStatus.UPLOADING, lastSyncError = "old")
        val p2 = tp(clientPointId = "b", syncStatus = TrackPointEntity.SyncStatus.QUEUED)

        val receipt = TracksBatchResponse(
            acceptedIds = listOf("a"),
            rejected = emptyList(),
        )

        val out = SyncReceiptApplier.applyTrackPointReceipt(listOf(p1, p2), receipt)

        assertEquals(TrackPointEntity.SyncStatus.ACKED, out[0].syncStatus)
        assertNull(out[0].lastSyncError)
        assertEquals(p2, out[1])
    }

    @Test
    fun rejected_setsFailedAndIncludesReason() {
        val p = tp(clientPointId = "x", syncStatus = TrackPointEntity.SyncStatus.UPLOADING)

        val receipt = TracksBatchResponse(
            acceptedIds = emptyList(),
            rejected = listOf(
                RejectedItem(
                    clientPointId = "x",
                    reasonCode = "bad_coord",
                    message = "outside bounds",
                ),
            ),
        )

        val out = SyncReceiptApplier.applyTrackPointReceipt(listOf(p), receipt)
        assertEquals(TrackPointEntity.SyncStatus.FAILED, out[0].syncStatus)
        assertEquals("bad_coord: outside bounds", out[0].lastSyncError)
    }

    @Test
    fun neitherAcceptedNorRejected_leavesUnchanged() {
        val p = tp(clientPointId = "n", syncStatus = TrackPointEntity.SyncStatus.QUEUED)

        val receipt = TracksBatchResponse(
            acceptedIds = listOf("other"),
            rejected = listOf(RejectedItem(clientPointId = "other2", reasonCode = "nope")),
        )

        val out = SyncReceiptApplier.applyTrackPointReceipt(listOf(p), receipt)
        assertEquals(p, out[0])
    }

    @Test
    fun applyingSameReceiptTwice_isIdempotent() {
        val p1 = tp(clientPointId = "a", syncStatus = TrackPointEntity.SyncStatus.UPLOADING)
        val p2 = tp(clientPointId = "b", syncStatus = TrackPointEntity.SyncStatus.UPLOADING)

        val receipt = TracksBatchResponse(
            acceptedIds = listOf("a"),
            rejected = listOf(RejectedItem(clientPointId = "b", reasonCode = "oops")),
        )

        val once = SyncReceiptApplier.applyTrackPointReceipt(listOf(p1, p2), receipt)
        val twice = SyncReceiptApplier.applyTrackPointReceipt(once, receipt)
        assertEquals(once, twice)
    }

    @Test
    fun rejectedWinsOverAccepted_whenIdAppearsInBoth() {
        val p = tp(clientPointId = "dup", syncStatus = TrackPointEntity.SyncStatus.UPLOADING)

        val receipt = TracksBatchResponse(
            acceptedIds = listOf("dup"),
            rejected = listOf(RejectedItem(clientPointId = "dup", reasonCode = "bad")),
        )

        val out = SyncReceiptApplier.applyTrackPointReceipt(listOf(p), receipt)
        assertEquals(TrackPointEntity.SyncStatus.FAILED, out[0].syncStatus)
    }

    private fun tp(
        clientPointId: String,
        syncStatus: Int = TrackPointEntity.SyncStatus.NEW,
        lastSyncError: String? = null,
    ): TrackPointEntity {
        return TrackPointEntity(
            localId = 1,
            userId = "u",
            clientPointId = clientPointId,
            recordedAtUtc = "2026-01-30T12:34:56Z",
            latitudeWgs84 = 0.0,
            longitudeWgs84 = 0.0,
            coordSource = TrackPointEntity.CoordSource.GPS,
            coordTransformStatus = TrackPointEntity.CoordTransformStatus.OK,
            geomHash = "h",
            syncStatus = syncStatus,
            lastSyncError = lastSyncError,
            createdAtUtc = "2026-01-30T12:34:56Z",
            updatedAtUtc = "2026-01-30T12:34:56Z",
        )
    }
}
