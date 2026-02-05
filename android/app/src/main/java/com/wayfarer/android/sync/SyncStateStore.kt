package com.wayfarer.android.sync

import android.content.Context

/**
 * Small SharedPreferences store for sync-related metadata.
 */
object SyncStateStore {
    internal const val PREFS_NAME = "wayfarer_sync_state"

    private const val KEY_LAST_UPLOAD_AT_MS = "last_upload_at_ms"
    private const val KEY_LAST_PULL_AT_MS = "last_pull_at_ms"
    private const val KEY_LAST_ERROR = "last_error"

    private const val KEY_SYNC_PHASE = "sync_phase"
    private const val KEY_SYNC_PROGRESS_TEXT = "sync_progress_text"

    private const val KEY_BOOTSTRAP_DONE_AT_MS = "bootstrap_done_at_ms"

    // Backfill state.
    private const val KEY_BACKFILL_ENABLED = "backfill_enabled"
    private const val KEY_BACKFILL_CURSOR_END_UTC = "backfill_cursor_end_utc"
    private const val KEY_BACKFILL_EMPTY_STREAK = "backfill_empty_streak"
    private const val KEY_BACKFILL_DONE_AT_MS = "backfill_done_at_ms"

    // Provide prefs access for UI observers within this app module.
    internal fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class SyncPhase(val raw: String) {
        IDLE("idle"),
        BOOTSTRAP_RECENT("bootstrap_recent"),
        BACKFILLING("backfilling"),
        PAUSED("paused"),
        UP_TO_DATE("up_to_date"),
        ERROR("error"),
    }

    data class Snapshot(
        val phase: SyncPhase,
        val progressText: String?,
        val lastUploadAtMs: Long?,
        val lastPullAtMs: Long?,
        val lastError: String?,
        val bootstrapDoneAtMs: Long?,
        val backfillEnabled: Boolean,
        val backfillCursorEndUtc: String?,
        val backfillEmptyStreak: Int,
        val backfillDoneAtMs: Long?,
    )

    fun readSnapshot(context: Context): Snapshot {
        val p = prefs(context)
        val rawPhase = p.getString(KEY_SYNC_PHASE, null)?.trim().orEmpty()
        val phase =
            SyncPhase.entries.firstOrNull { it.raw == rawPhase } ?: SyncPhase.IDLE

        val lastUpload = p.getLong(KEY_LAST_UPLOAD_AT_MS, -1L).let { if (it > 0) it else null }
        val lastPull = p.getLong(KEY_LAST_PULL_AT_MS, -1L).let { if (it > 0) it else null }
        val lastError = p.getString(KEY_LAST_ERROR, null)?.trim().takeIf { !it.isNullOrBlank() }
        val progress = p.getString(KEY_SYNC_PROGRESS_TEXT, null)?.trim().takeIf { !it.isNullOrBlank() }
        val bootstrapDone = p.getLong(KEY_BOOTSTRAP_DONE_AT_MS, -1L).let { if (it > 0) it else null }

        val backfillEnabled = p.getBoolean(KEY_BACKFILL_ENABLED, true)
        val cursorEnd = p.getString(KEY_BACKFILL_CURSOR_END_UTC, null)?.trim().takeIf { !it.isNullOrBlank() }
        val emptyStreak = p.getInt(KEY_BACKFILL_EMPTY_STREAK, 0).coerceAtLeast(0)
        val backfillDone = p.getLong(KEY_BACKFILL_DONE_AT_MS, -1L).let { if (it > 0) it else null }

        return Snapshot(
            phase = phase,
            progressText = progress,
            lastUploadAtMs = lastUpload,
            lastPullAtMs = lastPull,
            lastError = lastError,
            bootstrapDoneAtMs = bootstrapDone,
            backfillEnabled = backfillEnabled,
            backfillCursorEndUtc = cursorEnd,
            backfillEmptyStreak = emptyStreak,
            backfillDoneAtMs = backfillDone,
        )
    }

    fun readLastUploadAtMs(context: Context): Long? {
        val v = prefs(context).getLong(KEY_LAST_UPLOAD_AT_MS, -1L)
        return if (v > 0) v else null
    }

    fun readLastPullAtMs(context: Context): Long? {
        val v = prefs(context).getLong(KEY_LAST_PULL_AT_MS, -1L)
        return if (v > 0) v else null
    }

    fun readLastError(context: Context): String? {
        val v = prefs(context).getString(KEY_LAST_ERROR, null)?.trim()
        return if (v.isNullOrBlank()) null else v
    }

    fun readBackfillEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BACKFILL_ENABLED, true)

    fun setBackfillEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACKFILL_ENABLED, enabled).apply()
    }

    fun readBackfillCursorEndUtc(context: Context): String? {
        val v = prefs(context).getString(KEY_BACKFILL_CURSOR_END_UTC, null)?.trim()
        return if (v.isNullOrBlank()) null else v
    }

    fun setBackfillCursorEndUtc(context: Context, endUtc: String?) {
        val edit = prefs(context).edit()
        if (endUtc.isNullOrBlank()) edit.remove(KEY_BACKFILL_CURSOR_END_UTC) else edit.putString(
            KEY_BACKFILL_CURSOR_END_UTC,
            endUtc.trim(),
        )
        edit.apply()
    }

    fun readBackfillEmptyStreak(context: Context): Int = prefs(context).getInt(KEY_BACKFILL_EMPTY_STREAK, 0).coerceAtLeast(0)

    fun setBackfillEmptyStreak(context: Context, streak: Int) {
        prefs(context).edit().putInt(KEY_BACKFILL_EMPTY_STREAK, streak.coerceAtLeast(0)).apply()
    }

    fun readBackfillDoneAtMs(context: Context): Long? {
        val v = prefs(context).getLong(KEY_BACKFILL_DONE_AT_MS, -1L)
        return if (v > 0) v else null
    }

    private fun setPhase(context: Context, phase: SyncPhase) {
        prefs(context).edit().putString(KEY_SYNC_PHASE, phase.raw).apply()
    }

    fun markIdle(context: Context) {
        setPhase(context, SyncPhase.IDLE)
        prefs(context).edit().remove(KEY_SYNC_PROGRESS_TEXT).apply()
    }

    fun markBootstrapStarting(context: Context) {
        setPhase(context, SyncPhase.BOOTSTRAP_RECENT)
        prefs(context)
            .edit()
            .remove(KEY_LAST_ERROR)
            .remove(KEY_BACKFILL_DONE_AT_MS)
            .putInt(KEY_BACKFILL_EMPTY_STREAK, 0)
            .putBoolean(KEY_BACKFILL_ENABLED, true)
            .putString(KEY_SYNC_PROGRESS_TEXT, "正在同步近期数据…")
            .apply()
    }

    fun markBootstrapOk(context: Context, backfillCursorEndUtc: String?, progressText: String?) {
        val edit =
            prefs(context)
                .edit()
                .putLong(KEY_BOOTSTRAP_DONE_AT_MS, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .putBoolean(KEY_BACKFILL_ENABLED, true)
        if (progressText.isNullOrBlank()) edit.remove(KEY_SYNC_PROGRESS_TEXT) else edit.putString(
            KEY_SYNC_PROGRESS_TEXT,
            progressText.trim(),
        )
        if (backfillCursorEndUtc.isNullOrBlank()) edit.remove(KEY_BACKFILL_CURSOR_END_UTC) else edit.putString(
            KEY_BACKFILL_CURSOR_END_UTC,
            backfillCursorEndUtc.trim(),
        )
        edit.apply()
    }

    fun markBackfillStarting(context: Context, progressText: String? = null) {
        setPhase(context, SyncPhase.BACKFILLING)
        val edit = prefs(context).edit().remove(KEY_LAST_ERROR)
        if (progressText.isNullOrBlank()) edit.remove(KEY_SYNC_PROGRESS_TEXT) else edit.putString(
            KEY_SYNC_PROGRESS_TEXT,
            progressText.trim(),
        )
        edit.apply()
    }

    fun updateBackfillProgress(
        context: Context,
        cursorEndUtc: String,
        progressText: String?,
        emptyStreak: Int,
    ) {
        val edit =
            prefs(context)
                .edit()
                .putString(KEY_BACKFILL_CURSOR_END_UTC, cursorEndUtc.trim())
                .putInt(KEY_BACKFILL_EMPTY_STREAK, emptyStreak.coerceAtLeast(0))
                .remove(KEY_LAST_ERROR)
        if (progressText.isNullOrBlank()) edit.remove(KEY_SYNC_PROGRESS_TEXT) else edit.putString(
            KEY_SYNC_PROGRESS_TEXT,
            progressText.trim(),
        )
        edit.apply()
        setPhase(context, SyncPhase.BACKFILLING)
    }

    fun markBackfillDone(context: Context) {
        val edit =
            prefs(context)
                .edit()
                .putLong(KEY_BACKFILL_DONE_AT_MS, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .remove(KEY_SYNC_PROGRESS_TEXT)
        edit.apply()
        setPhase(context, SyncPhase.UP_TO_DATE)
    }

    fun markPaused(context: Context, reason: String? = null) {
        val edit = prefs(context).edit()
        if (reason.isNullOrBlank()) edit.remove(KEY_SYNC_PROGRESS_TEXT) else edit.putString(
            KEY_SYNC_PROGRESS_TEXT,
            reason.trim(),
        )
        edit.apply()
        setPhase(context, SyncPhase.PAUSED)
    }

    fun markUploadOk(context: Context) {
        prefs(context)
            .edit()
            .putLong(KEY_LAST_UPLOAD_AT_MS, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun markPullOk(context: Context) {
        prefs(context)
            .edit()
            .putLong(KEY_LAST_PULL_AT_MS, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun markError(context: Context, message: String) {
        prefs(context)
            .edit()
            .putString(KEY_LAST_ERROR, message.take(2_000))
            .apply()
        setPhase(context, SyncPhase.ERROR)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
