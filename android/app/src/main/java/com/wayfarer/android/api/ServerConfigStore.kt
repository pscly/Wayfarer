package com.wayfarer.android.api

import android.content.Context
import com.wayfarer.android.BuildConfig
import java.net.URI

object ServerConfigStore {
    /**
     * API base URL。
     *
     * 产品策略：不允许用户在 App 内修改服务器地址，避免“填错地址导致无法登录/无法同步”。
     *
     * 仍保留通过 Gradle 属性/环境变量注入的能力（见 build.gradle.kts 的 BuildConfig.WAYFARER_API_BASE_URL），
     * 用于开发/联调；但普通用户不会看到也不需要配置。
     */
    fun readBaseUrl(
        @Suppress("UNUSED_PARAMETER")
        context: Context,
    ): String {
        val injected = BuildConfig.WAYFARER_API_BASE_URL
        return normalizeBaseUrl(injected)
    }

    fun normalizeBaseUrl(raw: String): String {
        return normalizeBaseUrlOrNull(raw) ?: raw.trim().trimEnd('/')
    }

    /**
     * 尝试把输入规范化为可用的 base URL（只包含 scheme + host + 可选 port）。
     *
     * - 空字符串返回空字符串。
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
