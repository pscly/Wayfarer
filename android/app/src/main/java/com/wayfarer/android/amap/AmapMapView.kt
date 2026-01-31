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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer

@Composable
fun AmapMapView(
    modifier: Modifier = Modifier,
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
                        text = "AMap API key missing",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Set WAYFARER_AMAP_API_KEY via -P, env var, or android/local.properties.",
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
    )
}
