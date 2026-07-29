package com.example.zuppon.call

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory

object WebRtcFactory {

    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun get(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            return factory!!
        }
    }

    fun createAudioTrack(context: Context, label: String = "ZUP_AUDIO"): AudioTrack {
        val mediaConstraints = MediaConstraints()
        val audioSource: AudioSource = get(context).createAudioSource(mediaConstraints)
        return get(context).createAudioTrack(label, audioSource)
    }
}
