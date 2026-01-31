package com.wayfarer.android.steps

import org.junit.Assert.assertEquals
import org.junit.Test

class StepDeltaCalculatorTest {
    @Test
    fun firstSample_deltaIsZero() {
        val calc = StepDeltaCalculator()

        val out = calc.onSample(100)
        assertEquals(100L, out.stepCount)
        assertEquals(0L, out.stepDelta)
    }

    @Test
    fun normalIncrements_deltaEqualsDifference() {
        val calc = StepDeltaCalculator()

        calc.onSample(100)
        val out = calc.onSample(105)
        assertEquals(105L, out.stepCount)
        assertEquals(5L, out.stepDelta)
    }

    @Test
    fun noChange_deltaIsZero() {
        val calc = StepDeltaCalculator()

        calc.onSample(100)
        val out = calc.onSample(100)
        assertEquals(100L, out.stepCount)
        assertEquals(0L, out.stepDelta)
    }

    @Test
    fun resetWhenCurrentLessThanLast_deltaIsZeroAndLastBecomesCurrent() {
        val calc = StepDeltaCalculator()

        calc.onSample(100)
        val resetOut = calc.onSample(10)
        assertEquals(10L, resetOut.stepCount)
        assertEquals(0L, resetOut.stepDelta)

        val next = calc.onSample(12)
        assertEquals(12L, next.stepCount)
        assertEquals(2L, next.stepDelta)
    }

    @Test
    fun largeJump_deltaHandlesBigNumbers() {
        val calc = StepDeltaCalculator()

        calc.onSample(1)
        val out = calc.onSample(1_000_001)
        assertEquals(1_000_001L, out.stepCount)
        assertEquals(1_000_000L, out.stepDelta)
    }
}
