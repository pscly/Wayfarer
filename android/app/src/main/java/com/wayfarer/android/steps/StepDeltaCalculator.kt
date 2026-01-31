package com.wayfarer.android.steps

import kotlin.math.floor

/**
 * Android-free calculator for deriving per-sample deltas from a
 * Sensor.TYPE_STEP_COUNTER-style cumulative count.
 */
class StepDeltaCalculator {
    data class Output(
        val stepCount: Long,
        val stepDelta: Long,
    )

    private var lastCount: Long? = null

    fun reset() {
        lastCount = null
    }

    fun onSample(cumulative: Int): Output = onSample(cumulative.toLong())

    fun onSample(cumulative: Long): Output {
        val current = cumulative.coerceAtLeast(0L)
        val last = lastCount
        val delta = when {
            last == null -> 0L
            current < last -> 0L // reset/reboot
            else -> current - last
        }
        lastCount = current
        return Output(stepCount = current, stepDelta = delta)
    }

    fun onSample(cumulative: Float): Output {
        val current = when {
            cumulative.isNaN() || cumulative.isInfinite() -> 0L
            else -> floor(cumulative.toDouble()).toLong().coerceAtLeast(0L)
        }
        return onSample(current)
    }

    fun onSample(cumulative: Double): Output {
        val current = when {
            cumulative.isNaN() || cumulative.isInfinite() -> 0L
            else -> floor(cumulative).toLong().coerceAtLeast(0L)
        }
        return onSample(current)
    }
}
