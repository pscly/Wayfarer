package com.wayfarer.android.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiExceptionTest {
    @Test
    fun toUserMessageZh_mapsCommonAuthCodes() {
        val invalidCred =
            ApiException(
                statusCode = 401,
                responseBody = null,
                message = "HTTP 401",
                apiCode = "AUTH_INVALID_CREDENTIALS",
            )
        assertEquals("用户名或密码错误", invalidCred.toUserMessageZh())

        val expired =
            ApiException(
                statusCode = 401,
                responseBody = null,
                message = "HTTP 401",
                apiCode = "AUTH_TOKEN_EXPIRED",
            )
        assertEquals("登录已过期，请重新登录", expired.toUserMessageZh())
    }
}

