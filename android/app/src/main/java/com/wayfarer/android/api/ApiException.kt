package com.wayfarer.android.api

/**
 * Simple exception type for HTTP API errors.
 *
 * Keep it lightweight: callers can surface [responseBody] for debugging.
 */
class ApiException(
    val statusCode: Int,
    val responseBody: String?,
    message: String,
    val requestPath: String? = null,
    val apiCode: String? = null,
    val apiMessage: String? = null,
    val traceId: String? = null,
    val serverBaseUrl: String? = null,
) : RuntimeException(message)

fun ApiException.toUserMessageZh(): String {
    val code = (apiCode ?: "").trim()
    if (code.isNotEmpty()) {
        return when (code) {
            "AUTH_INVALID_CREDENTIALS" -> "用户名或密码错误"
            "AUTH_TOKEN_EXPIRED" -> "登录已过期，请重新登录"
            "AUTH_TOKEN_INVALID" -> "登录信息无效，请重新登录"
            "AUTH_REFRESH_REUSED" -> "登录状态异常（令牌复用检测），请重新登录"
            "VALIDATION_ERROR" -> "请求参数不合法"
            else -> apiMessage?.trim().takeIf { !it.isNullOrBlank() } ?: message.orEmpty()
        }
    }

    return when (statusCode) {
        400 -> apiMessage?.trim().takeIf { !it.isNullOrBlank() } ?: "请求失败（400）"
        401 -> apiMessage?.trim().takeIf { !it.isNullOrBlank() } ?: "未授权，请先登录"
        403 -> apiMessage?.trim().takeIf { !it.isNullOrBlank() } ?: "没有权限（403）"
        404 -> apiMessage?.trim().takeIf { !it.isNullOrBlank() } ?: "接口不存在（404）"
        408 -> "请求超时（408）"
        429 -> "请求过于频繁（429），请稍后重试"
        in 500..599 -> "服务器错误（${statusCode}），请稍后重试"
        else -> apiMessage?.trim().takeIf { !it.isNullOrBlank() } ?: message.orEmpty()
    }
}

fun ApiException.toDebugDetail(): String {
    val sb = StringBuilder()
    sb.appendLine("Wayfarer API 请求失败")
    if (!serverBaseUrl.isNullOrBlank()) sb.appendLine("- base_url: $serverBaseUrl")
    if (!requestPath.isNullOrBlank()) sb.appendLine("- path: $requestPath")
    sb.appendLine("- http_status: $statusCode")
    if (!apiCode.isNullOrBlank()) sb.appendLine("- code: $apiCode")
    if (!apiMessage.isNullOrBlank()) sb.appendLine("- message: $apiMessage")
    if (!traceId.isNullOrBlank()) sb.appendLine("- trace_id: $traceId")
    val body = responseBody?.trim().orEmpty()
    if (body.isNotBlank()) {
        val clipped = if (body.length > 800) body.take(800) + "…(truncated)" else body
        sb.appendLine("- body: $clipped")
    }
    return sb.toString().trim()
}
