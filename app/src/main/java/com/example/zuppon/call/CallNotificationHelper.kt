package com.example.zuppon.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.zuppon.R

object CallNotificationHelper {

    const val CHANNEL_ID = "zuppon_incoming_calls"
    const val ACTION_ACCEPT = "com.example.zuppon.call.ACCEPT"
    const val ACTION_REJECT = "com.example.zuppon.call.REJECT"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.call_notification_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.call_notification_channel_desc)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(channel)
    }

    fun showIncoming(context: Context, watch: IncomingCallWatchContext, signalTs: Double) {
        ensureChannel(context)
        val fromLabel = if (watch.isDriver) {
            watch.contactName.ifBlank { "Cliente" }
        } else {
            "Repartidor"
        }

        val contentIntent = PaymentChatActivityIntents.open(context, watch, autoAccept = false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val acceptIntent = PendingIntent.getBroadcast(
            context,
            watch.orderId * 10 + 1,
            Intent(context, IncomingCallReceiver::class.java).apply {
                action = ACTION_ACCEPT
                putExtra(IncomingCallReceiver.EXTRA_ORDER_ID, watch.orderId)
                putExtra(IncomingCallReceiver.EXTRA_SIGNAL_TS, signalTs)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = PendingIntent.getBroadcast(
            context,
            watch.orderId * 10 + 2,
            Intent(context, IncomingCallReceiver::class.java).apply {
                action = ACTION_REJECT
                putExtra(IncomingCallReceiver.EXTRA_ORDER_ID, watch.orderId)
                putExtra(IncomingCallReceiver.EXTRA_ROLE, watch.role)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            watch.orderId * 10 + 3,
            PaymentChatActivityIntents.open(context, watch, autoAccept = true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(context.getString(R.string.incoming_call_title))
            .setContentText(context.getString(R.string.incoming_call_body, fromLabel, watch.orderId))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(PendingIntent.getActivity(
                context,
                watch.orderId * 10,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(0, context.getString(R.string.call_action_reject), rejectIntent)
            .addAction(0, context.getString(R.string.call_action_accept), acceptIntent)
            .build()

        NotificationManagerCompat.from(context).notify(watch.orderId, notification)
    }

    fun dismiss(context: Context, orderId: Int) {
        NotificationManagerCompat.from(context).cancel(orderId)
    }
}
