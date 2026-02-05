package com.wayfarer.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiErrorParserTest {
    @Test
    fun parse_returnsNull_forBlankOrNonJson() {
        assertNull(ApiErrorParser.parse(null))
        assertNull(ApiErrorParser.parse(""))
        assertNull(ApiErrorParser.parse("   "))
        assertNull(ApiErrorParser.parse("<html>nope</html>"))
    }

    @Test
    fun parse_extractsEnvelopeFields() {
        val env =
            ApiErrorParser.parse(
                """{"code":"AUTH_TOKEN_INVALID","message":"Invalid refresh token","trace_id":"t-123","details":null}""",
            )
        assertEquals("AUTH_TOKEN_INVALID", env?.code)
        assertEquals("Invalid refresh token", env?.message)
        assertEquals("t-123", env?.traceId)
    }

    @Test
    fun parse_ignoresMissingOrBlankFields() {
        val env = ApiErrorParser.parse("""{"code":"  ","message":"","trace_id":""}""")
        assertEquals(null, env?.code)
        assertEquals(null, env?.message)
        assertEquals(null, env?.traceId)
    }
}

