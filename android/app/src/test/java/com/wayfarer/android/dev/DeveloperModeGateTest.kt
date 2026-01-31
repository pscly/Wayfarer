package com.wayfarer.android.dev

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperModeGateTest {
    @Test
    fun startsDisabled() {
        val gate = DeveloperModeGate()
        assertEquals(false, gate.isEnabled)
    }

    @Test
    fun sixTaps_doesNotEnable() {
        val gate = DeveloperModeGate()
        repeat(6) { gate.tap() }
        assertEquals(false, gate.isEnabled)
    }

    @Test
    fun sevenTaps_enables() {
        val gate = DeveloperModeGate()
        repeat(6) { gate.tap() }
        assertEquals(false, gate.isEnabled)

        val enabledAfter7th = gate.tap()
        assertEquals(true, enabledAfter7th)
        assertEquals(true, gate.isEnabled)
    }

    @Test
    fun reset_disablesAndRearmsgate() {
        val gate = DeveloperModeGate()
        repeat(7) { gate.tap() }
        assertEquals(true, gate.isEnabled)

        gate.reset()
        assertEquals(false, gate.isEnabled)

        repeat(6) { gate.tap() }
        assertEquals(false, gate.isEnabled)

        gate.tap()
        assertEquals(true, gate.isEnabled)
    }
}
