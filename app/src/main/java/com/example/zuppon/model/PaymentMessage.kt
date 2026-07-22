package com.example.zuppon.model

data class PaymentMessage(
    val id: Int = 0,
    val orderId: Int = 0,
    val sender: String = "client",
    val type: String = "text",
    val body: String = "",
    val imageUrl: String? = null,
    val createdAt: String? = null
)

data class PaymentInfo(
    val alias: String = "",
    val cedula: String = "",
    val holder: String = "",
    val amountGs: Int? = null
)
