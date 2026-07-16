package com.example.zuppon.ui.driver

import androidx.lifecycle.ViewModel
import com.example.zuppon.repository.TripRepository

class DriverViewModel : ViewModel() {

    // Todo viene del Repository singleton — persiste al salir y volver
    val tripState      = TripRepository.tripState
    val pendingRequest = TripRepository.pendingRequest
    val pendingOrders  = TripRepository.pendingOrders   // lista completa de PENDING
    val driverStatus   = TripRepository.driverStatus
    val tripStep       = TripRepository.tripStep
    val tripsToday     = TripRepository.tripsToday
    val earningsToday  = TripRepository.earningsToday

    fun goOnline()  = TripRepository.driverGoOnline()
    fun goOffline() = TripRepository.driverGoOffline()

    /** Acepta un pedido específico de la lista por su server ID */
    fun acceptOrder(serverId: Int) = TripRepository.driverAcceptOrder(serverId)

    /** Rechaza (oculta 60s) un pedido específico de la lista */
    fun rejectOrder(serverId: Int) = TripRepository.driverRejectOrder(serverId)

    fun acceptTrip() = TripRepository.driverAcceptTrip()
    fun rejectTrip() = TripRepository.driverRejectTrip()

    fun advanceTripStep() {
        when (TripRepository.tripStep.value ?: 0) {
            0 -> TripRepository.driverArrived()
            1 -> TripRepository.driverStartTrip()
            2 -> TripRepository.driverEndTrip()
        }
    }
}
