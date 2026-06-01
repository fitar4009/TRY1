package com.qalaarikha.assistant.domain

import com.qalaarikha.assistant.data.model.IntentResult

/**
 * Stateless intent-recognition engine for the Hebrew voice assistant.
 *
 * Input: a *normalised* utterance (number words already converted to digits
 *        by [HebrewNumberNormalizer]).
 *
 * Output: a sealed [IntentResult] that the ViewModel acts upon.
 *
 * Recognised patterns (in priority order):
 *  1. Dial command   – "חייג ל…" / "התקשר ל…"
 *  2. Dictation on   – "מצב הכתבה" / "התחל הכתבה" / "הכתבה"
 *  3. Dictation off  – "עצור הכתבה" / "בטל הכתבה" / "עצור"
 *  4. Selection      – "1","2","3" / "אחד","שתיים","שלוש" (for disambiguation)
 *  5. Confirmation   – "כן"/"אוקי" (yes) or "לא"/"בטל" (no)
 *  6. Unknown        – none of the above
 */
object IntentRecognizer {

    // ── Regex patterns ────────────────────────────────────────────────────────

    /** Matches "חייג ל…", "התקשר ל…", "תתקשר ל…", with ל optionally attached. */
    private val DIAL_REGEX = Regex(
        """^(?:חייג|התקשר|תתקשר|חייגי)\s+ל(.+)$""",
        RegexOption.IGNORE_CASE
    )

    /** Selection words mapping to 1-based index. */
    private val SELECTION_MAP = mapOf(
        "1"       to 1, "אחד" to 1, "אחת" to 1,
        "2"       to 2, "שתיים" to 2, "שניים" to 2, "שני" to 2,
        "3"       to 3, "שלוש" to 3, "שלושה" to 3,
    )

    /** Confirmation – affirmative. */
    private val YES_WORDS = setOf("כן", "אוקי", "אוקיי", "בסדר", "בצע", "חייג", "אישור")

    /** Confirmation – negative / cancel. */
    private val NO_WORDS  = setOf("לא", "בטל", "ביטול", "סגור", "עצור")

    /** Dictation ON triggers. */
    private val DICTATION_ON  = setOf("מצב הכתבה", "התחל הכתבה", "הכתבה", "כתוב", "כתיבה")

    /** Dictation OFF triggers. */
    private val DICTATION_OFF = setOf("עצור הכתבה", "בטל הכתבה", "עצור", "סיום הכתבה", "סיים")

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Recognises a top-level command from a *normalised* utterance.
     * Call this for any utterance that is NOT in a sub-interaction state.
     *
     * @param normalised  Text with number words already converted to digits.
     * @param raw         Original utterance (used as fallback display text).
     */
    fun recognizeCommand(normalised: String, raw: String = normalised): IntentResult {
        val t = normalised.trim()

        // 1 – Dictation OFF (check before ON to avoid "עצור" matching "עצור הכתבה" as ON)
        if (DICTATION_OFF.any { t == it || t.startsWith(it) })
            return IntentResult.StopDictation

        // 2 – Dictation ON
        if (DICTATION_ON.any { t == it || t.startsWith(it) })
            return IntentResult.StartDictation

        // 3 – Dial command
        val dialMatch = DIAL_REGEX.find(t)
        if (dialMatch != null) {
            val captured = dialMatch.groupValues[1].trim()
            // The captured portion may start with "ל" (preposition attached to target)
            val target = HebrewNumberNormalizer.stripLeadingLamed(captured).trim()

            // Try to resolve as phone number
            val phone = HebrewNumberNormalizer.resolvePhoneNumber(target)
            return if (phone != null) IntentResult.DialNumber(phone)
            else                      IntentResult.DialContact(target)
        }

        return IntentResult.Unknown
    }

    /**
     * Recognises a numeric selection (1/2/3) while in [WaitingForSelection] state.
     * Returns 1-based index or null if not recognized.
     */
    fun recognizeSelection(text: String): Int? = SELECTION_MAP[text.trim()]

    /**
     * Recognises a yes/no confirmation while in [WaitingForConfirmation] state.
     * Returns true = yes, false = no, null = not recognized.
     */
    fun recognizeConfirmation(text: String): Boolean? {
        val t = text.trim()
        return when {
            YES_WORDS.contains(t) -> true
            NO_WORDS.contains(t)  -> false
            else                  -> null
        }
    }
}
