package com.example.zuppon.call

import android.os.Handler
import android.os.Looper
import com.example.zuppon.network.ApiClient
import com.example.zuppon.network.CallSignalDto
import com.example.zuppon.network.CallSignalRequest

class CallSignaling(
    private val orderId: Int,
    private val role: String,
    private val onSignal: (CallSignalDto) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var since = 0.0
    private var polling = false
    private var pollRunnable: Runnable? = null

    fun send(
        type: String,
        sdp: String? = null,
        candidate: String? = null,
        sdpMid: String? = null,
        sdpMLineIndex: Int? = null
    ) {
        val api = ApiClient.api ?: return
        Thread {
            try {
                val resp = api.sendCallSignal(
                    orderId,
                    CallSignalRequest(
                        type = type,
                        from = role,
                        sdp = sdp,
                        candidate = candidate,
                        sdp_mid = sdpMid,
                        sdp_mline_index = sdpMLineIndex
                    )
                ).execute()
                if (resp.isSuccessful) {
                    resp.body()?.ts?.let { ts ->
                        if (ts > since) since = ts
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    fun startPolling() {
        if (polling) return
        polling = true
        pollOnce()
    }

    fun stop() {
        polling = false
        pollRunnable?.let { main.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun pollOnce() {
        if (!polling) return
        val api = ApiClient.api
        if (api == null) {
            scheduleNext()
            return
        }
        Thread {
            try {
                val resp = api.getCallSignals(orderId, since).execute()
                if (resp.isSuccessful) {
                    val list = resp.body().orEmpty()
                    for (signal in list) {
                        if (signal.ts > since) since = signal.ts
                        if (signal.from != role) {
                            main.post { onSignal(signal) }
                        }
                    }
                }
            } catch (_: Exception) { }
            scheduleNext()
        }.start()
    }

    private fun scheduleNext() {
        if (!polling) return
        pollRunnable = Runnable { pollOnce() }
        main.postDelayed(pollRunnable!!, 350L)
    }
}
