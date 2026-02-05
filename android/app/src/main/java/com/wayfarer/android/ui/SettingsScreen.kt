package com.wayfarer.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wayfarer.android.BuildConfig
import com.wayfarer.android.R
import com.wayfarer.android.amap.AmapApiKey
import com.wayfarer.android.api.AuthStore
import com.wayfarer.android.api.ServerConfigStore
import com.wayfarer.android.dev.DeveloperModeGate
import com.wayfarer.android.sync.SyncStateStore
import com.wayfarer.android.sync.WayfarerSyncManager
import com.wayfarer.android.sync.WayfarerSyncScheduler
import com.wayfarer.android.tracking.LifeEventRepository
import com.wayfarer.android.tracking.TrackPointRepository
import com.wayfarer.android.ui.auth.AuthGateStore
import com.wayfarer.android.ui.sync.rememberSyncSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SettingsRoute {
    HOME,
    ACCOUNT,
    SYNC,
    PERMISSIONS,
    ABOUT,
    DEVELOPER,
}

fun settingsTitle(route: SettingsRoute): String {
    return when (route) {
        SettingsRoute.HOME -> "设置"
        SettingsRoute.ACCOUNT -> "账号"
        SettingsRoute.SYNC -> "同步"
        SettingsRoute.PERMISSIONS -> "权限"
        SettingsRoute.ABOUT -> "关于"
        SettingsRoute.DEVELOPER -> "开发者工具"
    }
}

@Composable
fun SettingsScreen(
    route: SettingsRoute,
    onRouteChange: (SettingsRoute) -> Unit,
    onAuthStateChanged: () -> Unit,
) {
    val context = LocalContext.current

    val syncSnapshot = rememberSyncSnapshot(context)
    val syncManager = remember { WayfarerSyncManager(context) }
    val trackRepo = remember { TrackPointRepository(context) }
    val lifeEventRepo = remember { LifeEventRepository(context) }

    val dtf = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    fun fmtMs(ms: Long?): String {
        if (ms == null) return "-"
        return runCatching { dtf.format(Instant.ofEpochMilli(ms)) }.getOrDefault("-")
    }

    val locationGranted = hasLocationPermission(context)
    val activityGranted =
        context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    // Auth snapshot (refresh on resume, because Worker may clear tokens).
    var authedUserId by remember { mutableStateOf(AuthStore.readUserId(context)) }
    var authedUsername by remember { mutableStateOf(AuthStore.readUsername(context)) }

    fun reloadAuthState() {
        authedUserId = AuthStore.readUserId(context)
        authedUsername = AuthStore.readUsername(context)
    }

    DisposableEffect(Unit) {
        reloadAuthState()
        onDispose { }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_clear_local_data_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_local_data_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        // 同时清空轨迹点与标记（让“清空本地数据”语义完整）。
                        trackRepo.clearAllAsync(
                            onDone = {
                                lifeEventRepo.clearAllAsync(
                                    onDone = {},
                                    onError = {},
                                )
                            },
                            onError = {},
                        )
                    },
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    when (route) {
        SettingsRoute.HOME -> SettingsHome(
            authedUserId = authedUserId,
            authedUsername = authedUsername,
            syncSnapshot = syncSnapshot,
            locationGranted = locationGranted,
            activityGranted = activityGranted,
            onOpenAccount = { onRouteChange(SettingsRoute.ACCOUNT) },
            onOpenSync = { onRouteChange(SettingsRoute.SYNC) },
            onOpenPermissions = { onRouteChange(SettingsRoute.PERMISSIONS) },
            onOpenAbout = { onRouteChange(SettingsRoute.ABOUT) },
        )

        SettingsRoute.ACCOUNT -> SettingsAccountPage(
            authedUserId = authedUserId,
            authedUsername = authedUsername,
            onLogin = {
                // 离线模式进入主界面后，如果用户想登录，需要重新展示 AuthGate。
                AuthGateStore.reset(context)
                onAuthStateChanged()
            },
            onLogout = {
                syncManager.logout()
                WayfarerSyncScheduler.cancelAll(context)
                AuthGateStore.reset(context)
                onAuthStateChanged()
            },
        )

        SettingsRoute.SYNC -> SettingsSyncPage(
            authedUserId = authedUserId,
            syncSnapshot = syncSnapshot,
            fmtMs = ::fmtMs,
            onToggleBackfill = { enabled ->
                SyncStateStore.setBackfillEnabled(context, enabled)
                if (enabled) {
                    WayfarerSyncScheduler.enqueueBackfillStep(context, delayMinutes = 0)
                } else {
                    WayfarerSyncScheduler.cancelBackfill(context)
                    SyncStateStore.markPaused(context, reason = "已暂停历史回填")
                }
            },
            onSyncNow = {
                WayfarerSyncScheduler.enqueueOneTimeSync(context)
            },
            onBootstrapRecent = {
                WayfarerSyncScheduler.enqueueBootstrapRecent(context)
            },
            onBackfillStep = {
                WayfarerSyncScheduler.enqueueBackfillStep(context, delayMinutes = 0)
            },
            onTestConnection = {
                syncManager.testConnectionAsync(
                    onResult = { ok ->
                        if (!ok) {
                            SyncStateStore.markError(context, "服务器连接失败")
                        }
                    },
                    onError = { err ->
                        SyncStateStore.markError(context, err.message ?: err.toString())
                    },
                )
            },
        )

        SettingsRoute.PERMISSIONS -> SettingsPermissionsPage(
            locationGranted = locationGranted,
            activityGranted = activityGranted,
            gpsEnabled = isGpsEnabled(context),
            onOpenAppSettings = { openAppSettings(context) },
            onOpenLocationSettings = { openLocationSettings(context) },
        )

        SettingsRoute.ABOUT -> SettingsAboutPage(
            syncSnapshot = syncSnapshot,
            onShowClearDialog = { showClearDialog = true },
            onUnlockedDeveloperTools = { onRouteChange(SettingsRoute.DEVELOPER) },
            onOpenDeveloperTools = { onRouteChange(SettingsRoute.DEVELOPER) },
        )

        SettingsRoute.DEVELOPER -> DeveloperToolsPage()
    }
}

@Composable
private fun SettingsHome(
    authedUserId: String?,
    authedUsername: String?,
    syncSnapshot: SyncStateStore.Snapshot,
    locationGranted: Boolean,
    activityGranted: Boolean,
    onOpenAccount: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val accountSubtitle =
        if (!authedUserId.isNullOrBlank() && !authedUsername.isNullOrBlank()) {
            "已登录：$authedUsername"
        } else {
            "未登录（离线模式）"
        }

    val syncSubtitle =
        when (syncSnapshot.phase) {
            SyncStateStore.SyncPhase.BOOTSTRAP_RECENT -> syncSnapshot.progressText ?: "正在同步…"
            SyncStateStore.SyncPhase.BACKFILLING -> syncSnapshot.progressText ?: "正在回填历史…"
            SyncStateStore.SyncPhase.PAUSED -> syncSnapshot.progressText ?: "已暂停"
            SyncStateStore.SyncPhase.ERROR -> syncSnapshot.lastError ?: "同步失败"
            SyncStateStore.SyncPhase.UP_TO_DATE -> "已同步完成"
            SyncStateStore.SyncPhase.IDLE -> "空闲"
        }

    val permissionsSubtitle =
        "定位：${if (locationGranted) "已授权" else "未授权"}；活动：${if (activityGranted) "已授权" else "未授权"}"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionTitle(text = "常用")
        }
        item {
            NavItem(
                icon = Icons.Rounded.AccountCircle,
                title = "账号",
                subtitle = accountSubtitle,
                onClick = onOpenAccount,
            )
        }
        item {
            NavItem(
                icon = Icons.Rounded.Sync,
                title = "同步",
                subtitle = syncSubtitle,
                onClick = onOpenSync,
            )
        }
        item {
            NavItem(
                icon = Icons.Rounded.Lock,
                title = "权限与系统",
                subtitle = permissionsSubtitle,
                onClick = onOpenPermissions,
            )
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            SectionTitle(text = "其它")
        }
        item {
            NavItem(
                icon = Icons.Rounded.Info,
                title = "关于",
                subtitle = "版本、服务器信息与开发者工具",
                onClick = onOpenAbout,
            )
        }
    }
}

@Composable
private fun SettingsAccountPage(
    authedUserId: String?,
    authedUsername: String?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "账号", style = MaterialTheme.typography.titleMedium)
                    if (!authedUserId.isNullOrBlank()) {
                        Text(text = "用户名：${authedUsername ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "用户 ID：$authedUserId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "当前处于离线模式。登录后可同步云端历史数据，并在多设备之间一致。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            if (!authedUserId.isNullOrBlank()) {
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_logout))
                }
            } else {
                Button(
                    onClick = onLogin,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("登录 / 注册")
                }
            }
        }
    }
}

@Composable
private fun SettingsSyncPage(
    authedUserId: String?,
    syncSnapshot: SyncStateStore.Snapshot,
    fmtMs: (Long?) -> String,
    onToggleBackfill: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onBootstrapRecent: () -> Unit,
    onBackfillStep: () -> Unit,
    onTestConnection: () -> Unit,
) {
    val canSync = !authedUserId.isNullOrBlank()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = "同步状态", style = MaterialTheme.typography.titleMedium)

                    val phaseText =
                        when (syncSnapshot.phase) {
                            SyncStateStore.SyncPhase.BOOTSTRAP_RECENT -> "正在同步近期数据"
                            SyncStateStore.SyncPhase.BACKFILLING -> "正在回填历史数据"
                            SyncStateStore.SyncPhase.PAUSED -> "历史回填已暂停"
                            SyncStateStore.SyncPhase.ERROR -> "同步失败"
                            SyncStateStore.SyncPhase.UP_TO_DATE -> "已同步完成"
                            SyncStateStore.SyncPhase.IDLE -> "空闲"
                        }
                    Text(
                        text = phaseText,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    val msg =
                        when (syncSnapshot.phase) {
                            SyncStateStore.SyncPhase.ERROR -> syncSnapshot.lastError
                            else -> syncSnapshot.progressText
                        }
                    if (!msg.isNullOrBlank()) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (syncSnapshot.phase == SyncStateStore.SyncPhase.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "上次上传：${fmtMs(syncSnapshot.lastUploadAtMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "上次拉取：${fmtMs(syncSnapshot.lastPullAtMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "后台回填历史",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = syncSnapshot.backfillEnabled,
                            onCheckedChange = { onToggleBackfill(it) },
                            enabled = canSync,
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = if (canSync) "操作" else "需要登录后才能同步",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Button(
                enabled = canSync,
                onClick = onSyncNow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("立即同步（上传 + 增量拉取）")
            }
        }

        item {
            FilledTonalButton(
                enabled = canSync,
                onClick = onBootstrapRecent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("同步近期 7 天（首次/重置）")
            }
        }

        item {
            FilledTonalButton(
                enabled = canSync && syncSnapshot.backfillEnabled,
                onClick = onBackfillStep,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("回填一步（更早历史）")
            }
        }

        item {
            FilledTonalButton(
                onClick = onTestConnection,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_test_connection))
            }
        }
    }
}

@Composable
private fun SettingsPermissionsPage(
    locationGranted: Boolean,
    activityGranted: Boolean,
    gpsEnabled: Boolean,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "权限与系统", style = MaterialTheme.typography.titleMedium)
                    StatusRow(label = "定位权限", ok = locationGranted)
                    StatusRow(label = "活动识别", ok = activityGranted)
                    StatusRow(label = "GPS 开关", ok = gpsEnabled)
                }
            }
        }

        item {
            Button(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_open_app_settings))
            }
        }
        item {
            FilledTonalButton(onClick = onOpenLocationSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_open_location_settings))
            }
        }
    }
}

@Composable
private fun SettingsAboutPage(
    syncSnapshot: SyncStateStore.Snapshot,
    onShowClearDialog: () -> Unit,
    onUnlockedDeveloperTools: () -> Unit,
    onOpenDeveloperTools: () -> Unit,
) {
    val context = LocalContext.current
    val developerModeGate = remember { DeveloperModeGate() }
    var developerModeEnabled by rememberSaveable { mutableStateOf(false) }

    val baseUrl = remember { ServerConfigStore.readBaseUrl(context) }
    val amapKeyRaw = remember { AmapApiKey.readFromManifest(context) }
    val amapKeyPresent = remember(amapKeyRaw) { AmapApiKey.isPresent(amapKeyRaw) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "Wayfarer", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable {
                                developerModeEnabled = developerModeGate.tap()
                                if (developerModeEnabled) onUnlockedDeveloperTools()
                            },
                        )
                        if (developerModeEnabled) {
                            Text(
                                text = "已开启",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = "API：$baseUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "同步：${syncSnapshot.phase.raw}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "地图", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (amapKeyPresent) "高德 API Key：已配置" else "高德 API Key：未配置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (amapKeyPresent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    if (!amapKeyPresent) {
                        Text(
                            text = stringResource(R.string.amap_key_missing_setup),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = onShowClearDialog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.settings_clear_local_data))
            }
        }

        if (developerModeEnabled) {
            item {
                NavItem(
                    icon = Icons.Rounded.Info,
                    title = "开发者工具",
                    subtitle = "传感器探测、排障工具等",
                    onClick = onOpenDeveloperTools,
                )
            }
        } else {
            item {
                Text(
                    text = stringResource(R.string.dev_tools_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeveloperToolsPage() {
    val context = LocalContext.current
    // 复用原有开发者卡片（仅作为隐藏入口）。
    DeveloperToolsCard(context = context)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (ok) "正常" else "需要处理",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun openLocationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun hasLocationPermission(context: Context): Boolean {
    return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun isGpsEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return false
    return runCatching {
        lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
}
