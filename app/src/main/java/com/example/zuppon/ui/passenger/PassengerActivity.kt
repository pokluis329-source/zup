package com.example.zuppon.ui.passenger

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zuppon.R
import com.example.zuppon.RoleSelectionActivity
import com.example.zuppon.databinding.ActivityPassengerBinding
import com.example.zuppon.model.FoodMenu
import com.example.zuppon.model.TripState
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import java.util.Locale

class PassengerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityPassengerBinding
    private val viewModel: PassengerViewModel by viewModels()
    private lateinit var menuAdapter: FoodMenuAdapter

    // ── Maps ──────────────────────────────────────────────────────────────────
    private var confirmMap: GoogleMap? = null
    private var trackingMap: GoogleMap? = null
    private var deliveryLatLng: LatLng? = null
    private var userMarker: Marker? = null
    private var gpsMarker: Marker? = null
    private var courierMarker: Marker? = null
    private var cameraFollowing = true
    private var customLocationSet = false
    // true cuando el usuario está en la pantalla de confirmación —
    // impide que el GPS sobreescriba la posición exacta que eligió en el mapa
    private var confirmLocationLocked = false
    private var courierLatLng: LatLng? = null

    // ── GPS en tiempo real ────────────────────────────────────────────────────
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isTrackingActive = false

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 3000L   // update cada 3 segundos
    ).setMinUpdateIntervalMillis(1500L)
     .setMaxUpdateDelayMillis(5000L)
     .build()

    // ── Frases de búsqueda ────────────────────────────────────────────────────
    private val searchingPhrases = listOf(
        "buscando al héroe de tu hambre… 🦸",
        "coordinando tu misión gastronómica 🛵",
        "encontrando al repartidor más rápido ⚡",
        "tu estómago ya nos avisó 📡",
        "avisando al restaurante… no te muevas 🍔"
    )
    private var phraseIndex = 0
    private val phraseHandler = android.os.Handler(Looper.getMainLooper())
    private val paymentHandler = android.os.Handler(Looper.getMainLooper())
    private var paymentPollRunnable: Runnable? = null
    private val phraseRunnable = object : Runnable {
        override fun run() {
            if (viewModel.tripState.value == TripState.SearchingDriver) {
                binding.tvSearchingText.animate().alpha(0f).setDuration(300).withEndAction {
                    phraseIndex = (phraseIndex + 1) % searchingPhrases.size
                    binding.tvSearchingText.text = searchingPhrases[phraseIndex]
                    binding.tvSearchingText.animate().alpha(1f).setDuration(300).start()
                }.start()
                phraseHandler.postDelayed(this, 2500)
            }
        }
    }

    // ── Debounce para reverseGeocode ──────────────────────────────────────────
    // Se cancela y reprograma en cada tick GPS — solo ejecuta tras 1.5s sin movimiento
    private val geocodeHandler = android.os.Handler(Looper.getMainLooper())
    private var geocodeRunnable: Runnable? = null
    private fun scheduleReverseGeocode(latlng: LatLng) {
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        geocodeRunnable = Runnable { reverseGeocode(latlng) }
        geocodeHandler.postDelayed(geocodeRunnable!!, 1_500)
    }

    // ── Permisos ──────────────────────────────────────────────────────────────
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLocationTracking()
        else binding.etDestination.hint = "escribe tu dirección 📍"
    }

    // ── Detalle producto ──────────────────────────────────────────────────────
    private val detailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val itemId = result.data?.getIntExtra(ProductDetailActivity.EXTRA_ITEM_ID, -1) ?: -1
            val qty    = result.data?.getIntExtra(ProductDetailActivity.EXTRA_QUANTITY, 1) ?: 1
            val item   = FoodMenu.items.find { it.id == itemId } ?: return@registerForActivityResult
            repeat(qty) { viewModel.addToCart(item) }
            punchAnimation(binding.cardCartButton)
        }
    }

    // ── Selector de ubicación personalizada ───────────────────────────────────
    private val pickLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val address = result.data?.getStringExtra(PickLocationActivity.EXTRA_ADDRESS) ?: return@registerForActivityResult
            val lat     = result.data?.getDoubleExtra(PickLocationActivity.EXTRA_LAT, 0.0) ?: 0.0
            val lng     = result.data?.getDoubleExtra(PickLocationActivity.EXTRA_LNG, 0.0) ?: 0.0
            val latlng  = LatLng(lat, lng)

            // Aplicar dirección elegida
            deliveryLatLng = latlng
            customLocationSet = true
            binding.etDestination.setText(address)
            binding.etDeliveryAddress.setText(address)

            // userMarker se fija en la ubicación personalizada (no sigue al GPS)
            if (userMarker != null) {
                userMarker?.position = latlng
            } else {
                userMarker = trackingMap?.addMarker(
                    MarkerOptions()
                        .position(latlng)
                        .title("Dirección de entrega 📍")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                        .zIndex(1f)
                )
            }
            trackingMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latlng, 15f))
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPassengerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        setupMenu()
        setupListeners()
        observeViewModel()
        initMaps()

        // Pedir permiso GPS automáticamente al entrar
        requestLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) startLocationTracking()
        if (customLocationSet) restoreCustomMarker()
        if (viewModel.tripState.value == TripState.AwaitingPayment) {
            viewModel.checkPayment(
                onPaid = { stopPaymentPolling() },
                onPending = { startPaymentPolling() }
            )
        }
    }

    /** Asegura que el marker naranja de la ubicación personalizada esté visible */
    private fun restoreCustomMarker() {
        val latlng = deliveryLatLng ?: return
        val map    = trackingMap   ?: return
        // Remover el marker viejo (puede estar invalidado) y crear uno nuevo
        userMarker?.remove()
        userMarker = map.addMarker(
            MarkerOptions()
                .position(latlng)
                .title("Dirección de entrega 📍")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                .zIndex(1f)
        )
    }

    override fun onPause() {
        super.onPause()
        stopLocationTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPaymentPolling()
        phraseHandler.removeCallbacks(phraseRunnable)
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        stopLocationTracking()
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    // ── GPS en tiempo real ────────────────────────────────────────────────────

    private fun requestLocationPermission() {
        if (hasLocationPermission()) startLocationTracking()
        else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startLocationTracking() {
        if (isTrackingActive || !hasLocationPermission()) return
        isTrackingActive = true

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val latlng = LatLng(location.latitude, location.longitude)
                    // No sobreescribir si el usuario ya fijó la posición (mapa de confirmación o selector)
                    if (!customLocationSet && !confirmLocationLocked) deliveryLatLng = latlng
                    onLocationUpdated(latlng)
                }
            }
        }

        fusedClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationTracking() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        isTrackingActive = false
    }

    /** Llamado en cada nuevo punto GPS — dos markers separados: entrega vs posición física */
    private fun onLocationUpdated(latlng: LatLng) {
        if (trackingMap == null) {
            if (!customLocationSet) deliveryLatLng = latlng
            return
        }

        // ── Marker GPS (punto azul pequeño — siempre se mueve con el dispositivo) ──
        if (gpsMarker == null) {
            gpsMarker = trackingMap?.addMarker(
                MarkerOptions()
                    .position(latlng)
                    .title("Tu posición actual")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .zIndex(0.5f)
            )
        } else {
            gpsMarker?.position = latlng
        }

        if (!customLocationSet) {
            // Sin ubicación personalizada: marker de entrega = posición GPS
            deliveryLatLng = latlng
            if (userMarker == null) {
                userMarker = trackingMap?.addMarker(
                    MarkerOptions()
                        .position(latlng)
                        .title("Dirección de entrega 📍")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                        .zIndex(1f)
                )
                trackingMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latlng, 15f))
            } else {
                userMarker?.position = latlng
                if (cameraFollowing) {
                    trackingMap?.animateCamera(CameraUpdateFactory.newLatLng(latlng))
                }
            }
            if (binding.etDestination.text.isNullOrBlank()) scheduleReverseGeocode(latlng)
        } else {
            // Con ubicación personalizada:
            // - userMarker permanece FIJO en deliveryLatLng
            // - Si el mapa se reinició y userMarker es null, recrearlo
            val fixedLatlng = deliveryLatLng
            if (fixedLatlng != null && userMarker == null) {
                userMarker = trackingMap?.addMarker(
                    MarkerOptions()
                        .position(fixedLatlng)
                        .title("Dirección de entrega 📍")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                        .zIndex(1f)
                )
            }
            // La cámara NO sigue al GPS — no tocar deliveryLatLng ni el texto
        }
    }

    private fun reverseGeocode(latlng: LatLng) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale("es", "PY"))
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latlng.latitude, latlng.longitude, 1)
                val address = addresses?.firstOrNull()
                val text = if (address != null) buildString {
                    if (!address.thoroughfare.isNullOrBlank()) {
                        append(address.thoroughfare)
                        if (!address.subThoroughfare.isNullOrBlank()) append(" ${address.subThoroughfare}")
                    }
                    if (!address.locality.isNullOrBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(address.locality)
                    }
                }.ifBlank { "%.4f, %.4f".format(latlng.latitude, latlng.longitude) }
                else "%.4f, %.4f".format(latlng.latitude, latlng.longitude)

                runOnUiThread {
                    binding.etDestination.setText(text)
                    binding.etDeliveryAddress.setText(text)
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun placeMarkerOnConfirmMap(latlng: LatLng) {
        confirmMap?.apply {
            clear()
            addMarker(
                MarkerOptions()
                    .position(latlng)
                    .title("Tu ubicación 📍")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
            animateCamera(CameraUpdateFactory.newLatLngZoom(latlng, 15f))
        }
    }

    // ── Maps init ─────────────────────────────────────────────────────────────

    private fun initMaps() {
        // map_confirm ahora es FrameLayout — añadimos el fragmento programáticamente
        val confirmFrag = supportFragmentManager
            .findFragmentByTag("map_confirm_frag") as? SupportMapFragment
            ?: SupportMapFragment.newInstance().also { frag ->
                supportFragmentManager.beginTransaction()
                    .replace(R.id.map_confirm, frag, "map_confirm_frag")
                    .commitNow()
            }
        confirmFrag.getMapAsync { map ->
            confirmMap = map
            styleMap(map)
            map.uiSettings.isZoomControlsEnabled = false
            map.uiSettings.isScrollGesturesEnabled = true
            map.uiSettings.isZoomGesturesEnabled = true

            // Centrar en la posición GPS actual — se hace aquí la primera vez
            val startPos = deliveryLatLng ?: ASUNCION
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(startPos, 16f))

            // Cuando la cámara para, guardar el centro exacto como posición de entrega
            // Ignorar (0,0) — es el valor default antes de que el mapa se inicialice
            map.setOnCameraIdleListener {
                val center = map.cameraPosition.target
                if (center.latitude != 0.0 || center.longitude != 0.0) {
                    deliveryLatLng = center
                }
            }
        }

        (supportFragmentManager.findFragmentById(R.id.map_tracking) as? SupportMapFragment)
            ?.getMapAsync { map ->
                trackingMap = map
                styleMap(map)
                map.uiSettings.isZoomControlsEnabled = false
                map.setOnCameraMoveStartedListener { reason ->
                    if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                        cameraFollowing = false
                    }
                }
                map.setOnMapClickListener { cameraFollowing = true }

                // Restaurar marcador de usuario
                val lastLatlng = deliveryLatLng
                userMarker = null
                gpsMarker  = null
                if (lastLatlng != null) {
                    if (customLocationSet) {
                        // Ubicación personalizada — marker naranja fijo
                        userMarker = map.addMarker(
                            MarkerOptions()
                                .position(lastLatlng)
                                .title("Dirección de entrega 📍")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                                .zIndex(1f)
                        )
                    } else {
                        // Ubicación GPS — marker azul que sigue al usuario
                        userMarker = map.addMarker(
                            MarkerOptions()
                                .position(lastLatlng)
                                .title("Tu ubicación 📍")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                                .zIndex(1f)
                        )
                    }
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(lastLatlng, 15f))
                } else {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(ASUNCION, 12f))
                }

                // ── Restaurar repartidor y ruta si hay pedido activo ──────────
                val currentState = viewModel.tripState.value
                val courier = courierLatLng
                val destination = deliveryLatLng
                if (courier != null && destination != null &&
                    (currentState is TripState.DriverOnWay ||
                     currentState is TripState.DriverArrived ||
                     currentState is TripState.InProgress)) {

                    // Redibujar marker del repartidor
                    courierMarker = map.addMarker(
                        MarkerOptions()
                            .position(courier)
                            .title("Repartidor 🛵")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    )

                    // Redibujar ruta en background
                    val apiKey = getString(R.string.google_maps_key)
                    Thread {
                        val points = com.example.zuppon.util.DirectionsHelper.getRoute(
                            apiKey, courier, destination
                        )
                        runOnUiThread {
                            if (points.isNotEmpty()) {
                                map.addPolyline(
                                    PolylineOptions()
                                        .addAll(points)
                                        .width(16f)
                                        .color(0xFF2196F3.toInt())
                                        .geodesic(true)
                                )
                                val bounds = com.google.android.gms.maps.model.LatLngBounds.Builder()
                                points.forEach { bounds.include(it) }
                                map.animateCamera(
                                    CameraUpdateFactory.newLatLngBounds(bounds.build(), 120)
                                )
                            }
                        }
                    }.start()
                }
            }
    }

    private fun styleMap(map: GoogleMap) {
        try { map.setMapStyle(MapStyleOptions(DARK_MAP_STYLE)) } catch (_: Exception) { }
        map.isBuildingsEnabled = true
        map.isTrafficEnabled = true
    }

    override fun onMapReady(map: GoogleMap) { /* usado vía lambda */ }

    private fun addCourierMarker(near: LatLng) {
        val courierPos = LatLng(near.latitude + 0.008, near.longitude - 0.005)
        courierLatLng = courierPos          // ← guardar para redibujar al volver
        courierMarker?.remove()
        courierMarker = trackingMap?.addMarker(
            MarkerOptions()
                .position(courierPos)
                .title("Repartidor 🛵")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
        // Draw real route from courier to user
        val apiKey = getString(R.string.google_maps_key)
        Thread {
            val points = com.example.zuppon.util.DirectionsHelper.getRoute(apiKey, courierPos, near)
            runOnUiThread {
                if (points.isNotEmpty()) {
                    trackingMap?.addPolyline(
                        PolylineOptions()
                            .addAll(points)
                            .width(16f)
                            .color(0xFF2196F3.toInt())
                            .geodesic(true)
                    )
                    // Fit camera to show both markers + route
                    val bounds = com.google.android.gms.maps.model.LatLngBounds.Builder()
                    points.forEach { bounds.include(it) }
                    trackingMap?.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(bounds.build(), 120)
                    )
                }
            }
        }.start()
    }

    private fun openPickLocation() {
        val lat = deliveryLatLng?.latitude ?: -25.2867
        val lng = deliveryLatLng?.longitude ?: -57.6470
        pickLocationLauncher.launch(PickLocationActivity.createIntent(this, lat, lng))
    }

    // ── Menú ──────────────────────────────────────────────────────────────────

    private fun setupMenu() {
        menuAdapter = FoodMenuAdapter(
            quantities = emptyMap(),
            onAdd    = { item -> viewModel.addToCart(item); punchAnimation(binding.cardCartButton) },
            onRemove = { viewModel.removeFromCart(it) },
            onDetail = { item -> ProductDetailActivity.startForResult(this, item.id, detailLauncher) }
        )
        binding.rvFoodItems.apply {
            layoutManager = LinearLayoutManager(this@PassengerActivity)
            adapter = menuAdapter
            isNestedScrollingEnabled = false
        }
        buildCategoryChips(viewModel.categories.value?.firstOrNull() ?: FoodMenu.categories.first())
    }

    private fun buildCategoryChips(selectedCategory: String) {
        binding.llCategories.removeAllViews()
        val cats = viewModel.categories.value ?: FoodMenu.categories
        cats.forEach { cat ->
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_category_chip, binding.llCategories, false) as TextView
            chip.text = cat
            chip.isSelected = (cat == selectedCategory)
            chip.alpha = if (cat == selectedCategory) 1f else 0.6f
            chip.setOnClickListener {
                viewModel.selectCategory(cat)
                buildCategoryChips(cat)
                binding.layoutMenuScreen.smoothScrollTo(0, 0)
            }
            binding.llCategories.addView(chip)
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnChangeRole.setOnClickListener {
            viewModel.cancelOrder()
            startActivity(Intent(this, RoleSelectionActivity::class.java))
            finish()
        }
        binding.cardCartButton.setOnClickListener {
            bounceIn(binding.cardCartButton); showConfirmScreen()
        }
        binding.btnGps.setOnClickListener         { requestLocationPermission() }
        binding.btnGpsConfirm.setOnClickListener  { requestLocationPermission() }
        binding.btnPickMap.setOnClickListener     { openPickLocation() }
        binding.btnPickMapConfirm.setOnClickListener { openPickLocation() }

        binding.btnRequestRide.setOnClickListener {
            val address = binding.etDeliveryAddress.text?.toString()?.trim() ?: ""
            if (address.isBlank()) {
                binding.tilDeliveryAddress.error = "sin dirección no llega 😅"
                shakeView(binding.tilDeliveryAddress); return@setOnClickListener
            }
            binding.tilDeliveryAddress.error = null
            hideKeyboard()

            val lat = deliveryLatLng?.latitude ?: 0.0
            val lng = deliveryLatLng?.longitude ?: 0.0

            if (lat == 0.0 && lng == 0.0 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                binding.btnRequestRide.isEnabled = false
                binding.btnRequestRide.text = "obteniendo ubicación… 📍"
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    binding.btnRequestRide.isEnabled = true
                    binding.btnRequestRide.text = "💳 Pagar con Pagopar"
                    val finalLat = loc?.latitude ?: 0.0
                    val finalLng = loc?.longitude ?: 0.0
                    if (finalLat != 0.0) {
                        deliveryLatLng = LatLng(finalLat, finalLng)
                        confirmMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(finalLat, finalLng), 16f))
                    }
                    submitOrder(address, finalLat, finalLng)
                }.addOnFailureListener {
                    binding.btnRequestRide.isEnabled = true
                    binding.btnRequestRide.text = "💳 Pagar con Pagopar"
                    submitOrder(address, 0.0, 0.0)
                }
            } else {
                submitOrder(address, lat, lng)
            }
        }
        binding.btnBackToMenu.setOnClickListener { showMenuScreen() }
        binding.btnCancelRide.setOnClickListener {
            stopPaymentPolling()
            viewModel.cancelOrder()
        }
        binding.btnOpenPagopar.setOnClickListener {
            val url = com.example.zuppon.network.NetworkRepository.lastCheckoutUrl
            if (url.isNullOrBlank()) {
                Toast.makeText(this, "No hay link de pago. Intentá confirmar el pedido de nuevo.", Toast.LENGTH_LONG).show()
            } else {
                openPagoparCheckout(url)
            }
        }
        binding.btnNewTrip.setOnClickListener {
            viewModel.startNewOrder()
            val firstCat = viewModel.categories.value?.firstOrNull() ?: FoodMenu.categories.first()
            buildCategoryChips(firstCat)
        }
        listOf(binding.star1, binding.star2, binding.star3, binding.star4, binding.star5)
            .forEachIndexed { i, tv ->
                tv.setOnClickListener { viewModel.setRating(i + 1); punchAnimation(tv) }
            }
    }

    // ── Observadores ──────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.menuItems.observe(this) { items ->
            menuAdapter.submitList(items)
        }
        viewModel.cart.observe(this) { cart ->
            menuAdapter.updateQuantities(cart)
        }
        // Cuando el servidor devuelve el menú, reconstruir chips de categoría
        viewModel.categories.observe(this) { cats ->
            val selected = viewModel.selectedCategory.value ?: cats.firstOrNull() ?: return@observe
            buildCategoryChips(selected)
        }
        viewModel.cartCount.observe(this) { count ->
            if (count > 0) {
                binding.tvCartCountBadge.text = count.toString()
                if (binding.cardCartButton.visibility != View.VISIBLE) {
                    binding.cardCartButton.visibility = View.VISIBLE
                    slideUpIn(binding.cardCartButton)
                }
            } else {
                binding.cardCartButton.animate().alpha(0f).translationY(40f).setDuration(200)
                    .withEndAction {
                        binding.cardCartButton.visibility = View.GONE
                        binding.cardCartButton.alpha = 1f
                        binding.cardCartButton.translationY = 0f
                    }.start()
            }
        }
        viewModel.cartTotal.observe(this) { total ->
            binding.tvCartTotalBadge.text = formatGs(total)
            binding.tvEstimatedPrice.text = formatGs(total)
        }
        viewModel.estimatedMinutes.observe(this) { binding.tvEstimatedTime.text = "$it min" }
        viewModel.rating.observe(this) { rating ->
            listOf(binding.star1, binding.star2, binding.star3, binding.star4, binding.star5)
                .forEachIndexed { i, tv ->
                    tv.animate()
                        .scaleX(if (i < rating) 1.15f else 1f)
                        .scaleY(if (i < rating) 1.15f else 1f)
                        .alpha(if (i < rating) 1f else 0.3f).setDuration(150).start()
                }
        }
        viewModel.tripState.observe(this) { renderTripState(it) }
    }

    // ── Formato Gs ────────────────────────────────────────────────────────────

    private fun formatGs(amount: Double): String =
        "Gs %,d".format((amount * 7300).toLong()).replace(',', '.')

    // ── Navegación ────────────────────────────────────────────────────────────

    private fun showMenuScreen() {
        binding.layoutMenuScreen.visibility     = View.VISIBLE
        binding.layoutConfirmScreen.visibility  = View.GONE
        binding.layoutTrackingScreen.visibility = View.GONE
        if ((viewModel.cartCount.value ?: 0) > 0) binding.cardCartButton.visibility = View.VISIBLE
        // Liberar el lock — el GPS vuelve a actualizar la posición
        confirmLocationLocked = false
    }

    private fun showConfirmScreen() {
        binding.layoutMenuScreen.visibility     = View.GONE
        binding.layoutConfirmScreen.visibility  = View.VISIBLE
        binding.layoutTrackingScreen.visibility = View.GONE
        binding.cardCartButton.visibility       = View.GONE
        val addr = binding.etDestination.text?.toString() ?: ""
        binding.etDeliveryAddress.setText(addr)
        buildCartSummaryView()
        // Desde este momento el GPS no puede pisar la posición — el mapa manda
        confirmLocationLocked = true
        // Si el mapa ya existe, centrarlo en la posición GPS actual
        val pos = deliveryLatLng
        if (pos != null) {
            confirmMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f))
        }
    }

    private fun showTrackingScreen() {
        binding.layoutMenuScreen.visibility     = View.GONE
        binding.layoutConfirmScreen.visibility  = View.GONE
        binding.layoutTrackingScreen.visibility = View.VISIBLE
        binding.cardCartButton.visibility       = View.GONE
        phraseHandler.removeCallbacks(phraseRunnable)
        cameraFollowing = true
        deliveryLatLng?.let {
            trackingMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 15f))
        }
    }

    private fun buildCartSummaryView() {
        binding.llCartSummary.removeAllViews()
        viewModel.cart.value.orEmpty().forEach { (id, qty) ->
            val item = FoodMenu.items.find { it.id == id } ?: return@forEach
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_cart_row, binding.llCartSummary, false)
            row.findViewById<TextView>(R.id.tv_cart_row_name).text =
                "${item.emoji} ${item.name}  ×$qty"
            row.findViewById<TextView>(R.id.tv_cart_row_price).text =
                formatGs(item.price * qty)
            binding.llCartSummary.addView(row)
        }
    }

    // ── Estado del pedido ─────────────────────────────────────────────────────

    private fun renderTripState(state: TripState) {
        when (state) {
            is TripState.Idle, TripState.Cancelled -> {
                phraseHandler.removeCallbacks(phraseRunnable)
                showMenuScreen()
                binding.layoutSearching.visibility = View.GONE
                binding.layoutCompleted.visibility = View.GONE
                binding.cardStatusBadge.visibility = View.GONE
                binding.cardDriverInfo.visibility  = View.GONE
                binding.btnOpenPagopar.visibility  = View.GONE
            }
            is TripState.AwaitingPayment -> {
                showTrackingScreen()
                binding.layoutSearching.visibility = View.VISIBLE
                binding.btnCancelRide.visibility   = View.VISIBLE
                binding.btnOpenPagopar.visibility  = View.VISIBLE
                binding.layoutCompleted.visibility = View.GONE
                binding.cardStatusBadge.visibility = View.VISIBLE
                binding.tvStatusBadge.text         = "💳 esperando pago"
                binding.tvSearchingText.text       =
                    "tocá «Abrir pago Pagopar» para pagar con tarjeta u otros medios"
                binding.cardDriverInfo.visibility  = View.GONE
                phraseHandler.removeCallbacks(phraseRunnable)
            }
            is TripState.SearchingDriver -> {
                showTrackingScreen()
                binding.layoutSearching.visibility = View.VISIBLE
                binding.btnCancelRide.visibility   = View.VISIBLE
                binding.btnOpenPagopar.visibility  = View.GONE
                binding.layoutCompleted.visibility = View.GONE
                binding.cardStatusBadge.visibility = View.VISIBLE
                binding.tvStatusBadge.text         = "⚡ conectando con repartidor"
                binding.cardDriverInfo.visibility  = View.GONE
                binding.tvSearchingText.text = searchingPhrases[0]
                phraseIndex = 0
                phraseHandler.postDelayed(phraseRunnable, 2500)
            }
            is TripState.DriverOnWay -> {
                phraseHandler.removeCallbacks(phraseRunnable)
                showTrackingScreen()
                binding.layoutSearching.visibility = View.VISIBLE
                binding.btnCancelRide.visibility   = View.GONE
                binding.tvSearchingText.text       = "¡repartidor recogiendo tu pedido! 🛵"
                binding.cardStatusBadge.visibility = View.VISIBLE
                binding.tvStatusBadge.text         = "🛵 recogiendo tu pedido"
                binding.cardDriverInfo.visibility  = View.VISIBLE
                populateCourierInfo(state.driver)
                fadeInView(binding.cardDriverInfo)
                deliveryLatLng?.let { addCourierMarker(it) }
            }
            is TripState.DriverArrived -> {
                showTrackingScreen()
                binding.layoutSearching.visibility = View.VISIBLE
                binding.btnCancelRide.visibility   = View.GONE
                binding.tvSearchingText.text       = "pedido recogido · viene hacia ti 🔥"
                binding.cardStatusBadge.visibility = View.VISIBLE
                binding.tvStatusBadge.text         = "✅ pedido recogido"
                binding.cardDriverInfo.visibility  = View.VISIBLE
                populateCourierInfo(state.driver)
            }
            is TripState.InProgress -> {
                showTrackingScreen()
                binding.layoutSearching.visibility = View.VISIBLE
                binding.btnCancelRide.visibility   = View.GONE
                binding.tvSearchingText.text       = "tu comida viene volando 🛵💨"
                binding.cardStatusBadge.visibility = View.VISIBLE
                binding.tvStatusBadge.text         = "🍔 en camino"
                binding.cardDriverInfo.visibility  = View.VISIBLE
                populateCourierInfo(state.driver)
            }
            is TripState.Completed -> {
                showTrackingScreen()
                binding.layoutSearching.visibility = View.GONE
                binding.layoutCompleted.visibility = View.VISIBLE
                binding.cardStatusBadge.visibility = View.GONE
                binding.cardDriverInfo.visibility  = View.GONE
                bounceIn(binding.layoutCompleted)
            }
        }
    }

    private fun submitOrder(address: String, lat: Double, lng: Double) {
        val email = binding.etBuyerEmail.text?.toString()?.trim().orEmpty()
        val phone = binding.etBuyerPhone.text?.toString()?.trim().orEmpty()
        val name  = binding.etBuyerName.text?.toString()?.trim().orEmpty().ifBlank { "Cliente" }

        if (email.isBlank() || !email.contains("@")) {
            binding.tilBuyerEmail.error = "email inválido"
            shakeView(binding.tilBuyerEmail)
            return
        }
        binding.tilBuyerEmail.error = null

        binding.btnRequestRide.isEnabled = false
        binding.btnRequestRide.text = "preparando pago…"

        viewModel.requestOrder(
            deliveryAddress = address,
            destLat = lat,
            destLng = lng,
            buyerEmail = email,
            buyerPhone = phone,
            buyerName = name,
            onCheckoutReady = { url ->
                binding.btnRequestRide.isEnabled = true
                binding.btnRequestRide.text = "💳 Pagar con Pagopar"
                openPagoparCheckout(url)
                startPaymentPolling()
            },
            onError = { msg ->
                binding.btnRequestRide.isEnabled = true
                binding.btnRequestRide.text = "💳 Pagar con Pagopar"
                Toast.makeText(this, "No se pudo iniciar el pago: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun openPagoparCheckout(url: String) {
        startActivity(
            Intent(this, PagoparCheckoutActivity::class.java)
                .putExtra(PagoparCheckoutActivity.EXTRA_URL, url)
        )
    }

    private fun startPaymentPolling() {
        stopPaymentPolling()
        paymentPollRunnable = object : Runnable {
            override fun run() {
                if (viewModel.tripState.value != TripState.AwaitingPayment) return
                viewModel.checkPayment(
                    onPaid = { stopPaymentPolling() },
                    onPending = { paymentHandler.postDelayed(this, 3000) },
                    onError = { paymentHandler.postDelayed(this, 5000) }
                )
            }
        }
        paymentHandler.postDelayed(paymentPollRunnable!!, 2500)
    }

    private fun stopPaymentPolling() {
        paymentPollRunnable?.let { paymentHandler.removeCallbacks(it) }
        paymentPollRunnable = null
    }

    private fun populateCourierInfo(courier: com.example.zuppon.model.DriverInfo) {
        binding.tvDriverName.text   = courier.name
        binding.tvDriverCar.text    = courier.carModel
        binding.tvDriverRating.text = "⭐ ${courier.rating}"
        binding.tvDriverEta.text    = "⏱ ${courier.etaMinutes} min"
    }

    // ── Animaciones ───────────────────────────────────────────────────────────

    private fun punchAnimation(view: View) {
        view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80).withEndAction {
            view.animate().scaleX(1f).scaleY(1f)
                .setDuration(150).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    private fun slideUpIn(view: View) {
        view.translationY = 60f; view.alpha = 0f
        view.animate().translationY(0f).alpha(1f)
            .setDuration(350).setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    private fun bounceIn(view: View) {
        view.scaleX = 0.7f; view.scaleY = 0.7f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(400).setInterpolator(OvershootInterpolator(1.5f)).start()
    }

    private fun fadeInView(view: View) {
        view.alpha = 0f; view.animate().alpha(1f).setDuration(300).start()
    }

    private fun shakeView(view: View) {
        ObjectAnimator.ofFloat(view, View.TRANSLATION_X,
            0f, 14f, -14f, 10f, -10f, 6f, -6f, 0f
        ).apply { duration = 400 }.start()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    // ── Constantes ────────────────────────────────────────────────────────────

    companion object {
        private val ASUNCION = LatLng(-25.2867, -57.6470)

        private val DARK_MAP_STYLE = """
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
