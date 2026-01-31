package com.wayfarer.android.ui

import androidx.lifecycle.ViewModel

class TracksViewModel(
    amapKeyRaw: String?,
    amapKeyPresent: Boolean,
) : ViewModel() {
    val uiState = TracksUiState(
        amapKeyPresent = amapKeyPresent,
        amapKeyRaw = amapKeyRaw,
    )
}
