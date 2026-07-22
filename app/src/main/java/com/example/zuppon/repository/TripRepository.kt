package com.example.zuppon.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.zuppon.model.ActiveOrder
import com.example.zuppon.model.ActiveOrderPhase
import com.example.zuppon.model.DriverInfo
import com.example.zuppon.model.OrderRecord
import com.example.zuppon.model.OrderStatus
import com.example.zuppon.model.TripRequest
import com.example.zuppon.model.TripState
import com.example.zuppon.network.OrderDto
import com.example.zuppon.util.ActiveOrderStorage
import com.example.zuppon.util.OrderStorage

enum class DriverStatus { OFFLINE, ONLINE, ACTIVE_TRIP }

object TripRepository {

    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    // ── Polling de pedidos para el repartidor ─────────────────────────────────
    private val pollHandler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private val POLL_INTERVAL_MS = 3_000L

    // ── Polling del pedido activo del pasajero ────────────────────────────────
    private val passengerPollHandler = Handler(Looper.getMainLooper())
    private var isPassengerPolling = false
    private val PASSENGER_POLL_MS = 4_000L

    // Lista de TODOS los pedidos PENDING visibles para el repartidor
    private val _pendingOrders = MutableLiveData<List<TripRequest>>(emptyList())
    val pendingOrders: LiveData<List<TripRequest>> = _pendingOrders

    // Mapa server_id → TripRequest para aceptar un pedido específico de la lista
    private val pendingOrderMap = mutableMapOf<Int, TripRequest>()

    fun init(context: Context) {
        appContext = context.applicationContext
        Thread {
            val savedOrder = ActiveOrderStorage.load(context)
            val savedHistory = OrderStorage.loadHistory(context)
            main.post {
                if (savedHistory.isNotEmpty()) _orderHistory.value = savedHistory
                if (savedOrder != null) restoreActiveOrder(savedOrder)
            }
        }.start()
    }

    private fun restoreActiveOrder(order: ActiveOrder) {
        _activeOrder.value = order
        com.example.zuppon.network.NetworkRepository.serverOrderId = order.serverOrderId
        currentOrderId = order.serverOrderId.toLong()
        applyTripStateFromPhase(order.phase, order.driverName, order.driverVehicle)
        startPassengerPolling()
        syncActiveOrderFromServer()
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

    private val _activeOrder = MutableLiveData<ActiveOrder?>(null)
    val activeOrder: LiveData<ActiveOrder?> = _activeOrder

    private val _userMessage = MutableLiveData<String?>(null)
    val userMessage: LiveData<String?> = _userMessage

    fun consumeUserMessage() { _userMessage.value = null }

    // ── Passenger actions ─────────────────────────────────────────────────────

    fun passengerRequestTrip(
        request: TripRequest,
        buyerPhone: String = "",
        buyerName: String = "Cliente",
        onOrderCreated: (OrderDto) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        _pendingRequest.value = request
        _tripState.value = TripState.AwaitingPayment

        val record = OrderRecord(
            items       = request.passengerName,
            destination = request.destination,
            fare        = request.fare,
            status      = OrderStatus.PENDING
        )
        currentOrderId = record.id
        addOrUpdateHistory(record)

        com.example.zuppon.network.NetworkRepository.createOrder(
            items       = request.passengerName,
            destination = request.destination,
            fare        = request.fare,
            clientName  = buyerName,
            destLat     = request.destLat,
            destLng     = request.destLng,
            onSuccess   = { order ->
                com.example.zuppon.network.NetworkRepository.serverOrderId = order.id
                currentOrderId = order.id.toLong()
                val active = ActiveOrder(
                    serverOrderId = order.id,
                    items         = request.passengerName,
                    destination   = request.destination,
                    fare          = request.fare,
                    amountGs      = order.amount_gs.takeIf { it > 0 } ?: order.fare_gs,
                    alias         = order.payment?.alias ?: "zup.cacupe",
                    cedula        = order.payment?.cedula ?: "6208713",
                    phase         = ActiveOrderPhase.AWAITING_PAYMENT
                )
                saveActiveOrder(active)
                startPassengerPolling()
                onOrderCreated(order)
            },
            onError = onError
        )
    }

    fun onPaymentApproved() {
        updateActiveOrderPhase(ActiveOrderPhase.SEARCHING_DRIVER)
        _tripState.value = TripState.SearchingDriver
        _userMessage.value = "Pago confirmado 🎉 Buscando repartidor…"
    }

    fun onReceiptUploaded() {
        updateActiveOrderPhase(ActiveOrderPhase.PENDING_REVIEW)
    }

    fun passengerCheckPayment(
        onPaid: () -> Unit = {},
        onPending: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val orderId = _activeOrder.value?.serverOrderId
            ?: com.example.zuppon.network.NetworkRepository.serverOrderId
        if (orderId == -1) {
            onError("Sin pedido activo")
            return
        }
        com.example.zuppon.network.NetworkRepository.fetchPaymentStatus(
            orderId,
            onSuccess = { status ->
                if (status.paid) {
                    onPaymentApproved()
                    onPaid()
                } else {
                    val phase = when (status.payment_status) {
                        "PENDING_REVIEW" -> ActiveOrderPhase.PENDING_REVIEW
                        else -> ActiveOrderPhase.AWAITING_PAYMENT
                    }
                    updateActiveOrderPhase(phase)
                    onPending()
                }
            },
            onError = onError
        )
    }

    fun syncActiveOrderFromServer() {
        val orderId = _activeOrder.value?.serverOrderId ?: return
        com.example.zuppon.network.NetworkRepository.fetchOrder(
            orderId,
            onSuccess = { applyServerOrder(it) },
            onError = { }
        )
    }

    fun ensurePassengerPolling() {
        if (_activeOrder.value != null && !isPassengerPolling) startPassengerPolling()
    }

    fun passengerCancelTrip() {
        val orderId = _activeOrder.value?.serverOrderId
            ?: com.example.zuppon.network.NetworkRepository.serverOrderId
        com.example.zuppon.network.NetworkRepository.cancelOrder(orderId)
        updateCurrentOrderStatus(OrderStatus.CANCELLED)
        clearActiveOrder()
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
        android.util.Log.d("ZUPPON_REPO", "🟢 driverGoOnline() - iniciando polling")
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
        // Solo cierra el diálogo — el pin sigue en el mapa por si quiere aceptarlo después
    }

    fun driverRejectTrip() {
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
        _tripState.value     = TripState.Idle
        _pendingRequest.value = null
        currentOrderId       = -1L
        com.example.zuppon.network.NetworkRepository.serverOrderId = -1
        startPolling()  // volver a buscar pedidos
    }

    fun reset() {
        _tripState.value      = TripState.Idle
        _pendingRequest.value = null
    }

    fun clearActiveOrder() {
        stopPassengerPolling()
        appContext?.let { ActiveOrderStorage.clear(it) }
        _activeOrder.value = null
        com.example.zuppon.network.NetworkRepository.serverOrderId = -1
        currentOrderId = -1L
    }

    // ── Pedido activo del pasajero ────────────────────────────────────────────

    private fun saveActiveOrder(order: ActiveOrder) {
        _activeOrder.value = order
        appContext?.let { ActiveOrderStorage.save(it, order) }
    }

    private fun updateActiveOrderPhase(phase: ActiveOrderPhase) {
        val current = _activeOrder.value ?: return
        val prev = current.phase
        val updated = current.copy(phase = phase)
        saveActiveOrder(updated)
        applyTripStateFromPhase(phase, updated.driverName, updated.driverVehicle)
        if (prev != ActiveOrderPhase.SEARCHING_DRIVER &&
            phase == ActiveOrderPhase.SEARCHING_DRIVER &&
            _userMessage.value == null
        ) {
            _userMessage.value = "Pago confirmado 🎉 Buscando repartidor…"
        }
    }

    private fun applyServerOrder(dto: OrderDto) {
        if (dto.status == "CANCELLED") {
            clearActiveOrder()
            _tripState.value = TripState.Idle
            return
        }

        val phase = phaseFromDto(dto) ?: return
        val current = _activeOrder.value
        val prevPhase = current?.phase

        val updated = (current ?: ActiveOrder(
            serverOrderId = dto.id,
            items         = dto.items.ifBlank { dto.client_name },
            destination   = dto.destination,
            fare          = dto.fare,
            amountGs      = dto.amount_gs.takeIf { it > 0 } ?: dto.fare_gs,
            alias         = dto.payment?.alias ?: "zup.cacupe",
            cedula        = dto.payment?.cedula ?: "6208713",
            phase         = phase
        )).copy(
            items         = dto.items.ifBlank { dto.client_name },
            destination   = dto.destination,
            fare          = dto.fare,
            amountGs      = dto.amount_gs.takeIf { it > 0 } ?: dto.fare_gs,
            phase         = phase,
            driverName    = dto.driver_name,
            driverVehicle = dto.driver_vehicle
        )

        com.example.zuppon.network.NetworkRepository.serverOrderId = dto.id
        currentOrderId = dto.id.toLong()
        saveActiveOrder(updated)
        applyTripStateFromPhase(phase, dto.driver_name, dto.driver_vehicle)
        syncHistoryFromDto(dto, phase)

        if ((prevPhase == ActiveOrderPhase.AWAITING_PAYMENT ||
                prevPhase == ActiveOrderPhase.PENDING_REVIEW) &&
            phase == ActiveOrderPhase.SEARCHING_DRIVER
        ) {
            _userMessage.value = "Pago confirmado 🎉 Buscando repartidor…"
        }
        if (phase == ActiveOrderPhase.COMPLETED) {
            _userMessage.value = "¡Pedido entregado! 🎉"
        }
    }

    private fun phaseFromDto(dto: OrderDto): ActiveOrderPhase? = when {
        dto.status == "CANCELLED" -> null
        dto.status == "COMPLETED" -> ActiveOrderPhase.COMPLETED
        dto.payment_status == "AWAITING_PAYMENT" -> ActiveOrderPhase.AWAITING_PAYMENT
        dto.payment_status == "PENDING_REVIEW"   -> ActiveOrderPhase.PENDING_REVIEW
        dto.status == "DELIVERING" -> ActiveOrderPhase.DELIVERING
        dto.status == "PICKED_UP"  -> ActiveOrderPhase.PICKED_UP
        dto.status == "ACCEPTED"   -> ActiveOrderPhase.DRIVER_ASSIGNED
        dto.payment_status == "PAID" -> ActiveOrderPhase.SEARCHING_DRIVER
        else -> ActiveOrderPhase.AWAITING_PAYMENT
    }

    private fun applyTripStateFromPhase(
        phase: ActiveOrderPhase,
        driverName: String?,
        driverVehicle: String?
    ) {
        val driver = if (!driverName.isNullOrBlank()) {
            DriverInfo(
                name         = driverName,
                carModel     = driverVehicle ?: "Vehículo",
                licensePlate = "ZUP",
                rating       = 5.0,
                etaMinutes   = 8
            )
        } else null

        _tripState.value = when (phase) {
            ActiveOrderPhase.AWAITING_PAYMENT,
            ActiveOrderPhase.PENDING_REVIEW   -> TripState.AwaitingPayment
            ActiveOrderPhase.SEARCHING_DRIVER -> TripState.SearchingDriver
            ActiveOrderPhase.DRIVER_ASSIGNED  -> TripState.DriverOnWay(
                driver ?: DriverInfo("Repartidor", "Vehículo", "ZUP", 5.0, 8)
            )
            ActiveOrderPhase.PICKED_UP        -> TripState.DriverArrived(
                driver ?: DriverInfo("Repartidor", "Vehículo", "ZUP", 5.0, 5)
            )
            ActiveOrderPhase.DELIVERING       -> TripState.InProgress(
                driver ?: DriverInfo("Repartidor", "Vehículo", "ZUP", 5.0, 5)
            )
            ActiveOrderPhase.COMPLETED        -> TripState.Completed(
                _activeOrder.value?.fare ?: 0.0
            )
        }
    }

    private fun syncHistoryFromDto(dto: OrderDto, phase: ActiveOrderPhase) {
        val status = when (phase) {
            ActiveOrderPhase.COMPLETED        -> OrderStatus.COMPLETED
            ActiveOrderPhase.DELIVERING       -> OrderStatus.DELIVERING
            ActiveOrderPhase.PICKED_UP        -> OrderStatus.PICKED_UP
            ActiveOrderPhase.DRIVER_ASSIGNED  -> OrderStatus.ACCEPTED
            else -> OrderStatus.PENDING
        }
        addOrUpdateHistory(OrderRecord(
            id          = dto.id.toLong(),
            items       = dto.items.ifBlank { dto.client_name },
            destination = dto.destination,
            fare        = dto.fare,
            status      = status
        ))
    }

    private fun startPassengerPolling() {
        if (isPassengerPolling || _activeOrder.value == null) return
        isPassengerPolling = true
        passengerPollHandler.post(passengerPollRunnable)
    }

    private fun stopPassengerPolling() {
        isPassengerPolling = false
        passengerPollHandler.removeCallbacks(passengerPollRunnable)
    }

    private val passengerPollRunnable = object : Runnable {
        override fun run() {
            if (!isPassengerPolling) return
            val order = _activeOrder.value
            if (order == null || order.phase == ActiveOrderPhase.COMPLETED) {
                stopPassengerPolling()
                return
            }
            com.example.zuppon.network.NetworkRepository.fetchOrder(
                order.serverOrderId,
                onSuccess = { dto ->
                    applyServerOrder(dto)
                    if (isPassengerPolling && _activeOrder.value?.phase != ActiveOrderPhase.COMPLETED) {
                        passengerPollHandler.postDelayed(this, PASSENGER_POLL_MS)
                    } else {
                        stopPassengerPolling()
                    }
                },
                onError = {
                    if (isPassengerPolling) {
                        passengerPollHandler.postDelayed(this, PASSENGER_POLL_MS)
                    }
                }
            )
        }
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
        android.util.Log.d("ZUPPON_REPO", "🔄 fetchPendingOrders() - iniciando fetch")
        if (_driverStatus.value != DriverStatus.ONLINE) {
            android.util.Log.d("ZUPPON_REPO", "⚠️ Driver no está ONLINE, abortando fetch")
            return
        }

        com.example.zuppon.network.NetworkRepository.fetchAllOrders(
            onSuccess = { orders ->
                android.util.Log.d("ZUPPON_REPO", "✅ fetchAllOrders recibió ${orders.size} pedidos del servidor")
                if (_driverStatus.value != DriverStatus.ONLINE) {
                    android.util.Log.d("ZUPPON_REPO", "⚠️ Driver cambió de estado, ignorando respuesta")
                    return@fetchAllOrders
                }

                val pendingDtos = orders.filter {
                    it.status == "PENDING" && it.payment_status == "PAID"
                }
                android.util.Log.d("ZUPPON_REPO", "📋 Pedidos PENDING filtrados: ${pendingDtos.size}")

                pendingDtos.forEachIndexed { i, dto ->
                    android.util.Log.d("ZUPPON_REPO", "  [$i] id=${dto.id}, items='${dto.items}', dest='${dto.destination}', lat=${dto.dest_lat}, lng=${dto.dest_lng}")
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

                android.util.Log.d("ZUPPON_REPO", "📦 pendingOrderMap.size = ${pendingOrderMap.size}")
                _pendingOrders.value = pendingOrderMap.values.toList()
                android.util.Log.d("ZUPPON_REPO", "📤 _pendingOrders.value actualizado con ${_pendingOrders.value?.size} pedidos")
            },
            onError = { 
                android.util.Log.e("ZUPPON_REPO", "❌ Error fetching orders: $it")
            }
        )
    }
}
