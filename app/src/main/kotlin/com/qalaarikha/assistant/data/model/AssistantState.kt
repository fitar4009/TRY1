package com.qalaarikha.assistant.data.model

// ── State machine ────────────────────────────────────────────────────────────

/**
 * All possible states of the voice-assistant state machine.
 *
 * Transitions (happy path):
 *   Idle ──startListening()──► Listening
 *   Listening ──finalResult──► Processing
 *   Processing ──intent=dial──► Speaking ──ttsDone──► Listening
 *   Processing ──multiMatch──► WaitingForSelection ──userPick──► Speaking
 *   Processing ──confirmOff──► WaitingForConfirmation ──yes──► Speaking
 *   Processing ──dictation──►  Dictating ──stopDictation──► Idle
 *   * ──error──► Error ──autoRetry──► Listening
 */
sealed class AssistantState {

    /** Not actively listening; initial state after construction. */
    object Idle : AssistantState()

    /** Microphone is open; waiting for user speech. */
    object Listening : AssistantState()

    /** Final transcript received; computing intent. */
    data class Processing(val transcript: String) : AssistantState()

    /** TTS is playing an audio response; mic is suppressed. */
    data class Speaking(val text: String) : AssistantState()

    /** ≥2 contacts matched; awaiting numeric voice selection (1/2/3). */
    data class WaitingForSelection(
        val candidates: List<ContactCandidate>,
        val pendingAction: PendingAction
    ) : AssistantState()

    /** Exactly 1 match; awaiting yes/no confirmation before dialing. */
    data class WaitingForConfirmation(
        val phoneNumber: String,
        val displayName: String
    ) : AssistantState()

    /** Dictation mode; each utterance is appended to [accumulatedText]. */
    data class Dictating(val accumulatedText: String) : AssistantState()

    /** Something went wrong; [message] is shown to the user. */
    data class Error(val message: String, val errorCode: Int = -1) : AssistantState()
}

// ── Supporting types ──────────────────────────────────────────────────────────

/** A contact paired with its fuzzy-match confidence score (0.0–1.0). */
data class ContactCandidate(
    val contact: Contact,
    val score: Float
)

/** What to do once the user picks a candidate from the disambiguation list. */
sealed class PendingAction {
    object Dial : PendingAction()
}

// ── Intent result ─────────────────────────────────────────────────────────────

/** Structured intent parsed from a normalized utterance. */
sealed class IntentResult {

    /** "חייג ל..." / "התקשר ל..." – target is a contact name. */
    data class DialContact(val query: String) : IntentResult()

    /** "חייג ל..." / "התקשר ל..." – target is a phone number. */
    data class DialNumber(val number: String) : IntentResult()

    /** User said 1/2/3 (or Hebrew equivalent) to pick from a candidate list. */
    data class SelectCandidate(val index: Int) : IntentResult()   // 1-based

    /** User said כן / לא to confirm a pending dial. */
    data class ConfirmDial(val confirmed: Boolean) : IntentResult()

    /** User said "מצב הכתבה" / "התחל הכתבה". */
    object StartDictation : IntentResult()

    /** User said "עצור הכתבה" / "בטל". */
    object StopDictation : IntentResult()

    /** No recognised intent. */
    object Unknown : IntentResult()
}
