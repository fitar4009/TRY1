package com.kala.arichta

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Centralized access to SharedPreferences for all app settings.
 */
class AppPreferences(context: Context) {

    companion object {
        const val KEY_MODEL_PATH       = "model_path"
        const val KEY_AUDIO_FOCUS      = "audio_focus_enabled"
        const val KEY_AUDIO_DUCK       = "audio_duck_only"
        const val KEY_VAD_TIMEOUT_MS   = "vad_timeout_ms"
        const val KEY_MATCH_THRESHOLD  = "match_threshold"

        // Defaults
        const val DEFAULT_AUDIO_FOCUS    = true
        const val DEFAULT_AUDIO_DUCK     = true
        const val DEFAULT_VAD_TIMEOUT_MS = 1500
        const val DEFAULT_MATCH_THRESHOLD = 60
    }

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MODEL_PATH, v).apply()

    var audioFocusEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_FOCUS, DEFAULT_AUDIO_FOCUS)
        set(v) = prefs.edit().putBoolean(KEY_AUDIO_FOCUS, v).apply()

    var audioDuckOnly: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_DUCK, DEFAULT_AUDIO_DUCK)
        set(v) = prefs.edit().putBoolean(KEY_AUDIO_DUCK, v).apply()

    var vadTimeoutMs: Int
        get() = prefs.getInt(KEY_VAD_TIMEOUT_MS, DEFAULT_VAD_TIMEOUT_MS)
        set(v) = prefs.edit().putInt(KEY_VAD_TIMEOUT_MS, v).apply()

    var matchThreshold: Int
        get() = prefs.getInt(KEY_MATCH_THRESHOLD, DEFAULT_MATCH_THRESHOLD)
        set(v) = prefs.edit().putInt(KEY_MATCH_THRESHOLD, v).apply()
}
