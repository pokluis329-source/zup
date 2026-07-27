package com.example.zuppon

import android.app.Application
import com.example.zuppon.network.ApiClient
import com.example.zuppon.repository.TripRepository
import com.example.zuppon.util.UserSession

class ZupponApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UserSession.init(this)
        ApiClient.authTokenProvider = { UserSession.getToken() }
        TripRepository.init(this)
    }
}
