package com.qalaarikha.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "SpeechManager"

/**
 * Thin, flow-based wrapper around Android's [SpeechRecognizer].
 *
 * Inspired by Dicio Assistant's STT device abstraction:
 *  - Prefers an on-device (offline) recogniser on Android 13+ via
 *    [SpeechRecognizer.createOnDeviceSpeechRecognizer].
 *  - Falls back to [SpeechRecognizer.createSpeechRecognizer] with
 *    [RecognizerIntent.EXTRA_PREFER_OFFLINE] = true on older devices.
 *  - Targets the Hebrew locale ("he-IL").
 *
 * ⚠️ **All public methods MUST be called on the Main thread.**
 * [SpeechRecognizer] is not thread-safe and enforces this contract.
 *
 * @param silenceLengthMs  Milliseconds of silence before end-of-speech is declared.
 */
class SpeechRecognitionManager(
    private val context: Context,
    private var silenceLengthMs: Long = 1500L,
) {
    private var recognizer: SpeechRecognizer? = null

    /** Whether the recogniser is currently listening. */
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** Whether the device supports offline Hebrew recognition. */
    private val _offlineAvailable = MutableStateFlow(false)
    val offlineAvailable: StateFlow<Boolean> = _offlineAvailable.asStateFlow()

    /** Stream of recognition events consumed by the ViewModel. */
    private val _events = MutableSharedFlow<SpeechEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Creates the [SpeechRecognizer] instance.
     * Must be called once on the Main thread before [startListening].
     */
    @MainThread
    fun initialize() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }
        recognizer = createRecognizer()
        recognizer?.setRecognitionListener(buildListener())
        Log.d(TAG, "SpeechRecognizer initialised (offline=${_offlineAvailable.value})")
    }

    /**
     * Starts a single recognition session.
     * After results are returned (or an error occurs), call this again for
     * continuous listening – the recogniser is not continuous by default.
     */
    @MainThread
    fun startListening() {
        if (_isListening.value) return
        recognizer?.startListening(buildIntent())
        _isListening.value = true
    }

    /** Gracefully stops the active session. */
    @MainThread
    fun stopListening() {
        recognizer?.stopListening()
        _isListening.value = false
    }

    /** Cancels the current session without waiting for results. */
    @MainThread
    fun cancel() {
        recognizer?.cancel()
        _isListening.value = false
    }

    /** Update silence threshold at runtime (e.g. from Settings). */
    fun updateSilenceLengthMs(ms: Long) { silenceLengthMs = ms }

    /** Release native resources. Must be called when the ViewModel is cleared. */
    @MainThread
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        _isListening.value = false
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    /**
     * Follows Dicio's model-selection logic:
     * On Android 13+ prefer the dedicated on-device recogniser when available;
     * otherwise fall back to the standard recogniser with EXTRA_PREFER_OFFLINE.
     */
    @MainThread
    private fun createRecognizer(): SpeechRecognizer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                _offlineAvailable.value = true
                Log.i(TAG, "Using on-device recogniser (API 33+)")
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }
        }
        // Pre-API-33 or no on-device model: use the default recogniser with
        // EXTRA_PREFER_OFFLINE set in the intent (see buildIntent()).
        Log.i(TAG, "Using default recogniser with PREFER_OFFLINE intent extra")
        _offlineAvailable.value = false   // will be confirmed by actual usage
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                 RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // Hebrew locale
        putExtra(RecognizerIntent.EXTRA_LANGUAGE,            "he-IL")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL")
        // Prefer offline; works on API 23+ (minSdk = 23)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Allow up to 5 alternative hypotheses (best is index 0)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        // Enable partial (live) results for the typing animation
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Fine-tune silence detection
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                 silenceLengthMs)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                 (silenceLengthMs * 0.6).toLong())
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
    }

    // ── RecognitionListener ───────────────────────────────────────────────────

    private fun buildListener() = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _events.tryEmit(SpeechEvent.Ready)
        }

        override fun onBeginningOfSpeech() { /* no-op */ }

        override fun onRmsChanged(rmsdB: Float) {
            _events.tryEmit(SpeechEvent.RmsChanged(rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) { /* no-op */ }

        override fun onEndOfSpeech() {
            _events.tryEmit(SpeechEvent.EndOfSpeech)
        }

        override fun onPartialResults(partial: Bundle?) {
            val text = partial
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                _events.tryEmit(SpeechEvent.Partial(text))
            }
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.filter { it.isNotBlank() }
            if (!matches.isNullOrEmpty()) {
                // Mark that offline recognition delivered a result at least once
                _offlineAvailable.value = true
                _events.tryEmit(SpeechEvent.Final(matches))
            } else {
                _events.tryEmit(SpeechEvent.Error(SpeechRecognizer.ERROR_NO_MATCH))
            }
        }

        override fun onError(error: Int) {
            _isListening.value = false
            Log.d(TAG, "onError: $error")
            _events.tryEmit(SpeechEvent.Error(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* no-op */ }
    }
}
