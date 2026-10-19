package com.example.disasterconnect.map

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.disasterconnect.ui.theme.Charcoal
import com.example.disasterconnect.ui.theme.OutlineLight
import com.example.disasterconnect.ui.theme.White
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

// =========================================================
// BASEMAP
// =========================================================
// Same raster basemap as MapActivity. Tiles come from the Internet; MapLibre's
// built-in ambient cache may still show areas viewed earlier while offline.

private val BASEMAP_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "arcgis-tiles": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "attribution": "Basemap source: Esri, USGS"
    }
  },
  "layers": [{"id": "arcgis-layer", "type": "raster", "source": "arcgis-tiles", "minzoom": 0, "maxzoom": 19}]
}
""".trimIndent()

private const val USER_ZOOM = 15.0

enum class MapLoadState {
    LOADING,
    READY,
    FAILED
}

// =========================================================
// COMPOSE MAP
// =========================================================

/**
 * MapLibre map centred on a real, known location. Callers must only show it when
 * the location is known; it never falls back to a default position.
 *
 * [recenterRequest] and [reloadRequest] are counters: increase them to recenter
 * the camera or to load the map style again after a failure.
 */
@Composable
fun LocationMap(
    latitude: Double,
    longitude: Double,
    recenterRequest: Int,
    reloadRequest: Int,
    onLoadStateChange: (MapLoadState) -> Unit,
    modifier: Modifier = Modifier
) {

    val context = LocalContext.current

    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember { createMapView(context) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    var userMarker by remember { mutableStateOf<Marker?>(null) }

    val userIcon = remember { IconFactory.getInstance(context).fromBitmap(userLocationBitmap(context)) }

    // ---------------------------------------------------------
    // Lifecycle forwarding (rules.md §6)
    // ---------------------------------------------------------

    DisposableEffect(lifecycle, mapView) {

        var started = false

        var resumed = false

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> Unit
            }
        }

        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = Unit
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = mapView.onLowMemory()
        }

        lifecycle.addObserver(observer)

        context.registerComponentCallbacks(memoryCallbacks)

        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memoryCallbacks)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // ---------------------------------------------------------
    // Style (initial load and retry)
    // ---------------------------------------------------------

    DisposableEffect(mapView) {

        val failure = MapView.OnDidFailLoadingMapListener { onLoadStateChange(MapLoadState.FAILED) }

        mapView.addOnDidFailLoadingMapListener(failure)

        onDispose { mapView.removeOnDidFailLoadingMapListener(failure) }
    }

    LaunchedEffect(mapView, reloadRequest) {

        onLoadStateChange(MapLoadState.LOADING)

        // After a retry the marker is added again once the new style is ready.
        map = null

        mapView.getMapAsync { loaded ->
            loaded.setStyle(Style.Builder().fromJson(BASEMAP_STYLE_JSON)) {
                loaded.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), USER_ZOOM))
                map = loaded
                onLoadStateChange(MapLoadState.READY)
            }
        }
    }

    // ---------------------------------------------------------
    // "My location" marker and recentering
    // ---------------------------------------------------------

    LaunchedEffect(map, latitude, longitude) {

        val currentMap = map ?: return@LaunchedEffect

        userMarker?.let(currentMap::removeMarker)

        userMarker = currentMap.addMarker(
            MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title("You are here")
                .icon(userIcon)
        )
    }

    LaunchedEffect(recenterRequest) {

        if (recenterRequest > 0) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), USER_ZOOM))
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

// =========================================================
// HELPERS
// =========================================================

// Texture mode lets the map clip to rounded corners and scroll with the page.
// Rotation and tilt are off to keep the map predictable.
@SuppressLint("ClickableViewAccessibility")
private fun createMapView(context: Context): MapView {

    MapLibre.getInstance(context)

    val options =
        MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .compassEnabled(false)

    return MapView(context, options).apply {
        onCreate(null)
        // Keep map drags on the map instead of scrolling the Home page.
        setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
    }
}

/** Map legend symbol matching the "My location" marker (outlined so the white ring shows on light cards). */
@Composable
fun UserLocationLegendDot(modifier: Modifier = Modifier) {

    Canvas(modifier = modifier.size(USER_DOT_DP.dp)) {

        val radius = size.minDimension / 2

        drawCircle(color = White, radius = radius)

        drawCircle(color = Charcoal, radius = radius - USER_RING_DP.dp.toPx())

        drawCircle(color = OutlineLight, radius = radius, style = Stroke(width = 1.dp.toPx()))
    }
}

/** Solid charcoal dot with a white ring. [UserLocationLegendDot] draws the same shape. */
private fun userLocationBitmap(context: Context): Bitmap {

    val density = context.resources.displayMetrics.density

    val size = (USER_DOT_DP * density).toInt()

    val radius = size / 2f

    return createBitmap(size, size).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = White.toArgb()
        canvas.drawCircle(radius, radius, radius, paint)
        paint.color = Charcoal.toArgb()
        canvas.drawCircle(radius, radius, radius - USER_RING_DP * density, paint)
    }
}

internal const val USER_DOT_DP = 22f

internal const val USER_RING_DP = 4f
