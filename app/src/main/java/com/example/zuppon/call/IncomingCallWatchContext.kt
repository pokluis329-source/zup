package com.example.zuppon.call

data class IncomingCallWatchContext(
    val orderId: Int,
    val role: String,
    val isDriver: Boolean,
    val contactName: String = "",
    val contactPhone: String = "",
    val amountGs: Int = 0,
    val alias: String = "",
    val cedula: String = ""
)
