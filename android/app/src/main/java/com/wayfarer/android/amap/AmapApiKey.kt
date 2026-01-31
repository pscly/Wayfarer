package com.wayfarer.android.amap

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object AmapApiKey {
    private const val META_DATA_NAME = "com.amap.api.v2.apikey"
    private const val MISSING_SENTINEL_PREFIX = "MISSING_"

    fun readFromManifest(context: Context): String? {
        val appInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
        }
        return appInfo.metaData?.getString(META_DATA_NAME)
    }

    fun isPresent(key: String?): Boolean {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith(MISSING_SENTINEL_PREFIX)) return false
        if (trimmed.equals("YOUR_AMAP_API_KEY", ignoreCase = true)) return false
        return true
    }
}
