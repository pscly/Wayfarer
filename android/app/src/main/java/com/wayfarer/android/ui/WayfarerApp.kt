package com.wayfarer.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding

private enum class AppTab {
    RECORDS,
    MAP,
    STATS,
    SETTINGS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WayfarerApp() {
    var tab by rememberSaveable { androidx.compose.runtime.mutableStateOf(AppTab.RECORDS) }

    val title = stringResource(
        when (tab) {
            AppTab.RECORDS -> com.wayfarer.android.R.string.records_title
            AppTab.MAP -> com.wayfarer.android.R.string.map_title
            AppTab.STATS -> com.wayfarer.android.R.string.stats_title
            AppTab.SETTINGS -> com.wayfarer.android.R.string.tab_settings
        },
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
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
                RecordsScreen()
            }

            AppTab.MAP -> androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.padding(paddingValues),
            ) {
                MapScreen()
            }

            AppTab.STATS -> androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.padding(paddingValues),
            ) {
                StatsScreen()
            }

            AppTab.SETTINGS -> androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.padding(paddingValues),
            ) {
                SettingsScreen()
            }
        }
    }
}
