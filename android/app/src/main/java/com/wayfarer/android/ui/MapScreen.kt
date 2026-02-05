package com.wayfarer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wayfarer.android.amap.AmapApiKey
import com.wayfarer.android.amap.AmapMapView
import com.wayfarer.android.amap.Wgs84Point
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.tracking.TrackPointRepository
import com.wayfarer.android.ui.sync.rememberSyncSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private enum class MapRange {
    LAST_1H,
    LAST_24H,
    TODAY,
    LAST_7D,
}

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val syncSnapshot = rememberSyncSnapshot(context)

    val amapKeyRaw = remember { AmapApiKey.readFromManifest(context) }
    val amapKeyPresent = remember(amapKeyRaw) { AmapApiKey.isPresent(amapKeyRaw) }

    val repository = remember { TrackPointRepository(context) }

    var range by rememberSaveable { mutableStateOf(MapRange.LAST_24H) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var points by remember { mutableStateOf<List<TrackPointEntity>>(emptyList()) }

    fun resolveRange(): Pair<String, String> {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val start =
            when (range) {
                MapRange.LAST_1H -> now.minusSeconds(60L * 60L)
                MapRange.LAST_24H -> now.minusSeconds(24L * 60L * 60L)
                MapRange.LAST_7D -> now.minusSeconds(7L * 24L * 60L * 60L)
                MapRange.TODAY -> {
                    val zdt = ZonedDateTime.ofInstant(now, zone)
                    zdt.toLocalDate().atStartOfDay(zone).toInstant()
                }
            }
        return start.toString() to now.toString()
    }

    fun refresh() {
        val (startUtc, endUtc) = resolveRange()
        loading = true
        error = null
        repository.rangePointsAsync(
            startUtc = startUtc,
            endUtc = endUtc,
            limit = 5000,
            onResult = {
                points = it
                loading = false
            },
            onError = {
                error = it.message ?: it.toString()
                loading = false
            },
        )
    }

    LaunchedEffect(range, syncSnapshot.lastPullAtMs) {
        refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(com.wayfarer.android.R.string.map_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "点数：${points.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { refresh() }) {
                        Text(stringResource(com.wayfarer.android.R.string.track_stats_refresh))
                    }
                }

                RangeRow(
                    range = range,
                    onChange = { range = it },
                )

                if (loading) {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.track_stats_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (error != null) {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (points.isEmpty()) {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.map_no_points),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!amapKeyPresent) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.amap_key_missing_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.amap_key_missing_setup),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (amapKeyRaw.isNullOrBlank()) {
                            stringResource(com.wayfarer.android.R.string.amap_key_missing_manifest_blank)
                        } else {
                            stringResource(com.wayfarer.android.R.string.amap_key_missing_manifest_invalid)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        } else {
            // Full-screen map inside this tab.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                val sampled = remember(points) { samplePath(points, maxPoints = 1200) }
                val path = sampled.map { Wgs84Point(it.latitudeWgs84, it.longitudeWgs84) }
                val pathKey = sampled.size * 31 + (sampled.lastOrNull()?.recordedAtUtc?.hashCode() ?: 0)
                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    AmapMapView(
                        modifier = Modifier.fillMaxSize(),
                        pathWgs84 = path,
                        pathKey = pathKey,
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeRow(range: MapRange, onChange: (MapRange) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RangeChip(
            selected = range == MapRange.LAST_1H,
            label = stringResource(com.wayfarer.android.R.string.map_range_1h),
            onClick = { onChange(MapRange.LAST_1H) },
        )
        RangeChip(
            selected = range == MapRange.LAST_24H,
            label = stringResource(com.wayfarer.android.R.string.map_range_24h),
            onClick = { onChange(MapRange.LAST_24H) },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RangeChip(
            selected = range == MapRange.TODAY,
            label = stringResource(com.wayfarer.android.R.string.map_range_today),
            onClick = { onChange(MapRange.TODAY) },
        )
        RangeChip(
            selected = range == MapRange.LAST_7D,
            label = stringResource(com.wayfarer.android.R.string.map_range_7d),
            onClick = { onChange(MapRange.LAST_7D) },
        )
    }
}

@Composable
private fun RangeChip(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        FilledTonalButton(onClick = onClick) {
            Text(label)
        }
    } else {
        Button(onClick = onClick) {
            Text(label)
        }
    }
}

private fun samplePath(points: List<TrackPointEntity>, maxPoints: Int): List<TrackPointEntity> {
    if (points.size <= maxPoints) return points
    if (maxPoints <= 2) return listOf(points.first(), points.last())
    val step = (points.size / maxPoints).coerceAtLeast(1)
    val sampled = mutableListOf<TrackPointEntity>()
    var i = 0
    while (i < points.size) {
        sampled.add(points[i])
        i += step
    }
    if (sampled.last() != points.last()) sampled.add(points.last())
    return sampled
}
