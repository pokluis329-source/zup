package com.example.zuppon.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.zuppon.network.ApiClient
import com.example.zuppon.network.CallSignalRequest

class IncomingCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val orderId = intent.getIntExtra(EXTRA_ORDER_ID, -1)
        if (orderId == -1) return

        when (intent.action) {
            CallNotificationHelper.ACTION_REJECT -> {
                val role = intent.getStringExtra(EXTRA_ROLE).orEmpty()
                if (role.isNotBlank()) {
                    Thread {
                        try {
                            ApiClient.api?.sendCallSignal(
                                orderId,
                                CallSignalRequest(type = "reject", from = role)
                            )?.execute()
                        } catch (_: Exception) { }
                    }.start()
                }
                IncomingCallWatcher.clearRing(orderId)
                CallNotificationHelper.dismiss(context, orderId)
            }
            CallNotificationHelper.ACTION_ACCEPT -> {
                val watch = IncomingCallWatcher.contextFor(orderId) ?: return
                IncomingCallWatcher.clearRing(orderId)
                CallNotificationHelper.dismiss(context, orderId)
                context.startActivity(
                    PaymentChatActivityIntents.open(context, watch, autoAccept = true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
    }

    companion object {
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_ROLE = "role"
        const val EXTRA_SIGNAL_TS = "signal_ts"
    }
}
