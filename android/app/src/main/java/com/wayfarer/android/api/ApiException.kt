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
) : RuntimeException(message)
