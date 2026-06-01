package com.qalaarikha.assistant.ui.home

import com.qalaarikha.assistant.data.model.AssistantState
import com.qalaarikha.assistant.data.model.Contact

/**
 * Snapshot of everything the [HomeScreen] needs to render.
 * Emitted as an immutable [StateFlow] from [HomeViewModel].
 */
data class HomeUiState(
    /** Current voice-assistant state machine node. */
    val assistantState: AssistantState = AssistantState.Idle,

    /** Live partial transcript shown while the user is speaking. */
    val partialText: String = "",

    /** Normalised microphone level for the waveform animation (0.0–1.0). */
    val rmsLevel: Float = 0f,

    /** Total number of contacts loaded across all sources. */
    val contactCount: Int = 0,

    /** Text accumulated during dictation mode. */
    val dictationText: String = "",

    /** True if the device lacks the Hebrew offline speech model. */
    val showOfflineWarning: Boolean = false,
)

/**
 * One-shot UI side-effects emitted by [HomeViewModel] via [SharedFlow].
 * Handled in a [LaunchedEffect] inside [HomeScreen].
 */
sealed class UiEffect {
    /** Launch the phone dialer / call app. */
    data class LaunchCall(val phoneNumber: String) : UiEffect()

    /** Show a brief snackbar message. */
    data class ShowSnackbar(val message: String) : UiEffect()

    /** Copy dictation text to the clipboard. */
    data class CopyToClipboard(val text: String) : UiEffect()
}
