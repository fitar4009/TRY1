package com.qalaarikha.assistant.ui.home

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qalaarikha.assistant.App
import com.qalaarikha.assistant.R
import com.qalaarikha.assistant.data.model.*
import com.qalaarikha.assistant.data.preferences.PreferencesManager
import com.qalaarikha.assistant.data.repository.ContactRepository
import com.qalaarikha.assistant.domain.ContactMatcher
import com.qalaarikha.assistant.domain.HebrewNumberNormalizer
import com.qalaarikha.assistant.domain.IntentRecognizer
import com.qalaarikha.assistant.speech.SpeechEvent
import com.qalaarikha.assistant.speech.SpeechRecognitionManager
import com.qalaarikha.assistant.speech.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "HomeViewModel"

/**
 * Core ViewModel for the home screen.
 *
 * Owns the full assistant state machine:
 *   Idle → Listening → Processing → Speaking → Listening (loop)
 *   with sub-interactions for disambiguation and confirmation.
 *
 * All STT/TTS operations are dispatched to [Dispatchers.Main]
 * because [SpeechRecognizer] is not thread-safe.
 */
class HomeViewModel(
    application: Application,
    private val contactRepository: ContactRepository,
    private val speechManager: SpeechRecognitionManager,
    private val ttsManager: TtsManager,
    private val prefs: PreferencesManager,
    private val contactMatcher: ContactMatcher,
) : AndroidViewModel(application) {

    // ── Exposed state ─────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()

    // ── Internal ──────────────────────────────────────────────────────────────

    private var contacts: List<com.qalaarikha.assistant.data.model.Contact> = emptyList()
    private var restartJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        // Initialise STT on the Main thread (required by SpeechRecognizer)
        viewModelScope.launch(Dispatchers.Main) {
            speechManager.initialize()
            observeSpeechEvents()
        }

        // TTS completion → restart listening after a short echo-guard delay
        ttsManager.onComplete = {
            viewModelScope.launch(Dispatchers.Main) {
                delay(350)   // let residual audio die down
                restartListening()
            }
        }

        // Observe offline-availability flag for the UI warning
        viewModelScope.launch {
            speechManager.offlineAvailable.collect { available ->
                // Only warn if we have already tried at least once and it is offline
                _uiState.update { it.copy(showOfflineWarning = !available && it.contactCount > 0) }
            }
        }

        // Load contacts, then update threshold and auto-start STT
        viewModelScope.launch {
            prefs.fuzzyThreshold.first().let { t ->
                contactMatcher.setThreshold(t / 100f)
            }
            loadContacts()
            // Small delay so the UI renders before mic opens
            delay(400)
            restartListening()
        }

        // Observe threshold changes at runtime
        viewModelScope.launch {
            prefs.fuzzyThreshold.collect { t -> contactMatcher.setThreshold(t / 100f) }
        }

        // Observe silence-length changes
        viewModelScope.launch {
            prefs.silenceLengthMs.collect { ms -> speechManager.updateSilenceLengthMs(ms) }
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────

    fun toggleListening() {
        if (_uiState.value.assistantState is AssistantState.Listening) stopListening()
        else restartListening()
    }

    fun exitDictation() {
        viewModelScope.launch(Dispatchers.Main) {
            speechManager.cancel()
            _uiState.update { it.copy(assistantState = AssistantState.Idle, dictationText = "") }
        }
    }

    fun copyDictationText() {
        val text = _uiState.value.dictationText
        if (text.isNotBlank()) {
            viewModelScope.launch { _effects.emit(UiEffect.CopyToClipboard(text)) }
        }
    }

    fun reloadContacts() {
        viewModelScope.launch { loadContacts() }
    }

    // ── STT control ───────────────────────────────────────────────────────────

    private fun restartListening() {
        restartJob?.cancel()
        restartJob = viewModelScope.launch(Dispatchers.Main) {
            if (ttsManager.isSpeaking.value) return@launch   // never listen while speaking
            speechManager.startListening()
            _uiState.update { it.copy(assistantState = AssistantState.Listening, partialText = "") }
        }
    }

    private fun stopListening() {
        viewModelScope.launch(Dispatchers.Main) {
            speechManager.stopListening()
            _uiState.update { it.copy(assistantState = AssistantState.Idle) }
        }
    }

    // ── Speech event handling ─────────────────────────────────────────────────

    private fun observeSpeechEvents() {
        viewModelScope.launch {
            speechManager.events.collect { event -> handleSpeechEvent(event) }
        }
    }

    private fun handleSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Ready       -> _uiState.update { it.copy(assistantState = AssistantState.Listening) }
            is SpeechEvent.Partial     -> _uiState.update { it.copy(partialText = event.text) }
            is SpeechEvent.RmsChanged  -> _uiState.update { it.copy(rmsLevel = rmsNormalize(event.rmsdB)) }
            is SpeechEvent.EndOfSpeech -> { /* recogniser is computing – keep Processing state */ }
            is SpeechEvent.Final       -> handleFinalResult(event.results)
            is SpeechEvent.Error       -> handleSpeechError(event.errorCode)
        }
    }

    private fun handleFinalResult(utterances: List<String>) {
        val rawText      = utterances.first()
        val previousState = _uiState.value.assistantState   // capture BEFORE update

        _uiState.update { it.copy(
            partialText     = "",
            assistantState  = AssistantState.Processing(rawText)
        ) }

        viewModelScope.launch {
            // Route based on current sub-interaction
            when (previousState) {
                is AssistantState.WaitingForSelection    -> handleSelection(rawText, previousState)
                is AssistantState.WaitingForConfirmation -> handleConfirmation(rawText, previousState)
                is AssistantState.Dictating              -> appendDictation(rawText, previousState)
                else                                     -> processCommand(rawText, utterances)
            }
        }
    }

    private fun handleSpeechError(errorCode: Int) {
        when (errorCode) {
            // Silent retries – no user-facing error
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                viewModelScope.launch(Dispatchers.Main) {
                    delay(150)
                    restartListening()
                }
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                viewModelScope.launch(Dispatchers.Main) {
                    delay(700)
                    restartListening()
                }
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                _uiState.update { it.copy(
                    assistantState = AssistantState.Error(
                        getString(R.string.error_permission_audio), errorCode)
                ) }
            }
            else -> {
                val msg = getString(R.string.error_generic, errorCode)
                _uiState.update { it.copy(assistantState = AssistantState.Error(msg, errorCode)) }
                viewModelScope.launch(Dispatchers.Main) { delay(2000); restartListening() }
            }
        }
    }

    // ── Command processing ────────────────────────────────────────────────────

    private suspend fun processCommand(raw: String, allUtterances: List<String>) {
        val normalised = HebrewNumberNormalizer.normalize(raw)
        val intent     = IntentRecognizer.recognizeCommand(normalised, raw)

        when (intent) {
            is IntentResult.DialContact   -> handleDialContact(intent.query)
            is IntentResult.DialNumber    -> handleDialNumber(intent.number)
            is IntentResult.StartDictation -> enterDictation()
            is IntentResult.StopDictation  -> {
                // StopDictation outside dictation mode = unknown
                speakThenListen(getString(R.string.tts_not_understood))
            }
            is IntentResult.Unknown -> {
                // Try remaining alternatives before giving up
                val handled = allUtterances.drop(1).any { alt ->
                    val normAlt = HebrewNumberNormalizer.normalize(alt)
                    val altIntent = IntentRecognizer.recognizeCommand(normAlt, alt)
                    if (altIntent !is IntentResult.Unknown) {
                        viewModelScope.launch { processCommand(alt, listOf(alt)) }
                        true
                    } else false
                }
                if (!handled) speakThenListen(getString(R.string.tts_not_understood))
            }
            else -> speakThenListen(getString(R.string.tts_not_understood))
        }
    }

    // ── Dial contact ──────────────────────────────────────────────────────────

    private suspend fun handleDialContact(query: String) {
        val matches = contactMatcher.findMatches(query, contacts)

        when {
            matches.isEmpty() -> {
                speakThenListen(getString(R.string.tts_contact_not_found, query))
            }
            matches.size == 1 -> {
                val c = matches[0].contact
                initiateCallOrConfirm(c.normalizedPhone(), c.name)
            }
            else -> {
                // Show disambiguation list (max 3)
                val candidates = matches.take(3)
                val list = candidates.mapIndexed { i, cc ->
                    "${i + 1}. ${cc.contact.name}"
                }.joinToString(separator = "  ")

                val speech = getString(R.string.tts_multiple_contacts_found) +
                             "  $list  " +
                             getString(R.string.tts_say_1_2_3)

                _uiState.update { it.copy(
                    assistantState = AssistantState.WaitingForSelection(candidates, PendingAction.Dial)
                ) }
                speakOnly(speech)       // STT restarts automatically after TTS
            }
        }
    }

    // ── Dial number ───────────────────────────────────────────────────────────

    private suspend fun handleDialNumber(number: String) {
        initiateCallOrConfirm(number, number)
    }

    // ── Confirm / immediate dial ──────────────────────────────────────────────

    private suspend fun initiateCallOrConfirm(phone: String, displayName: String) {
        val immediate = prefs.dialImmediately.first()
        if (immediate) {
            performCall(phone)
        } else {
            val speech = getString(R.string.tts_confirm_dial, displayName)
            _uiState.update { it.copy(
                assistantState = AssistantState.WaitingForConfirmation(phone, displayName)
            ) }
            speakOnly(speech)
        }
    }

    // ── Sub-interactions ──────────────────────────────────────────────────────

    private fun handleSelection(text: String, state: AssistantState.WaitingForSelection) {
        viewModelScope.launch {
            val raw  = HebrewNumberNormalizer.normalize(text)
            val idx  = IntentRecognizer.recognizeSelection(raw)
                        ?: IntentRecognizer.recognizeSelection(text)

            if (idx != null && idx in 1..state.candidates.size) {
                val selected = state.candidates[idx - 1].contact
                when (state.pendingAction) {
                    PendingAction.Dial -> initiateCallOrConfirm(
                        selected.normalizedPhone(), selected.name
                    )
                }
            } else {
                _uiState.update { it.copy(assistantState = state) }  // stay in selection
                speakOnly(getString(R.string.tts_invalid_selection))
            }
        }
    }

    private fun handleConfirmation(text: String, state: AssistantState.WaitingForConfirmation) {
        viewModelScope.launch {
            when (IntentRecognizer.recognizeConfirmation(text)) {
                true  -> performCall(state.phoneNumber)
                false -> speakThenListen(getString(R.string.tts_call_cancelled))
                null  -> {
                    _uiState.update { it.copy(assistantState = state) } // stay
                    speakOnly(getString(R.string.tts_say_yes_or_no))
                }
            }
        }
    }

    // ── Dictation ─────────────────────────────────────────────────────────────

    private fun enterDictation() {
        _uiState.update { it.copy(assistantState = AssistantState.Dictating(""), dictationText = "") }
        speakOnly(getString(R.string.tts_dictation_on))
    }

    private fun appendDictation(text: String, state: AssistantState.Dictating) {
        // Check for stop command first
        val stopIntent = IntentRecognizer.recognizeCommand(
            HebrewNumberNormalizer.normalize(text), text)
        if (stopIntent is IntentResult.StopDictation) {
            speakThenListen(getString(R.string.tts_dictation_off))
            return
        }

        val updated = if (state.accumulatedText.isEmpty()) text
                      else "${state.accumulatedText} $text"
        _uiState.update { it.copy(
            assistantState = AssistantState.Dictating(updated),
            dictationText  = updated
        ) }
        // Restart listening immediately for next chunk
        viewModelScope.launch(Dispatchers.Main) { restartListening() }
    }

    // ── Call ──────────────────────────────────────────────────────────────────

    private suspend fun performCall(phoneNumber: String) {
        val speech = getString(R.string.tts_calling, phoneNumber)
        _uiState.update { it.copy(assistantState = AssistantState.Speaking(speech)) }
        ttsManager.speak(speech)
        delay(1000)   // slight pause so TTS finishes before app leaves foreground
        _effects.emit(UiEffect.LaunchCall(phoneNumber))
    }

    // ── TTS helpers ───────────────────────────────────────────────────────────

    /** Speak text; STT will auto-restart when TTS finishes (via onComplete). */
    private fun speakThenListen(text: String) {
        _uiState.update { it.copy(assistantState = AssistantState.Speaking(text)) }
        ttsManager.speak(text)
    }

    /** Speak text and keep the current non-Idle state after TTS finishes. */
    private fun speakOnly(text: String) {
        val prevState = _uiState.value.assistantState
        _uiState.update { it.copy(assistantState = AssistantState.Speaking(text)) }
        // Override onComplete to restore previous state then restart STT
        ttsManager.onComplete = {
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.update { it.copy(assistantState = prevState) }
                delay(350)
                restartListening()
            }
        }
        ttsManager.speak(text)
    }

    // ── Contact loading ───────────────────────────────────────────────────────

    private suspend fun loadContacts() {
        val loaded = contactRepository.getAllContacts()
        contacts = loaded
        _uiState.update { it.copy(contactCount = loaded.size) }
        Log.i(TAG, "Loaded ${loaded.size} contacts")
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun getString(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    /** Normalise raw dB RMS to 0.0–1.0 for the waveform animation. */
    private fun rmsNormalize(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.Main) {
            speechManager.destroy()
            ttsManager.destroy()
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val a = app as App
            return HomeViewModel(
                application        = app,
                contactRepository  = a.contactRepository,
                speechManager      = SpeechRecognitionManager(app),
                ttsManager         = TtsManager(app),
                prefs              = a.preferencesManager,
                contactMatcher     = ContactMatcher(),
            ) as T
        }
    }
}
