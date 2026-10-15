package com.example.disasterconnect

import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast

import androidx.activity.ComponentActivity

import org.maplibre.android.MapLibre
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

    // List to hold our emergency shelters
    private val shelterList = mutableListOf<Shelter>()

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
        val userLat = intent.getDoubleExtra("latitude", 28.6139)
        val userLng = intent.getDoubleExtra("longitude", 77.2090)
        val userLocation = LatLng(userLat, userLng)

        // -----------------------------------------------------
        // GENERATE SAMPLE SHELTER DATA (Realistic for testing)
        // -----------------------------------------------------
        createSampleShelters(userLat, userLng)

        // -----------------------------------------------------
        // CREATE ROOT CONTAINER
        // -----------------------------------------------------
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
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
        // CREATE "MY LOCATION" BUTTON
        // -----------------------------------------------------
        val myLocationButton = createStyledButton("🎯 MY LOCATION", 100)
        rootLayout.addView(myLocationButton)

        // -----------------------------------------------------
        // CREATE "NEAREST SHELTER" BUTTON
        // -----------------------------------------------------
        val nearestShelterButton = createStyledButton("🏠 NEAREST SHELTER", 240)
        rootLayout.addView(nearestShelterButton)

        setContentView(rootLayout)
        mapView.onCreate(savedInstanceState)

        // -----------------------------------------------------
        // STYLE CONFIGURATION
        // -----------------------------------------------------
        val arcgisStyleJson = """
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

        // -----------------------------------------------------
        // LOAD MAP ASYNCHRONOUSLY
        // -----------------------------------------------------
        mapView.getMapAsync { map ->
            mapInstance = map

            map.setStyle(
                Style.Builder()
                    .fromJson(arcgisStyleJson)
            ) { style ->
                // 1. Center on user
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 14.0))

                // 2. Add User Marker
                map.addMarker(MarkerOptions()
                    .position(userLocation)
                    .title("YOU ARE HERE")
                )

                // 3. Add Shelter Markers
                for (shelter in shelterList) {
                    map.addMarker(MarkerOptions()
                        .position(LatLng(shelter.latitude, shelter.longitude))
                        .title(shelter.name)
                        .snippet("Status: ${shelter.status}\nCapacity: ${shelter.capacity}\nDist: ${String.format("%.2f", shelter.distanceToUser / 1000)} km")
                    )
                }

                Toast.makeText(this, "Found ${shelterList.size} Shelters Nearby", Toast.LENGTH_SHORT).show()
            }
        }

        // -----------------------------------------------------
        // BUTTON LOGIC
        // -----------------------------------------------------
        myLocationButton.setOnClickListener {
            mapInstance?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 15.0))
        }

        nearestShelterButton.setOnClickListener {
            val nearest = shelterList.minByOrNull { it.distanceToUser }
            nearest?.let {
                val nearestLatLng = LatLng(it.latitude, it.longitude)
                mapInstance?.animateCamera(CameraUpdateFactory.newLatLngZoom(nearestLatLng, 16.0))
                Toast.makeText(this, "Heading to: ${it.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================
    // HELPER: CREATE SAMPLE SHELTERS
    // =========================================================
    private fun createSampleShelters(userLat: Double, userLng: Double) {
        // Sample shelters offset slightly from user location
        val rawShelters = listOf(
            Shelter("1", "City Community Center", userLat + 0.005, userLng + 0.005, "123 Safety St", 200, "OPEN"),
            Shelter("2", "Westside Primary School", userLat - 0.008, userLng + 0.002, "45 Education Ave", 500, "OPEN"),
            Shelter("3", "Emergency Stadium Shelter", userLat + 0.010, userLng - 0.005, "Sports Complex Plaza", 2000, "FULL"),
            Shelter("4", "Red Cross Field Camp", userLat - 0.003, userLng - 0.007, "Green Park Sector 4", 150, "OPEN")
        )

        // Calculate distance for each shelter
        for (shelter in rawShelters) {
            val results = FloatArray(1)
            Location.distanceBetween(userLat, userLng, shelter.latitude, shelter.longitude, results)
            shelter.distanceToUser = results[0]
            shelterList.add(shelter)
        }
    }

    // =========================================================
    // HELPER: UI STYLING
    // =========================================================
    private fun createStyledButton(label: String, bottomMargin: Int): Button {
        return Button(this).apply {
            text = label
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, 60, bottomMargin)
            }
        }
    }

    // =========================================================
    // MAPVIEW LIFECYCLE FORWARDING
    // =========================================================
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
