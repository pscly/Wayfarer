package com.wayfarer.android.health

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * 系统全天步数仓库（Health Connect）：
 * - 读取“系统已聚合/已记录”的 StepsRecord 数据，不依赖 Wayfarer 是否在记录轨迹。
 * - 需要用户授权 `android.permission.health.READ_STEPS`（通过 Health Connect 的权限面板）。
 *
 * 注意：
 * - 若用户设备未安装/不支持 Health Connect，会返回 SDK 状态并提示引导安装。
 * - 若用户未授权，会抛出 SecurityException（交由 UI 引导授权）。
 */
class SystemStepsRepository(
    context: Context,
) {
    companion object {
        /**
         * Health Connect provider 包名在不同设备/ROM 上可能不同：
         * - Google 版本（Play / Pixel 等）：com.google.android.apps.healthdata
         * - Android 14+ AOSP 模块化实现：常见为 com.android.healthconnect.controller
         *
         * 这里做候选探测，避免“设备已内置但 SDK 状态被误判为需要安装”的问题。
         */
        private val PROVIDER_CANDIDATES: List<String> =
            listOf(
                "com.google.android.apps.healthdata",
                "com.android.healthconnect.controller",
            )
    }

    private val appContext = context.applicationContext

    private val ioExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun sdkStatus(): Int {
        var sawUpdateRequired = false
        for (provider in PROVIDER_CANDIDATES) {
            val status = HealthConnectClient.getSdkStatus(appContext, provider)
            when (status) {
                HealthConnectClient.SDK_AVAILABLE -> return HealthConnectClient.SDK_AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> sawUpdateRequired = true
            }
        }
        return if (sawUpdateRequired) {
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        } else {
            HealthConnectClient.SDK_UNAVAILABLE
        }
    }

    fun providerPackageNameOrNull(): String? {
        for (provider in PROVIDER_CANDIDATES) {
            val status = HealthConnectClient.getSdkStatus(appContext, provider)
            if (status == HealthConnectClient.SDK_AVAILABLE) return provider
        }
        return null
    }

    fun requiredPermissions(): Set<String> {
        return setOf(HealthPermission.getReadPermission(StepsRecord::class))
    }

    fun hasPermissionsAsync(
        onResult: (Boolean) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                val provider = providerPackageNameOrNull() ?: return@runCatching false
                val client = HealthConnectClient.getOrCreate(appContext, provider)
                val granted = runBlocking { client.permissionController.getGrantedPermissions() }
                granted.containsAll(requiredPermissions())
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun todayStepsAsync(
        zone: ZoneId,
        onResult: (Long) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant()
        val end = Instant.now()
        totalStepsAsync(startUtc = start, endUtc = end, onResult = onResult, onError = onError)
    }

    fun totalStepsAsync(
        startUtc: Instant,
        endUtc: Instant,
        onResult: (Long) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                val client = clientOrThrow()
                val response = runBlocking {
                    client.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(startUtc, endUtc),
                        ),
                    )
                }
                (response[StepsRecord.COUNT_TOTAL] ?: 0L).coerceAtLeast(0L)
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun dailyStepsAsync(
        startDay: LocalDate,
        endDayInclusive: LocalDate,
        zone: ZoneId,
        onResult: (Map<LocalDate, Long>) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        val startUtc = startDay.atStartOfDay(zone).toInstant()
        val endUtc = endDayInclusive.plusDays(1).atStartOfDay(zone).toInstant()

        ioExecutor.execute {
            val result = runCatching {
                val client = clientOrThrow()
                val response = runBlocking {
                    client.aggregateGroupByDuration(
                        AggregateGroupByDurationRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(startUtc, endUtc),
                            timeRangeSlicer = Duration.ofDays(1),
                        ),
                    )
                }

                val stepsByDay = HashMap<LocalDate, Long>(128)
                for (row in response) {
                    val day = row.startTime.atZone(zone).toLocalDate()
                    val steps = (row.result[StepsRecord.COUNT_TOTAL] ?: 0L).coerceAtLeast(0L)
                    stepsByDay[day] = steps
                }

                // Fill missing days with 0 to keep UI stable.
                val out = LinkedHashMap<LocalDate, Long>()
                var d = startDay
                while (!d.isAfter(endDayInclusive)) {
                    out[d] = stepsByDay[d] ?: 0L
                    d = d.plusDays(1)
                }
                out
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun hourlyStepsAsync(
        day: LocalDate,
        zone: ZoneId,
        onResult: (LongArray) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        val startUtc = day.atStartOfDay(zone).toInstant()
        val endUtc = day.plusDays(1).atStartOfDay(zone).toInstant()

        ioExecutor.execute {
            val result = runCatching {
                val client = clientOrThrow()
                val response = runBlocking {
                    client.aggregateGroupByDuration(
                        AggregateGroupByDurationRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(startUtc, endUtc),
                            timeRangeSlicer = Duration.ofHours(1),
                        ),
                    )
                }

                val out = LongArray(24) { 0L }
                for (row in response) {
                    val hour = row.startTime.atZone(zone).hour
                    if (hour in 0..23) {
                        out[hour] = (row.result[StepsRecord.COUNT_TOTAL] ?: 0L).coerceAtLeast(0L)
                    }
                }
                out
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    private fun ensureAvailableOrThrow() {
        val status = sdkStatus()
        when (status) {
            HealthConnectClient.SDK_AVAILABLE -> Unit
            HealthConnectClient.SDK_UNAVAILABLE ->
                throw IllegalStateException("Health Connect 不可用：SDK_UNAVAILABLE")
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                throw IllegalStateException("Health Connect 需要安装/更新：SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED")
            else ->
                throw IllegalStateException("Health Connect 不可用：status=$status")
        }
    }

    private fun clientOrThrow(): HealthConnectClient {
        val provider = providerPackageNameOrNull()
        if (provider.isNullOrBlank()) {
            ensureAvailableOrThrow()
            throw IllegalStateException("Health Connect provider 不可用")
        }
        return HealthConnectClient.getOrCreate(appContext, provider)
    }
}
