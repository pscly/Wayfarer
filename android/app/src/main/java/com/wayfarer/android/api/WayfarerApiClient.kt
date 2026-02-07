package com.wayfarer.android.api

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

import com.wayfarer.android.sync.RejectedItem
import com.wayfarer.android.sync.TracksBatchResponse

class WayfarerApiClient(
    context: Context,
) {
    private val appContext = context.applicationContext

    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    data class TokenResponse(
        val accessToken: String,
        val expiresIn: Int,
        val refreshToken: String?,
    )

    data class UserInfo(
        val userId: String,
        val username: String,
        val email: String?,
        val isAdmin: Boolean,
        val createdAt: String?,
    )

    data class TrackQueryItem(
        val clientPointId: String,
        val recordedAtUtc: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double?,
        val gcj02Latitude: Double?,
        val gcj02Longitude: Double?,
        val stepCount: Long?,
        val stepDelta: Long?,
        val isDirty: Boolean,
    )

    data class StepsDailyItem(
        val day: String, // YYYY-MM-DD（按 tz 分桶）
        val steps: Long,
    )

    data class StepsHourlyItem(
        val hourStart: String, // ISO8601（按 tz 分桶，可能带 offset）
        val steps: Long,
    )

    data class LifeEventListItem(
        val id: String,
        val eventType: String,
        val startAtUtc: String,
        val endAtUtc: String,
        val locationName: String?,
        val manualNote: String?,
        val latitude: Double?,
        val longitude: Double?,
        val gcj02Latitude: Double?,
        val gcj02Longitude: Double?,
        val payloadJson: JSONObject?,
        val createdAtUtc: String?,
        val updatedAtUtc: String?,
    )

    private fun baseUrl(): String = ServerConfigStore.readBaseUrl(appContext)

    private fun url(path: String): String {
        val b = baseUrl().trim().trimEnd('/')
        val p = if (path.startsWith('/')) path else "/$path"
        return b + p
    }

    private fun requestJson(
        method: String,
        path: String,
        accessToken: String? = null,
        body: JSONObject? = null,
    ): JSONObject {
        val requestTraceId = UUID.randomUUID().toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val rb = when {
            body != null -> body.toString().toRequestBody(mediaType)
            method == "POST" || method == "PUT" || method == "PATCH" ->
                "{}".toRequestBody(mediaType)
            else -> null
        }

        val builder = Request.Builder().url(url(path))
        if (!accessToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        builder.header("Accept", "application/json")
        builder.header("X-Trace-Id", requestTraceId)

        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(rb!!)
            "PUT" -> builder.put(rb!!)
            "PATCH" -> builder.patch(rb!!)
            "DELETE" -> builder.delete(rb)
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }

        val req = builder.build()
        http.newCall(req).execute().use { res ->
            val bodyText = res.body?.string()
            if (!res.isSuccessful) {
                val env = ApiErrorParser.parse(bodyText)
                val traceId = env?.traceId?.trim().takeIf { !it.isNullOrBlank() }
                    ?: res.header("X-Trace-Id")?.trim().takeIf { !it.isNullOrBlank() }
                    ?: requestTraceId
                throw ApiException(
                    statusCode = res.code,
                    responseBody = bodyText,
                    message = "HTTP ${res.code} for $path",
                    requestPath = path,
                    apiCode = env?.code,
                    apiMessage = env?.message,
                    traceId = traceId,
                    serverBaseUrl = baseUrl(),
                )
            }
            val text = bodyText?.trim().orEmpty()
            if (text.isBlank()) return JSONObject()
            return JSONObject(text)
        }
    }

    fun healthCheck(): Boolean {
        // Health endpoint lives at root, not under /v1.
        val req = Request.Builder()
            .url(url("/healthz"))
            .get()
            .build()

        return try {
            http.newCall(req).execute().use { res ->
                res.isSuccessful
            }
        } catch (_: IOException) {
            false
        }
    }

    fun register(username: String, email: String?, password: String): UserInfo {
        val payload = JSONObject()
            .put("username", username)
            .put("password", password)
        if (!email.isNullOrBlank()) payload.put("email", email)
        else payload.put("email", JSONObject.NULL)

        val obj = requestJson("POST", "/v1/auth/register", body = payload)
        val emailValue = obj.opt("email")
        return UserInfo(
            userId = obj.getString("user_id"),
            username = obj.getString("username"),
            email = if (emailValue == JSONObject.NULL) null else obj.optString("email").takeIf { it.isNotBlank() },
            isAdmin = obj.optBoolean("is_admin", false),
            createdAt = obj.optString("created_at").takeIf { it.isNotBlank() },
        )
    }

    fun login(username: String, password: String): TokenResponse {
        val payload = JSONObject()
            .put("username", username)
            .put("password", password)

        val obj = requestJson("POST", "/v1/auth/login", body = payload)
        return TokenResponse(
            accessToken = obj.getString("access_token"),
            expiresIn = obj.optInt("expires_in", 0),
            refreshToken = obj.optString("refresh_token").takeIf { it.isNotBlank() },
        )
    }

    fun refresh(refreshToken: String): TokenResponse {
        val payload = JSONObject().put("refresh_token", refreshToken)
        val obj = requestJson("POST", "/v1/auth/refresh", body = payload)
        return TokenResponse(
            accessToken = obj.getString("access_token"),
            expiresIn = obj.optInt("expires_in", 0),
            refreshToken = obj.optString("refresh_token").takeIf { it.isNotBlank() },
        )
    }

    fun me(accessToken: String): UserInfo {
        val obj = requestJson("GET", "/v1/users/me", accessToken = accessToken)
        val email = obj.opt("email")
        return UserInfo(
            userId = obj.getString("user_id"),
            username = obj.getString("username"),
            email = if (email == JSONObject.NULL) null else obj.optString("email").takeIf { it.isNotBlank() },
            isAdmin = obj.optBoolean("is_admin", false),
            createdAt = obj.optString("created_at").takeIf { it.isNotBlank() },
        )
    }

    fun tracksBatchUpload(accessToken: String, items: JSONArray): TracksBatchResponse {
        val payload = JSONObject().put("items", items)
        val obj = requestJson("POST", "/v1/tracks/batch", accessToken = accessToken, body = payload)
        val acceptedJson = obj.optJSONArray("accepted_ids") ?: JSONArray()
        val accepted = ArrayList<String>(acceptedJson.length())
        for (i in 0 until acceptedJson.length()) {
            accepted.add(acceptedJson.optString(i))
        }

        val rejectedJson = obj.optJSONArray("rejected") ?: JSONArray()
        val rejected = ArrayList<RejectedItem>(rejectedJson.length())
        for (i in 0 until rejectedJson.length()) {
            val it = rejectedJson.optJSONObject(i) ?: continue
            rejected.add(
                RejectedItem(
                    clientPointId = it.optString("client_point_id"),
                    reasonCode = it.optString("reason_code"),
                    message = it.optString("message").takeIf { s -> s.isNotBlank() },
                ),
            )
        }

        return TracksBatchResponse(
            acceptedIds = accepted,
            rejected = rejected,
        )
    }

    fun tracksQuery(
        accessToken: String,
        startUtc: String,
        endUtc: String,
        limit: Int,
        offset: Int,
    ): List<TrackQueryItem> {
        val qs =
            "start=${encode(startUtc)}&end=${encode(endUtc)}&limit=$limit&offset=$offset"
        val obj = requestJson("GET", "/v1/tracks/query?$qs", accessToken = accessToken)
        val items = obj.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<TrackQueryItem>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            out.add(
                TrackQueryItem(
                    clientPointId = it.getString("client_point_id"),
                    recordedAtUtc = it.getString("recorded_at"),
                    latitude = it.getDouble("latitude"),
                    longitude = it.getDouble("longitude"),
                    accuracy = if (!it.has("accuracy") || it.isNull("accuracy")) null else it.getDouble("accuracy"),
                    gcj02Latitude = if (!it.has("gcj02_latitude") || it.isNull("gcj02_latitude")) null else it.getDouble("gcj02_latitude"),
                    gcj02Longitude = if (!it.has("gcj02_longitude") || it.isNull("gcj02_longitude")) null else it.getDouble("gcj02_longitude"),
                    stepCount = if (!it.has("step_count") || it.isNull("step_count")) null else it.getLong("step_count"),
                    stepDelta = if (!it.has("step_delta") || it.isNull("step_delta")) null else it.getLong("step_delta"),
                    isDirty = it.optBoolean("is_dirty", false),
                ),
            )
        }
        return out
    }

    fun stepsDaily(
        accessToken: String,
        startUtc: String,
        endUtc: String,
        tz: String? = null,
        tzOffsetMinutes: Int? = null,
    ): List<StepsDailyItem> {
        val qs = StringBuilder()
        qs.append("start=${encode(startUtc)}&end=${encode(endUtc)}")
        if (!tz.isNullOrBlank()) qs.append("&tz=${encode(tz)}")
        if (tzOffsetMinutes != null) qs.append("&tz_offset_minutes=$tzOffsetMinutes")

        val obj = requestJson("GET", "/v1/stats/steps/daily?$qs", accessToken = accessToken)
        val items = obj.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<StepsDailyItem>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            out.add(
                StepsDailyItem(
                    day = it.optString("day").trim(),
                    steps = if (!it.has("steps") || it.isNull("steps")) 0L else it.optLong("steps", 0L),
                ),
            )
        }
        return out
    }

    fun stepsHourly(
        accessToken: String,
        startUtc: String,
        endUtc: String,
        tz: String? = null,
        tzOffsetMinutes: Int? = null,
    ): List<StepsHourlyItem> {
        val qs = StringBuilder()
        qs.append("start=${encode(startUtc)}&end=${encode(endUtc)}")
        if (!tz.isNullOrBlank()) qs.append("&tz=${encode(tz)}")
        if (tzOffsetMinutes != null) qs.append("&tz_offset_minutes=$tzOffsetMinutes")

        val obj = requestJson("GET", "/v1/stats/steps/hourly?$qs", accessToken = accessToken)
        val items = obj.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<StepsHourlyItem>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            out.add(
                StepsHourlyItem(
                    hourStart = it.optString("hour_start").trim(),
                    steps = if (!it.has("steps") || it.isNull("steps")) 0L else it.optLong("steps", 0L),
                ),
            )
        }
        return out
    }

    fun lifeEventCreate(accessToken: String, payload: JSONObject): JSONObject {
        return requestJson("POST", "/v1/life-events", accessToken = accessToken, body = payload)
    }

    fun lifeEventUpdate(accessToken: String, id: String, payload: JSONObject): JSONObject {
        val eventId = id.trim()
        require(eventId.isNotBlank()) { "Missing life event id" }
        return requestJson("PUT", "/v1/life-events/$eventId", accessToken = accessToken, body = payload)
    }

    fun lifeEventDelete(accessToken: String, id: String): JSONObject {
        val eventId = id.trim()
        require(eventId.isNotBlank()) { "Missing life event id" }
        return requestJson("DELETE", "/v1/life-events/$eventId", accessToken = accessToken)
    }

    fun lifeEventsList(
        accessToken: String,
        startUtc: String? = null,
        endUtc: String? = null,
        limit: Int = 200,
        offset: Int = 0,
    ): List<LifeEventListItem> {
        fun optStringOrNull(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            val v = obj.optString(key).trim()
            return v.takeIf { s -> s.isNotBlank() }
        }

        val qs = StringBuilder()
        if (!startUtc.isNullOrBlank()) qs.append("start=${encode(startUtc)}")
        if (!endUtc.isNullOrBlank()) {
            if (qs.isNotEmpty()) qs.append("&")
            qs.append("end=${encode(endUtc)}")
        }
        if (qs.isNotEmpty()) qs.append("&")
        qs.append("limit=$limit&offset=$offset")

        val obj = requestJson("GET", "/v1/life-events?$qs", accessToken = accessToken)
        val items = obj.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<LifeEventListItem>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            out.add(
                LifeEventListItem(
                    id = it.optString("id").trim(),
                    eventType = it.optString("event_type").trim(),
                    startAtUtc = it.optString("start_at").trim(),
                    endAtUtc = it.optString("end_at").trim(),
                    locationName = optStringOrNull(it, "location_name"),
                    manualNote = optStringOrNull(it, "manual_note"),
                    latitude = if (!it.has("latitude") || it.isNull("latitude")) null else it.optDouble("latitude"),
                    longitude = if (!it.has("longitude") || it.isNull("longitude")) null else it.optDouble("longitude"),
                    gcj02Latitude = if (!it.has("gcj02_latitude") || it.isNull("gcj02_latitude")) null else it.optDouble("gcj02_latitude"),
                    gcj02Longitude = if (!it.has("gcj02_longitude") || it.isNull("gcj02_longitude")) null else it.optDouble("gcj02_longitude"),
                    payloadJson = it.optJSONObject("payload_json"),
                    createdAtUtc = optStringOrNull(it, "created_at"),
                    updatedAtUtc = optStringOrNull(it, "updated_at"),
                ),
            )
        }
        return out
    }

    private fun encode(s: String): String {
        return URLEncoder.encode(s, "UTF-8")
    }
}
