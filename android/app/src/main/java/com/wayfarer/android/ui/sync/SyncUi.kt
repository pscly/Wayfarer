package com.wayfarer.android.ui.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wayfarer.android.sync.SyncStateStore

/**
 * 轻量观察 SyncStateStore 的 Compose 适配层。
 *
 * 目标：同步 worker 在后台更新 SharedPreferences 时，UI 能即时刷新（无需 Room Flow / 轮询）。
 */
@Composable
fun rememberSyncSnapshot(context: Context = LocalContext.current): SyncStateStore.Snapshot {
    val appContext = remember(context) { context.applicationContext }
    var snapshot by remember { mutableStateOf(SyncStateStore.readSnapshot(appContext)) }

    DisposableEffect(appContext) {
        val prefs = SyncStateStore.prefs(appContext)
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                snapshot = SyncStateStore.readSnapshot(appContext)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return snapshot
}

