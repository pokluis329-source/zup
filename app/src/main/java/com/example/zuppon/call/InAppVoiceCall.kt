package com.example.zuppon.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.zuppon.network.CallSignalDto
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

enum class VoiceCallState {
    IDLE,
    CALLING,
    INCOMING,
    CONNECTING,
    CONNECTED,
    ENDED,
    FAILED
}

class InAppVoiceCall(
    private val context: Context,
    private val orderId: Int,
    private val role: String,
    private val listener: (VoiceCallState, String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val signaling = CallSignaling(orderId, role, ::handleRemoteSignal)
    private var peerConnection: PeerConnection? = null
    private var localAudio: AudioTrack? = null
    private var remoteAudio: AudioTrack? = null
    private var pendingOffer: String? = null
    private var active = false

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED ->
                    emit(VoiceCallState.CONNECTED, "En llamada")
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED ->
                    emit(VoiceCallState.FAILED, "Conexión perdida")
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            signaling.send(
                type = "ice",
                candidate = candidate.sdp,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex
            )
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) {
            stream?.audioTracks?.firstOrNull()?.let { track ->
                remoteAudio = track
                track.setEnabled(true)
            }
        }
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    fun startListening() {
        signaling.startPolling()
    }

    fun startOutgoing() {
        if (active) return
        active = true
        signaling.startPolling()
        emit(VoiceCallState.CALLING, "Llamando…")
        signaling.send(type = "ring")
        ensurePeerConnection()
        createOffer()
    }

    fun acceptIncoming() {
        if (active) return
        active = true
        signaling.startPolling()
        emit(VoiceCallState.CONNECTING, "Conectando…")
        ensurePeerConnection()
        pendingOffer?.let { setRemoteAndAnswer(it) }
    }

    fun rejectIncoming() {
        signaling.send(type = "reject")
        cleanup()
        emit(VoiceCallState.ENDED, "Llamada rechazada")
    }

    fun hangUp() {
        signaling.send(type = "hangup")
        cleanup()
        emit(VoiceCallState.ENDED, "Llamada finalizada")
    }

    fun release() {
        cleanup()
        signaling.stop()
    }

    private fun handleRemoteSignal(signal: CallSignalDto) {
        when (signal.type) {
            "ring" -> if (!active) emit(VoiceCallState.INCOMING, "Llamada entrante")
            "offer" -> {
                pendingOffer = signal.sdp
                if (active) setRemoteAndAnswer(signal.sdp.orEmpty())
                else emit(VoiceCallState.INCOMING, "Llamada entrante")
            }
            "answer" -> setRemoteAnswer(signal.sdp.orEmpty())
            "ice" -> addIceCandidate(signal)
            "hangup", "reject" -> {
                cleanup()
                emit(VoiceCallState.ENDED, if (signal.type == "reject") "Rechazada" else "Llamada finalizada")
            }
        }
    }

    private fun ensurePeerConnection() {
        if (peerConnection != null) return
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = WebRtcFactory.get(context).createPeerConnection(rtcConfig, observer)
        localAudio = WebRtcFactory.createAudioTrack(context).apply {
            setEnabled(true)
        }
        peerConnection?.addTrack(localAudio)
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                description ?: return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), description)
                signaling.send(type = "offer", sdp = description.description)
            }
            override fun onCreateFailure(error: String?) {
                emit(VoiceCallState.FAILED, error ?: "No se pudo iniciar la llamada")
            }
        }, constraints)
    }

    private fun setRemoteAndAnswer(offerSdp: String) {
        if (offerSdp.isBlank()) return
        val remote = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(description: SessionDescription?) {
                        description ?: return
                        peerConnection?.setLocalDescription(SimpleSdpObserver(), description)
                        signaling.send(type = "answer", sdp = description.description)
                        emit(VoiceCallState.CONNECTING, "Conectando…")
                    }
                    override fun onCreateFailure(error: String?) {
                        emit(VoiceCallState.FAILED, error ?: "Error al responder")
                    }
                }, constraints)
            }
            override fun onSetFailure(error: String?) {
                emit(VoiceCallState.FAILED, error ?: "Error de conexión")
            }
        }, remote)
    }

    private fun setRemoteAnswer(answerSdp: String) {
        if (answerSdp.isBlank()) return
        val remote = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), remote)
    }

    private fun addIceCandidate(signal: CallSignalDto) {
        val candidate = signal.candidate ?: return
        val ice = IceCandidate(
            signal.sdp_mid,
            signal.sdp_mline_index ?: 0,
            candidate
        )
        peerConnection?.addIceCandidate(ice)
    }

    private fun cleanup() {
        active = false
        pendingOffer = null
        try {
            localAudio?.setEnabled(false)
            localAudio?.dispose()
        } catch (_: Exception) { }
        localAudio = null
        remoteAudio = null
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (_: Exception) { }
        peerConnection = null
    }

    private fun emit(state: VoiceCallState, message: String) {
        main.post { listener(state, message) }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
