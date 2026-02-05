package com.wayfarer.android.api

import android.content.Context

object AuthStore {
    private const val PREFS_NAME = "wayfarer_auth"

    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_USER_CREATED_AT = "user_created_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readAccessToken(context: Context): String? {
        val v = prefs(context).getString(KEY_ACCESS_TOKEN, null)?.trim()
        return if (v.isNullOrBlank()) null else v
    }

    fun readRefreshToken(context: Context): String? {
        val v = prefs(context).getString(KEY_REFRESH_TOKEN, null)?.trim()
        return if (v.isNullOrBlank()) null else v
    }

    fun readUserId(context: Context): String? {
        val v = prefs(context).getString(KEY_USER_ID, null)?.trim()
        return if (v.isNullOrBlank()) null else v
    }

    fun readUsername(context: Context): String? {
        val v = prefs(context).getString(KEY_USERNAME, null)?.trim()
        return if (v.isNullOrBlank()) null else v
    }

    fun readUserCreatedAtUtc(context: Context): String? {
        val v = prefs(context).getString(KEY_USER_CREATED_AT, null)?.trim()
        return if (v.isNullOrBlank()) null else v
    }

    fun writeTokens(context: Context, accessToken: String, refreshToken: String?) {
        val edit = prefs(context).edit().putString(KEY_ACCESS_TOKEN, accessToken)
        if (refreshToken.isNullOrBlank()) edit.remove(KEY_REFRESH_TOKEN) else edit.putString(
            KEY_REFRESH_TOKEN,
            refreshToken,
        )
        edit.apply()
    }

    fun writeUserInfo(context: Context, userId: String, username: String, createdAtUtc: String? = null) {
        val edit =
            prefs(context)
                .edit()
                .putString(KEY_USER_ID, userId)
                .putString(KEY_USERNAME, username)
        if (createdAtUtc.isNullOrBlank()) edit.remove(KEY_USER_CREATED_AT) else edit.putString(
            KEY_USER_CREATED_AT,
            createdAtUtc.trim(),
        )
        edit.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
