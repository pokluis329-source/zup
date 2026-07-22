package com.example.zuppon.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    const val BASE_URL = "https://institutocaacupepy.es/"
    private const val TAG = "ApiClient"

    private val httpClient: OkHttpClient by lazy {
        try {
            OkHttpClient.Builder()
                .apply {
                    val isDebuggable = (0 != (android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
                        and (try {
                            Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication").invoke(null)
                                .let { it as android.app.Application }
                                .applicationInfo.flags
                        } catch (_: Exception) { 0 })))
                    if (isDebuggable) {
                        addInterceptor(HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BODY
                        })
                    }
                }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(5, 60, TimeUnit.SECONDS))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "OkHttp init failed: ${e.message}")
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
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
                .also { Log.d(TAG, "Retrofit inicializado → $BASE_URL") }
        } catch (e: Exception) {
            Log.w(TAG, "Retrofit init failed — app runs offline: ${e.message}")
            null
        }
    }
}
