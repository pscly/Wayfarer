package com.wayfarer.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wayfarer.android.api.AuthStore
import com.wayfarer.android.steps.StepsSamplingScheduler
import com.wayfarer.android.sync.WayfarerSyncScheduler
import com.wayfarer.android.sync.SyncStateStore
import com.wayfarer.android.ui.auth.AuthGateScreen
import com.wayfarer.android.ui.auth.AuthGateStore
import com.wayfarer.android.ui.sync.SyncBanner
import com.wayfarer.android.ui.sync.rememberSyncSnapshot
import com.wayfarer.android.ui.components.LocalWfSnackbarHostState

private enum class AppTab {
    RECORDS,
    MAP,
    STATS,
    SETTINGS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WayfarerApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    data class AuthSnapshot(
        val userId: String?,
        val refreshToken: String?,
    ) {
        val isLoggedIn: Boolean
            get() = !userId.isNullOrBlank() && !refreshToken.isNullOrBlank()
    }

    fun readAuthSnapshot(): AuthSnapshot {
        return AuthSnapshot(
            userId = AuthStore.readUserId(context),
            refreshToken = AuthStore.readRefreshToken(context),
        )
    }

    var authSnapshot by remember { mutableStateOf(readAuthSnapshot()) }
    var gateDismissed by remember { mutableStateOf(AuthGateStore.isDismissed(context)) }

    fun refreshAuthState() {
        authSnapshot = readAuthSnapshot()
        gateDismissed = AuthGateStore.isDismissed(context)
    }

    // 国内手机“系统步数”：后台 15 分钟采样（best-effort）。
    LaunchedEffect(Unit) {
        StepsSamplingScheduler.ensurePeriodicScheduled(context)
    }

    // 当 Auth 变化发生在后台（例如 Worker 刷新失败清理 token）时，App 回到前台需要刷新状态。
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshAuthState()
                    // App 回到前台时，触发一次计步采样，让“今日步数”更及时。
                    StepsSamplingScheduler.enqueueSampleNow(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(authSnapshot.isLoggedIn) {
        if (authSnapshot.isLoggedIn) {
            WayfarerSyncScheduler.ensurePeriodicSyncScheduled(context)
        } else {
            WayfarerSyncScheduler.cancelPeriodicSync(context)
        }
    }

    if (!authSnapshot.isLoggedIn && !gateDismissed) {
        AuthGateScreen(
            onLoginSuccess = { refreshAuthState() },
            onContinueOffline = { refreshAuthState() },
        )
        return
    }

    WayfarerAppShell(onAuthStateChanged = { refreshAuthState() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WayfarerAppShell(
    onAuthStateChanged: () -> Unit,
) {
    var tab by rememberSaveable { androidx.compose.runtime.mutableStateOf(AppTab.RECORDS) }
    var settingsRoute by rememberSaveable { androidx.compose.runtime.mutableStateOf(SettingsRoute.HOME) }

    val context = LocalContext.current
    val syncSnapshot = rememberSyncSnapshot(context)
    val snackbarHostState = remember { SnackbarHostState() }
    val userId = AuthStore.readUserId(context)
    val refresh = AuthStore.readRefreshToken(context)
    val isLoggedIn = !userId.isNullOrBlank() && !refresh.isNullOrBlank()

    val title =
        if (tab == AppTab.SETTINGS) {
            settingsTitle(settingsRoute)
        } else {
            stringResource(
                when (tab) {
                    AppTab.RECORDS -> com.wayfarer.android.R.string.records_title
                    AppTab.MAP -> com.wayfarer.android.R.string.map_title
                    AppTab.STATS -> com.wayfarer.android.R.string.stats_title
                    AppTab.SETTINGS -> com.wayfarer.android.R.string.tab_settings
                },
            )
        }

    CompositionLocalProvider(LocalWfSnackbarHostState provides snackbarHostState) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        val canBack = tab == AppTab.SETTINGS && settingsRoute != SettingsRoute.HOME
                        if (canBack) {
                            IconButton(onClick = { settingsRoute = SettingsRoute.HOME }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                            }
                        }
                    },
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                )
            },
            bottomBar = {
                NavigationBar(tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = tab == AppTab.RECORDS,
                        onClick = { tab = AppTab.RECORDS },
                        icon = { Icon(Icons.Rounded.LocationOn, contentDescription = null) },
                        label = { Text(stringResource(com.wayfarer.android.R.string.tab_tracks)) },
                    )
                    NavigationBarItem(
                        selected = tab == AppTab.MAP,
                        onClick = { tab = AppTab.MAP },
                        icon = { Icon(Icons.Rounded.Map, contentDescription = null) },
                        label = { Text(stringResource(com.wayfarer.android.R.string.tab_map)) },
                    )
                    NavigationBarItem(
                        selected = tab == AppTab.STATS,
                        onClick = { tab = AppTab.STATS },
                        icon = { Icon(Icons.Rounded.BarChart, contentDescription = null) },
                        label = { Text(stringResource(com.wayfarer.android.R.string.tab_stats)) },
                    )
                    NavigationBarItem(
                        selected = tab == AppTab.SETTINGS,
                        onClick = { tab = AppTab.SETTINGS },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text(stringResource(com.wayfarer.android.R.string.tab_settings)) },
                    )
                }
            },
        ) { paddingValues ->
            when (tab) {
            AppTab.RECORDS -> androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.padding(paddingValues),
            ) {
                androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    if (isLoggedIn) {
                        SyncBanner(
                            snapshot = syncSnapshot,
                            onOpenDetails = {
                                tab = AppTab.SETTINGS
                                settingsRoute = SettingsRoute.SYNC
                            },
                            onRetry = {
                                when {
                                    syncSnapshot.bootstrapDoneAtMs == null ->
                                        WayfarerSyncScheduler.enqueueBootstrapRecent(context)
                                    syncSnapshot.backfillDoneAtMs == null ->
                                        WayfarerSyncScheduler.enqueueBackfillStep(context, delayMinutes = 0)
                                    else -> WayfarerSyncScheduler.enqueueOneTimeSync(context)
                                }
                            },
                            onResumeBackfill = {
                                SyncStateStore.setBackfillEnabled(context, true)
                                WayfarerSyncScheduler.enqueueBackfillStep(context, delayMinutes = 0)
                            },
                        )
                    }
                    androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                        RecordsScreen()
                    }
                }
            }

            AppTab.MAP -> androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.padding(paddingValues),
            ) {
                androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    if (isLoggedIn) {
                        SyncBanner(
                            snapshot = syncSnapshot,
                            onOpenDetails = {
                                tab = AppTab.SETTINGS
                                settingsRoute = SettingsRoute.SYNC
                            },
                            onRetry = {
                                when {
                                    syncSnapshot.bootstrapDoneAtMs == null ->
                                        WayfarerSyncScheduler.enqueueBootstrapRecent(context)
                                    syncSnapshot.backfillDoneAtMs == null ->
                                        WayfarerSyncScheduler.enqueueBackfillStep(context, delayMinutes = 0)
                                    else -> WayfarerSyncScheduler.enqueueOneTimeSync(context)
                                }
                            },
                            onResumeBackfill = {
                                SyncStateStore.setBackfillEnabled(context, true)
                                WayfarerSyncScheduler.enqueueBackfillStep(context, delayMinutes = 0)
                            },
                        )
                    }
                    androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                        MapScreen()
                    }
                }
            }

            AppTab.STATS -> androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.padding(paddingValues),
            ) {
                androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    if (isLoggedIn) {
                        SyncBanner(
                            snapshot = syncSnapshot,
                            onOpenDetails = {
                                tab = AppTab.SETTINGS
                                settingsRoute = SettingsRoute.SYNC
                            },
                            onRetry = {
                                when {
                                    syncSnapshot.bootstrapDoneAtMs == null ->
                                        WayfarerSyncScheduler.enqueueBootstrapRecent(context)
                                    syncSnapshot.backfillDoneAtMs == null ->
                                        WayfarerSyncScheduler.enqueueBackfillStep(context, delayMinutes = 0)
                                    else -> WayfarerSyncScheduler.enqueueOneTimeSync(context)
                                }
                            },
                            onResumeBackfill = {
                                SyncStateStore.setBackfillEnabled(context, true)
                                WayfarerSyncScheduler.enqueueBackfillStep(context, delayMinutes = 0)
                            },
                        )
                    }
                    androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                        StatsScreen()
                    }
                }
            }

            AppTab.SETTINGS -> androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.padding(paddingValues),
            ) {
                SettingsScreen(
                    route = settingsRoute,
                    onRouteChange = { settingsRoute = it },
                    onAuthStateChanged = onAuthStateChanged,
                )
            }
            }
        }
    }
}
