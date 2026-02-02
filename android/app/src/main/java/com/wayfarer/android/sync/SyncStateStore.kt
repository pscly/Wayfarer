package com.wayfarer.android.sync

import android.content.Context

/**
 * Small SharedPreferences store for sync-related metadata.
 */
object SyncStateStore {
    private const val PREFS_NAME = "wayfarer_sync_state"

    private const val KEY_LAST_UPLOAD_AT_MS = "last_upload_at_ms"
    private const val KEY_LAST_PULL_AT_MS = "last_pull_at_ms"
    private const val KEY_LAST_ERROR = "last_error"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
