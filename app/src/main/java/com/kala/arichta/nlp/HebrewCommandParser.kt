package com.kala.arichta.nlp

/**
 * Strips Hebrew natural-language call/dial command prefixes from a transcription,
 * leaving only the target (contact name or phone number).
 *
 * Examples:
 *   "התקשר לדוד כהן"      → "דוד כהן"
 *   "אני רוצה להתקשר לאמא" → "אמא"
 *   "תחייג לאבא"           → "אבא"
 *   "חייג אפס חמש שתיים"   → "אפס חמש שתיים"
 *   "דוד כהן"              → "דוד כהן"   (no prefix — returned as-is)
 */
object HebrewCommandParser {

    // Order matters: longer / more-specific patterns first
    private val CALL_PREFIXES = listOf(
        "אני רוצה להתקשר עם",
        "אני רוצה להתקשר אל",
        "אני רוצה להתקשר ל",
        "אני רוצה להתקשר",
        "אני צריך להתקשר ל",
        "אני צריך להתקשר",
        "בבקשה התקשר אל",
        "בבקשה התקשר ל",
        "בבקשה חייג אל",
        "בבקשה חייג ל",
        "בבקשה תחייג ל",
        "אנא התקשר אל",
        "אנא התקשר ל",
        "אנא חייג אל",
        "אנא חייג ל",
        "תתקשר אל",
        "תתקשר ל",
        "להתקשר אל",
        "להתקשר ל",
        "התקשר אל",
        "התקשר ל",
        "התקשר",
        "תחייג אל",
        "תחייג ל",
        "תחייג",
        "לחייג אל",
        "לחייג ל",
        "לחייג",
        "חייג אל",
        "חייג ל",
        "חייג",
        "לקרוא ל",
        "קרא ל"
    )

    /**
     * Returns the cleaned query with any command prefix removed.
     * The result is trimmed; if nothing matched the original is returned unchanged.
     */
    fun extractTarget(raw: String): String {
        val text = raw.trim()
        for (prefix in CALL_PREFIXES) {
            if (text.startsWith(prefix, ignoreCase = false)) {
                return text.removePrefix(prefix).trim()
            }
        }
        return text
    }

    /**
     * Returns true if the text contains a recognisable call/dial command word,
     * so the UI can show "📞 Calling …" instead of the raw transcription.
     */
    fun hasCallIntent(raw: String): Boolean {
        val text = raw.trim()
        return CALL_PREFIXES.any { text.startsWith(it, ignoreCase = false) }
    }
}
