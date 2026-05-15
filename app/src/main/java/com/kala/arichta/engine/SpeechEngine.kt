package com.kala.arichta.engine

/**
 * Common interface for all speech recognition engines (GGML and ONNX).
 * MainActivity only depends on this — never on a concrete engine class.
 */
interface SpeechEngine {
    val isModelLoaded: Boolean

    /** Load model from an absolute file-system path (never a content:// URI). */
    suspend fun loadModel(modelPath: String): Boolean

    /** Transcribe PCM float samples (16 kHz, mono, range −1..1). Returns raw text. */
    suspend fun transcribe(pcmSamples: FloatArray): String

    /** Free native/session resources. */
    fun release()

    /** Convert raw PCM ShortArray → FloatArray normalised to −1..1. */
    fun shortToFloat(samples: ShortArray): FloatArray =
        FloatArray(samples.size) { i -> samples[i] / 32768f }
}
