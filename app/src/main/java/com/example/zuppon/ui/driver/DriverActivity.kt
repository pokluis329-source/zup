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
import com.example.zuppon.R
import com.example.zuppon.RoleSelectionActivity
import com.example.zuppon.databinding.ActivityDriverBinding
import com.example.zuppon.databinding.DialogOrderRequestBinding
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
import com.google.android.material.bottomsheet.BottomSheetDialog

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

    // ── Pedidos en mapa: marker → (serverId, TripRequest) ─────────────────────
    private val markerToOrder = mutableMapOf<Marker, Pair<Int, TripRequest>>()
    private var lastKnownPendingOrders: List<TripRequest> = emptyList()
    private var pendingCameraFitDone = false
    private var orderSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        initMap()
        setupListeners()
        observeViewModel()
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnChangeRole.setOnClickListener {
            viewModel.goOffline()
            startActivity(Intent(this, RoleSelectionActivity::class.java))
            finish()
        }
        binding.btnGoOnline.setOnClickListener { viewModel.goOnline() }
        binding.btnGoOffline.setOnClickListener {
            pendingCameraFitDone = false
            orderSheet?.dismiss()
            viewModel.goOffline()
        }
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

        // ── Pedidos PENDING → pins en el mapa ─────────────────────────────────
        viewModel.pendingOrders.observe(this) { orders ->
            android.util.Log.d("ZUPPON_DRIVER", "🔔 Observer pendingOrders disparado - DriverStatus=${viewModel.driverStatus.value}, pedidos=${orders.size}")
            if (viewModel.driverStatus.value != DriverStatus.ONLINE) {
                android.util.Log.d("ZUPPON_DRIVER", "⚠️ Driver no está ONLINE, ignorando pedidos")
                return@observe
            }
            updateOrderPinsOnMap(orders)

            // Panel inferior: solo mostrar badge con cantidad — no lista
            val count = orders.size
            if (count > 0) {
                binding.tvOrdersAvailable.text = "🔔 $count pedido${if (count > 1) "s" else ""} disponible${if (count > 1) "s" else ""} · toca un pin"
                binding.tvOrdersAvailable.visibility = View.VISIBLE
            } else {
                binding.tvOrdersAvailable.visibility = View.GONE
            }
            // Ocultar siempre la lista — la interacción es por el mapa
            binding.rvPendingOrders.visibility = View.GONE
            binding.layoutWaiting.visibility = if (count > 0) View.GONE else View.VISIBLE
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

        TripRepository.orderHistory.observe(this) { renderHistory(it) }
    }

    // ── Pins de pedidos en el mapa ────────────────────────────────────────────

    private fun updateOrderPinsOnMap(orders: List<TripRequest>) {
        android.util.Log.d("ZUPPON_DRIVER", "════════════════════════════════════════════════")
        android.util.Log.d("ZUPPON_DRIVER", "📍 updateOrderPinsOnMap() llamado")
        android.util.Log.d("ZUPPON_DRIVER", "📍 Total pedidos recibidos: ${orders.size}")
        
        lastKnownPendingOrders = orders
        val map = driverMap ?: run {
            android.util.Log.e("ZUPPON_DRIVER", "❌ Mapa no está listo")
            return
        }

        markerToOrder.keys.forEach { it.remove() }
        markerToOrder.clear()

        if (orders.isEmpty()) {
            android.util.Log.d("ZUPPON_DRIVER", "⚠️ Lista de pedidos vacía")
            pendingCameraFitDone = false
            return
        }

        // Solo pinear pedidos con coords exactas — sin geocoding de texto
        val withCoords = orders.filter { it.hasCoords }
        android.util.Log.d("ZUPPON_DRIVER", "📍 Pedidos con coords válidas: ${withCoords.size}")
        
        orders.forEachIndexed { i, req ->
            android.util.Log.d("ZUPPON_DRIVER", "  [$i] ${req.passengerName} → hasCoords=${req.hasCoords}, lat=${req.destLat}, lng=${req.destLng}, dest='${req.destination}'")
        }
        
        withCoords.forEach { req ->
            val serverId = TripRepository.getServerIdForRequest(req)
            android.util.Log.d("ZUPPON_DRIVER", "  → Creando pin para serverId=$serverId en (${req.destLat}, ${req.destLng})")
            if (serverId == -1) {
                android.util.Log.e("ZUPPON_DRIVER", "  ❌ serverId no encontrado para pedido")
                return@forEach
            }
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(req.destLat, req.destLng))
                    .title("🔔 Toca para ver el pedido")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
                    .zIndex(3f)
            ) ?: run {
                android.util.Log.e("ZUPPON_DRIVER", "  ❌ No se pudo crear marker")
                return@forEach
            }
            android.util.Log.d("ZUPPON_DRIVER", "  ✅ Pin creado exitosamente")
            markerToOrder[marker] = Pair(serverId, req)
        }
        
        android.util.Log.d("ZUPPON_DRIVER", "📍 Total pins creados: ${markerToOrder.size}")
        android.util.Log.d("ZUPPON_DRIVER", "════════════════════════════════════════════════")

        map.setOnMarkerClickListener { tapped ->
            val (sid, r) = markerToOrder[tapped] ?: return@setOnMarkerClickListener false
            showOrderSheet(sid, r)
            true
        }

        if (!pendingCameraFitDone && withCoords.isNotEmpty()) {
            pendingCameraFitDone = true
            fitCameraToOrderPins(map)
        }
    }

    private fun fitCameraToOrderPins(map: GoogleMap) {
        val doFit = {
            try {
                val b = com.google.android.gms.maps.model.LatLngBounds.Builder()
                driverMarker?.position?.let { b.include(it) }
                markerToOrder.keys.forEach { b.include(it.position) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 160))
            } catch (_: Exception) {
                val first = markerToOrder.keys.firstOrNull()?.position
                if (first != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(first, 14f))
            }
        }
        try { map.setOnMapLoadedCallback { doFit() } } catch (_: Exception) { doFit() }
    }

    // ── BottomSheet al tocar un pin ───────────────────────────────────────────

    private fun showOrderSheet(serverId: Int, req: TripRequest) {
        orderSheet?.dismiss()
        val sheet = BottomSheetDialog(this, R.style.Theme_Zuppon)
        val b = DialogOrderRequestBinding.inflate(layoutInflater)
        sheet.setContentView(b.root)

        b.tvDlgItems.text       = req.passengerName
        b.tvDlgDestination.text = req.destination
        b.tvDlgFare.text        = formatGs(req.fare)

        b.btnDlgAccept.setOnClickListener {
            sheet.dismiss()
            viewModel.acceptOrder(serverId)
            // Limpiar pins de otros pedidos
            markerToOrder.keys.forEach { it.remove() }
            markerToOrder.clear()
            // Dibujar ruta al destino aceptado
            if (req.hasCoords) {
                lastRouteDraw = 0L
                val origin = driverMarker?.position
                if (origin != null) drawRouteToLatLng(origin, LatLng(req.destLat, req.destLng))
            }
        }

        b.btnDlgReject.setOnClickListener {
            sheet.dismiss()
            viewModel.rejectOrder(serverId)
            // Quitar solo el pin de este pedido
            markerToOrder.entries.find { it.value.first == serverId }?.key?.let {
                it.remove()
                markerToOrder.remove(it)
            }
        }

        orderSheet = sheet
        sheet.show()
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
            val card = layoutInflater.inflate(R.layout.item_order_history, binding.llOrderHistory, false)
            card.findViewById<TextView>(R.id.tv_order_destination).text = "📍 ${record.destination}"
            card.findViewById<TextView>(R.id.tv_order_fare).text        = record.formattedFare()
            card.findViewById<TextView>(R.id.tv_order_time).text        = record.formattedTime()
            val statusTv = card.findViewById<TextView>(R.id.tv_order_status)
            when (record.status) {
                OrderStatus.PENDING    -> { statusTv.text = "🔍 Buscando repartidor"; statusTv.setTextColor(getColor(R.color.zuppon_text_secondary)) }
                OrderStatus.ACCEPTED   -> { statusTv.text = "🛵 Repartidor en camino"; statusTv.setTextColor(getColor(R.color.zuppon_accent)) }
                OrderStatus.PICKED_UP  -> { statusTv.text = "✅ Pedido recogido";      statusTv.setTextColor(getColor(R.color.zuppon_accent)) }
                OrderStatus.DELIVERING -> { statusTv.text = "🍔 En camino";            statusTv.setTextColor(getColor(R.color.zuppon_yellow)) }
                OrderStatus.COMPLETED  -> { statusTv.text = "✅ Entregado";            statusTv.setTextColor(getColor(R.color.zuppon_green)) }
                OrderStatus.CANCELLED  -> { statusTv.text = "❌ Cancelado";            statusTv.setTextColor(getColor(R.color.zuppon_accent)) }
            }
            binding.llOrderHistory.addView(card)
        }
    }

    // ── Estado del panel inferior ─────────────────────────────────────────────

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
                if (viewModel.driverStatus.value == DriverStatus.ONLINE)
                    binding.layoutOnline.visibility = View.VISIBLE
                else
                    binding.layoutOffline.visibility = View.VISIBLE
            }
            is TripState.DriverOnWay -> {
                if (viewModel.driverStatus.value == DriverStatus.ACTIVE_TRIP) {
                    binding.layoutActiveTrip.visibility = View.VISIBLE
                    TripRepository.pendingRequest.value?.let { drawRouteToRequest(it) }
                } else {
                    binding.layoutOnline.visibility = View.VISIBLE
                }
            }
            is TripState.DriverArrived, is TripState.InProgress -> {
                if (viewModel.driverStatus.value == DriverStatus.ACTIVE_TRIP)
                    binding.layoutActiveTrip.visibility = View.VISIBLE
                else
                    binding.layoutOnline.visibility = View.VISIBLE
            }
            is TripState.Completed -> binding.layoutOnline.visibility = View.VISIBLE
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) startDriverTracking()
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
        try { map.setMapStyle(MapStyleOptions(MAP_STYLE)) } catch (_: Exception) { }
        map.isBuildingsEnabled = true
        map.isTrafficEnabled = true
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) startDriverTracking()

        val status = TripRepository.driverStatus.value
        val req    = TripRepository.pendingRequest.value

        if (req != null && status == DriverStatus.ACTIVE_TRIP) {
            drawRouteToRequest(req)
            return
        }
        if (status == DriverStatus.ONLINE && lastKnownPendingOrders.isNotEmpty()) {
            pendingCameraFitDone = false
            updateOrderPinsOnMap(lastKnownPendingOrders)
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private fun startDriverTracking() {
        if (isTracking || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        isTracking = true
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { updateDriverMarker(LatLng(it.latitude, it.longitude)) }
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

    // ── Ruta ──────────────────────────────────────────────────────────────────

    private var lastRouteDraw = 0L

    private fun drawRouteToRequest(req: TripRequest) {
        if (!req.hasCoords) return
        val dest = LatLng(req.destLat, req.destLng)
        val origin = driverMarker?.position
        if (origin != null) {
            drawRouteToLatLng(origin, dest)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) drawRouteToLatLng(LatLng(loc.latitude, loc.longitude), dest)
            }
        }
    }

    private fun drawRouteToLatLng(origin: LatLng, dest: LatLng) {
        val now = System.currentTimeMillis()
        if (now - lastRouteDraw < 15_000) return
        lastRouteDraw = now

        val apiKey = getString(R.string.google_maps_key)
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
