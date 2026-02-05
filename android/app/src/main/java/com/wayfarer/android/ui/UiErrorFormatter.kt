package com.wayfarer.android.ui

import com.wayfarer.android.api.ApiException
import com.wayfarer.android.api.toDebugDetail
import com.wayfarer.android.api.toUserMessageZh
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

data class UiError(
    val message: String,
    val detail: String? = null,
)

object UiErrorFormatter {
    fun format(err: Throwable): UiError {
        if (err is ApiException) {
            return UiError(
                message = err.toUserMessageZh(),
                detail = err.toDebugDetail(),
            )
        }

        val message = when (err) {
            is UnknownHostException -> "无法解析服务器地址，请检查服务器地址与网络"
            is ConnectException -> "无法连接服务器，请检查网络与端口"
            is SocketTimeoutException -> "请求超时，请稍后重试"
            is SSLHandshakeException -> "HTTPS 证书校验失败，请检查系统时间或证书配置"
            is IOException -> "网络错误，请检查网络连接"
            else -> err.message?.trim().takeIf { !it.isNullOrBlank() } ?: (err::class.java.name)
        }

        val detail = buildString {
            appendLine("Wayfarer 客户端错误")
            appendLine("- type: ${err::class.java.name}")
            val msg = err.message?.trim().orEmpty()
            if (msg.isNotBlank()) appendLine("- message: $msg")
        }.trim()

        return UiError(
            message = message,
            detail = detail,
        )
    }
}

