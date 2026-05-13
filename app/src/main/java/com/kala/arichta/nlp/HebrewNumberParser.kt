package com.kala.arichta.nlp

/**
 * Converts Hebrew number words to digit strings.
 *
 * Examples:
 *   "אפס חמש שתיים שלוש" → "0523"
 *   "כוכבית שמונה" → "*8"
 *   "חמישים ושתיים" → "52"  (compound numbers)
 *
 * Handles:
 *   - Individual digits: אפס=0, אחת/אחד=1 … תשע=9
 *   - Tens: עשר=10, עשרים=20 … תשעים=90
 *   - Hundreds: מאה=100, מאתיים=200
 *   - Special: כוכבית=*, סולמית/#=# 
 *   - Common phone words: חירום=112
 */
object HebrewNumberParser {

    // Single digits
    private val DIGIT_MAP = mapOf(
        "אפס"      to "0",
        "אפס"      to "0",
        "אחת"      to "1",
        "אחד"      to "1",
        "שתיים"    to "2",
        "שניים"    to "2",
        "שתים"     to "2",
        "שני"      to "2",
        "שלוש"     to "3",
        "שלושה"    to "3",
        "שלש"      to "3",
        "ארבע"     to "4",
        "ארבעה"    to "4",
        "חמש"      to "5",
        "חמישה"    to "5",
        "שש"       to "6",
        "שישה"     to "6",
        "שבע"      to "7",
        "שבעה"     to "7",
        "שמונה"    to "8",
        "שמוֹנה"   to "8",
        "תשע"      to "9",
        "תשעה"     to "9"
    )

    // Tens (for number construction)
    private val TENS_MAP = mapOf(
        "עשר"      to 10,
        "עשרה"     to 10,
        "עשרים"    to 20,
        "שלושים"   to 30,
        "ארבעים"   to 40,
        "חמישים"   to 50,
        "שישים"    to 60,
        "שבעים"    to 70,
        "שמונים"   to 80,
        "תשעים"    to 90
    )

    private val HUNDREDS_MAP = mapOf(
        "מאה"      to 100,
        "מאתיים"   to 200,
        "שלוש מאות" to 300,
        "ארבע מאות" to 400,
        "חמש מאות"  to 500
    )

    // Special characters
    private val SPECIAL_MAP = mapOf(
        "כוכבית"   to "*",
        "כוכב"     to "*",
        "סולמית"   to "#",
        "תריס"     to "#",
        "מספר"     to "",   // "number" – ignore
        "טלפון"    to "",   // "phone" – ignore
        "חייג"     to "",   // "dial" – ignore
        "ל"        to "",   // linking word
        "את"       to "",
        "ו"        to "",
        "של"       to ""
    )

    // Well-known short codes
    private val SHORTCODES = mapOf(
        "חירום"        to "112",
        "אמבולנס"      to "101",
        "משטרה"        to "100",
        "כבאים"        to "102",
        "מד"           to "101"  // מד"א
    )

    /**
     * Parse a full Hebrew transcript into a phone-number string.
     *
     * Strategy:
     * 1. Check for known short codes
     * 2. Try to parse as a sequence of word-digits (most common for phone numbers)
     * 3. Fall back to compound number parsing
     *
     * Returns null if no numeric content found.
     */
    fun parsePhoneNumber(text: String): String? {
        val normalized = text.trim()
            .replace("[.,!?]".toRegex(), "")
            .replace("\\s+".toRegex(), " ")

        if (normalized.isBlank()) return null

        // Check for shortcodes first
        for ((word, code) in SHORTCODES) {
            if (normalized.contains(word)) return code
        }

        // Tokenize
        val tokens = normalized.split(" ").filter { it.isNotBlank() }

        // Remove filler tokens
        val filtered = tokens.filter { tok ->
            val t = tok.trim()
            SPECIAL_MAP[t] != "" || DIGIT_MAP.containsKey(t) ||
            TENS_MAP.containsKey(t) || HUNDREDS_MAP.containsKey(t) ||
            SPECIAL_MAP.containsKey(t) || t.any { it.isDigit() }
        }

        if (filtered.isEmpty()) return null

        val result = StringBuilder()
        for (tok in filtered) {
            val t = tok.trim()
            when {
                // Already a digit sequence
                t.all { it.isDigit() } -> result.append(t)
                // Special char
                SPECIAL_MAP.containsKey(t) -> {
                    val v = SPECIAL_MAP[t]!!
                    if (v.isNotEmpty()) result.append(v)
                }
                // Single digit word
                DIGIT_MAP.containsKey(t) -> result.append(DIGIT_MAP[t])
                // Tens
                TENS_MAP.containsKey(t) -> result.append(TENS_MAP[t].toString())
                // Hundreds → multi-digit
                HUNDREDS_MAP.containsKey(t) -> result.append(HUNDREDS_MAP[t].toString())
                // "ו" prefix compound (וחמש = and-five)
                t.startsWith("ו") && DIGIT_MAP.containsKey(t.substring(1)) ->
                    result.append(DIGIT_MAP[t.substring(1)])
                t.startsWith("ו") && TENS_MAP.containsKey(t.substring(1)) ->
                    result.append(TENS_MAP[t.substring(1)].toString())
            }
        }

        return if (result.isEmpty()) null else result.toString()
    }

    /**
     * Check whether the transcript looks like a phone number request
     * (contains digit words or special number indicators).
     */
    fun looksLikeNumber(text: String): Boolean {
        val tokens = text.split(" ")
        return tokens.any { tok ->
            DIGIT_MAP.containsKey(tok) ||
            TENS_MAP.containsKey(tok) ||
            SPECIAL_MAP.containsKey(tok) ||
            SHORTCODES.containsKey(tok) ||
            tok.any { it.isDigit() }
        }
    }

    /**
     * Try to extract which list item number the user said
     * (for disambiguation: "אחד", "שתיים", etc.)
     */
    fun parseListSelection(text: String): Int? {
        val normalized = text.trim()
        val ordinals = mapOf(
            "אחד" to 1, "אחת" to 1, "ראשון" to 1, "ראשונה" to 1, "1" to 1,
            "שתיים" to 2, "שניים" to 2, "שני" to 2, "שניה" to 2, "2" to 2,
            "שלוש" to 3, "שלושה" to 3, "שלישי" to 3, "3" to 3,
            "ארבע" to 4, "ארבעה" to 4, "רביעי" to 4, "4" to 4,
            "חמש" to 5, "חמישה" to 5, "חמישי" to 5, "5" to 5
        )
        for ((word, idx) in ordinals) {
            if (normalized.contains(word)) return idx
        }
        return null
    }
}
