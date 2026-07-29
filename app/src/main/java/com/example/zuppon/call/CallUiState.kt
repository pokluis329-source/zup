package com.example.zuppon.call

import java.util.concurrent.atomic.AtomicInteger

object CallUiState {
    private val openChatOrderId = AtomicInteger(-1)

    fun setChatOpen(orderId: Int, open: Boolean) {
        if (open) openChatOrderId.set(orderId)
        else if (openChatOrderId.get() == orderId) openChatOrderId.set(-1)
    }

    fun isChatOpen(orderId: Int): Boolean = openChatOrderId.get() == orderId
}
