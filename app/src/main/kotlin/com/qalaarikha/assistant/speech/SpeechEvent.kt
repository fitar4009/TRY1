package com.qalaarikha.assistant.speech

/**
 * Events emitted by [SpeechRecognitionManager] via its [SharedFlow].
 * The ViewModel maps these to [AssistantState] transitions.
 */
sealed class SpeechEvent {

    /** Recogniser is initialised and ready; mic is now open. */
    object Ready : SpeechEvent()

    /** Partial (interim) result – shown in the UI as the user speaks. */
    data class Partial(val text: String) : SpeechEvent()

    /** Final result – one or more alternative hypotheses, best-first. */
    data class Final(val results: List<String>) : SpeechEvent()

    /** Microphone audio level changed; [rmsdB] in range ~[-2, 10] dBFS. */
    data class RmsChanged(val rmsdB: Float) : SpeechEvent()

    /** User stopped speaking; recogniser is processing. */
    object EndOfSpeech : SpeechEvent()

    /** Recognition failed. [errorCode] maps to [android.speech.SpeechRecognizer] constants. */
    data class Error(val errorCode: Int) : SpeechEvent()
}
