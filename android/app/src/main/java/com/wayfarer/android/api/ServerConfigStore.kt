package com.wayfarer.android.api

import android.content.Context
import com.wayfarer.android.BuildConfig
import java.net.URI

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
        return normalizeBaseUrlOrNull(raw) ?: raw.trim().trimEnd('/')
    }

    /**
     * 尝试把用户输入规范化为可用的 base URL（只包含 scheme + host + 可选 port）。
     *
     * - 空字符串表示“清空 override”（合法）。
     * - 返回 null 表示输入明显不合法（例如 host 缺失、格式不可解析）。
     */
    fun normalizeBaseUrlOrNull(raw: String): String? {
        var out = raw.trim()
        if (out.isBlank()) return ""

        if (!out.startsWith("http://") && !out.startsWith("https://")) {
            out = "https://$out"
        }

        val uri =
            runCatching {
                URI(out)
            }.getOrNull() ?: return null

        val scheme = uri.scheme?.trim().orEmpty()
        if (scheme != "http" && scheme != "https") return null

        var host = uri.host?.trim().orEmpty()
        var port = uri.port

        // 某些非标准输入（例如包含 userinfo）可能导致 host 为空；尝试从 authority 提取。
        if (host.isBlank()) {
            val authority = uri.rawAuthority?.trim().orEmpty()
            if (authority.isBlank()) return null

            val withoutUserInfo = authority.substringAfterLast('@').trim()
            if (withoutUserInfo.isBlank()) return null

            val hostPart = withoutUserInfo.substringBefore(':').trim()
            if (hostPart.isBlank()) return null
            host = hostPart

            if (port == -1) {
                val portPart = withoutUserInfo.substringAfter(':', missingDelimiterValue = "").trim()
                if (portPart.isNotBlank()) {
                    port = portPart.toIntOrNull() ?: return null
                }
            }
        }

        val hostForUrl =
            if (host.contains(":") && !host.startsWith("[") && !host.endsWith("]")) {
                "[$host]"
            } else {
                host
            }

        val portPart = if (port in 1..65535) ":$port" else ""
        return "$scheme://$hostForUrl$portPart".trimEnd('/')
    }
}
