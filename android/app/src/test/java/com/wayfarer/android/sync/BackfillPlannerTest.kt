package com.wayfarer.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BackfillPlannerTest {
    @Test
    fun bootstrapRecentRange_defaultsTo7Days() {
        val planner = BackfillPlanner()
        val now = Instant.parse("2026-02-05T00:00:00Z")
        val w = planner.bootstrapRecentRange(now)
        assertEquals("2026-01-29T00:00:00Z", w.startUtc.toString())
        assertEquals("2026-02-05T00:00:00Z", w.endUtc.toString())
    }

    @Test
    fun nextBackfillWindow_movesBackwardByWindowDays() {
        val planner = BackfillPlanner(windowDays = 7)
        val cursorEnd = Instant.parse("2026-01-29T00:00:00Z")
        val w = planner.nextBackfillWindow(cursorEndUtc = cursorEnd)
        requireNotNull(w)
        assertEquals("2026-01-22T00:00:00Z", w.startUtc.toString())
        assertEquals("2026-01-29T00:00:00Z", w.endUtc.toString())
    }

    @Test
    fun nextBackfillWindow_respectsMinUtcLowerBound() {
        val planner = BackfillPlanner(windowDays = 7)
        val cursorEnd = Instant.parse("2026-01-05T00:00:00Z")
        val minUtc = Instant.parse("2026-01-01T00:00:00Z")
        val w = planner.nextBackfillWindow(cursorEndUtc = cursorEnd, minUtc = minUtc)
        requireNotNull(w)
        assertEquals("2026-01-01T00:00:00Z", w.startUtc.toString())
        assertEquals("2026-01-05T00:00:00Z", w.endUtc.toString())
    }

    @Test
    fun nextBackfillWindow_returnsNullWhenNoRoom() {
        val planner = BackfillPlanner(windowDays = 7)
        val cursorEnd = Instant.parse("2026-01-01T00:00:00Z")
        val minUtc = Instant.parse("2026-01-01T00:00:00Z")
        val w = planner.nextBackfillWindow(cursorEndUtc = cursorEnd, minUtc = minUtc)
        assertNull(w)
    }
}

