package com.example.disasterconnect

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import androidx.activity.ComponentActivity
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

// =============================================================
// SHELTER DATA MODEL
// =============================================================
data class Shelter(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val capacity: Int,
    val status: String,
    var distanceToUser: Float = 0f
)

class MapActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private var mapInstance: MapLibreMap? = null

    // Emergency shelters. No shelter data source exists in the app yet, so this
    // stays empty; the sample shelters that used to be generated here were made up.
    private val shelterList = mutableListOf<Shelter>()
    private val shelterMarkers = mutableMapOf<Marker, Shelter>()

    // Real location passed from Home, or null. Never replaced by a default position.
    private var userLocation: LatLng? = null

    // UI elements updated as the map, network and selection change
    private lateinit var topBar: LinearLayout
    private lateinit var bottomPanel: ScrollView
    private lateinit var statusChip: TextView
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var shelterNotice: TextView
    private lateinit var shelterPanel: LinearLayout
    private lateinit var shelterName: TextView
    private lateinit var shelterAddress: TextView
    private lateinit var shelterDistance: TextView
    private lateinit var showShelterButton: Button
    private var selectedShelter: Shelter? = null

    private var mapState = MapState.LOADING

    private enum class MapState { LOADING, READY, FAILED }

    // =========================================================
    // ACTIVITY START
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Initialize MapLibre BEFORE super.onCreate
        MapLibre.getInstance(this)

        super.onCreate(savedInstanceState)

        // -----------------------------------------------------
        // GET GPS COORDINATES FROM INTENT
        // -----------------------------------------------------
        // Home only passes coordinates it actually has. No fallback position.
        if (intent.hasExtra("latitude") && intent.hasExtra("longitude")) {
            userLocation = LatLng(
                intent.getDoubleExtra("latitude", 0.0),
                intent.getDoubleExtra("longitude", 0.0)
            )
        }

        // -----------------------------------------------------
        // SHELTER DISTANCES (for whatever real shelters exist)
        // -----------------------------------------------------
        userLocation?.let { location ->
            for (shelter in shelterList) {
                val results = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, shelter.latitude, shelter.longitude, results)
                shelter.distanceToUser = results[0]
            }
        }

        // -----------------------------------------------------
        // CREATE ROOT CONTAINER
        // -----------------------------------------------------
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(color(R.color.dc_off_white))
        }

        // -----------------------------------------------------
        // CREATE MAP VIEW
        // -----------------------------------------------------
        mapView = MapView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(mapView)

        // -----------------------------------------------------
        // HEADER AND BOTTOM PANEL
        // -----------------------------------------------------
        topBar = createTopBar()
        rootLayout.addView(topBar)

        bottomPanel = createBottomPanel()
        rootLayout.addView(bottomPanel)

        applySystemBarInsets(rootLayout)

        setContentView(rootLayout)
        mapView.onCreate(savedInstanceState)

        // Dark system-bar icons so they stay visible on the off-white header and panel.
        WindowCompat.getInsetsController(window, rootLayout).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        // -----------------------------------------------------
        // LOAD MAP ASYNCHRONOUSLY
        // -----------------------------------------------------
        mapView.addOnDidFailLoadingMapListener {
            mapState = MapState.FAILED
            refreshStatus()
        }

        mapView.getMapAsync { map ->
            mapInstance = map

            // Shelter markers open the details panel; the user's own marker keeps its default info window.
            map.setOnMarkerClickListener { marker ->
                shelterMarkers[marker]?.let { selectShelter(it); true } ?: false
            }

            loadStyle(map)
        }

        refreshStatus()
    }

    // =========================================================
    // STYLE CONFIGURATION
    // =========================================================

    private val arcgisStyleJson = """
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

    private fun loadStyle(map: MapLibreMap) {

        mapState = MapState.LOADING
        refreshStatus()

        map.setStyle(
            Style.Builder()
                .fromJson(arcgisStyleJson)
        ) { _ ->
            mapState = MapState.READY

            // Keep the user and markers clear of the header and bottom panel.
            applyMapPadding()

            // 1. Center on user (only when the real location is known)
            userLocation?.let { location ->
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 14.0))
            }

            // 2. Add User Marker
            map.clear()
            shelterMarkers.clear()
            userLocation?.let { location ->
                map.addMarker(
                    MarkerOptions()
                        .position(location)
                        .title(getString(R.string.map_your_location_marker))
                )
            }

            // 3. Add Shelter Markers (titles name each shelter, so they are not colour-only)
            for (shelter in shelterList) {
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(shelter.latitude, shelter.longitude))
                        .title(shelter.name)
                )
                shelterMarkers[marker] = shelter
            }

            refreshStatus()
        }
    }

    // =========================================================
    // BUTTON LOGIC
    // =========================================================

    private fun recenterOnUser() {
        val location = userLocation ?: return
        mapInstance?.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15.0))
    }

    private fun showNearestShelter() {
        val nearest =
            if (userLocation != null) shelterList.minByOrNull { it.distanceToUser }
            else shelterList.firstOrNull()
        nearest?.let(::selectShelter)
    }

    private fun selectShelter(shelter: Shelter) {
        selectedShelter = shelter

        // Only fields that come from real shelter data. Capacity and status are not
        // shown because no source currently says whether they are up to date.
        shelterName.text = shelter.name
        shelterAddress.text = shelter.address
        shelterAddress.visibility = if (shelter.address.isBlank()) View.GONE else View.VISIBLE

        if (userLocation != null) {
            shelterDistance.text = getString(R.string.map_shelter_distance, formatDistance(shelter.distanceToUser))
            shelterDistance.visibility = View.VISIBLE
        } else {
            shelterDistance.visibility = View.GONE
        }

        shelterPanel.visibility = View.VISIBLE
        focusShelter(shelter)
    }

    private fun focusShelter(shelter: Shelter) {
        mapInstance?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(shelter.latitude, shelter.longitude), 16.0)
        )
    }

    // =========================================================
    // STATUS
    // =========================================================

    /** Rebuilds every status text from real state: map load, network, location and shelters. */
    private fun refreshStatus() {

        val online = isInternetAvailable()

        statusChip.text = getString(
            when {
                mapState == MapState.LOADING -> R.string.map_chip_loading
                mapState == MapState.FAILED -> R.string.map_chip_failed
                online -> R.string.map_chip_online
                else -> R.string.map_chip_offline
            }
        )

        val messages = mutableListOf<String>()

        when (mapState) {
            MapState.LOADING -> messages += getString(R.string.map_status_loading)
            MapState.FAILED -> messages += getString(R.string.map_status_failed)
            MapState.READY -> Unit
        }

        if (!online && mapState != MapState.FAILED) {
            messages += getString(R.string.map_status_offline)
        }

        messages += getString(
            when {
                userLocation != null -> R.string.map_status_location_shown
                !hasLocationPermission() -> R.string.map_status_location_permission
                else -> R.string.map_status_location_unavailable
            }
        )

        statusText.text = messages.joinToString("\n\n")
        retryButton.visibility = if (mapState == MapState.FAILED) View.VISIBLE else View.GONE

        val hasShelters = shelterList.isNotEmpty()
        shelterNotice.visibility = if (hasShelters) View.GONE else View.VISIBLE
        nearbySheltersButton.visibility = if (hasShelters) View.VISIBLE else View.GONE
        myLocationButton.isEnabled = userLocation != null
        myLocationButton.alpha = if (userLocation != null) 1f else 0.5f
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun formatDistance(meters: Float): String =
        if (meters < 1000f) "%.0f m".format(meters) else "%.1f km".format(meters / 1000f)

    // =========================================================
    // HELPER: HEADER
    // =========================================================

    private fun createTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = solidShape(color(R.color.dc_off_white), 0f)
            setPadding(dimen(R.dimen.dc_space_md), dimen(R.dimen.dc_space_sm), dimen(R.dimen.dc_space_lg), dimen(R.dimen.dc_space_sm))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )

            addView(createButton(R.string.map_back, ButtonStyle.TEXT) { finish() })

            addView(createText(R.dimen.dc_text_title, R.color.dc_ink, bold = true).apply {
                setText(R.string.map_title)
                ViewCompat.setAccessibilityHeading(this, true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dimen(R.dimen.dc_space_sm)
                    marginEnd = dimen(R.dimen.dc_space_sm)
                }
            })

            statusChip = createText(R.dimen.dc_text_label, R.color.dc_ink, bold = true).apply {
                background = solidShape(color(R.color.dc_soft_cream), dimen(R.dimen.dc_radius_small).toFloat(), color(R.color.dc_outline))
                setPadding(dimen(R.dimen.dc_space_md), dimen(R.dimen.dc_space_xs), dimen(R.dimen.dc_space_md), dimen(R.dimen.dc_space_xs))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(statusChip)
        }
    }

    // =========================================================
    // HELPER: BOTTOM PANEL
    // =========================================================

    private lateinit var myLocationButton: Button
    private lateinit var nearbySheltersButton: Button

    private fun createBottomPanel(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dimen(R.dimen.dc_space_screen), dimen(R.dimen.dc_space_lg), dimen(R.dimen.dc_space_screen), dimen(R.dimen.dc_space_lg))
        }

        statusText = createText(R.dimen.dc_text_body, R.color.dc_ink).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(statusText)

        retryButton = createButton(R.string.map_retry, ButtonStyle.PRIMARY) {
            mapInstance?.let(::loadStyle)
        }.apply { visibility = View.GONE }
        content.addView(retryButton, stackedParams())

        // Shelters
        shelterNotice = createText(R.dimen.dc_text_body, R.color.dc_ink).apply {
            setText(R.string.map_no_shelters)
            background = solidShape(color(R.color.dc_soft_cream), dimen(R.dimen.dc_radius_medium).toFloat())
            setPadding(dimen(R.dimen.dc_space_md), dimen(R.dimen.dc_space_md), dimen(R.dimen.dc_space_md), dimen(R.dimen.dc_space_md))
        }
        content.addView(shelterNotice, stackedParams())

        shelterPanel = createShelterPanel().apply { visibility = View.GONE }
        content.addView(shelterPanel, stackedParams())

        // Main controls
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        myLocationButton = createButton(R.string.map_my_location, ButtonStyle.PRIMARY) { recenterOnUser() }
        nearbySheltersButton = createButton(R.string.map_nearby_shelters, ButtonStyle.SECONDARY) { showNearestShelter() }
        controls.addView(myLocationButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(nearbySheltersButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dimen(R.dimen.dc_space_sm)
        })
        content.addView(controls, stackedParams())

        val radius = dimen(R.dimen.dc_radius_large).toFloat()

        return ScrollView(this).apply {
            addView(content)
            isFillViewport = false
            background = solidShape(color(R.color.dc_off_white), radius, color(R.color.dc_outline), topOnly = true)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            // With very large text the panel scrolls instead of covering the whole map.
            addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
                val maxHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
                if (bottom - top > maxHeight && view.layoutParams.height != maxHeight) {
                    view.layoutParams = view.layoutParams.apply { height = maxHeight }
                }
                applyMapPadding()
            }
        }
    }

    private fun createShelterPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = solidShape(color(R.color.white), dimen(R.dimen.dc_radius_medium).toFloat(), color(R.color.dc_outline))
            setPadding(dimen(R.dimen.dc_space_md), dimen(R.dimen.dc_space_md), dimen(R.dimen.dc_space_md), dimen(R.dimen.dc_space_md))

            shelterName = createText(R.dimen.dc_text_title, R.color.dc_ink, bold = true)
            shelterAddress = createText(R.dimen.dc_text_body, R.color.dc_muted_ink)
            shelterDistance = createText(R.dimen.dc_text_body, R.color.dc_ink)
            showShelterButton = createButton(R.string.map_show_on_map, ButtonStyle.SECONDARY) {
                selectedShelter?.let(::focusShelter)
            }

            addView(shelterName)
            addView(shelterAddress)
            addView(shelterDistance)
            addView(showShelterButton, stackedParams())
        }
    }

    /** Pads the camera so the location pin is centred in the visible part of the map. */
    private fun applyMapPadding() {
        val map = mapInstance ?: return
        if (!::topBar.isInitialized || !::bottomPanel.isInitialized) return
        val top = topBar.height.toDouble()
        val bottom = bottomPanel.height.toDouble()
        map.moveCamera(CameraUpdateFactory.paddingTo(0.0, top, 0.0, bottom))
        // Keep the basemap attribution and logo visible above the panel.
        map.uiSettings.setAttributionMargins(dimen(R.dimen.dc_space_sm), 0, 0, bottomPanel.height + dimen(R.dimen.dc_space_sm))
        map.uiSettings.setLogoMargins(dimen(R.dimen.dc_space_sm), 0, 0, bottomPanel.height + dimen(R.dimen.dc_space_sm))
    }

    private fun applySystemBarInsets(root: View) {
        val topPadding = topBar.paddingTop
        val bottomContent = (bottomPanel.getChildAt(0))
        val bottomPadding = bottomContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(topBar.paddingLeft, topPadding + bars.top, topBar.paddingRight, topBar.paddingBottom)
            bottomContent.setPadding(bottomContent.paddingLeft, bottomContent.paddingTop, bottomContent.paddingRight, bottomPadding + bars.bottom)
            insets
        }
    }

    // =========================================================
    // HELPER: UI STYLING (solid colours only)
    // =========================================================

    private enum class ButtonStyle { PRIMARY, SECONDARY, TEXT }

    private fun createButton(@StringRes label: Int, style: ButtonStyle, onClick: () -> Unit): Button {
        return Button(this).apply {
            setText(label)
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.dc_text_body))
            minHeight = dimen(R.dimen.dc_touch_target)
            minimumHeight = dimen(R.dimen.dc_touch_target)
            minWidth = dimen(R.dimen.dc_touch_target)
            minimumWidth = dimen(R.dimen.dc_touch_target)
            stateListAnimator = null
            elevation = 0f
            setPadding(dimen(R.dimen.dc_space_lg), dimen(R.dimen.dc_space_sm), dimen(R.dimen.dc_space_lg), dimen(R.dimen.dc_space_sm))

            val radius = dimen(R.dimen.dc_radius_medium).toFloat()
            val (fill, text, stroke) = when (style) {
                ButtonStyle.PRIMARY -> Triple(color(R.color.dc_charcoal), color(R.color.white), null)
                ButtonStyle.SECONDARY -> Triple(color(R.color.dc_off_white), color(R.color.dc_ink), color(R.color.dc_outline))
                ButtonStyle.TEXT -> Triple(color(R.color.dc_off_white), color(R.color.dc_ink), null)
            }
            setTextColor(text)
            background = RippleDrawable(
                ColorStateList.valueOf(color(R.color.dc_outline)),
                solidShape(fill, radius, stroke),
                solidShape(color(R.color.white), radius)
            )
            setOnClickListener { onClick() }
        }
    }

    private fun createText(@DimenRes size: Int, @ColorRes textColor: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
            setTextColor(color(textColor))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }
    }

    /** Solid rounded rectangle, optionally with a 1dp solid border. */
    private fun solidShape(fill: Int, radius: Float, stroke: Int? = null, topOnly: Boolean = false): Drawable {
        fun shape(r: Float, paintColor: Int) = ShapeDrawable(
            RoundRectShape(
                if (topOnly) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else FloatArray(8) { r },
                null,
                null
            )
        ).apply { paint.color = paintColor }

        if (stroke == null) return shape(radius, fill)

        val width = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        return LayerDrawable(arrayOf(shape(radius, stroke), shape((radius - width).coerceAtLeast(0f), fill))).apply {
            setLayerInset(1, width, width, width, if (topOnly) 0 else width)
        }
    }

    private fun stackedParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dimen(R.dimen.dc_space_md) }

    private fun color(@ColorRes id: Int): Int = getColor(id)

    private fun dimen(@DimenRes id: Int): Int = resources.getDimensionPixelSize(id)

    // =========================================================
    // MAPVIEW LIFECYCLE FORWARDING
    // =========================================================
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); refreshStatus() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
