package com.kala.arichta.engine

import java.io.File

/**
 * Creates the correct SpeechEngine for a given model file path.
 *
 *   *.onnx           →  OnnxSpeechEngine  (point at encoder_model.onnx)
 *   *.bin / *.gguf   →  GgmlSpeechEngine  (whisper.cpp JNI)
 */
object EngineFactory {

    fun create(modelPath: String): SpeechEngine {
        val ext = File(modelPath).extension.lowercase()
        return if (ext == "onnx") OnnxSpeechEngine() else GgmlSpeechEngine()
    }

    /**
     * Returns a human-readable description of which engine will be used,
     * shown in the Settings summary.
     */
    fun describe(modelPath: String): String {
        val ext = File(modelPath).extension.lowercase()
        return if (ext == "onnx") "ONNX Runtime (hardware accelerated)"
        else "whisper.cpp (CPU)"
    }
}
