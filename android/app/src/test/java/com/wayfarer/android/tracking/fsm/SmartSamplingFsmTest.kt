package com.wayfarer.android.tracking.fsm

import com.wayfarer.android.tracking.fsm.SmartSamplingFsm.ActivityType
import com.wayfarer.android.tracking.fsm.SmartSamplingFsm.InferenceMode
import com.wayfarer.android.tracking.fsm.SmartSamplingFsm.MotionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartSamplingFsmTest {
    private fun step(
        fsm: SmartSamplingFsm,
        nowMs: Long,
        isCapturing: Boolean = true,
        gpsAvailable: Boolean = true,
        speedMps: Double? = null,
        accuracyM: Double? = 5.0,
        activityPermissionGranted: Boolean = false,
        activity: ActivityType? = null,
    ): SmartSamplingFsm.Output {
        return fsm.step(
            SmartSamplingFsm.Input(
                nowMs = nowMs,
                isCapturing = isCapturing,
                gpsAvailable = gpsAvailable,
                speedMps = speedMps,
                accuracyM = accuracyM,
                activityPermissionGranted = activityPermissionGranted,
                activity = activity,
            ),
        )
    }

    @Test
    fun activityRecognitionDenied_entersGpsOnlyMode() {
        val fsm = SmartSamplingFsm()
        val out = step(
            fsm = fsm,
            nowMs = 0L,
            gpsAvailable = true,
            speedMps = 1.2,
            activityPermissionGranted = false,
            activity = ActivityType.WALKING,
        )
        assertEquals(InferenceMode.GPS_ONLY, out.inferenceMode)
    }

    @Test
    fun speedBelow0_5For120s_transitionsToStationary() {
        val fsm = SmartSamplingFsm()
        var now = 0L

        // Reach WALKING using GPS-only inference: speed >=0.5 for >=10s + debounce.
        repeat(20) {
            step(fsm, nowMs = now, speedMps = 1.0)
            now += 1_000
        }
        assertEquals(MotionState.WALKING, fsm.currentState())

        // Now stop: <0.5 m/s for 120s + debounce.
        repeat(130) {
            step(fsm, nowMs = now, speedMps = 0.0)
            now += 1_000
        }

        val out = step(fsm, nowMs = now, speedMps = 0.0)
        assertEquals(MotionState.STATIONARY, out.state)
        assertEquals(120, out.profile.intervalSec)
        assertEquals(50f, out.profile.minDistanceM)
    }

    @Test
    fun speedAbove8_3For30s_transitionsToDriving() {
        val fsm = SmartSamplingFsm()
        var now = 0L

        // Start in WALKING.
        repeat(20) {
            step(fsm, nowMs = now, speedMps = 1.0)
            now += 1_000
        }
        assertEquals(MotionState.WALKING, fsm.currentState())

        // Sustained driving speed for >=30s (+ debounce).
        repeat(40) {
            step(fsm, nowMs = now, speedMps = 9.0)
            now += 1_000
        }

        val out = step(fsm, nowMs = now, speedMps = 9.0)
        assertEquals(MotionState.DRIVING, out.state)
        assertEquals(5, out.profile.intervalSec)
        assertEquals(20f, out.profile.minDistanceM)
    }

    @Test
    fun walkingSpeedAbove2_5For10s_transitionsToRunning() {
        val fsm = SmartSamplingFsm()
        var now = 0L

        // Start in WALKING.
        repeat(20) {
            step(fsm, nowMs = now, speedMps = 1.0)
            now += 1_000
        }
        assertEquals(MotionState.WALKING, fsm.currentState())

        // Sustained running speed for >=10s (+ debounce).
        repeat(20) {
            step(fsm, nowMs = now, speedMps = 3.0)
            now += 1_000
        }

        val out = step(fsm, nowMs = now, speedMps = 3.0)
        assertEquals(MotionState.RUNNING, out.state)
        assertEquals(3, out.profile.intervalSec)
        assertEquals(3f, out.profile.minDistanceM)
    }

    @Test
    fun runningSpeedBelow2_0For30s_transitionsBackToWalking_withDebounce() {
        val fsm = SmartSamplingFsm()
        var now = 0L

        // Enter RUNNING quickly via Activity Recognition.
        repeat(6) {
            step(
                fsm,
                nowMs = now,
                speedMps = 3.5,
                activityPermissionGranted = true,
                activity = ActivityType.RUNNING,
            )
            now += 1_000
        }
        assertEquals(MotionState.RUNNING, fsm.currentState())

        // Drop speed <2.0 for >=30s (+ debounce).
        repeat(40) {
            step(fsm, nowMs = now, speedMps = 1.5)
            now += 1_000
        }
        val out = step(fsm, nowMs = now, speedMps = 1.5)
        assertEquals(MotionState.WALKING, out.state)

        // Flip-flop protection: a brief spike above walking threshold (< debounce) must not exit WALKING.
        val before = fsm.currentState()
        repeat(2) {
            step(fsm, nowMs = now, speedMps = 3.5)
            now += 1_000
        }
        assertEquals(before, fsm.currentState())
    }

    @Test
    fun gpsUnavailableForMoreThan30s_transitionsToUnknown_andUsesUnknownProfile() {
        val fsm = SmartSamplingFsm()
        var now = 0L

        // Start in WALKING.
        repeat(20) {
            step(fsm, nowMs = now, gpsAvailable = true, speedMps = 1.0)
            now += 1_000
        }
        assertEquals(MotionState.WALKING, fsm.currentState())

        // GPS drops.
        repeat(40) {
            step(fsm, nowMs = now, gpsAvailable = false, speedMps = null)
            now += 1_000
        }

        val out = step(fsm, nowMs = now, gpsAvailable = false, speedMps = null)
        assertEquals(MotionState.UNKNOWN, out.state)
        assertEquals(10, out.profile.intervalSec)
        assertEquals(10f, out.profile.minDistanceM)
    }

    @Test
    fun stationaryJitterSpikeShorterThanDebounce_doesNotExitStationary() {
        val fsm = SmartSamplingFsm()
        var now = 0L

        // Reach STATIONARY.
        repeat(130) {
            step(fsm, nowMs = now, speedMps = 0.0)
            now += 1_000
        }
        assertEquals(MotionState.STATIONARY, fsm.currentState())

        // Brief spike above 0.5 m/s but < debounce window: must not transition.
        repeat(3) {
            step(fsm, nowMs = now, speedMps = 1.0)
            now += 1_000
        }
        assertEquals(MotionState.STATIONARY, fsm.currentState())
    }
}
