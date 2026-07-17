package com.example.zuppon.ui.passenger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.zuppon.R
import com.example.zuppon.databinding.ActivityPickLocationBinding
import com.example.zuppon.util.DirectionsHelper
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import java.util.Locale

class PickLocationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPickLocationBinding
    private lateinit var placesClient: PlacesClient
    private var googleMap: GoogleMap? = null
    private var currentAddress = ""
    private var lastCenter: LatLng = ASUNCION
    private var originLatLng: LatLng? = null  // posición del usuario para trazar ruta

    private val geocodeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var geocodeRunnable: Runnable? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) goToMyLocation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPickLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val apiKey = getString(R.string.google_maps_key)

        // Inicializar Places SDK
        if (!Places.isInitialized()) Places.initialize(applicationContext, apiKey)
        placesClient = Places.createClient(this)

        val initLat = intent.getDoubleExtra(EXTRA_LAT, ASUNCION.latitude)
        val initLng = intent.getDoubleExtra(EXTRA_LNG, ASUNCION.longitude)
        lastCenter = LatLng(initLat, initLng)
        originLatLng = lastCenter

        initMap()
        setupSearch()
        setupListeners()
    }

    // ── Mapa ──────────────────────────────────────────────────────────────────

    private fun initMap() {
        (supportFragmentManager.findFragmentById(R.id.map_picker) as? SupportMapFragment)
            ?.getMapAsync { map ->
                googleMap = map
                try { map.setMapStyle(MapStyleOptions(DARK_STYLE)) } catch (_: Exception) { }
                map.isBuildingsEnabled = true
                map.uiSettings.isZoomControlsEnabled = false
                map.uiSettings.isCompassEnabled = true
                map.isTrafficEnabled = true  // Mostrar tráfico en tiempo real

                map.moveCamera(CameraUpdateFactory.newLatLngZoom(lastCenter, 15f))
                updateAddressForCenter(lastCenter)

                map.setOnCameraMoveStartedListener {
                    binding.tvPickAddressConfirm.text = "Moviendo…"
                    binding.tvCenterPin.animate().translationY(-16f).setDuration(120).start()
                }
                map.setOnCameraIdleListener {
                    lastCenter = map.cameraPosition.target
                    binding.tvCenterPin.animate()
                        .translationY(0f).setDuration(220)
                        .setInterpolator(OvershootInterpolator(2.5f)).start()
                    scheduleGeocode(lastCenter)
                }
            }
    }

    private fun scheduleGeocode(latlng: LatLng) {
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        geocodeRunnable = Runnable { updateAddressForCenter(latlng) }
        geocodeHandler.postDelayed(geocodeRunnable!!, 500)
    }

    private fun updateAddressForCenter(latlng: LatLng) {
        Thread {
            try {
                val gc = Geocoder(this, Locale("es", "PY"))
                @Suppress("DEPRECATION")
                val list = gc.getFromLocation(latlng.latitude, latlng.longitude, 1)
                val addr = list?.firstOrNull()
                val text = if (addr != null) buildString {
                    if (!addr.thoroughfare.isNullOrBlank()) {
                        append(addr.thoroughfare)
                        if (!addr.subThoroughfare.isNullOrBlank()) append(" ${addr.subThoroughfare}")
                    }
                    if (!addr.subLocality.isNullOrBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(addr.subLocality)
                    }
                    if (!addr.locality.isNullOrBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(addr.locality)
                    }
                }.ifBlank { "%.5f, %.5f".format(latlng.latitude, latlng.longitude) }
                else "%.5f, %.5f".format(latlng.latitude, latlng.longitude)

                currentAddress = text
                runOnUiThread {
                    binding.tvPickAddressConfirm.text = text
                    binding.tvPickAddress.text = text
                }
            } catch (_: Exception) { }
        }.start()
    }

    // ── Ruta por calles (Directions API) ─────────────────────────────────────

    private fun drawRoute(destination: LatLng) {
        val origin = originLatLng ?: return
        val apiKey = getString(R.string.google_maps_key)
        Thread {
            val points = DirectionsHelper.getRoute(apiKey, origin, destination)
            runOnUiThread {
                if (points.isNotEmpty()) {
                    googleMap?.apply {
                        clear()
                        // Línea de ruta naranja estilo navigation
                        addPolyline(
                            PolylineOptions()
                                .addAll(points)
                                .width(14f)
                                .color(0xFFFF5722.toInt())   // naranja Zuppon
                                .geodesic(true)
                        )
                        // Marcador de origen (usuario)
                        addMarker(
                            MarkerOptions()
                                .position(origin)
                                .title("Tu ubicación")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        )
                        // Marcador de destino
                        addMarker(
                            MarkerOptions()
                                .position(destination)
                                .title(currentAddress)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                        )
                        // Ajustar cámara para ver toda la ruta
                        val builder = com.google.android.gms.maps.model.LatLngBounds.Builder()
                        points.forEach { builder.include(it) }
                        animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
                    }
                }
            }
        }.start()
    }

    // ── Places Autocomplete ───────────────────────────────────────────────────

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.length >= 2) fetchPredictions(query)
                else binding.rvSuggestions.visibility = View.GONE
            }
        })
    }

    private fun fetchPredictions(query: String) {
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setCountries("PY")   // Priorizar Paraguay
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                val predictions = response.autocompletePredictions
                showSuggestions(predictions)
            }
            .addOnFailureListener { }
    }

    private fun showSuggestions(predictions: List<AutocompletePrediction>) {
        binding.rvSuggestions.removeAllViews()
        if (predictions.isEmpty()) {
            binding.rvSuggestions.visibility = View.GONE
            return
        }
        binding.rvSuggestions.visibility = View.VISIBLE

        predictions.take(5).forEach { prediction ->
            val tv = android.widget.TextView(this).apply {
                text = "📍  ${prediction.getPrimaryText(null)}\n${prediction.getSecondaryText(null)}"
                setTextColor(getColor(R.color.white))
                textSize = 13f
                setPadding(20, 20, 20, 20)
                setBackgroundResource(R.color.zuppon_card)
                setOnClickListener { selectPlace(prediction.placeId) }
            }
            binding.rvSuggestions.addView(tv)

            // Divisor
            val divider = View(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(getColor(R.color.zuppon_divider))
            }
            binding.rvSuggestions.addView(divider)
        }
    }

    private fun selectPlace(placeId: String) {
        binding.rvSuggestions.visibility = View.GONE
        val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
        val request = FetchPlaceRequest.newInstance(placeId, fields)

        placesClient.fetchPlace(request)
            .addOnSuccessListener { response ->
                val place = response.place
                val latlng = place.latLng ?: return@addOnSuccessListener
                val address = place.address ?: place.name ?: ""

                currentAddress = address
                lastCenter = latlng
                binding.etSearch.setText(place.name)
                binding.tvPickAddressConfirm.text = address
                binding.tvPickAddress.text = address

                // Mover mapa al lugar seleccionado
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latlng, 16f))

                // Trazar ruta desde la ubicación del usuario hasta este lugar
                drawRoute(latlng)
            }
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnPickBack.setOnClickListener { finish() }

        binding.btnPickGps.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) goToMyLocation()
            else locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        binding.btnConfirmLocation.setOnClickListener {
            val result = Intent().apply {
                putExtra(EXTRA_ADDRESS, currentAddress)
                putExtra(EXTRA_LAT, lastCenter.latitude)
                putExtra(EXTRA_LNG, lastCenter.longitude)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun goToMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    val latlng = LatLng(loc.latitude, loc.longitude)
                    originLatLng = latlng
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latlng, 16f))
                }
            }
    }

    companion object {
        const val EXTRA_ADDRESS = "pick_address"
        const val EXTRA_LAT     = "pick_lat"
        const val EXTRA_LNG     = "pick_lng"
        private val ASUNCION = LatLng(-25.2867, -57.6470)

        fun createIntent(context: Context, initLat: Double = -25.2867, initLng: Double = -57.6470) =
            Intent(context, PickLocationActivity::class.java).apply {
                putExtra(EXTRA_LAT, initLat)
                putExtra(EXTRA_LNG, initLng)
            }

        private val DARK_STYLE = """
        [{"featureType":"water","elementType":"geometry","stylers":[{"color":"#aee0f4"}]},
         {"featureType":"landscape.man_made","elementType":"geometry.fill","stylers":[{"color":"#f2f0eb"}]},
         {"featureType":"landscape.natural","elementType":"geometry.fill","stylers":[{"color":"#ddeecb"}]},
         {"featureType":"building","elementType":"geometry.fill","stylers":[{"color":"#e8e0d8"},{"lightness":10}]},
         {"featureType":"building","elementType":"geometry.stroke","stylers":[{"color":"#c8bfb0"}]},
         {"featureType":"road.highway","elementType":"geometry.fill","stylers":[{"color":"#ffe082"}]},
         {"featureType":"road.highway","elementType":"geometry.stroke","stylers":[{"color":"#ffb300","weight":1}]},
         {"featureType":"road.arterial","elementType":"geometry.fill","stylers":[{"color":"#ffffff"}]},
         {"featureType":"road.arterial","elementType":"geometry.stroke","stylers":[{"color":"#e0e0e0"}]},
         {"featureType":"road.local","elementType":"geometry.fill","stylers":[{"color":"#ffffff"}]},
         {"featureType":"road.local","elementType":"geometry.stroke","stylers":[{"color":"#eeeeee"}]},
         {"featureType":"poi","elementType":"geometry.fill","stylers":[{"color":"#c8e6c9"}]},
         {"featureType":"poi.park","elementType":"geometry.fill","stylers":[{"color":"#a5d6a7"}]},
         {"featureType":"poi.business","elementType":"labels.icon","stylers":[{"visibility":"on"}]},
         {"featureType":"poi.medical","elementType":"labels.icon","stylers":[{"visibility":"on"}]},
         {"featureType":"poi.school","elementType":"labels.icon","stylers":[{"visibility":"on"}]},
         {"featureType":"transit.station","elementType":"labels.icon","stylers":[{"visibility":"on"}]},
         {"elementType":"labels.text.fill","stylers":[{"color":"#444444"}]},
         {"elementType":"labels.text.stroke","stylers":[{"color":"#ffffff","weight":3}]}]
        """.trimIndent()
    }
}
