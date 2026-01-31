package com.wayfarer.android.dev

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdleWhitelistTest {
    @Test
    fun buildWhitelistCommand_matchesExpectedFormatExactly() {
        val cmd = DeviceIdleWhitelist.buildWhitelistCommand("com.wayfarer.android")
        assertEquals("dumpsys deviceidle whitelist +com.wayfarer.android", cmd)
    }
}
