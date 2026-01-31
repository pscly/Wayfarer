package com.wayfarer.android.sync

import com.wayfarer.android.db.TrackPointEntity

object SyncReceiptApplier {
    /**
     * Applies `/v1/tracks/batch` receipt semantics to local points, matching on `client_point_id`.
     *
     * - If `client_point_id` is in `accepted_ids` => sync_status=ACKED
     * - If `client_point_id` is in `rejected` => sync_status=FAILED + last_sync_error
     * - Otherwise unchanged
     *
     * Pure transformation: no I/O, preserves original list order.
     */
    fun applyTrackPointReceipt(
        points: List<TrackPointEntity>,
        receipt: TracksBatchResponse,
    ): List<TrackPointEntity> {
        if (points.isEmpty()) return points

        val accepted = receipt.acceptedIds.toHashSet()
        val rejectedById = receipt.rejected.associateBy { it.clientPointId }

        return points.map { point ->
            val rejected = rejectedById[point.clientPointId]
            when {
                rejected != null -> {
                    point.copy(
                        syncStatus = TrackPointEntity.SyncStatus.FAILED,
                        lastSyncError = formatRejectedError(rejected),
                    )
                }

                accepted.contains(point.clientPointId) -> {
                    point.copy(
                        syncStatus = TrackPointEntity.SyncStatus.ACKED,
                        lastSyncError = null,
                    )
                }

                else -> point
            }
        }
    }

    private fun formatRejectedError(item: RejectedItem): String {
        val msg = item.message?.trim().orEmpty()
        return if (msg.isBlank()) item.reasonCode else "${item.reasonCode}: $msg"
    }
}
