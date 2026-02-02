package com.wayfarer.android.api

import android.content.Context
import com.wayfarer.android.BuildConfig

object ServerConfigStore {
    private const val PREFS_NAME = "wayfarer_server_config"

    private const val KEY_BASE_URL = "base_url"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the configured base URL if present, otherwise BuildConfig default.
     */
    fun readBaseUrl(context: Context): String {
        val saved = prefs(context).getString(KEY_BASE_URL, null)?.trim().orEmpty()
        val fallback = BuildConfig.WAYFARER_API_BASE_URL
        return normalizeBaseUrl(if (saved.isNotBlank()) saved else fallback)
    }

    /**
     * Saves a user override. If [raw] is blank, clears the override.
     */
    fun saveBaseUrl(context: Context, raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            prefs(context).edit().remove(KEY_BASE_URL).apply()
            return
        }
        prefs(context).edit().putString(KEY_BASE_URL, normalizeBaseUrl(trimmed)).apply()
    }

    fun readBaseUrlOverride(context: Context): String? {
        val raw = prefs(context).getString(KEY_BASE_URL, null)?.trim()
        return if (raw.isNullOrBlank()) null else normalizeBaseUrl(raw)
    }

    fun normalizeBaseUrl(raw: String): String {
        var out = raw.trim()
        if (out.isBlank()) return out

        if (!out.startsWith("http://") && !out.startsWith("https://")) {
            out = "https://$out"
        }

        while (out.endsWith("/")) {
            out = out.dropLast(1)
        }
        return out
    }
}
