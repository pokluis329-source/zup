package com.example.zuppon.network

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    var BASE_URL = "http://192.168.50.178:5000/"
    private const val TAG = "ApiClient"

    private val httpClient: OkHttpClient by lazy {
        try {
            OkHttpClient.Builder()
                .apply {
                    // Logging solo en builds debuggeables — en release no pagar el overhead
                    val isDebuggable = (0 != (android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
                        and (try { Class.forName("android.app.ActivityThread")
                            .getMethod("currentApplication").invoke(null)
                            .let { it as android.app.Application }
                            .applicationInfo.flags } catch (_: Exception) { 0 })))
                    if (isDebuggable) {
                        addInterceptor(HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        })
                    }
                }
                .connectTimeout(6, TimeUnit.SECONDS)   // era 8 — LAN local, 6 es suficiente
                .readTimeout(10, TimeUnit.SECONDS)      // era 15
                .writeTimeout(8, TimeUnit.SECONDS)      // antes no había write timeout
                .connectionPool(
                    okhttp3.ConnectionPool(5, 60, TimeUnit.SECONDS)  // pool de 5 conexiones, 60s idle
                )
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "OkHttp init failed: ${e.message}")
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }

    /** null si Retrofit no se pudo inicializar (JARs faltantes) */
    val api: ApiService? by lazy {
        try {
            Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Retrofit init failed — app runs offline: ${e.message}")
            null
        }
    }
}
