package com.example.zuppon.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.zuppon.network.ApiClient
import com.example.zuppon.network.CallSignalDto

object IncomingCallWatcher {

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var watch: IncomingCallWatchContext? = null
    private var since = 0.0
    private var polling = false
    private var notifiedRingTs = 0.0
    private val activeRings = mutableSetOf<Int>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollOnce()
            if (polling) main.postDelayed(this, 900L)
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        CallNotificationHelper.ensureChannel(context.applicationContext)
    }

    fun watch(context: IncomingCallWatchContext) {
        appContext?.let { CallNotificationHelper.ensureChannel(it) }
        if (watch?.orderId == context.orderId && watch?.role == context.role) {
            watch = context
            return
        }
        watch = context
        since = 0.0
        notifiedRingTs = 0.0
        startPolling()
    }

    fun stop() {
        polling = false
        main.removeCallbacks(pollRunnable)
        val orderId = watch?.orderId
        watch = null
        since = 0.0
        notifiedRingTs = 0.0
        orderId?.let { id ->
            activeRings.remove(id)
            appContext?.let { CallNotificationHelper.dismiss(it, id) }
        }
    }

    fun contextFor(orderId: Int): IncomingCallWatchContext? =
        watch?.takeIf { it.orderId == orderId }

    fun dismissNotification(orderId: Int) {
        appContext?.let { CallNotificationHelper.dismiss(it, orderId) }
        activeRings.remove(orderId)
    }

    fun clearRing(orderId: Int) {
        activeRings.remove(orderId)
        if (watch?.orderId == orderId) notifiedRingTs = 0.0
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        main.post(pollRunnable)
    }

    private fun pollOnce() {
        val ctx = watch ?: return
        if (CallUiState.isChatOpen(ctx.orderId)) {
            dismissNotification(ctx.orderId)
            return
        }

        val api = ApiClient.api ?: return
        Thread {
            try {
                val resp = api.getCallSignals(ctx.orderId, since).execute()
                if (!resp.isSuccessful) return@Thread
                val signals = resp.body().orEmpty()
                for (signal in signals) {
                    if (signal.ts > since) since = signal.ts
                    if (signal.from == ctx.role) continue
                    handleRemote(ctx, signal)
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun handleRemote(ctx: IncomingCallWatchContext, signal: CallSignalDto) {
        when (signal.type) {
            "ring", "offer" -> {
                if (signal.ts <= notifiedRingTs) return
                if (CallUiState.isChatOpen(ctx.orderId)) return
                notifiedRingTs = signal.ts
                activeRings.add(ctx.orderId)
                main.post {
                    appContext?.let { CallNotificationHelper.showIncoming(it, ctx, signal.ts) }
                }
            }
            "hangup", "reject" -> main.post {
                clearRing(ctx.orderId)
                appContext?.let { CallNotificationHelper.dismiss(it, ctx.orderId) }
            }
        }
    }
}
