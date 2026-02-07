package com.wayfarer.android.ui.onboarding

import android.content.Context

/**
 * 轻量新手引导状态（SharedPreferences）。
 *
 * 目标：让首次进入 App 的用户知道“步数来自系统全天步数（Health Connect）”，并引导完成授权与同步。
 */
object OnboardingStore {
    private const val PREFS_NAME = "wayfarer_onboarding"
    private const val KEY_SYSTEM_STEPS_INTRO_SHOWN = "system_steps_intro_shown"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSystemStepsIntroShown(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SYSTEM_STEPS_INTRO_SHOWN, false)
    }

    fun markSystemStepsIntroShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_SYSTEM_STEPS_INTRO_SHOWN, true).apply()
    }

    fun resetSystemStepsIntro(context: Context) {
        prefs(context).edit().putBoolean(KEY_SYSTEM_STEPS_INTRO_SHOWN, false).apply()
    }
}

