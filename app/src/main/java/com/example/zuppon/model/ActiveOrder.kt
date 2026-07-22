package com.example.zuppon.model

enum class ActiveOrderPhase {
    AWAITING_PAYMENT,
    PENDING_REVIEW,
    SEARCHING_DRIVER,
    DRIVER_ASSIGNED,
    PICKED_UP,
    DELIVERING,
    COMPLETED
}

data class ActiveOrder(
    val serverOrderId: Int,
    val items: String,
    val destination: String,
    val fare: Double,
    val amountGs: Int,
    val alias: String,
    val cedula: String,
    val phase: ActiveOrderPhase,
    val driverName: String? = null,
    val driverVehicle: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun statusLabel(): String = when (phase) {
        ActiveOrderPhase.AWAITING_PAYMENT -> "💸 Transferí y enviá comprobante"
        ActiveOrderPhase.PENDING_REVIEW   -> "📸 Comprobante enviado · verificando"
        ActiveOrderPhase.SEARCHING_DRIVER -> "⚡ Pago ok · buscando repartidor"
        ActiveOrderPhase.DRIVER_ASSIGNED  -> "🛵 Repartidor en camino"
        ActiveOrderPhase.PICKED_UP        -> "✅ Pedido recogido"
        ActiveOrderPhase.DELIVERING       -> "🍔 En camino a vos"
        ActiveOrderPhase.COMPLETED        -> "🎉 Entregado"
    }

    fun formattedAmount(): String =
        "Gs %,d".format(amountGs.toLong()).replace(',', '.')
}
