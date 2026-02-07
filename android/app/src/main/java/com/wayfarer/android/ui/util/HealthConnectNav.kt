package com.wayfarer.android.ui.util

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient

/**
 * 打开 Health Connect 的“管理数据/权限”页面（尽量兼容不同 ROM / provider 包名）。
 *
 * 为什么需要这个跳转：
 * - 系统“全天步数”属于健康数据，必须由用户在系统侧授权一次（这是 Android 的隐私机制）。
 * - App 内直接弹授权面板在部分 ROM 上可能不稳定；统一跳系统页更可靠、更可解释。
 */
fun openHealthConnectManageData(
    context: Context,
    providerPackageName: String?,
): Boolean {
    val appContext = context.applicationContext

    val intent: Intent? =
        runCatching {
            if (!providerPackageName.isNullOrBlank()) {
                HealthConnectClient.getHealthConnectManageDataIntent(appContext, providerPackageName)
            } else {
                HealthConnectClient.getHealthConnectManageDataIntent(appContext)
            }
        }.getOrNull()
            ?: runCatching {
                // 退化：使用公开的 action 字符串（老 ROM/实现差异时可能仍可用）。
                Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
            }.getOrNull()

    if (intent == null) return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        appContext.startActivity(intent)
        true
    }.getOrDefault(false)
}
