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

    // Lista de TODOS los pedidos PENDING visibles para el repartidor
    private val _pendingOrders = MutableLiveData<List<TripRequest>>(emptyList())
    val pendingOrders: LiveData<List<TripRequest>> = _pendingOrders

    // Mapa server_id → TripRequest para aceptar un pedido específico de la lista
    private val pendingOrderMap = mutableMapOf<Int, TripRequest>()

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
        _pendingOrders.value = emptyList()
        pendingOrderMap.clear()
    }

    /** Arranca el polling si el driver está ONLINE y no está ya corriendo.
     *  Llamado desde onResume para cubrir el caso de volver a la Activity. */
    fun ensurePolling() {
        if (_driverStatus.value == DriverStatus.ONLINE && !isPolling) {
            startPolling()
        }
    }

    fun driverRejectOrder(serverId: Int) {
        tempRejectedIds[serverId] = System.currentTimeMillis() + 60_000L
        pendingOrderMap.remove(serverId)
        _pendingOrders.value = pendingOrderMap.values.toList()
    }

    fun driverRejectTrip() {
        val rejectedId = com.example.zuppon.network.NetworkRepository.serverOrderId
        if (rejectedId != -1) {
            tempRejectedIds[rejectedId] = System.currentTimeMillis() + 60_000L
            pendingOrderMap.remove(rejectedId)
            _pendingOrders.value = pendingOrderMap.values.toList()
        }
        _tripState.value = TripState.Idle
        _pendingRequest.value = null
        _driverStatus.value = DriverStatus.ONLINE
        startPolling()
    }

    /** Acepta un pedido específico de la lista por su server ID */
    fun driverAcceptOrder(serverId: Int) {
        val request = pendingOrderMap[serverId] ?: return
        stopPolling()
        _pendingOrders.value = emptyList()
        pendingOrderMap.clear()
        _driverStatus.value = DriverStatus.ACTIVE_TRIP
        _tripStep.value = 0
        _tripState.value = TripState.DriverOnWay(myCourierInfo)
        _pendingRequest.value = request

        com.example.zuppon.network.NetworkRepository.serverOrderId = serverId
        val record = OrderRecord(
            id          = serverId.toLong(),
            items       = request.passengerName,
            destination = request.destination,
            fare        = request.fare,
            status      = OrderStatus.ACCEPTED
        )
        currentOrderId = record.id
        addOrUpdateHistory(record)

        com.example.zuppon.network.NetworkRepository.acceptOrder(
            serverId,
            com.example.zuppon.network.NetworkRepository.serverDriverId,
            myCourierInfo.name,
            myCourierInfo.carModel
        )
    }

    fun driverAcceptTrip() {
        stopPolling()
        _pendingOrders.value = emptyList()
        pendingOrderMap.clear()
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

    /** Devuelve el server ID de un TripRequest buscando en pendingOrderMap. -1 si no se encuentra. */
    fun getServerIdForRequest(request: TripRequest): Int =
        pendingOrderMap.entries.firstOrNull { it.value == request }?.key ?: -1

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
        tempRejectedIds.entries.removeAll { it.value < now }

        com.example.zuppon.network.NetworkRepository.fetchAllOrders(
            onSuccess = { orders ->
                if (_driverStatus.value != DriverStatus.ONLINE) return@fetchAllOrders

                // Filtrar PENDING y no rechazados temporalmente
                val pendingDtos = orders.filter {
                    it.status == "PENDING" && (tempRejectedIds[it.id] ?: 0L) < now
                }

                // Actualizar el mapa — agrega nuevos, elimina los que ya no son PENDING
                val activeIds = pendingDtos.map { it.id }.toSet()
                pendingOrderMap.keys.retainAll(activeIds)

                pendingDtos.forEach { dto ->
                    // Solo agregar si no estaba ya (evitar sobrescribir innecesariamente)
                    if (!pendingOrderMap.containsKey(dto.id)) {
                        pendingOrderMap[dto.id] = TripRequest(
                            passengerName = dto.items.ifBlank { dto.client_name },
                            destination   = dto.destination,
                            fare          = dto.fare,
                            destLat       = dto.dest_lat,
                            destLng       = dto.dest_lng
                        )
                        // Agregar al historial para el driver
                        addOrUpdateHistory(OrderRecord(
                            id          = dto.id.toLong(),
                            items       = dto.items.ifBlank { dto.client_name },
                            destination = dto.destination,
                            fare        = dto.fare,
                            status      = OrderStatus.PENDING
                        ))
                    }
                }

                _pendingOrders.value = pendingOrderMap.values.toList()
            },
            onError = { /* ignorar errores de red silenciosamente */ }
        )
    }
}
