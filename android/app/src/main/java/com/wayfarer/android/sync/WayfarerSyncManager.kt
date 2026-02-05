package com.wayfarer.android.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wayfarer.android.api.ApiException
import com.wayfarer.android.api.AuthStore
import com.wayfarer.android.api.WayfarerApiClient
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.db.WayfarerDatabaseProvider
import com.wayfarer.android.tracking.GeomHash
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class WayfarerSyncManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dao = WayfarerDatabaseProvider.get(appContext).trackPointDao()
    private val api = WayfarerApiClient(appContext)

    private val ioExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class LoginResult(
        val userInfo: WayfarerApiClient.UserInfo,
    )

    data class UploadResult(
        val attempted: Int,
        val sent: Int,
        val accepted: Int,
        val rejected: Int,
        val locallyRejected: Int,
    )

    data class PullResult(
        val fetched: Int,
        val inserted: Int,
    )

    fun logout() {
        AuthStore.clear(appContext)
        SyncStateStore.clear(appContext)
    }

    fun testConnectionAsync(
        onResult: (Boolean) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                api.healthCheck()
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun loginAsync(
        username: String,
        password: String,
        onResult: (LoginResult) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                val token = api.login(username = username, password = password)
                val refresh = token.refreshToken
                if (refresh.isNullOrBlank()) {
                    throw IllegalStateException("Missing refresh_token in login response")
                }
                AuthStore.writeTokens(appContext, accessToken = token.accessToken, refreshToken = refresh)

                val me = api.me(accessToken = token.accessToken)
                AuthStore.writeUserInfo(appContext, userId = me.userId, username = me.username)

                // Bind offline points to the authenticated user for subsequent sync.
                runCatching { dao.reassignUser(oldUserId = USER_ID_LOCAL, newUserId = me.userId) }

                LoginResult(userInfo = me)
            }

            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun registerAndLoginAsync(
        username: String,
        email: String?,
        password: String,
        onResult: (LoginResult) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                api.register(username = username, email = email, password = password)
                val token = api.login(username = username, password = password)
                val refresh = token.refreshToken
                if (refresh.isNullOrBlank()) {
                    throw IllegalStateException("Missing refresh_token in login response")
                }
                AuthStore.writeTokens(appContext, accessToken = token.accessToken, refreshToken = refresh)

                val me = api.me(accessToken = token.accessToken)
                AuthStore.writeUserInfo(appContext, userId = me.userId, username = me.username)
                runCatching { dao.reassignUser(oldUserId = USER_ID_LOCAL, newUserId = me.userId) }

                LoginResult(userInfo = me)
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun uploadPendingBlocking(maxItems: Int = 100): UploadResult {
        return SyncRunLock.runExclusive { uploadPending(maxItems = maxItems) }
    }

    fun pullRangeBlocking(startUtc: String, endUtc: String): PullResult {
        return SyncRunLock.runExclusive { pullRange(startUtc = startUtc, endUtc = endUtc) }
    }

    fun pullLast24hBlocking(): PullResult {
        val end = Instant.now()
        val start = end.minus(Duration.ofHours(24))
        return pullRangeBlocking(startUtc = start.toString(), endUtc = end.toString())
    }

    fun pullIncrementalBlocking(
        defaultHours: Long = 2,
        overlapMinutes: Long = 10,
    ): PullResult {
        val end = Instant.now()
        val lastPullMs = SyncStateStore.readLastPullAtMs(appContext)
        val start =
            if (lastPullMs != null && lastPullMs > 0) {
                Instant.ofEpochMilli(lastPullMs).minus(Duration.ofMinutes(overlapMinutes))
            } else {
                end.minus(Duration.ofHours(defaultHours))
            }
        return pullRangeBlocking(startUtc = start.toString(), endUtc = end.toString())
    }

    fun uploadPendingAsync(
        maxItems: Int = 100,
        onResult: (UploadResult) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                uploadPendingBlocking(maxItems = maxItems)
            }

            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun pullLast24hAsync(
        onResult: (PullResult) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        val end = Instant.now()
        val start = end.minus(Duration.ofHours(24))
        pullRangeAsync(startUtc = start.toString(), endUtc = end.toString(), onResult = onResult, onError = onError)
    }

    fun pullRangeAsync(
        startUtc: String,
        endUtc: String,
        onResult: (PullResult) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                pullRangeBlocking(startUtc = startUtc, endUtc = endUtc)
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    private fun <T> authenticated(block: (accessToken: String) -> T): T {
        val access = AuthStore.readAccessToken(appContext)
        if (!access.isNullOrBlank()) {
            try {
                return block(access)
            } catch (e: ApiException) {
                if (e.statusCode != 401) throw e
            }
        }

        val refresh = AuthStore.readRefreshToken(appContext)
            ?: throw IllegalStateException("Missing refresh token")
        val refreshed = api.refresh(refreshToken = refresh)
        val newRefresh = refreshed.refreshToken
        if (newRefresh.isNullOrBlank()) {
            throw IllegalStateException("Missing refresh_token in refresh response")
        }
        AuthStore.writeTokens(appContext, accessToken = refreshed.accessToken, refreshToken = newRefresh)
        return block(refreshed.accessToken)
    }

    private fun uploadPending(maxItems: Int): UploadResult {
        val userId = AuthStore.readUserId(appContext)
            ?: throw IllegalStateException("Not logged in")

        val now = Instant.now().toString()

        val pending = dao.listPendingSync(userId = userId, limit = maxItems)
        if (pending.isEmpty()) {
            return UploadResult(
                attempted = 0,
                sent = 0,
                accepted = 0,
                rejected = 0,
                locallyRejected = 0,
            )
        }

        val valid = ArrayList<TrackPointEntity>(pending.size)
        val invalidIds = ArrayList<String>()
        for (p in pending) {
            val acc = p.accuracyM
            if (acc == null || acc <= 0.0) {
                invalidIds.add(p.clientPointId)
            } else {
                valid.add(p)
            }
        }

        if (invalidIds.isNotEmpty()) {
            dao.markSyncStatusByClientIds(
                userId = userId,
                clientPointIds = invalidIds,
                status = TrackPointEntity.SyncStatus.FAILED,
                error = "LOCAL_VALIDATION_FAILED: missing_accuracy",
                syncedAtUtc = now,
                updatedAtUtc = now,
            )
        }

        if (valid.isEmpty()) {
            return UploadResult(
                attempted = pending.size,
                sent = 0,
                accepted = 0,
                rejected = 0,
                locallyRejected = invalidIds.size,
            )
        }

        val sendingIds = valid.map { it.clientPointId }
        dao.markSyncStatusByClientIds(
            userId = userId,
            clientPointIds = sendingIds,
            status = TrackPointEntity.SyncStatus.UPLOADING,
            error = null,
            syncedAtUtc = null,
            updatedAtUtc = now,
        )

        val itemsJson = JSONArray()
        for (p in valid) {
            val obj = JSONObject()
                .put("client_point_id", p.clientPointId)
                .put("recorded_at", p.recordedAtUtc)
                .put("latitude", p.latitudeWgs84)
                .put("longitude", p.longitudeWgs84)
                .put("accuracy", p.accuracyM)
                .put("coord_source", p.coordSource)
                .put("coord_transform_status", p.coordTransformStatus)

            if (p.latitudeGcj02 != null) obj.put("gcj02_latitude", p.latitudeGcj02)
            if (p.longitudeGcj02 != null) obj.put("gcj02_longitude", p.longitudeGcj02)
            if (p.altitudeM != null) obj.put("altitude", p.altitudeM)
            if (p.speedMps != null) obj.put("speed", p.speedMps)

            itemsJson.put(obj)
        }

        try {
            val receipt = authenticated { token ->
                api.tracksBatchUpload(accessToken = token, items = itemsJson)
            }

            val acceptedSet = receipt.acceptedIds.toHashSet()
            val rejectedById = receipt.rejected.associateBy { it.clientPointId }

            val acceptedIds = receipt.acceptedIds.distinct().filter { it.isNotBlank() }
            if (acceptedIds.isNotEmpty()) {
                dao.markSyncStatusByClientIds(
                    userId = userId,
                    clientPointIds = acceptedIds,
                    status = TrackPointEntity.SyncStatus.ACKED,
                    error = null,
                    syncedAtUtc = now,
                    updatedAtUtc = now,
                )
            }

            var rejectedCount = 0
            for (rej in receipt.rejected) {
                val id = rej.clientPointId.trim()
                if (id.isBlank()) continue
                rejectedCount += 1
                dao.markSyncStatusByClientIds(
                    userId = userId,
                    clientPointIds = listOf(id),
                    status = TrackPointEntity.SyncStatus.FAILED,
                    error = formatRejectedError(rej),
                    syncedAtUtc = now,
                    updatedAtUtc = now,
                )
            }

            // Safety: anything we sent but didn't get a receipt for should not stay UPLOADING forever.
            val missing = ArrayList<String>()
            for (id in sendingIds) {
                if (acceptedSet.contains(id)) continue
                if (rejectedById.containsKey(id)) continue
                missing.add(id)
            }
            if (missing.isNotEmpty()) {
                dao.markSyncStatusByClientIds(
                    userId = userId,
                    clientPointIds = missing,
                    status = TrackPointEntity.SyncStatus.FAILED,
                    error = "SYNC_NO_RECEIPT",
                    syncedAtUtc = now,
                    updatedAtUtc = now,
                )
            }

            SyncStateStore.markUploadOk(appContext)

            return UploadResult(
                attempted = pending.size,
                sent = valid.size,
                accepted = acceptedIds.size,
                rejected = rejectedCount,
                locallyRejected = invalidIds.size,
            )
        } catch (e: Throwable) {
            // Revert UPLOADING -> QUEUED so user can retry.
            val msg = e.message ?: e.toString()
            runCatching {
                dao.markSyncStatusByClientIdsIfUploading(
                    userId = userId,
                    clientPointIds = sendingIds,
                    status = TrackPointEntity.SyncStatus.QUEUED,
                    error = "SYNC_UPLOAD_FAILED: ${msg.take(200)}",
                    updatedAtUtc = now,
                )
            }
            SyncStateStore.markError(appContext, msg)
            throw e
        }
    }

    private fun pullRange(startUtc: String, endUtc: String): PullResult {
        val userId = AuthStore.readUserId(appContext)
            ?: throw IllegalStateException("Not logged in")

        val now = Instant.now().toString()
        var offset = 0
        val limit = 5000
        var fetched = 0
        var inserted = 0

        while (true) {
            val page = authenticated { token ->
                api.tracksQuery(
                    accessToken = token,
                    startUtc = startUtc,
                    endUtc = endUtc,
                    limit = limit,
                    offset = offset,
                )
            }
            if (page.isEmpty()) break

            fetched += page.size

            val entities = page.map { it ->
                TrackPointEntity(
                    userId = userId,
                    clientPointId = it.clientPointId,
                    recordedAtUtc = it.recordedAtUtc,
                    latitudeWgs84 = it.latitude,
                    longitudeWgs84 = it.longitude,
                    latitudeGcj02 = it.gcj02Latitude,
                    longitudeGcj02 = it.gcj02Longitude,
                    coordSource = TrackPointEntity.CoordSource.UNKNOWN,
                    coordTransformStatus =
                        if (it.gcj02Latitude != null && it.gcj02Longitude != null) {
                            TrackPointEntity.CoordTransformStatus.OK
                        } else {
                            TrackPointEntity.CoordTransformStatus.BYPASS
                        },
                    accuracyM = it.accuracy,
                    altitudeM = null,
                    speedMps = null,
                    bearingDeg = null,
                    geomHash = GeomHash.sha256LatLonWgs84(it.latitude, it.longitude),
                    weatherSnapshotJson = null,
                    serverTrackPointId = null,
                    syncStatus = TrackPointEntity.SyncStatus.ACKED,
                    lastSyncError = null,
                    lastSyncedAtUtc = now,
                    createdAtUtc = it.recordedAtUtc,
                    updatedAtUtc = it.recordedAtUtc,
                )
            }

            val insertResults = dao.insertIgnoreAll(entities)
            inserted += insertResults.count { id -> id != -1L }

            // Mark any existing local rows as ACKED as well.
            val ids = page.map { it.clientPointId }
            if (ids.isNotEmpty()) {
                dao.markSyncStatusByClientIds(
                    userId = userId,
                    clientPointIds = ids,
                    status = TrackPointEntity.SyncStatus.ACKED,
                    error = null,
                    syncedAtUtc = now,
                    updatedAtUtc = now,
                )
            }

            offset += page.size
            if (page.size < limit) break
        }

        SyncStateStore.markPullOk(appContext)
        return PullResult(fetched = fetched, inserted = inserted)
    }

    private fun formatRejectedError(item: RejectedItem): String {
        val msg = item.message?.trim().orEmpty()
        return if (msg.isBlank()) item.reasonCode else "${item.reasonCode}: $msg"
    }

    companion object {
        private const val USER_ID_LOCAL = "local"
    }
}
