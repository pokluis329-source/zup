package com.example.zuppon.ui.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.zuppon.R
import com.example.zuppon.RoleSelectionActivity
import com.example.zuppon.databinding.ActivityDriverBinding
import com.example.zuppon.databinding.DialogOrderRequestBinding
import com.example.zuppon.model.TripRequest
import com.example.zuppon.model.TripState
import com.example.zuppon.repository.DriverStatus
import com.example.zuppon.repository.TripRepository
import com.example.zuppon.util.DirectionsHelper
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

class DriverActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityDriverBinding
    private val viewModel: DriverViewModel by viewModels()

    // ── Map & GPS ──────────────────────────────────────────────────────────────
    private var driverMap: GoogleMap? = null
    private var driverMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var orderMarker: Marker? = null          // marker del pedido pendiente en el mapa
    private var orderSheet: BottomSheetDialog? = null // diálogo activo
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isTracking = false
    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
        .setMinUpdateIntervalMillis(1500L).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        initMap()

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnChangeRole.setOnClickListener {
            viewModel.goOffline()
            startActivity(Intent(this, RoleSelectionActivity::class.java))
            finish()
        }
        binding.btnGoOnline.setOnClickListener   { viewModel.goOnline() }
        binding.btnGoOffline.setOnClickListener  { viewModel.goOffline() }
        // btnAccept / btnReject ahora viven en el BottomSheet del mapa
        binding.btnTripAction.setOnClickListener {
            lastRouteDraw = 0L   // forzar redibujado inmediato de ruta al avanzar paso
            // NO poner destinationMarker = null — el pin debe permanecer visible siempre
            viewModel.advanceTripStep()
        }
    }

    private fun observeViewModel() {
        viewModel.tripsToday.observe(this) { binding.tvTripsToday.text = it.toString() }

        viewModel.earningsToday.observe(this) { earnings ->
            binding.tvEarningsToday.text =
                "Gs %,d".format((earnings * 7300).toLong()).replace(',', '.')
        }

        viewModel.driverStatus.observe(this) { status ->
            when (status) {
                DriverStatus.OFFLINE -> {
                    binding.statusDot.setBackgroundResource(R.drawable.circle_gray)
                    binding.tvConnectionStatus.text = getString(R.string.status_offline)
                    binding.tvConnectionStatus.setTextColor(getColor(R.color.zuppon_text_secondary))
                }
                DriverStatus.ONLINE -> {
                    binding.statusDot.setBackgroundResource(R.drawable.circle_green)
                    binding.tvConnectionStatus.text = getString(R.string.status_online)
                    binding.tvConnectionStatus.setTextColor(getColor(R.color.zuppon_green))
                }
                DriverStatus.ACTIVE_TRIP -> {
                    binding.statusDot.setBackgroundResource(R.drawable.circle_green)
                    binding.tvConnectionStatus.text = "pedido activo 🔥"
                    binding.tvConnectionStatus.setTextColor(getColor(R.color.zuppon_yellow))
                }
            }
            // Re-renderizar el panel inferior porque renderTripState depende del driverStatus.
            // Sin esto, al pulsar "Empezar a ganar" el panel no cambia hasta que tripState
            // emite otro valor (lo cual requería salir y volver a entrar).
            viewModel.tripState.value?.let { renderTripState(it) }
        }

        viewModel.tripState.observe(this) { renderTripState(it) }

        viewModel.pendingRequest.observe(this) { request ->
            if (request != null && viewModel.driverStatus.value == DriverStatus.ONLINE
                && viewModel.tripState.value == TripState.SearchingDriver
                && orderMarker == null) {   // no redibujar si el marker ya está
                placeOrderMarkerOnMap(request)
            }
        }

        viewModel.tripStep.observe(this) { step ->
            when (step) {
                0 -> {
                    binding.btnTripAction.text      = getString(R.string.arrive_passenger)
                    binding.tvActiveTripStatus.text = "🛵  Yendo al restaurante…"
                }
                1 -> {
                    binding.btnTripAction.text      = getString(R.string.start_trip)
                    binding.tvActiveTripStatus.text = "✅  Pedido recogido · saliendo a entregar"
                }
                2 -> {
                    binding.btnTripAction.text      = getString(R.string.end_trip)
                    binding.tvActiveTripStatus.text = "🍔  Pedido en camino al cliente"
                }
            }
        }

        // Historial de pedidos
        TripRepository.orderHistory.observe(this) { history ->
            renderHistory(history)
        }
    }

    private fun renderHistory(history: List<com.example.zuppon.model.OrderRecord>) {
        binding.llOrderHistory.removeAllViews()
        if (history.isEmpty()) {
            binding.layoutEmptyHistory.visibility = View.VISIBLE
            return
        }
        binding.layoutEmptyHistory.visibility = View.GONE

        history.forEach { record ->
            val card = layoutInflater.inflate(
                R.layout.item_order_history, binding.llOrderHistory, false
            )
            card.findViewById<TextView>(R.id.tv_order_destination).text = "📍 ${record.destination}"
            card.findViewById<TextView>(R.id.tv_order_fare).text = record.formattedFare()
            card.findViewById<TextView>(R.id.tv_order_time).text = record.formattedTime()

            val statusTv = card.findViewById<TextView>(R.id.tv_order_status)
            when (record.status) {
                com.example.zuppon.model.OrderStatus.PENDING -> {
                    statusTv.text = "🔍 Buscando repartidor"
                    statusTv.setTextColor(getColor(R.color.zuppon_text_secondary))
                }
                com.example.zuppon.model.OrderStatus.ACCEPTED -> {
                    statusTv.text = "🛵 Repartidor en camino"
                    statusTv.setTextColor(getColor(R.color.zuppon_accent))
                }
                com.example.zuppon.model.OrderStatus.PICKED_UP -> {
                    statusTv.text = "✅ Pedido recogido"
                    statusTv.setTextColor(getColor(R.color.zuppon_accent))
                }
                com.example.zuppon.model.OrderStatus.DELIVERING -> {
                    statusTv.text = "🍔 En camino"
                    statusTv.setTextColor(getColor(R.color.zuppon_yellow))
                }
                com.example.zuppon.model.OrderStatus.COMPLETED -> {
                    statusTv.text = "✅ Entregado"
                    statusTv.setTextColor(getColor(R.color.zuppon_green))
                }
                com.example.zuppon.model.OrderStatus.CANCELLED -> {
                    statusTv.text = "❌ Cancelado"
                    statusTv.setTextColor(getColor(R.color.zuppon_accent))
                }
            }
            binding.llOrderHistory.addView(card)
        }
    }

    private fun renderTripState(state: TripState) {
        binding.layoutOffline.visibility    = View.GONE
        binding.layoutOnline.visibility     = View.GONE
        binding.layoutRequest.visibility    = View.GONE
        binding.layoutActiveTrip.visibility = View.GONE
        binding.layoutHistory.visibility    = View.GONE  // oculto por defecto, se activa solo en Idle/Completed+Offline

        when (state) {
            is TripState.Idle, TripState.Cancelled -> {
                when (viewModel.driverStatus.value) {
                    DriverStatus.OFFLINE -> {
                        binding.layoutOffline.visibility = View.VISIBLE
                        binding.layoutHistory.visibility = View.VISIBLE
                    }
                    DriverStatus.ONLINE  -> binding.layoutOnline.visibility = View.VISIBLE
                    else                 -> {
                        binding.layoutOffline.visibility = View.VISIBLE
                        binding.layoutHistory.visibility = View.VISIBLE
                    }
                }
            }
            is TripState.SearchingDriver -> {
                if (viewModel.driverStatus.value == DriverStatus.ONLINE) {
                    binding.layoutOnline.visibility = View.VISIBLE
                    // Solo dibujar si no hay marker ya en pantalla (evita redibujo al rechazar)
                    if (orderMarker == null) {
                        TripRepository.pendingRequest.value?.let { req ->
                            placeOrderMarkerOnMap(req)
                        }
                    }
                } else {
                    binding.layoutOffline.visibility = View.VISIBLE
                }
            }
            is TripState.DriverOnWay -> {
                if (viewModel.driverStatus.value == DriverStatus.ACTIVE_TRIP) {
                    binding.layoutActiveTrip.visibility = View.VISIBLE
                    TripRepository.pendingRequest.value?.let { drawRouteToDestination(it) }
                } else if (viewModel.driverStatus.value == DriverStatus.ONLINE)
                    binding.layoutOnline.visibility = View.VISIBLE
                else
                    binding.layoutOffline.visibility = View.VISIBLE
            }
            is TripState.DriverArrived,
            is TripState.InProgress -> {
                if (viewModel.driverStatus.value == DriverStatus.ACTIVE_TRIP)
                    binding.layoutActiveTrip.visibility = View.VISIBLE
                else if (viewModel.driverStatus.value == DriverStatus.ONLINE)
                    binding.layoutOnline.visibility = View.VISIBLE
                else
                    binding.layoutOffline.visibility = View.VISIBLE
            }
            is TripState.Completed -> {
                // Al completar el pedido el driver ya está ONLINE de nuevo — mostrar panel de espera
                binding.layoutOnline.visibility = View.VISIBLE
            }
        }
    }

    /** Crea un pedido de prueba con tu ubicación GPS actual como destino */
    private fun simulateNearbyOrder() {
        val items = listOf(
            "Burger Clásica x1, Refresco x1",
            "Pizza Pepperoni x2",
            "Tacos al Pastor x3, Malteada x1",
            "Pasta Carbonara x1, Ensalada César x1",
            "Burger BBQ Doble x2, Jugo de Naranja x1"
        ).random()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        // Offset pequeño cerca del conductor
                        val latOffset = ((-5..5).random()) * 0.0005
                        val lngOffset = ((-5..5).random()) * 0.0005
                        val nearLat = loc.latitude + latOffset
                        val nearLng = loc.longitude + lngOffset
                        // Geocodificar reverso en background para obtener dirección real
                        Thread {
                            val address = try {
                                val gc = Geocoder(this, Locale("es", "PY"))
                                @Suppress("DEPRECATION")
                                val list = gc.getFromLocation(nearLat, nearLng, 1)
                                val a = list?.firstOrNull()
                                if (a != null) buildString {
                                    if (!a.thoroughfare.isNullOrBlank())    append(a.thoroughfare)
                                    if (!a.subThoroughfare.isNullOrBlank()) append(" ${a.subThoroughfare}")
                                    if (!a.subLocality.isNullOrBlank())     append(", ${a.subLocality}")
                                    if (!a.locality.isNullOrBlank())        append(", ${a.locality}")
                                }.ifBlank { "%.4f, %.4f".format(nearLat, nearLng) }
                                else "%.4f, %.4f".format(nearLat, nearLng)
                            } catch (_: Exception) {
                                "%.4f, %.4f".format(nearLat, nearLng)
                            }
                            runOnUiThread { injectTestOrder(items, address) }
                        }.start()
                    } else {
                        injectTestOrder(items, "Av. Mariscal López 1234, Asunción")
                    }
                }
                .addOnFailureListener { injectTestOrder(items, "Av. Mariscal López 1234, Asunción") }
        } else {
            injectTestOrder(items, "Av. Mariscal López 1234, Asunción")
        }
    }

    private fun injectTestOrder(items: String, address: String) {
        val fare = (50.0..150.0).let {
            it.start + Math.random() * (it.endInclusive - it.start)
        }.let { Math.round(it * 10.0) / 10.0 }

        // 1. Primero conectar si está offline
        if (TripRepository.driverStatus.value == DriverStatus.OFFLINE) {
            TripRepository.driverGoOnline()
        }

        // 2. Crear el pedido — ahora el observer lo recibe con status ONLINE
        val request = TripRequest(
            passengerName = "🧪 PRUEBA: $items",
            destination   = address,
            fare          = fare
        )
        TripRepository.passengerRequestTrip(request)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) startDriverTracking()

        // Sincronizar estado persistido en TripRepository — maneja el caso de
        // volver a la Activity sin haber pasado por los observers de onCreate
        syncStateOnResume()
    }

    /** Restaura el estado visual correcto al volver a la pantalla */
    private fun syncStateOnResume() {
        val status = TripRepository.driverStatus.value ?: return
        val state  = TripRepository.tripState.value ?: return
        val req    = TripRepository.pendingRequest.value

        // 1. Si estaba ONLINE pero el polling no está activo, arrancarlo
        if (status == DriverStatus.ONLINE) {
            TripRepository.ensurePolling()
        }

        // 2. Si hay pedido en espera de aceptación, mostrarlo en el mapa
        if (state == TripState.SearchingDriver && req != null
            && status == DriverStatus.ONLINE) {
            if (driverMap != null && orderMarker == null) placeOrderMarkerOnMap(req)
        }

        // 3. Si hay viaje activo, restaurar ruta
        if (status == DriverStatus.ACTIVE_TRIP && req != null && driverMap != null) {
            drawRouteToDestination(req)
        }
    }

    override fun onPause() {
        super.onPause()
        stopDriverTracking()
    }

    // ── Map ───────────────────────────────────────────────────────────────────

    private fun initMap() {
        (supportFragmentManager.findFragmentById(R.id.map_driver) as? SupportMapFragment)
            ?.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        driverMap = map
        applyMapStyle(map)
        map.isBuildingsEnabled = true
        map.isTrafficEnabled = true
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = true
        // Start tracking driver position
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startDriverTracking()
        }
        // Restaurar estado al volver a la pantalla
        val req    = TripRepository.pendingRequest.value
        val status = TripRepository.driverStatus.value
        val state  = TripRepository.tripState.value
        when {
            // Viaje activo — redibujar ruta
            req != null && status == DriverStatus.ACTIVE_TRIP -> {
                drawRouteToDestination(req)
            }
            // Pedido pendiente de aceptación — redibujar marker amarillo solo si no existe
            req != null && status == DriverStatus.ONLINE
                    && state == TripState.SearchingDriver -> {
                if (orderMarker == null) placeOrderMarkerOnMap(req)
            }
        }
    }

    private fun applyMapStyle(map: GoogleMap) {
        try {
            map.setMapStyle(MapStyleOptions(MAP_STYLE))
        } catch (_: Exception) { }
    }

    // ── GPS tracking ──────────────────────────────────────────────────────────

    private fun startDriverTracking() {
        if (isTracking || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        isTracking = true
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val latlng = LatLng(loc.latitude, loc.longitude)
                    updateDriverMarker(latlng)
                }
            }
        }
        fusedClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopDriverTracking() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        isTracking = false
    }

    private fun updateDriverMarker(latlng: LatLng) {
        if (driverMarker == null) {
            driverMarker = driverMap?.addMarker(
                MarkerOptions().position(latlng).title("Tú 🛵")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .zIndex(2f)
            )
            driverMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latlng, 15f))
        } else {
            driverMarker?.position = latlng
        }
        // If active trip, draw route from current position to destination
        val req = TripRepository.pendingRequest.value
        if (req != null && TripRepository.driverStatus.value == DriverStatus.ACTIVE_TRIP) {
            drawRouteFromTo(latlng, req.destination)
        }
    }

    // ── Route drawing ─────────────────────────────────────────────────────────

    private var activePolyline: com.google.android.gms.maps.model.Polyline? = null

    /** Traza ruta activa usando las coords del TripRequest si están disponibles */
    private fun drawRouteToDestination(req: TripRequest) {
        val destLatLng = if (req.hasCoords)
            LatLng(req.destLat, req.destLng)
        else
            parseAddress(req.destination) ?: return

        val currentPos = driverMarker?.position
        if (currentPos != null) {
            drawRouteToLatLng(currentPos, destLatLng)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) drawRouteToLatLng(LatLng(loc.latitude, loc.longitude), destLatLng)
            }
        }
    }

    /** Sobrecarga legacy para llamadas internas que solo tienen texto */
    private fun drawRouteToDestination(destinationAddress: String) {
        val req = TripRepository.pendingRequest.value
        val destLatLng = if (req != null && req.hasCoords)
            LatLng(req.destLat, req.destLng)
        else
            parseAddress(destinationAddress) ?: return

        val currentPos = driverMarker?.position
        if (currentPos != null) {
            drawRouteToLatLng(currentPos, destLatLng)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) drawRouteToLatLng(LatLng(loc.latitude, loc.longitude), destLatLng)
            }
        }
    }

    private var lastRouteDraw = 0L
    private fun drawRouteFromTo(origin: LatLng, destinationAddress: String) {
        val req = TripRepository.pendingRequest.value
        val destLatLng = if (req != null && req.hasCoords)
            LatLng(req.destLat, req.destLng)
        else
            parseAddress(destinationAddress) ?: return
        drawRouteToLatLng(origin, destLatLng)
    }

    private fun drawRouteToLatLng(origin: LatLng, destLatLng: LatLng) {
        // Throttle: redibuja como máximo cada 15 segundos
        val now = System.currentTimeMillis()
        if (now - lastRouteDraw < 15_000) return
        lastRouteDraw = now

        val apiKey = getString(R.string.google_maps_key)

        // Asegurar que el marker de destino existe ANTES de llamar a Directions API
        // Así nunca desaparece aunque la API falle o devuelva vacío
        runOnUiThread {
            val map = driverMap ?: return@runOnUiThread
            if (destinationMarker == null) {
                destinationMarker = map.addMarker(
                    MarkerOptions().position(destLatLng)
                        .title("Destino 📍")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                )
            } else {
                destinationMarker?.position = destLatLng
            }
        }

        Thread {
            try {
                val points = DirectionsHelper.getRoute(apiKey, origin, destLatLng)
                runOnUiThread {
                    val map = driverMap ?: return@runOnUiThread
                    if (points.isNotEmpty()) {
                        activePolyline?.remove()
                        activePolyline = map.addPolyline(
                            PolylineOptions().addAll(points)
                                .width(14f).color(0xFFFF5722.toInt()).geodesic(true)
                        )
                        val bounds = com.google.android.gms.maps.model.LatLngBounds.Builder()
                        points.forEach { bounds.include(it) }
                        bounds.include(origin)
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    /** Preview del destino en el mapa ANTES de aceptar — usa coords exactas */
    private fun showDestinationPreview(req: TripRequest) {
        val destLatLng = if (req.hasCoords)
            LatLng(req.destLat, req.destLng)
        else
            parseAddress(req.destination) ?: return

        val apiKey = getString(R.string.google_maps_key)
        val origin = driverMarker?.position
        Thread {
            try {
                val points = if (origin != null)
                    DirectionsHelper.getRoute(apiKey, origin, destLatLng)
                else emptyList()

                runOnUiThread {
                    val map = driverMap ?: return@runOnUiThread
                    val driverPos = driverMarker?.position
                    map.clear()
                    // Resetear referencias tras map.clear()
                    driverMarker      = null
                    destinationMarker = null
                    orderMarker       = null
                    activePolyline    = null
                    if (driverPos != null) {
                        driverMarker = map.addMarker(
                            MarkerOptions().position(driverPos).title("Tú 🛵")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                                .zIndex(2f)
                        )
                    }
                    destinationMarker = map.addMarker(
                        MarkerOptions().position(destLatLng)
                            .title("📍 Destino del cliente")
                            .snippet(req.destination)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    )
                    // NO showInfoWindow — no mostrar texto flotante sobre el mapa
                    if (points.isNotEmpty()) {
                        map.addPolyline(
                            PolylineOptions().addAll(points)
                                .width(10f).color(0x88FF5722.toInt()).geodesic(true)
                        )
                        val bounds = com.google.android.gms.maps.model.LatLngBounds.Builder()
                        points.forEach { bounds.include(it) }
                        driverPos?.let { bounds.include(it) }
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
                    } else {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(destLatLng, 14f))
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    /** Muestra el pedido como marker animado en el mapa */
    private fun placeOrderMarkerOnMap(req: TripRequest) {
        orderMarker?.remove()
        val apiKey = getString(R.string.google_maps_key)

        // Resolver destino
        val destLatLng: LatLng? = if (req.hasCoords)
            LatLng(req.destLat, req.destLng)
        else
            null  // se resuelve en el Thread abajo si es necesario

        // Obtener posición real del conductor (GPS o lastLocation), nunca Asunción como fallback
        fun placeWithOrigin(origin: LatLng) {
            Thread {
                try {
                    val dest = destLatLng ?: parseAddress(req.destination) ?: return@Thread
                    val points = DirectionsHelper.getRoute(apiKey, origin, dest)

                    runOnUiThread {
                        val map = driverMap ?: return@runOnUiThread
                        val driverPos = driverMarker?.position ?: origin
                        map.clear()
                        // Resetear TODAS las referencias tras map.clear()
                        driverMarker      = null
                        destinationMarker = null
                        orderMarker       = null
                        activePolyline    = null

                        driverMarker = map.addMarker(
                            MarkerOptions().position(driverPos).title("Tú 🛵")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                                .zIndex(2f)
                        )
                        orderMarker = map.addMarker(
                            MarkerOptions()
                                .position(dest)
                                .title("🔔 Nuevo pedido — toca para ver")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
                                .zIndex(3f)
                        )
                        // NO showInfoWindow() — el precio no debe flotar encima del mapa

                        if (points.isNotEmpty()) {
                            map.addPolyline(
                                PolylineOptions().addAll(points)
                                    .width(10f).color(0x88FF5722.toInt()).geodesic(true)
                            )
                            val bounds = com.google.android.gms.maps.model.LatLngBounds.Builder()
                            points.forEach { bounds.include(it) }
                            bounds.include(driverPos)
                            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
                        } else {
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(dest, 14f))
                        }

                        map.setOnMarkerClickListener { marker ->
                            if (marker == orderMarker) { showOrderSheet(req); true } else false
                        }
                    }
                } catch (_: Exception) { }
            }.start()
        }

        val currentPos = driverMarker?.position
        if (currentPos != null) {
            placeWithOrigin(currentPos)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            // GPS aún no llegó — pedir lastLocation real en vez de usar Asunción
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    val latlng = LatLng(loc.latitude, loc.longitude)
                    driverMarker = driverMap?.addMarker(
                        MarkerOptions().position(latlng).title("Tú 🛵")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                            .zIndex(2f)
                    )
                    placeWithOrigin(latlng)
                } else {
                    // Sin GPS disponible: mostrar solo el marker del pedido sin ruta
                    val dest = destLatLng ?: return@addOnSuccessListener
                    runOnUiThread {
                        val map = driverMap ?: return@runOnUiThread
                        orderMarker = map.addMarker(
                            MarkerOptions().position(dest)
                                .title("🔔 Nuevo pedido — toca para ver")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
                                .zIndex(3f)
                        )
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(dest, 14f))
                        map.setOnMarkerClickListener { marker ->
                            if (marker == orderMarker) { showOrderSheet(req); true } else false
                        }
                    }
                }
            }
        }
    }

    /** BottomSheet con detalles del pedido: Aceptar / Rechazar */
    private fun showOrderSheet(req: TripRequest) {
        orderSheet?.dismiss()
        val sheet = BottomSheetDialog(this, R.style.Theme_Zuppon)
        val sheetBinding = DialogOrderRequestBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.tvDlgItems.text       = req.passengerName
        sheetBinding.tvDlgDestination.text = req.destination
        sheetBinding.tvDlgFare.text        = formatGs(req.fare)

        sheetBinding.btnDlgAccept.setOnClickListener {
            sheet.dismiss()
            orderMarker?.remove()
            orderMarker = null
            viewModel.acceptTrip()
        }
        sheetBinding.btnDlgReject.setOnClickListener {
            sheet.dismiss()
            // Guardamos el request antes de que el ViewModel lo limpie,
            // para mantener el tap del marker funcional
            val currentReq = TripRepository.pendingRequest.value
            viewModel.rejectTrip()
            // Restaurar el listener del marker con el pedido guardado
            if (currentReq != null && orderMarker != null) {
                driverMap?.setOnMarkerClickListener { marker ->
                    if (marker == orderMarker) { showOrderSheet(currentReq); true } else false
                }
            }
        }
        orderSheet = sheet
        sheet.show()
    }

    private fun parseAddress(address: String): LatLng? {
        val parts = address.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null) return LatLng(lat, lng)
        }
        return try {
            val gc = Geocoder(this, Locale("es", "PY"))
            @Suppress("DEPRECATION")
            gc.getFromLocationName(address, 1)?.firstOrNull()
                ?.let { LatLng(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

    private fun formatGs(amount: Double) =
        "Gs %,d".format((amount * 7300).toLong()).replace(',', '.')

    companion object {
        private val MAP_STYLE = """
        [{"featureType":"water","elementType":"geometry","stylers":[{"color":"#aee0f4"}]},
         {"featureType":"landscape.man_made","elementType":"geometry.fill","stylers":[{"color":"#f2f0eb"}]},
         {"featureType":"landscape.natural","elementType":"geometry.fill","stylers":[{"color":"#ddeecb"}]},
         {"featureType":"building","elementType":"geometry.fill","stylers":[{"color":"#e8e0d8"}]},
         {"featureType":"building","elementType":"geometry.stroke","stylers":[{"color":"#c8bfb0"}]},
         {"featureType":"road.highway","elementType":"geometry.fill","stylers":[{"color":"#ffe082"}]},
         {"featureType":"road.highway","elementType":"geometry.stroke","stylers":[{"color":"#ffb300"}]},
         {"featureType":"road.arterial","elementType":"geometry.fill","stylers":[{"color":"#ffffff"}]},
         {"featureType":"road.local","elementType":"geometry.fill","stylers":[{"color":"#ffffff"}]},
         {"featureType":"poi.park","elementType":"geometry.fill","stylers":[{"color":"#a5d6a7"}]},
         {"featureType":"poi.business","elementType":"labels.icon","stylers":[{"visibility":"on"}]},
         {"elementType":"labels.text.fill","stylers":[{"color":"#444444"}]},
         {"elementType":"labels.text.stroke","stylers":[{"color":"#ffffff"}]}]
        """.trimIndent()
    }
}
