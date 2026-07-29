package com.example.zuppon.call

import android.content.Context
import android.content.Intent
import com.example.zuppon.ui.passenger.PaymentChatActivity

object PaymentChatActivityIntents {

    fun open(
        context: Context,
        watch: IncomingCallWatchContext,
        autoAccept: Boolean
    ): Intent {
        return Intent(context, PaymentChatActivity::class.java).apply {
            putExtra(PaymentChatActivity.EXTRA_ORDER_ID, watch.orderId)
            putExtra(PaymentChatActivity.EXTRA_IS_DRIVER, watch.isDriver)
            putExtra(PaymentChatActivity.EXTRA_CONTACT_NAME, watch.contactName)
            putExtra(PaymentChatActivity.EXTRA_CONTACT_PHONE, watch.contactPhone)
            putExtra(PaymentChatActivity.EXTRA_AMOUNT_GS, watch.amountGs)
            putExtra(PaymentChatActivity.EXTRA_ALIAS, watch.alias)
            putExtra(PaymentChatActivity.EXTRA_CEDULA, watch.cedula)
            putExtra(PaymentChatActivity.EXTRA_AUTO_ACCEPT, autoAccept)
        }
    }
}
