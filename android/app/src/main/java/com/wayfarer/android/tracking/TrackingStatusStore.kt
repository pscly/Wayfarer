package com.wayfarer.android.tracking

import android.content.Context

object TrackingStatusStore {
    private const val PREFS_NAME = "wayfarer_tracking_state"

    private const val KEY_IS_TRACKING = "is_tracking"
    private const val KEY_LAST_STARTED_AT_MS = "last_started_at_ms"
    private const val KEY_LAST_STOPPED_AT_MS = "last_stopped_at_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readIsTracking(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_IS_TRACKING, false)
    }

    fun readLastStartedAtMs(context: Context): Long? {
        val value = prefs(context).getLong(KEY_LAST_STARTED_AT_MS, -1L)
        return if (value > 0) value else null
    }

    fun readLastStoppedAtMs(context: Context): Long? {
        val value = prefs(context).getLong(KEY_LAST_STOPPED_AT_MS, -1L)
        return if (value > 0) value else null
    }

    fun markStarted(context: Context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_IS_TRACKING, true)
            .putLong(KEY_LAST_STARTED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun markStopped(context: Context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_IS_TRACKING, false)
            .putLong(KEY_LAST_STOPPED_AT_MS, System.currentTimeMillis())
            .apply()
    }
}
