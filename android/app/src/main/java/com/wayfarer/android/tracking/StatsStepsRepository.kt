package com.wayfarer.android.tracking

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wayfarer.android.api.ApiException
import com.wayfarer.android.api.AuthStore
import com.wayfarer.android.api.WayfarerApiClient
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 步数统计（按天/按小时）仓库：
 * - 统一使用后端聚合接口分桶（支持 tz/tz_offset_minutes）
 * - 内部做 access_token 自动刷新（401 时尝试 refresh）
 *
 * 注意：调用方仍可在离线时回退到本地统计（本仓库仅负责云端数据）。
 */
class StatsStepsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val api = WayfarerApiClient(appContext)

    private val ioExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun dailyAsync(
        startUtc: String,
        endUtc: String,
        tz: String,
        tzOffsetMinutes: Int?,
        onResult: (List<WayfarerApiClient.StepsDailyItem>) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                authenticated { token ->
                    api.stepsDaily(
                        accessToken = token,
                        startUtc = startUtc,
                        endUtc = endUtc,
                        tz = tz,
                        tzOffsetMinutes = tzOffsetMinutes,
                    )
                }
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }

    fun hourlyAsync(
        startUtc: String,
        endUtc: String,
        tz: String,
        tzOffsetMinutes: Int?,
        onResult: (List<WayfarerApiClient.StepsHourlyItem>) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                authenticated { token ->
                    api.stepsHourly(
                        accessToken = token,
                        startUtc = startUtc,
                        endUtc = endUtc,
                        tz = tz,
                        tzOffsetMinutes = tzOffsetMinutes,
                    )
                }
            }
            mainHandler.post { result.fold(onResult, onError) }
        }
    }
}

