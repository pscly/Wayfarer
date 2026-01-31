package com.wayfarer.android.dev

/**
 * Android-free developer mode gate.
 *
 * Intended usage: wire a UI element to call [tap] repeatedly; after [requiredTaps]
 * taps, [isEnabled] becomes true.
 */
class DeveloperModeGate(
    private val requiredTaps: Int = DEFAULT_REQUIRED_TAPS,
) {
    init {
        require(requiredTaps > 0) { "requiredTaps must be > 0" }
    }

    private var tapCount: Int = 0
    var isEnabled: Boolean = false
        private set

    /**
     * Registers a tap and returns the new enabled state.
     *
     * Once enabled, further taps are ignored until [reset] is called.
     */
    fun tap(): Boolean {
        if (isEnabled) return true

        tapCount += 1
        if (tapCount >= requiredTaps) {
            isEnabled = true
        }
        return isEnabled
    }

    fun reset() {
        tapCount = 0
        isEnabled = false
    }

    companion object {
        const val DEFAULT_REQUIRED_TAPS: Int = 7
    }
}
