package com.example.zuppon.network

import android.os.Handler
import android.os.Looper
import android.util.Log

object NetworkRepository {

    private const val TAG = "ZupponNet"
    private val main = Handler(Looper.getMainLooper())

    var serverOrderId: Int = -1
    var serverDriverId: Int = -1
    var lastCheckoutUrl: String? = null

    // Helper: ejecuta en background con try-catch completo
    private fun bg(block: () -> Unit) {
        Thread {
            try { block() }
            catch (e: Exception) { Log.w(TAG, "Network error (offline): ${e.message}") }
        }.start()
    }

    // ── Pedidos ───────────────────────────────────────────────────────────────

    fun createCheckout(
        items: String, destination: String, fare: Double,
        clientName: String = "Cliente",
        destLat: Double = 0.0, destLng: Double = 0.0,
        buyerEmail: String, buyerPhone: String = "", buyerDocument: String = "0000000",
        onSuccess: (OrderDto) -> Unit = {}, onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: run {
            onError("Sin conexión al servidor")
            return
        }
        bg {
            val resp = api.checkoutOrder(
                CheckoutOrderRequest(
                    items = items,
                    destination = destination,
                    fare = fare,
                    client_name = clientName,
                    dest_lat = destLat,
                    dest_lng = destLng,
                    buyer_email = buyerEmail,
                    buyer_phone = buyerPhone,
                    buyer_document = buyerDocument
                )
            ).execute()
            if (resp.isSuccessful) {
                val order = resp.body()!!
                serverOrderId = order.id
                lastCheckoutUrl = order.checkout_url
                main.post { onSuccess(order) }
            } else {
                val msg = resp.errorBody()?.string()?.take(200) ?: "HTTP ${resp.code()}"
                main.post { onError(msg) }
            }
        }
    }

    fun fetchPaymentStatus(
        orderId: Int,
        onSuccess: (PaymentStatusDto) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: run { onError("Sin conexión"); return }
        bg {
            val resp = api.getPaymentStatus(orderId).execute()
            if (resp.isSuccessful) main.post { onSuccess(resp.body()!!) }
            else main.post { onError("HTTP ${resp.code()}") }
        }
    }

    fun createOrder(
        items: String, destination: String, fare: Double,
        clientName: String = "Cliente",
        destLat: Double = 0.0, destLng: Double = 0.0,
        onSuccess: (OrderDto) -> Unit = {}, onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: run {
            Log.w(TAG, "createOrder skipped — no API client")
            return
        }
        bg {
            val resp = api.createOrder(
                CreateOrderRequest(items, destination, fare, clientName, destLat, destLng)
            ).execute()
            if (resp.isSuccessful) {
                val order = resp.body()!!
                serverOrderId = order.id
                main.post { onSuccess(order) }
            } else {
                main.post { onError("HTTP ${resp.code()}") }
            }
        }
    }

    fun cancelOrder(onDone: () -> Unit = {}) {
        val id = serverOrderId
        if (id == -1) { onDone(); return }
        val api = ApiClient.api ?: run { serverOrderId = -1; onDone(); return }
        bg {
            api.cancelOrder(id).execute()
            serverOrderId = -1
            main.post { onDone() }
        }
    }

    fun acceptOrder(
        orderId: Int, driverId: Int, driverName: String, driverVehicle: String,
        onSuccess: (OrderDto) -> Unit = {}, onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: return
        bg {
            val resp = api.acceptOrder(
                orderId, AcceptOrderRequest(driverId, driverName, driverVehicle)
            ).execute()
            if (resp.isSuccessful) main.post { onSuccess(resp.body()!!) }
            else main.post { onError("HTTP ${resp.code()}") }
        }
    }

    fun pickedUp(orderId: Int, onDone: () -> Unit = {}) {
        val api = ApiClient.api ?: run { onDone(); return }
        bg { api.pickedUp(orderId).execute(); main.post { onDone() } }
    }

    fun delivering(orderId: Int, onDone: () -> Unit = {}) {
        val api = ApiClient.api ?: run { onDone(); return }
        bg { api.delivering(orderId).execute(); main.post { onDone() } }
    }

    fun completeOrder(orderId: Int, onDone: () -> Unit = {}) {
        val api = ApiClient.api ?: run { serverOrderId = -1; onDone(); return }
        bg {
            api.completeOrder(orderId).execute()
            serverOrderId = -1
            main.post { onDone() }
        }
    }

    fun fetchAllOrders(
        onSuccess: (List<OrderDto>) -> Unit, onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: run { onError("Sin conexión al servidor"); return }
        bg {
            val resp = api.getOrders().execute()
            if (resp.isSuccessful) main.post { onSuccess(resp.body()!!) }
            else main.post { onError("HTTP ${resp.code()}") }
        }
    }

    fun registerDriver(
        deviceId: String, name: String, vehicle: String, plate: String,
        isOnline: Boolean, lat: Double? = null, lng: Double? = null,
        onSuccess: (DriverDto) -> Unit = {}, onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: return
        bg {
            val resp = api.registerDriver(
                DriverRegisterRequest(deviceId, name, vehicle, plate, isOnline, lat, lng)
            ).execute()
            if (resp.isSuccessful) {
                val driver = resp.body()!!
                serverDriverId = driver.id
                main.post { onSuccess(driver) }
            } else main.post { onError("HTTP ${resp.code()}") }
        }
    }

    fun updateDriverLocation(lat: Double, lng: Double) {
        val id = serverDriverId
        if (id == -1) return
        val api = ApiClient.api ?: return
        bg { api.updateLocation(id, LocationRequest(lat, lng)).execute() }
    }

    fun fetchMenu(
        onSuccess: (List<MenuItemDto>) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: run { onError("Sin conexión"); return }
        bg {
            val resp = api.getMenu().execute()
            if (resp.isSuccessful) main.post { onSuccess(resp.body()!!) }
            else main.post { onError("HTTP ${resp.code()}") }
        }
    }
}
