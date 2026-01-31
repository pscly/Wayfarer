package com.wayfarer.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GzipUtilTest {
    @Test
    fun gzipThenGunzip_roundTripsUtf8Text() {
        val json = """{"k":"v","n":123,"unicode":"\u4e2d\u6587-\u00e9"}"""

        val gz = GzipUtil.gzipUtf8(json)

        // GZIP magic header: 1F 8B
        assertTrue(gz.size > 2)
        assertEquals(0x1f.toByte(), gz[0])
        assertEquals(0x8b.toByte(), gz[1])

        val out = GzipUtil.gunzipToUtf8(gz)
        assertEquals(json, out)
    }
}
