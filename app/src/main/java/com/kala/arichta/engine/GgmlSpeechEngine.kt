package com.kala.arichta.engine

import com.kala.arichta.whisper.WhisperEngine

/**
 * SpeechEngine implementation backed by whisper.cpp via JNI.
 * Accepts .bin (GGML) and .gguf model files.
 */
class GgmlSpeechEngine : SpeechEngine {

    private val engine = WhisperEngine()

    override val isModelLoaded: Boolean
        get() = engine.isModelLoaded

    override suspend fun loadModel(modelPath: String): Boolean =
        engine.loadModel(modelPath)

    override suspend fun transcribe(pcmSamples: FloatArray): String =
        engine.transcribe(pcmSamples)

    override fun release() = engine.release()
}
