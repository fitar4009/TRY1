package com.qalaarikha.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

private const val TAG        = "TtsManager"
private const val UTTERANCE  = "qala_utt"

/**
 * Wrapper around Android's [TextToSpeech] engine.
 *
 * - Initialises with the Hebrew locale (he-IL / iw-IL).
 * - Exposes [isSpeaking] so the ViewModel can gate microphone use.
 * - Calls [onComplete] callback when each utterance finishes, allowing the
 *   ViewModel to restart the STT session.
 */
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    /** True while TTS audio is playing. */
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /** Invoked on the Main thread after each utterance completes (or fails). */
    var onComplete: (() -> Unit)? = null

    init {
        initEngine()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private fun initEngine() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyHebrewLocale()
                attachProgressListener()
                isReady = true
                Log.i(TAG, "TTS ready")
            } else {
                Log.e(TAG, "TTS init failed, status=$status")
            }
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Speak [text] aloud.
     * If the engine is busy it flushes the current utterance first.
     * Safe to call from any thread.
     */
    fun speak(text: String) {
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS not ready, dropping: $text")
            onComplete?.invoke()   // unblock the ViewModel so STT can restart
            return
        }
        _isSpeaking.value = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }

    /** Stop any currently playing speech immediately. */
    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun applyHebrewLocale() {
        // "he" is the modern ISO-639 code; "iw" is the legacy code still used
        // by some Android versions. We try both.
        val heIL = Locale("he", "IL")
        val iwIL = Locale("iw", "IL")

        val result = tts?.isLanguageAvailable(heIL) ?: TextToSpeech.LANG_NOT_SUPPORTED
        when {
            result >= TextToSpeech.LANG_AVAILABLE -> {
                tts?.language = heIL
                Log.i(TAG, "TTS language set to he-IL")
            }
            tts?.isLanguageAvailable(iwIL) ?: TextToSpeech.LANG_NOT_SUPPORTED >= TextToSpeech.LANG_AVAILABLE -> {
                tts?.language = iwIL
                Log.i(TAG, "TTS language set to iw-IL (legacy code)")
            }
            else -> Log.w(TAG, "Hebrew TTS not available on this device")
        }
    }

    private fun attachProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?)   { _isSpeaking.value = true }
            override fun onDone(utteranceId: String?)    { finish() }
            override fun onError(utteranceId: String?)   { finish() }
            // API 21+
            override fun onError(utteranceId: String?, errorCode: Int) { finish() }
        })
    }

    private fun finish() {
        _isSpeaking.value = false
        onComplete?.invoke()
    }
}
