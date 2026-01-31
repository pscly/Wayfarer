package com.wayfarer.android.sync

/**
 * Models for the backend `/v1/tracks/batch` response.
 *
 * JSON keys (parsing out of scope here):
 * - `accepted_ids` -> [acceptedIds]
 * - `rejected` -> [rejected]
 * - `client_point_id` -> [RejectedItem.clientPointId]
 * - `reason_code` -> [RejectedItem.reasonCode]
 */
data class TracksBatchResponse(
    val acceptedIds: List<String>,
    val rejected: List<RejectedItem>,
)

data class RejectedItem(
    val clientPointId: String,
    val reasonCode: String,
    val message: String? = null,
)
