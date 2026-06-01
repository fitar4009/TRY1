package com.qalaarikha.assistant.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qalaarikha.assistant.App
import com.qalaarikha.assistant.data.preferences.PreferencesManager
import com.qalaarikha.assistant.data.repository.ContactRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val fuzzyThreshold: Int     = PreferencesManager.DEFAULT_THRESHOLD,
    val contactsFilePath: String = "",
    val dialImmediately: Boolean = false,
    val silenceLengthMs: Long   = PreferencesManager.DEFAULT_SILENCE_MS,
    val contactCount: Int       = 0,
    val reloadMessage: String?  = null,
)

class SettingsViewModel(
    application: Application,
    private val prefs: PreferencesManager,
    private val contactRepo: ContactRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Collect all preference flows and merge into UI state
        viewModelScope.launch {
            combine(
                prefs.fuzzyThreshold,
                prefs.contactsFilePath,
                prefs.dialImmediately,
                prefs.silenceLengthMs,
            ) { threshold, filePath, dialNow, silence ->
                _uiState.value.copy(
                    fuzzyThreshold   = threshold,
                    contactsFilePath = filePath,
                    dialImmediately  = dialNow,
                    silenceLengthMs  = silence,
                )
            }.collect { state -> _uiState.value = state }
        }
    }

    fun setFuzzyThreshold(value: Int) {
        viewModelScope.launch { prefs.setFuzzyThreshold(value) }
    }

    fun setContactsFilePath(path: String) {
        viewModelScope.launch { prefs.setContactsFilePath(path) }
    }

    fun setDialImmediately(value: Boolean) {
        viewModelScope.launch { prefs.setDialImmediately(value) }
    }

    fun setSilenceLengthMs(value: Long) {
        viewModelScope.launch { prefs.setSilenceLengthMs(value) }
    }

    fun reloadContacts() {
        viewModelScope.launch {
            val contacts = contactRepo.getAllContacts()
            _uiState.update { it.copy(
                contactCount   = contacts.size,
                reloadMessage  = "נטענו ${contacts.size} אנשי קשר"
            ) }
        }
    }

    fun clearReloadMessage() {
        _uiState.update { it.copy(reloadMessage = null) }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val a = app as App
            return SettingsViewModel(app, a.preferencesManager, a.contactRepository) as T
        }
    }
}
