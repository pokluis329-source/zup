package com.example.zuppon.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.zuppon.model.DriverInfo
import com.example.zuppon.model.OrderRecord
import com.example.zuppon.model.OrderStatus
import com.example.zuppon.model.TripRequest
import com.example.zuppon.model.TripState
import com.example.zuppon.util.OrderStorage

enum class DriverStatus { OFFLINE, ONLINE, ACTIVE_TRIP }

object TripRepository {

    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    // ── Polling de pedidos para el repartidor ─────────────────────────────────
    private val pollHandler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private val POLL_INTERVAL_MS = 3_000L  // 3s — más rápido para el repartidor
    // IDs rechazados temporalmente: id → timestamp hasta el que se ignoran (60s)
    private val tempRejectedIds = mutableMapOf<Int, Long>()

    fun init(context: Context) {
        appContext = context.applicationContext
        // Cargar historial en background para no bloquear el main thread
        Thread {
            val saved = OrderStorage.loadHistory(context)
            if (saved.isNotEmpty()) {
                main.post { _orderHistory.value = saved }
            }
        }.start()
    }

    // ── Trip state ────────────────────────────────────────────────────────────
    private val _tripState = MutableLiveData<TripState>(TripState.Idle)
    val tripState: LiveData<TripState> = _tripState

    private val _pendingRequest = MutableLiveData<TripRequest?>(null)
    val pendingRequest: LiveData<TripRequest?> = _pendingRequest

    private var currentOrderId: Long = -1L

    // ── Driver state ──────────────────────────────────────────────────────────
    private val _driverStatus = MutableLiveData(DriverStatus.OFFLINE)
    val driverStatus: LiveData<DriverStatus> = _driverStatus

    private val _tripStep = MutableLiveData(0)
    val tripStep: LiveData<Int> = _tripStep

    private val _tripsToday = MutableLiveData(0)
    val tripsToday: LiveData<Int> = _tripsToday

    private val _earningsToday = MutableLiveData(0.0)
    val earningsToday: LiveData<Double> = _earningsToday

    val myCourierInfo = DriverInfo(
        name         = "Repartidor Zuppon 🛵",
        carModel     = "Mi vehículo",
        licensePlate = "ZUP-2026",
        rating       = 5.0,
        etaMinutes   = 5
    )

    private val _orderHistory = MutableLiveData<List<OrderRecord>>(emptyList())
    val orderHistory: LiveData<List<OrderRecord>> = _orderHistory

    // ── Passenger actions ─────────────────────────────────────────────────────

    fun passengerRequestTrip(request: TripRequest) {
        // Todo en el main thread — solo actualizamos LiveData y disparamos red en bg
        _pendingRequest.value = request
        _tripState.value = TripState.SearchingDriver

        val record = OrderRecord(
            items       = request.passengerName,
            destination = request.destination,
            fare        = request.fare,
            status      = OrderStatus.PENDING
        )
        currentOrderId = record.id
        addOrUpdateHistory(record)  // guarda en disco en background

        // Sincronizar con backend sin bloquear
        com.example.zuppon.network.NetworkRepository.createOrder(
            items       = request.passengerName,
            destination = request.destination,
            fare        = request.fare,
            destLat     = request.destLat,
            destLng     = request.destLng,
            onSuccess   = { order ->
                // Guardar el ID del servidor para que cancelOrder funcione
                com.example.zuppon.network.NetworkRepository.serverOrderId = order.id
            }
        )
    }

    fun passengerCancelTrip() {
        com.example.zuppon.network.NetworkRepository.cancelOrder()
        updateCurrentOrderStatus(OrderStatus.CANCELLED)
        _pendingRequest.value = null
        _tripState.value = TripState.Cancelled
        _tripState.value = TripState.Idle
        if (_driverStatus.value == DriverStatus.ACTIVE_TRIP) {
            _driverStatus.value = DriverStatus.ONLINE
            _tripStep.value = 0
        }
    }

    // ── Driver actions ────────────────────────────────────────────────────────

    fun driverGoOnline()  {
        _driverStatus.value = DriverStatus.ONLINE
        startPolling()
    }
    fun driverGoOffline() {
        _driverStatus.value = DriverStatus.OFFLINE
        stopPolling()
    }

    /** Arranca el polling si el driver está ONLINE y no está ya corriendo.
     *  Llamado desde onResume para cubrir el caso de volver a la Activity. */
    fun ensurePolling() {
        if (_driverStatus.value == DriverStatus.ONLINE && !isPolling) {
            startPolling()
        }
    }

    fun driverRejectTrip() {
        // Tomar el ID del pedido actual (currentOrderId es el que seteó el polling)
        val rejectedId = if (currentOrderId != -1L) currentOrderId.toInt()
                         else com.example.zuppon.network.NetworkRepository.serverOrderId
        if (rejectedId != -1) {
            // Bloquear 60s — el driver puede reconsiderar tocando el marker que queda en pantalla
            tempRejectedIds[rejectedId] = System.currentTimeMillis() + 60_000L
        }
        _tripState.value = TripState.Idle
        _pendingRequest.value = null
        _driverStatus.value = DriverStatus.ONLINE
        startPolling()
    }

    fun driverAcceptTrip() {
        stopPolling()   // dejar de buscar más pedidos mientras está activo
        _driverStatus.value = DriverStatus.ACTIVE_TRIP
        _tripStep.value = 0
        _tripState.value = TripState.DriverOnWay(myCourierInfo)
        updateCurrentOrderStatus(OrderStatus.ACCEPTED)
        val orderId = com.example.zuppon.network.NetworkRepository.serverOrderId
        if (orderId != -1) {
            com.example.zuppon.network.NetworkRepository.acceptOrder(
                orderId,
                com.example.zuppon.network.NetworkRepository.serverDriverId,
                myCourierInfo.name,
                myCourierInfo.carModel
            )
        }
    }

    fun driverArrived() {
        _tripStep.value = 1
        _tripState.value = TripState.DriverArrived(myCourierInfo)
        updateCurrentOrderStatus(OrderStatus.PICKED_UP)
        val orderId = com.example.zuppon.network.NetworkRepository.serverOrderId
        if (orderId != -1) com.example.zuppon.network.NetworkRepository.pickedUp(orderId)
    }

    fun driverStartTrip() {
        _tripStep.value = 2
        _tripState.value = TripState.InProgress(myCourierInfo)
        updateCurrentOrderStatus(OrderStatus.DELIVERING)
        val orderId = com.example.zuppon.network.NetworkRepository.serverOrderId
        if (orderId != -1) com.example.zuppon.network.NetworkRepository.delivering(orderId)
    }

    fun driverEndTrip() {
        val fare = _pendingRequest.value?.fare ?: 0.0
        val orderId = com.example.zuppon.network.NetworkRepository.serverOrderId
        if (orderId != -1) com.example.zuppon.network.NetworkRepository.completeOrder(orderId)
        updateCurrentOrderStatus(OrderStatus.COMPLETED)
        _tripsToday.value    = (_tripsToday.value ?: 0) + 1
        _earningsToday.value = (_earningsToday.value ?: 0.0) + fare
        _tripStep.value      = 0
        _driverStatus.value  = DriverStatus.ONLINE
        _tripState.value     = TripState.Completed(fare)
        _pendingRequest.value = null
        currentOrderId       = -1L
        startPolling()  // volver a buscar pedidos
    }

    fun reset() {
        _tripState.value      = TripState.Idle
        _pendingRequest.value = null
    }

    // ── History — escritura en background ────────────────────────────────────

    private fun addOrUpdateHistory(record: OrderRecord) {
        val current = _orderHistory.value.orEmpty().toMutableList()
        val idx = current.indexOfFirst { it.id == record.id }
        if (idx >= 0) current[idx] = record else current.add(0, record)
        _orderHistory.value = current          // main thread — seguro
        val snapshot = current.toList()
        // Guardar en disco en background para no bloquear UI
        Thread {
            appContext?.let { OrderStorage.saveHistory(it, snapshot) }
        }.start()
    }

    private fun updateCurrentOrderStatus(status: OrderStatus) {
        if (currentOrderId == -1L) return
        val current = _orderHistory.value.orEmpty().toMutableList()
        val idx = current.indexOfFirst { it.id == currentOrderId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(status = status)
            _orderHistory.value = current      // main thread — seguro
            val snapshot = current.toList()
            Thread {
                appContext?.let { OrderStorage.saveHistory(it, snapshot) }
            }.start()
        }
    }

    // Compatibilidad con llamadas antiguas
    fun driverAcceptTrip(state: TripState.DriverOnWay) { driverAcceptTrip() }
    fun driverArrived(state: TripState.DriverArrived)   { driverArrived() }
    fun driverStartTrip(state: TripState.InProgress)    { driverStartTrip() }
    fun driverEndTrip(fare: Double)                     { driverEndTrip() }

    // ── Polling de pedidos PENDING ─────────────────────────────────────────────

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        pollHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        isPolling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            fetchPendingOrders()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun fetchPendingOrders() {
        if (_driverStatus.value != DriverStatus.ONLINE) return

        val now = System.currentTimeMillis()
        // Limpiar rechazados expirados para que puedan reaparecer
        tempRejectedIds.entries.removeAll { it.value < now }

        com.example.zuppon.network.NetworkRepository.fetchAllOrders(
            onSuccess = { orders ->
                // Si ya hay un pedido mostrándose, no interrumpir
                if (_tripState.value == TripState.SearchingDriver
                    && _pendingRequest.value != null) return@fetchAllOrders

                val pending = orders
                    .filter { it.status == "PENDING" }
                    .firstOrNull { (tempRejectedIds[it.id] ?: 0L) < now }
                    ?: return@fetchAllOrders

                // Guardar el ID del servidor para poder aceptarlo/cancelarlo
                com.example.zuppon.network.NetworkRepository.serverOrderId = pending.id

                val request = TripRequest(
                    passengerName = pending.items.ifBlank { pending.client_name },
                    destination   = pending.destination,
                    fare          = pending.fare,
                    destLat       = pending.dest_lat,
                    destLng       = pending.dest_lng
                )
                _pendingRequest.value = request

                val record = OrderRecord(
                    id          = pending.id.toLong(),
                    items       = pending.items.ifBlank { pending.client_name },
                    destination = pending.destination,
                    fare        = pending.fare,
                    status      = OrderStatus.PENDING
                )
                currentOrderId = record.id
                addOrUpdateHistory(record)

                _tripState.value = TripState.SearchingDriver
            },
            onError = { /* ignorar errores de red silenciosamente */ }
        )
    }
}
