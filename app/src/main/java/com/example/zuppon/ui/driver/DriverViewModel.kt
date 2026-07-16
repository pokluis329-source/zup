package com.example.zuppon.ui.driver

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.zuppon.repository.DriverStatus
import com.example.zuppon.repository.TripRepository

class DriverViewModel : ViewModel() {

    // Todo viene del Repository singleton — persiste al salir y volver
    val tripState     = TripRepository.tripState
    val pendingRequest = TripRepository.pendingRequest
    val driverStatus  = TripRepository.driverStatus
    val tripStep      = TripRepository.tripStep
    val tripsToday    = TripRepository.tripsToday
    val earningsToday = TripRepository.earningsToday

    fun goOnline()  = TripRepository.driverGoOnline()
    fun goOffline() = TripRepository.driverGoOffline()

    fun acceptTrip()  = TripRepository.driverAcceptTrip()
    fun rejectTrip()  = TripRepository.driverRejectTrip()

    fun advanceTripStep() {
        when (TripRepository.tripStep.value ?: 0) {
            0 -> TripRepository.driverArrived()
            1 -> TripRepository.driverStartTrip()
            2 -> TripRepository.driverEndTrip()
        }
    }
}
