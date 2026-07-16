package com.example.zuppon.model

/**
 * Representa todos los estados posibles en el ciclo de vida de un pedido de comida.
 */
sealed class TripState {
    /** Sin pedido activo. */
    object Idle : TripState()

    /** Cliente hizo un pedido; buscando repartidor. */
    object SearchingDriver : TripState()

    /** Un repartidor aceptó el pedido y está yendo al restaurante. */
    data class DriverOnWay(val driver: DriverInfo) : TripState()

    /** Repartidor llegó al restaurante / recogió el pedido. */
    data class DriverArrived(val driver: DriverInfo) : TripState()

    /** Pedido en camino al cliente. */
    data class InProgress(val driver: DriverInfo) : TripState()

    /** Pedido entregado exitosamente. */
    data class Completed(val fare: Double) : TripState()

    /** Pedido cancelado. */
    object Cancelled : TripState()
}

data class DriverInfo(
    val name: String,
    /** Vehículo o método de transporte del repartidor */
    val carModel: String,
    val licensePlate: String,
    val rating: Double,
    val etaMinutes: Int
)

data class TripRequest(
    val passengerName: String,
    /** Nombre del restaurante o lo que pidió */
    val destination: String,
    val fare: Double,
    /** Coordenadas exactas del punto de entrega — evita re-geocodificación */
    val destLat: Double = 0.0,
    val destLng: Double = 0.0
) {
    /** true si las coordenadas fueron provistas y son válidas */
    val hasCoords: Boolean get() = destLat != 0.0 && destLng != 0.0
}
