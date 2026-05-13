#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "KalaWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Global state ────────────────────────────────────────────────────────────
static whisper_context* g_ctx = nullptr;

// ─── Helper: convert jstring to std::string ──────────────────────────────────
static std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// ─── JNI: Initialize context from model file path ────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kala_arichta_whisper_WhisperEngine_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath) {

    std::string path = jstring_to_string(env, modelPath);
    LOGI("Loading whisper model from: %s", path.c_str());

    // Free previous context if any
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU-only for broad device compatibility

    g_ctx = whisper_init_from_file_with_params(path.c_str(), cparams);

    if (!g_ctx) {
        LOGE("Failed to load model: %s", path.c_str());
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully. System info: %s", whisper_print_system_info());
    return JNI_TRUE;
}

// ─── JNI: Transcribe PCM float samples ───────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_kala_arichta_whisper_WhisperEngine_nativeTranscribe(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray audioData,
        jint sampleCount) {

    if (!g_ctx) {
        LOGE("Whisper context not initialized");
        return env->NewStringUTF("");
    }

    jfloat* samples = env->GetFloatArrayElements(audioData, nullptr);
    if (!samples) {
        LOGE("Failed to get audio samples");
        return env->NewStringUTF("");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    // Hebrew language
    params.language        = "he";
    params.translate       = false;
    params.no_context      = true;
    params.single_segment  = false;
    params.print_progress  = false;
    params.print_realtime  = false;
    params.print_timestamps= false;
    params.suppress_blank  = true;
    // params.suppress_non_speech_tokens = true;  // הוסר בגרסה החדשה
    params.temperature     = 0.0f;
    params.n_threads       = 4;

    int result = whisper_full(g_ctx, params, samples, sampleCount);

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);

    if (result != 0) {
        LOGE("whisper_full failed with code: %d", result);
        return env->NewStringUTF("");
    }

    // Collect all segments
    std::string transcription;
    int n_segments = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < n_segments; i++) {
        const char* text = whisper_full_get_segment_text(g_ctx, i);
        if (text) {
            if (!transcription.empty()) transcription += " ";
            transcription += text;
        }
    }

    LOGI("Transcription result: %s", transcription.c_str());
    return env->NewStringUTF(transcription.c_str());
}

// ─── JNI: Free context ───────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_kala_arichta_whisper_WhisperEngine_nativeFree(
        JNIEnv* /* env */,
        jobject /* this */) {

    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        LOGI("Whisper context freed");
    }
}

// ─── JNI: Check if model is loaded ───────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kala_arichta_whisper_WhisperEngine_nativeIsLoaded(
        JNIEnv* /* env */,
        jobject /* this */) {
    return (g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}
