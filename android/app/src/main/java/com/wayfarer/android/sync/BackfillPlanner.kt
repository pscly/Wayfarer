package com.wayfarer.android.sync

import java.time.Duration
import java.time.Instant

/**
 * 规划“温和后台回填”的时间窗口。
 *
 * 设计目标：
 * - 登录后先快速拉取近期数据（默认 7 天）让 UI 立刻可用
 * - 更早历史按固定窗口（默认 7 天）向过去推进，每次只做一步，避免长时间占用
 */
class BackfillPlanner(
    private val bootstrapDays: Long = 7,
    private val windowDays: Long = 7,
) {
    data class Window(
        val startUtc: Instant,
        val endUtc: Instant,
    )

    fun bootstrapRecentRange(now: Instant = Instant.now()): Window {
        val end = now
        val start = end.minus(Duration.ofDays(bootstrapDays.coerceAtLeast(1)))
        return Window(startUtc = start, endUtc = end)
    }

    /**
     * 计算下一步回填窗口（向过去推进）。
     *
     * - cursorEndUtc：当前“已覆盖到的最早边界”的 end（下一次要回填的窗口 end）
     * - minUtc：可选的下界（例如用户 created_at），避免无限向过去扫描
     */
    fun nextBackfillWindow(
        cursorEndUtc: Instant,
        minUtc: Instant? = null,
    ): Window? {
        val end = cursorEndUtc
        val rawStart = end.minus(Duration.ofDays(windowDays.coerceAtLeast(1)))
        val start = if (minUtc != null && rawStart.isBefore(minUtc)) minUtc else rawStart
        if (!start.isBefore(end)) return null
        return Window(startUtc = start, endUtc = end)
    }
}

