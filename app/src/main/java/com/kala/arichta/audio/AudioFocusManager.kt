package com.kala.arichta.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manages AudioFocus requests to duck/mute other media (radio, music)
 * while the dialer is listening. Toggleable via settings.
 */
class AudioFocusManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN ->
                Log.i(TAG, "AudioFocus: GAIN")
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                Log.i(TAG, "AudioFocus: LOSS")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                Log.i(TAG, "AudioFocus: DUCK")
        }
    }

    /**
     * Request audio focus. Call when app starts listening.
     * @param duck true = duck (lower volume of others); false = exclusive focus
     */
    fun requestFocus(duck: Boolean = true): Boolean {
        if (hasFocus) return true

        val request = AudioFocusRequest.Builder(
            if (duck) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        ).apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnAudioFocusChangeListener(focusChangeListener)
            setWillPauseWhenDucked(false)
            setAcceptsDelayedFocusGain(false)
        }.build()

        focusRequest = request
        val result = audioManager.requestAudioFocus(request)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        Log.i(TAG, "AudioFocus request: ${if (hasFocus) "GRANTED" else "DENIED"} (duck=$duck)")
        return hasFocus
    }

    /**
     * Abandon audio focus. Call when done listening or on pause.
     */
    fun abandonFocus() {
        if (!hasFocus) return
        focusRequest?.let { req ->
            audioManager.abandonAudioFocusRequest(req)
            Log.i(TAG, "AudioFocus abandoned")
        }
        focusRequest = null
        hasFocus = false
    }
}
