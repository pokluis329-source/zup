package com.example.zuppon

import android.app.Application
import com.example.zuppon.repository.TripRepository

class ZupponApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Inicializar el repositorio con contexto para persistencia
        TripRepository.init(this)
    }
}
