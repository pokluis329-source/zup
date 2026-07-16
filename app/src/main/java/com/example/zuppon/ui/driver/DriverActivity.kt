package com.example.zuppon.ui.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zuppon.R
import com.example.zuppon.RoleSelectionActivity
import com.example.zuppon.databinding.ActivityDriverBinding
import com.example.zuppon.model.OrderRecord
import com.example.zuppon.model.OrderStatus
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

class DriverActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityDriverBinding
    private val viewModel: DriverViewModel by viewModels()

    // ── Map & GPS ──────────────────────────────────────────────────────────────
    private var driverMap: GoogleMap? = null
    private var driverMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var activePolyline: com.google.android.gms.maps.model.Polyline? = null
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isTracking = false
    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
        .setMinUpdateIntervalMillis(1500L).build()

    // ── Pedidos pendientes ─────────────────────────────────────────────────────
    private lateinit var pendingAdapter: PendingOrdersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        setupPendingOrdersList()
        initMap()
        setupListeners()
        observeViewModel()
    }

    // ── Adapter de pedidos pendientes ─────────────────────────────────────────

    private fun setupPendingOrdersList() {
        pendingAdapter = PendingOrdersAdapter(
            onAccept = { serverId, request ->
                // Al aceptar: limpiar lista, arrancar viaje activo y dibujar ruta
                viewModel.acceptOrder(serverId)
                if (request.hasCoords) {
                    drawRouteToLatLng(
                        driverMarker?.position ?: return@PendingOrdersAdapter,
                        LatLng(request.destLat, request.destLng)
                    )
                }
            },
            onReject = { serverId ->
                viewModel.rejectOrder(serverId)
            }
        )
        binding.rvPendingOrders.apply {
            layoutManager = LinearLayoutManager(this@DriverActivity)
            adapter = pendingAdapter
            isNestedScrollingEnabled = false
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnChangeRole.setOnClickListener {
            viewModel.goOffline()
            startActivity(Intent(this, RoleSelectionActivity::class.java))
            finish()
        }
        binding.btnGoOnline.setOnClickListener  { viewModel.goOnline() }
        binding.btnGoOffline.setOnClickListener { viewModel.goOffline() }
        binding.btnTripAction.setOnClickListener {
            lastRouteDraw = 0L
            viewModel.advanceTripStep()
        }
    }

    // ── Observadores ──────────────────────────────────────────────────────────

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
            viewModel.tripState.value?.let { renderTripState(it) }
        }

        viewModel.tripState.observe(this) { renderTripState(it) }

        // ── Lista de pedidos PENDING ───────────────────────────────────────────
        viewModel.pendingOrders.observe(this) { orders ->
            if (viewModel.driverStatus.value != DriverStatus.ONLINE) return@observe

            val items = orders.map { req ->
                // Encontrar el server ID en el mapa del repository
                val serverId = TripRepository.getServerIdForRequest(req)
                PendingOrderItem(serverId = serverId, request = req)
            }.filter { it.serverId != -1 }

            pendingAdapter.submitList(items)

            val hasPending = items.isNotEmpty()
            binding.tvOrdersAvailable.visibility  = if (hasPending) View.VISIBLE else View.GONE
            binding.rvPendingOrders.visibility    = if (hasPending) View.VISIBLE else View.GONE
            binding.layoutWaiting.visibility      = if (hasPending) View.GONE   else View.VISIBLE

            // Dibujar markers en el mapa para todos los pedidos disponibles
            if (hasPending) updateOrderMarkersOnMap(orders)
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

    // ── Markers de pedidos disponibles en el mapa ─────────────────────────────

    private val orderMarkers = mutableListOf<Marker>()

    private fun updateOrderMarkersOnMap(orders: List<TripRequest>) {
        val map = driverMap ?: return
        // Quitar markers viejos
        orderMarkers.forEach { it.remove() }
        orderMarkers.clear()

        orders.forEach { req ->
            if (!req.hasCoords) return@forEach
            val dest = LatLng(req.destLat, req.destLng)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(dest)
                    .title("🔔 ${req.passengerName.take(30)}")
                    .snippet(req.destination.take(50))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
                    .zIndex(3f)
            )
            if (marker != null) orderMarkers.add(marker)
        }
    }

    // ── Historial ─────────────────────────────────────────────────────────────

    private fun renderHistory(history: List<OrderRecord>) {
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
                OrderStatus.PENDING    -> { statusTv.text = "🔍 Buscando repartidor"; statusTv.setTextColor(getColor(R.color.zuppon_text_secondary)) }
                OrderStatus.ACCEPTED   -> { statusTv.text = "🛵 Repartidor en camino"; statusTv.setTextColor(getColor(R.color.zuppon_accent)) }
                OrderStatus.PICKED_UP  -> { statusTv.text = "✅ Pedido recogido"; statusTv.setTextColor(getColor(R.color.zuppon_accent)) }
                OrderStatus.DELIVERING -> { statusTv.text = "🍔 En camino"; statusTv.setTextColor(getColor(R.color.zuppon_yellow)) }
                OrderStatus.COMPLETED  -> { statusTv.text = "✅ Entregado"; statusTv.setTextColor(getColor(R.color.zuppon_green)) }
                OrderStatus.CANCELLED  -> { statusTv.text = "❌ Cancelado"; statusTv.setTextColor(getColor(R.color.zuppon_accent)) }
            }
            binding.llOrderHistory.addView(card)
        }
    }

    // ── Render del estado del panel inferior ──────────────────────────────────

    private fun renderTripState(state: TripState) {
        binding.layoutOffline.visibility    = View.GONE
        binding.layoutOnline.visibility     = View.GONE
        binding.layoutRequest.visibility    = View.GONE
        binding.layoutActiveTrip.visibility = View.GONE
        binding.layoutHistory.visibility    = View.GONE

        when (state) {
            is TripState.Idle, TripState.Cancelled -> {
                when (viewModel.driverStatus.value) {
                    DriverStatus.OFFLINE -> {
                        binding.layoutOffline.visibility = View.VISIBLE
                        binding.layoutHistory.visibility = View.VISIBLE
                    }
                    DriverStatus.ONLINE -> binding.layoutOnline.visibility = View.VISIBLE
                    else -> {
                        binding.layoutOffline.visibility = View.VISIBLE
                        binding.layoutHistory.visibility = View.VISIBLE
                    }
                }
            }
            is TripState.SearchingDriver -> {
                // SearchingDriver ahora solo lo usa el pasajero — el driver usa pendingOrders
                if (viewModel.driverStatus.value == DriverStatus.ONLINE)
                    binding.layoutOnline.visibility = View.VISIBLE
                else
                    binding.layoutOffline.visibility = View.VISIBLE
            }
            is TripState.DriverOnWay -> {
                if (viewModel.driverStatus.value == DriverStatus.ACTIVE_TRIP) {
                    binding.layoutActiveTrip.visibility = View.VISIBLE
                    TripRepository.pendingRequest.value?.let { drawRouteToRequest(it) }
                } else if (viewModel.driverStatus.value == DriverStatus.ONLINE)
                    binding.layoutOnline.visibility = View.VISIBLE
                else
                    binding.layoutOffline.visibility = View.VISIBLE
            }
            is TripState.DriverArrived, is TripState.InProgress -> {
                if (viewModel.driverStatus.value == DriverStatus.ACTIVE_TRIP)
                    binding.layoutActiveTrip.visibility = View.VISIBLE
                else if (viewModel.driverStatus.value == DriverStatus.ONLINE)
                    binding.layoutOnline.visibility = View.VISIBLE
                else
                    binding.layoutOffline.visibility = View.VISIBLE
            }
            is TripState.Completed -> {
                binding.layoutOnline.visibility = View.VISIBLE
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) startDriverTracking()
        syncStateOnResume()
    }

    private fun syncStateOnResume() {
        val status = TripRepository.driverStatus.value ?: return
        if (status == DriverStatus.ONLINE) TripRepository.ensurePolling()

        val req = TripRepository.pendingRequest.value
        if (status == DriverStatus.ACTIVE_TRIP && req != null && driverMap != null) {
            drawRouteToRequest(req)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) startDriverTracking()

        // Restaurar ruta al volver a la pantalla con viaje activo
        val req = TripRepository.pendingRequest.value
        if (req != null && TripRepository.driverStatus.value == DriverStatus.ACTIVE_TRIP) {
            drawRouteToRequest(req)
        }
        // Restaurar markers de pedidos pendientes
        val pending = TripRepository.pendingOrders.value
        if (!pending.isNullOrEmpty()) updateOrderMarkersOnMap(pending)
    }

    private fun applyMapStyle(map: GoogleMap) {
        try { map.setMapStyle(MapStyleOptions(MAP_STYLE)) } catch (_: Exception) { }
    }

    // ── GPS tracking ──────────────────────────────────────────────────────────

    private fun startDriverTracking() {
        if (isTracking || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        isTracking = true
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    updateDriverMarker(LatLng(loc.latitude, loc.longitude))
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
        val req = TripRepository.pendingRequest.value
        if (req != null && TripRepository.driverStatus.value == DriverStatus.ACTIVE_TRIP) {
            drawRouteToRequest(req)
        }
    }

    // ── Route drawing ─────────────────────────────────────────────────────────

    private var lastRouteDraw = 0L

    private fun drawRouteToRequest(req: TripRequest) {
        if (!req.hasCoords) return
        val destLatLng = LatLng(req.destLat, req.destLng)
        val origin = driverMarker?.position
        if (origin != null) {
            drawRouteToLatLng(origin, destLatLng)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) drawRouteToLatLng(LatLng(loc.latitude, loc.longitude), destLatLng)
            }
        }
    }

    private fun drawRouteToLatLng(origin: LatLng, dest: LatLng) {
        val now = System.currentTimeMillis()
        if (now - lastRouteDraw < 15_000) return
        lastRouteDraw = now

        val apiKey = getString(R.string.google_maps_key)

        // Asegurar marker de destino antes de la llamada a la API
        runOnUiThread {
            val map = driverMap ?: return@runOnUiThread
            if (destinationMarker == null) {
                destinationMarker = map.addMarker(
                    MarkerOptions().position(dest).title("Destino 📍")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                )
            } else {
                destinationMarker?.position = dest
            }
        }

        Thread {
            try {
                val points = DirectionsHelper.getRoute(apiKey, origin, dest)
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
