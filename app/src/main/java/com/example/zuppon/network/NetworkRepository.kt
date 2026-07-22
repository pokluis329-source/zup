package com.example.zuppon.network

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.zuppon.model.PaymentMessage
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object NetworkRepository {

    private const val TAG = "ZupponNet"
    private val main = Handler(Looper.getMainLooper())

    var serverOrderId: Int = -1
    var serverDriverId: Int = -1
    var lastPaymentInfo: PaymentInfoDto? = null

    private fun bg(block: () -> Unit) {
        Thread {
            try { block() }
            catch (e: Exception) { Log.w(TAG, "Network error: ${e.message}") }
        }.start()
    }

    private fun PaymentMessageDto.toModel() = PaymentMessage(
        id = id,
        orderId = order_id,
        sender = sender,
        type = type,
        body = body,
        imageUrl = image_url,
        createdAt = created_at
    )

    fun createOrder(
        items: String, destination: String, fare: Double,
        clientName: String = "Cliente",
        destLat: Double = 0.0, destLng: Double = 0.0,
        onSuccess: (OrderDto) -> Unit = {}, onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: run {
            onError("Sin conexión al servidor")
            return
        }
        bg {
            val resp = api.createOrder(
                CreateOrderRequest(items, destination, fare, clientName, destLat, destLng)
            ).execute()
            if (resp.isSuccessful) {
                val order = resp.body()!!
                serverOrderId = order.id
                lastPaymentInfo = order.payment
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

    fun fetchPaymentMessages(
        orderId: Int,
        onSuccess: (List<PaymentMessage>) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: run { onError("Sin conexión"); return }
        bg {
            val resp = api.getPaymentMessages(orderId).execute()
            if (resp.isSuccessful) {
                val list = resp.body()!!.map { it.toModel() }
                main.post { onSuccess(list) }
            } else {
                main.post { onError("HTTP ${resp.code()}") }
            }
        }
    }

    fun sendPaymentMessage(
        orderId: Int,
        body: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val api = ApiClient.api ?: run { onError("Sin conexión"); return }
        bg {
            val resp = api.sendPaymentMessage(orderId, SendMessageRequest(body)).execute()
            if (resp.isSuccessful) main.post { onSuccess() }
            else main.post { onError("HTTP ${resp.code()}") }
        }
    }

    fun uploadReceipt(
        orderId: Int,
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg",
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        bg {
            try {
                val ext = when {
                    mimeType.contains("png") -> "png"
                    mimeType.contains("webp") -> "webp"
                    else -> "jpg"
                }
                val media = mimeType.toMediaTypeOrNull() ?: "image/jpeg".toMediaTypeOrNull()!!
                val fileBody = imageBytes.toRequestBody(media)
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "receipt.$ext", fileBody)
                    .build()

                val url = "${ApiClient.BASE_URL.trimEnd('/')}/api/orders/$orderId/messages/receipt"
                val request = Request.Builder().url(url).post(multipart).build()
                ApiClient.okHttp.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        main.post { onSuccess() }
                    } else {
                        val msg = parseUploadError(raw, resp.code)
                        main.post { onError(msg) }
                    }
                }
            } catch (e: Exception) {
                main.post { onError(e.message ?: "Error al subir") }
            }
        }
    }

    fun uploadReceipt(
        orderId: Int,
        contentResolver: ContentResolver,
        uri: Uri,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        bg {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        main.post { onError("No se pudo leer la imagen") }
                        return@bg
                    }
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                uploadReceipt(orderId, bytes, mime, onSuccess, onError)
            } catch (e: Exception) {
                main.post { onError(e.message ?: "Error al leer imagen") }
            }
        }
    }

    private fun parseUploadError(raw: String, code: Int): String {
        if (raw.trimStart().startsWith("<")) {
            return when (code) {
                404 -> "Ruta de subida no encontrada en el servidor (404)"
                413 -> "Imagen muy pesada para el servidor"
                else -> "Error del servidor ($code). Actualizá el backend en el VPS."
            }
        }
        return raw.take(180).ifBlank { "HTTP $code" }
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
