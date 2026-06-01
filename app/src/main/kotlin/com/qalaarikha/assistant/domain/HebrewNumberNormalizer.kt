package com.qalaarikha.assistant.domain

/**
 * Pre-processing stage that converts spoken Hebrew number words into digits
 * and special dialing symbols before intent recognition.
 *
 * Design follows the Dicio Assistant pattern: normalise the utterance first,
 * then pass the cleaned string to the intent recogniser.
 *
 * Examples:
 *   "אפס חמש שתיים שבע" → "0527"  (joined because all tokens are digits)
 *   "כוכבית אפס חמש"    → "*05"
 *   "חייג לאפס חמש"     → "חייג ל05"  (only the number tokens are replaced)
 */
object HebrewNumberNormalizer {

    // ── Lookup tables ─────────────────────────────────────────────────────────

    private val WORD_TO_DIGIT: Map<String, String> = mapOf(
        // Digits 0-9 (masculine + feminine forms where applicable)
        "אפס"     to "0",
        "אחד"     to "1",
        "אחת"     to "1",
        "שתיים"   to "2",
        "שניים"   to "2",
        "שני"     to "2",
        "שתי"     to "2",
        "שלוש"    to "3",
        "שלושה"   to "3",
        "ארבע"    to "4",
        "ארבעה"   to "4",
        "חמש"     to "5",
        "חמישה"   to "5",
        "שש"      to "6",
        "ששה"     to "6",
        "שבע"     to "7",
        "שבעה"    to "7",
        "שמונה"   to "8",
        "תשע"     to "9",
        "תשעה"    to "9",
        // Common multi-digit numbers used in phone-menu codes
        "מאה"     to "100",
        "מאתיים"  to "200",
        "אלף"     to "1000",
        // Special dialing symbols
        "כוכבית"  to "*",
        "כוכב"    to "*",
        "סטאר"    to "*",
        "סולמית"  to "#",
        "האש"     to "#",
        "גריד"    to "#",
    )

    // Characters that are valid in a dialable sequence
    private val DIAL_CHARS = setOf('0','1','2','3','4','5','6','7','8','9','*','#','+')

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Replaces every recognised number word in [text] with its digit/symbol
     * equivalent while leaving all other words unchanged.
     *
     * Returns the transformed text; whitespace between tokens is preserved.
     */
    fun normalize(text: String): String {
        return text.trim()
            .split(Regex("\\s+"))
            .joinToString(" ") { word -> WORD_TO_DIGIT[word] ?: word }
    }

    /**
     * Given the *target* portion of a dial command (the part after "ל"),
     * normalises it and decides whether it resolves to a phone number string.
     *
     * Returns the normalised phone string, or null if it is not a phone number.
     *
     * Logic: after normalisation, if every token consists solely of dial characters,
     * the tokens are joined and the result is treated as a phone number.
     */
    fun resolvePhoneNumber(target: String): String? {
        val normalized = normalize(target)
        val tokens = normalized.trim().split(Regex("\\s+"))

        // All tokens must be pure dial-char sequences
        if (tokens.isEmpty()) return null
        val allDial = tokens.all { token -> token.isNotEmpty() && token.all { it in DIAL_CHARS } }
        if (!allDial) return null

        val joined = tokens.joinToString("")
        // Must have at least 4 characters to be a sensible phone number / code
        return if (joined.length >= 4) joined else null
    }

    /**
     * Strip a leading "ל" prepositional prefix from a captured target string.
     * In Hebrew, "ל" attaches to the following word: "למשה" → "משה".
     * If the target starts with the "ל" character AND has more content, strip it.
     */
    fun stripLeadingLamed(target: String): String {
        val t = target.trim()
        return if (t.startsWith("ל") && t.length > 1) t.substring(1) else t
    }
}
