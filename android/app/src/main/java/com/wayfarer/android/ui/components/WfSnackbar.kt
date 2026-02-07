package com.wayfarer.android.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

val LocalWfSnackbarHostState: ProvidableCompositionLocal<SnackbarHostState?> =
    compositionLocalOf { null }

@Composable
fun wfSnackbarHostStateOrThrow(): SnackbarHostState {
    val state = LocalWfSnackbarHostState.current
    return requireNotNull(state) { "WfSnackbarHostState 未注入（请在 WayfarerAppShell 中提供）" }
}

