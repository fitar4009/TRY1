package com.kala.arichta.whisper

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin wrapper around the native whisper.cpp JNI bridge.
 * Handles model lifecycle and transcription on a background thread.
 */
class WhisperEngine {

    companion object {
        private const val TAG = "WhisperEngine"
        private const val SAMPLE_RATE = 16000  // whisper.cpp requires 16kHz

        init {
            try {
                System.loadLibrary("kala_whisper_jni")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }
    }

    // ── Native declarations ─────────────────────────────────────────────────
    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeTranscribe(audioData: FloatArray, sampleCount: Int): String
    private external fun nativeFree()
    private external fun nativeIsLoaded(): Boolean

    // ── State ───────────────────────────────────────────────────────────────
    var isModelLoaded: Boolean = false
        private set

    /**
     * Load the GGUF model from the given file path.
     * Must be called on a background thread (suspend function).
     */
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Loading model: $modelPath")
        isModelLoaded = nativeInit(modelPath)
        if (!isModelLoaded) {
            Log.e(TAG, "Model load failed for path: $modelPath")
        }
        isModelLoaded
    }

    /**
     * Transcribe raw PCM samples (16kHz, mono, float32 normalized to -1..1).
     * Returns the Hebrew transcription string, or empty string on failure.
     */
    suspend fun transcribe(pcmSamples: FloatArray): String = withContext(Dispatchers.IO) {
        if (!isModelLoaded) {
            Log.w(TAG, "transcribe() called before model was loaded")
            return@withContext ""
        }
        try {
            val result = nativeTranscribe(pcmSamples, pcmSamples.size)
            result.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}")
            ""
        }
    }

    /**
     * Convert ShortArray PCM (from AudioRecord) to FloatArray normalized to [-1, 1].
     */
    fun shortToFloat(shorts: ShortArray): FloatArray {
        return FloatArray(shorts.size) { i ->
            shorts[i].toFloat() / Short.MAX_VALUE.toFloat()
        }
    }

    /**
     * Release native resources. Call in onDestroy().
     */
    fun release() {
        if (isModelLoaded) {
            nativeFree()
            isModelLoaded = false
            Log.i(TAG, "WhisperEngine released")
        }
    }
}
