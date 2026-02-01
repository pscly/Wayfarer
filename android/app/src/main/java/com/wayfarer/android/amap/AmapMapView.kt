package com.wayfarer.android.amap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.PolylineOptions

data class Wgs84Point(
    val latitude: Double,
    val longitude: Double,
)

@Composable
fun AmapMapView(
    modifier: Modifier = Modifier,
    pathWgs84: List<Wgs84Point> = emptyList(),
    pathKey: Int = 0,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Defensive: avoid instantiating MapView when key is missing.
    val amapKeyRaw = remember { AmapApiKey.readFromManifest(context) }
    val amapKeyPresent = remember(amapKeyRaw) { AmapApiKey.isPresent(amapKeyRaw) }

    if (!amapKeyPresent) {
        // CI-safe: no key -> show clear UI and skip MapView init.
        Surface(modifier = modifier) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Column {
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.amap_key_missing_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(com.wayfarer.android.R.string.amap_key_missing_short),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        return
    }

    // AMap SDK requires declaring privacy compliance status before MapView init.
    // In production, wire these to real user consent + a proper privacy flow.
    runCatching {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
    }

    val mapView = remember {
        MapView(context).apply { onCreate(null) }
    }

    // Avoid redrawing overlays on every recomposition.
    val lastRenderedKey = remember { mutableStateOf<Int?>(null) }

    // Basic lifecycle wiring.
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            if (pathKey == 0) return@AndroidView
            if (lastRenderedKey.value == pathKey) return@AndroidView
            lastRenderedKey.value = pathKey

            val amap = runCatching { view.map }.getOrNull() ?: return@AndroidView
            runCatching {
                amap.clear()

                if (pathWgs84.isEmpty()) return@runCatching

                val points = pathWgs84.map { LatLng(it.latitude, it.longitude) }
                if (points.size == 1) {
                    amap.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 16f))
                    return@runCatching
                }

                val options = PolylineOptions()
                    .addAll(points)
                    .width(8f)
                    .color(0xFF38BDF8.toInt())
                amap.addPolyline(options)

                val bounds = LatLngBounds.builder().also { b ->
                    for (p in points) b.include(p)
                }.build()

                // newLatLngBounds requires a laid-out view; post to be safe.
                view.post {
                    runCatching {
                        amap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
                    }
                }
            }
        },
    )
}
