package com.wayfarer.android.tracking.fsm

/**
 * JVM-testable smart sampling FSM.
 *
 * Design baselines are taken from `.sisyphus/deliverables/plan-supplement.md`:
 * - State set: IDLE/STATIONARY/WALKING/RUNNING/CYCLING/DRIVING/UNKNOWN
 * - Intervals: STATIONARY=120s, WALKING=5s, RUNNING=3s, CYCLING=3s, DRIVING=5s, UNKNOWN=10s
 * - Threshold tokens: speed <0.5 m/s, speed >2.5 m/s, speed >8.3 m/s, debounce: 3-5s
 * - Rule: Activity Recognition > GPS speed inference; permission denied => GPS-only
 */
class SmartSamplingFsm(
    private val config: Config = Config(),
) {
    data class Config(
        val debounceMs: Long = 4_000, // in the required 3-5s window
        val gpsUnavailableToUnknownMs: Long = 30_000,
        val accuracyDegradedToUnknownMs: Long = 30_000,
        val accuracyDegradedThresholdM: Double = 100.0,
        val walkingSpeedMinMps: Double = 0.5,
        val runningSpeedMinMps: Double = 2.5,
        val drivingSpeedMinMps: Double = 8.3,
        val walkingFromRunningMaxMps: Double = 2.0,
        val stationaryWindowMs: Long = 120_000,
        val stationaryToWalkingWindowMs: Long = 10_000,
        val walkingToRunningWindowMs: Long = 10_000,
        val anyToDrivingWindowMs: Long = 30_000,
        val runningToWalkingWindowMs: Long = 30_000,
    )

    enum class MotionState {
        IDLE,
        STATIONARY,
        WALKING,
        RUNNING,
        CYCLING,
        DRIVING,
        UNKNOWN,
    }

    enum class InferenceMode {
        GPS_ONLY,
        FUSED,
    }

    /**
        * Coarse activity types used by the FSM. If Activity Recognition isn't available,
        * set [Input.activityPermissionGranted] to false and [Input.activity] to null.
        */
    enum class ActivityType {
        STILL,
        WALKING,
        RUNNING,
        ON_BICYCLE,
        IN_VEHICLE,
    }

    data class SamplingProfile(
        val intervalSec: Int?,
        val minDistanceM: Float?,
    )

    data class Input(
        val nowMs: Long,
        val isCapturing: Boolean,
        val gpsAvailable: Boolean,
        val speedMps: Double?,
        val accuracyM: Double?,
        val activityPermissionGranted: Boolean,
        val activity: ActivityType?,
    )

    data class Output(
        val state: MotionState,
        val profile: SamplingProfile,
        val inferenceMode: InferenceMode,
    )

    private var state: MotionState = MotionState.IDLE

    private var candidateState: MotionState? = null
    private var candidateSinceMs: Long = 0L

    private var gpsUnavailableSinceMs: Long? = null
    private var accuracyDegradedSinceMs: Long? = null

    private var belowStationarySpeedSinceMs: Long? = null
    private var atOrAboveWalkingSpeedSinceMs: Long? = null
    private var aboveRunningSpeedSinceMs: Long? = null
    private var aboveDrivingSpeedSinceMs: Long? = null
    private var belowWalkingFromRunningSinceMs: Long? = null

    fun currentState(): MotionState = state

    fun step(input: Input): Output {
        val inferenceMode = if (input.activityPermissionGranted) {
            InferenceMode.FUSED
        } else {
            InferenceMode.GPS_ONLY
        }

        if (!input.isCapturing) {
            // Hard reset to IDLE when user/service stops capture.
            state = MotionState.IDLE
            candidateState = null
            return Output(state = state, profile = profileFor(state), inferenceMode = inferenceMode)
        }

        updateGpsTimers(input)
        updateSpeedTimers(input)

        val activity = if (inferenceMode == InferenceMode.FUSED) input.activity else null
        val candidate = computeCandidateState(
            current = state,
            nowMs = input.nowMs,
            gpsAvailable = input.gpsAvailable,
            activity = activity,
        )

        state = applyDebounce(candidate, input.nowMs)

        return Output(state = state, profile = profileFor(state), inferenceMode = inferenceMode)
    }

    private fun updateGpsTimers(input: Input) {
        val now = input.nowMs

        if (!input.gpsAvailable) {
            if (gpsUnavailableSinceMs == null) gpsUnavailableSinceMs = now
        } else {
            gpsUnavailableSinceMs = null
        }

        val acc = input.accuracyM
        val degraded = acc != null && acc >= config.accuracyDegradedThresholdM
        if (degraded) {
            if (accuracyDegradedSinceMs == null) accuracyDegradedSinceMs = now
        } else {
            accuracyDegradedSinceMs = null
        }
    }

    private fun updateSpeedTimers(input: Input) {
        val now = input.nowMs
        val speed = input.speedMps

        // If GPS is unavailable we treat speed timers as unknown and reset them.
        if (!input.gpsAvailable || speed == null) {
            belowStationarySpeedSinceMs = null
            atOrAboveWalkingSpeedSinceMs = null
            aboveRunningSpeedSinceMs = null
            aboveDrivingSpeedSinceMs = null
            belowWalkingFromRunningSinceMs = null
            return
        }

        if (speed < config.walkingSpeedMinMps) {
            if (belowStationarySpeedSinceMs == null) belowStationarySpeedSinceMs = now
        } else {
            belowStationarySpeedSinceMs = null
        }

        if (speed >= config.walkingSpeedMinMps) {
            if (atOrAboveWalkingSpeedSinceMs == null) atOrAboveWalkingSpeedSinceMs = now
        } else {
            atOrAboveWalkingSpeedSinceMs = null
        }

        if (speed > config.runningSpeedMinMps) {
            if (aboveRunningSpeedSinceMs == null) aboveRunningSpeedSinceMs = now
        } else {
            aboveRunningSpeedSinceMs = null
        }

        if (speed > config.drivingSpeedMinMps) {
            if (aboveDrivingSpeedSinceMs == null) aboveDrivingSpeedSinceMs = now
        } else {
            aboveDrivingSpeedSinceMs = null
        }

        if (speed < config.walkingFromRunningMaxMps) {
            if (belowWalkingFromRunningSinceMs == null) belowWalkingFromRunningSinceMs = now
        } else {
            belowWalkingFromRunningSinceMs = null
        }
    }

    private fun computeCandidateState(
        current: MotionState,
        nowMs: Long,
        gpsAvailable: Boolean,
        activity: ActivityType?,
    ): MotionState {
        // GPS unavailable or accuracy degraded -> UNKNOWN after a grace window.
        if (!gpsAvailable && durationMsSince(gpsUnavailableSinceMs, nowMs) >= config.gpsUnavailableToUnknownMs) {
            return MotionState.UNKNOWN
        }
        if (durationMsSince(accuracyDegradedSinceMs, nowMs) >= config.accuracyDegradedToUnknownMs) {
            return MotionState.UNKNOWN
        }

        // Rule: Activity Recognition > GPS speed inference.
        activityToState(activity)?.let { return it }

        // GPS-only inference path.
        return when (current) {
            MotionState.IDLE -> {
                // On capture start, converge conservatively until we have enough signals.
                inferFromSpeedOrUnknown(nowMs)
            }

            MotionState.STATIONARY -> {
                if (durationMsSince(atOrAboveWalkingSpeedSinceMs, nowMs) >= config.stationaryToWalkingWindowMs) {
                    MotionState.WALKING
                } else {
                    MotionState.STATIONARY
                }
            }

            MotionState.WALKING -> {
                when {
                    durationMsSince(aboveDrivingSpeedSinceMs, nowMs) >= config.anyToDrivingWindowMs -> MotionState.DRIVING
                    durationMsSince(aboveRunningSpeedSinceMs, nowMs) >= config.walkingToRunningWindowMs -> MotionState.RUNNING
                    durationMsSince(belowStationarySpeedSinceMs, nowMs) >= config.stationaryWindowMs -> MotionState.STATIONARY
                    else -> MotionState.WALKING
                }
            }

            MotionState.RUNNING -> {
                when {
                    durationMsSince(aboveDrivingSpeedSinceMs, nowMs) >= config.anyToDrivingWindowMs -> MotionState.DRIVING
                    durationMsSince(belowWalkingFromRunningSinceMs, nowMs) >= config.runningToWalkingWindowMs -> MotionState.WALKING
                    else -> MotionState.RUNNING
                }
            }

            MotionState.CYCLING -> {
                if (durationMsSince(aboveDrivingSpeedSinceMs, nowMs) >= config.anyToDrivingWindowMs) {
                    MotionState.DRIVING
                } else {
                    MotionState.CYCLING
                }
            }

            MotionState.DRIVING -> {
                if (durationMsSince(belowStationarySpeedSinceMs, nowMs) >= config.stationaryWindowMs) {
                    MotionState.STATIONARY
                } else {
                    MotionState.DRIVING
                }
            }

            MotionState.UNKNOWN -> inferFromSpeedOrUnknown(nowMs)
        }
    }

    private fun inferFromSpeedOrUnknown(nowMs: Long): MotionState {
        return when {
            durationMsSince(aboveDrivingSpeedSinceMs, nowMs) >= config.anyToDrivingWindowMs -> MotionState.DRIVING
            durationMsSince(aboveRunningSpeedSinceMs, nowMs) >= config.walkingToRunningWindowMs -> MotionState.RUNNING
            durationMsSince(belowStationarySpeedSinceMs, nowMs) >= config.stationaryWindowMs -> MotionState.STATIONARY
            durationMsSince(atOrAboveWalkingSpeedSinceMs, nowMs) >= config.stationaryToWalkingWindowMs -> MotionState.WALKING
            else -> MotionState.UNKNOWN
        }
    }

    private fun activityToState(activity: ActivityType?): MotionState? {
        return when (activity) {
            ActivityType.STILL -> MotionState.STATIONARY
            ActivityType.WALKING -> MotionState.WALKING
            ActivityType.RUNNING -> MotionState.RUNNING
            ActivityType.ON_BICYCLE -> MotionState.CYCLING
            ActivityType.IN_VEHICLE -> MotionState.DRIVING
            null -> null
        }
    }

    private fun applyDebounce(candidate: MotionState, nowMs: Long): MotionState {
        if (candidate == state) {
            candidateState = null
            return state
        }

        if (candidateState != candidate) {
            candidateState = candidate
            candidateSinceMs = nowMs
            return state
        }

        val stableMs = nowMs - candidateSinceMs
        return if (stableMs >= config.debounceMs) {
            candidate
        } else {
            state
        }
    }

    private fun durationMsSince(sinceMs: Long?, nowMs: Long): Long {
        return if (sinceMs == null) 0L else (nowMs - sinceMs).coerceAtLeast(0L)
    }

    private fun profileFor(state: MotionState): SamplingProfile {
        // Design-default interval/minDistance mapping (used to drive location request params).
        return when (state) {
            MotionState.IDLE -> SamplingProfile(intervalSec = null, minDistanceM = null)
            MotionState.STATIONARY -> SamplingProfile(intervalSec = 120, minDistanceM = 50f)
            MotionState.WALKING -> SamplingProfile(intervalSec = 5, minDistanceM = 5f)
            MotionState.RUNNING -> SamplingProfile(intervalSec = 3, minDistanceM = 3f)
            MotionState.CYCLING -> SamplingProfile(intervalSec = 3, minDistanceM = 5f)
            MotionState.DRIVING -> SamplingProfile(intervalSec = 5, minDistanceM = 20f)
            MotionState.UNKNOWN -> SamplingProfile(intervalSec = 10, minDistanceM = 10f)
        }
    }
}
