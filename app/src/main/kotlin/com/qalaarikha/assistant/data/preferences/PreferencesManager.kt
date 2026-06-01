package com.qalaarikha.assistant.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Extension property creates a single DataStore per app process. */
private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "qala_prefs")

/**
 * All user-configurable settings backed by DataStore (Preferences).
 *
 * Exposed as [Flow] so Compose / ViewModel can collect reactively.
 * Write operations are suspend functions safe to call from a coroutine.
 */
class PreferencesManager(private val context: Context) {

    // ── Keys ──────────────────────────────────────────────────────────────────
    companion object {
        private val KEY_FUZZY_THRESHOLD   = intPreferencesKey("fuzzy_threshold")
        private val KEY_CONTACTS_FILE     = stringPreferencesKey("contacts_file_path")
        private val KEY_DIAL_IMMEDIATELY  = booleanPreferencesKey("dial_immediately")
        private val KEY_SILENCE_MS        = longPreferencesKey("silence_length_ms")

        const val DEFAULT_THRESHOLD  = 80       // percent  (80 %)
        const val DEFAULT_SILENCE_MS = 1500L    // millis of silence → end-of-speech
    }

    // ── Read streams ──────────────────────────────────────────────────────────

    /** Minimum fuzzy-match confidence as a percentage (50–100). Default 80. */
    val fuzzyThreshold: Flow<Int> = context.dataStore.data
        .safeRead()
        .map { prefs -> prefs[KEY_FUZZY_THRESHOLD] ?: DEFAULT_THRESHOLD }

    /**
     * Absolute path to the contacts TXT file.
     * Empty string → use default: Android/data/<pkg>/files/contacts.txt
     */
    val contactsFilePath: Flow<String> = context.dataStore.data
        .safeRead()
        .map { prefs -> prefs[KEY_CONTACTS_FILE] ?: "" }

    /**
     * If true, dial immediately after recognition without voice confirmation.
     * Default false → ask "לחייג?" before calling.
     */
    val dialImmediately: Flow<Boolean> = context.dataStore.data
        .safeRead()
        .map { prefs -> prefs[KEY_DIAL_IMMEDIATELY] ?: false }

    /** Silence duration (ms) after which speech input is considered complete. */
    val silenceLengthMs: Flow<Long> = context.dataStore.data
        .safeRead()
        .map { prefs -> prefs[KEY_SILENCE_MS] ?: DEFAULT_SILENCE_MS }

    // ── Write helpers ─────────────────────────────────────────────────────────

    suspend fun setFuzzyThreshold(value: Int) {
        context.dataStore.edit { it[KEY_FUZZY_THRESHOLD] = value.coerceIn(50, 100) }
    }

    suspend fun setContactsFilePath(path: String) {
        context.dataStore.edit { it[KEY_CONTACTS_FILE] = path }
    }

    suspend fun setDialImmediately(value: Boolean) {
        context.dataStore.edit { it[KEY_DIAL_IMMEDIATELY] = value }
    }

    suspend fun setSilenceLengthMs(value: Long) {
        context.dataStore.edit { it[KEY_SILENCE_MS] = value.coerceIn(500L, 5000L) }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /** Absorbs IO exceptions (corrupted DataStore file) and returns empty prefs. */
    private fun Flow<Preferences>.safeRead(): Flow<Preferences> =
        catch { e ->
            if (e is IOException) emit(emptyPreferences())
            else throw e
        }
}
