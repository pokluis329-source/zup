package com.example.zuppon.network

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class OrderDto(
    val id: Int = 0,
    val client_name: String = "",
    val items: String = "",
    val destination: String = "",
    val fare: Double = 0.0,
    val fare_gs: Int = 0,
    val status: String = "PENDING",
    val driver_id: Int? = null,
    val driver_name: String? = null,
    val driver_vehicle: String? = null,
    val created_at: String? = null,
    val accepted_at: String? = null,
    val completed_at: String? = null,
    val dest_lat: Double = 0.0,
    val dest_lng: Double = 0.0
)

data class CreateOrderRequest(
    val items: String,
    val destination: String,
    val fare: Double,
    val client_name: String = "Cliente",
    val dest_lat: Double = 0.0,
    val dest_lng: Double = 0.0
)

data class AcceptOrderRequest(
    val driver_id: Int,
    val driver_name: String,
    val driver_vehicle: String
)

data class DriverRegisterRequest(
    val device_id: String,
    val name: String,
    val vehicle: String,
    val plate: String,
    val is_online: Boolean,
    val lat: Double? = null,
    val lng: Double? = null
)

data class LocationRequest(
    val lat: Double,
    val lng: Double
)

data class DriverDto(
    val id: Int = 0,
    val device_id: String = "",
    val name: String = "",
    val vehicle: String = "",
    val plate: String = "",
    val rating: Double = 5.0,
    val is_online: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null
)

data class MenuItemDto(
    val id: Int = 0,
    val name: String = "",
    val description: String = "",
    val price: Double = 0.0,
    val price_gs: Int = 0,
    val emoji: String = "🍔",
    val category: String = "",
    val is_popular: Boolean = false,
    val asset_image: String = "",
    val is_active: Boolean = true
)

// ── Retrofit interface (Call — synchronous compatible) ────────────────────────

interface ApiService {

    @GET("api/orders")
    fun getOrders(): Call<List<OrderDto>>

    @GET("api/orders/{id}")
    fun getOrder(@Path("id") id: Int): Call<OrderDto>

    @POST("api/orders")
    fun createOrder(@Body body: CreateOrderRequest): Call<OrderDto>

    @POST("api/orders/{id}/accept")
    fun acceptOrder(
        @Path("id") id: Int,
        @Body body: AcceptOrderRequest
    ): Call<OrderDto>

    @POST("api/orders/{id}/picked_up")
    fun pickedUp(@Path("id") id: Int): Call<OrderDto>

    @POST("api/orders/{id}/delivering")
    fun delivering(@Path("id") id: Int): Call<OrderDto>

    @POST("api/orders/{id}/complete")
    fun completeOrder(@Path("id") id: Int): Call<OrderDto>

    @POST("api/orders/{id}/cancel")
    fun cancelOrder(@Path("id") id: Int): Call<OrderDto>

    @GET("api/drivers")
    fun getDrivers(): Call<List<DriverDto>>

    @POST("api/drivers/register")
    fun registerDriver(@Body body: DriverRegisterRequest): Call<DriverDto>

    @POST("api/drivers/{id}/location")
    fun updateLocation(
        @Path("id") id: Int,
        @Body body: LocationRequest
    ): Call<Map<String, Boolean>>

    @GET("api/menu")
    fun getMenu(): Call<List<MenuItemDto>>
}
