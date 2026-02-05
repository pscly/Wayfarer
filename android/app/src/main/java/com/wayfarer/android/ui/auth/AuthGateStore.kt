package com.wayfarer.android.ui.auth

import android.content.Context

/**
 * 仅用于控制“启动时是否显示登录引导”的轻量状态。
 *
 * - 未登录默认显示引导页
 * - 用户点击“离线继续”后，允许进入主界面（但同步不可用）
 * - 用户退出登录后，重置为再次显示引导页
 */
object AuthGateStore {
    private const val PREFS_NAME = "wayfarer_auth_gate"
    private const val KEY_DISMISSED = "dismissed"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDismissed(context: Context): Boolean = prefs(context).getBoolean(KEY_DISMISSED, false)

    fun dismiss(context: Context) {
        prefs(context).edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().putBoolean(KEY_DISMISSED, false).apply()
    }
}

