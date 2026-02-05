package com.wayfarer.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerConfigStoreTest {
    @Test
    fun normalizeBaseUrlOrNull_blank_returnsEmptyString() {
        assertEquals("", ServerConfigStore.normalizeBaseUrlOrNull("   "))
    }

    @Test
    fun normalizeBaseUrlOrNull_addsHttps_whenSchemeMissing() {
        assertEquals("https://waf.pscly.cc", ServerConfigStore.normalizeBaseUrlOrNull("waf.pscly.cc"))
    }

    @Test
    fun normalizeBaseUrlOrNull_stripsTrailingSlash() {
        assertEquals("https://waf.pscly.cc", ServerConfigStore.normalizeBaseUrlOrNull("https://waf.pscly.cc/"))
    }

    @Test
    fun normalizeBaseUrlOrNull_stripsPathLikeV1() {
        assertEquals("https://waf.pscly.cc", ServerConfigStore.normalizeBaseUrlOrNull("https://waf.pscly.cc/v1"))
        assertEquals("https://waf.pscly.cc", ServerConfigStore.normalizeBaseUrlOrNull("waf.pscly.cc/v1/auth/login"))
    }

    @Test
    fun normalizeBaseUrlOrNull_keepsHostAndPort() {
        assertEquals("http://10.0.2.2:8000", ServerConfigStore.normalizeBaseUrlOrNull("http://10.0.2.2:8000/v1"))
        assertEquals("https://10.0.2.2:8000", ServerConfigStore.normalizeBaseUrlOrNull("10.0.2.2:8000/v1"))
    }

    @Test
    fun normalizeBaseUrlOrNull_returnsNull_forInvalidUrl() {
        assertNull(ServerConfigStore.normalizeBaseUrlOrNull("http://"))
        assertNull(ServerConfigStore.normalizeBaseUrlOrNull("waf.pscly.cc:abc"))
    }
}

