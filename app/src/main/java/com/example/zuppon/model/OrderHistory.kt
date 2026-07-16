package com.example.zuppon.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OrderRecord(
    val id: Long = System.currentTimeMillis(),
    val items: String,
    val destination: String,
    val fare: Double,
    val status: OrderStatus,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun formattedTime(): String =
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun formattedFare(): String =
        "Gs %,d".format((fare * 7300).toLong()).replace(',', '.')
}

enum class OrderStatus {
    PENDING,     // buscando repartidor
    ACCEPTED,    // repartidor en camino
    PICKED_UP,   // pedido recogido
    DELIVERING,  // en camino al cliente
    COMPLETED,   // entregado
    CANCELLED    // cancelado
}
