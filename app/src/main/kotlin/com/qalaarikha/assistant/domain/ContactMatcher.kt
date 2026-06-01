package com.qalaarikha.assistant.domain

import com.qalaarikha.assistant.data.model.Contact
import com.qalaarikha.assistant.data.model.ContactCandidate

/**
 * Fuzzy Hebrew contact-name matcher.
 *
 * Strategy (per query/name pair, best score wins):
 *  a) Exact substring match            → 1.00
 *  b) Normalised Levenshtein similarity of (query vs full name)
 *  c) Normalised Levenshtein similarity of (query vs each name token)
 *
 * Hebrew phonetic normalisation collapses phonetically near-identical letters
 * so that, e.g., "מושה" (misspelling) matches "משה" (Moses).
 *
 * @param threshold  Minimum score in [0.0, 1.0] to consider a match.
 *                   Corresponds to the "fuzzyThreshold" user setting / 100.
 */
class ContactMatcher(private var threshold: Float = 0.80f) {

    /** Update threshold at runtime when the user changes the setting. */
    fun setThreshold(value: Float) {
        threshold = value.coerceIn(0f, 1f)
    }

    /**
     * Searches [contacts] for entries whose name fuzzy-matches [query].
     * Returns up to [maxResults] candidates sorted by descending score,
     * all with score ≥ [threshold].
     */
    fun findMatches(
        query: String,
        contacts: List<Contact>,
        maxResults: Int = 3,
    ): List<ContactCandidate> {
        val normQuery = phoneticallyNormalize(query.trim())

        return contacts
            .mapNotNull { contact ->
                val score = bestScore(normQuery, contact.name)
                if (score >= threshold) ContactCandidate(contact, score) else null
            }
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private fun bestScore(normQuery: String, name: String): Float {
        val normFull   = phoneticallyNormalize(name)
        val nameParts  = name.split(Regex("\\s+")).map { phoneticallyNormalize(it) }

        var best = 0f

        // (a) Exact substring
        if (normFull.contains(normQuery) || normQuery.contains(normFull))
            best = maxOf(best, 0.97f)

        // (b) Query vs full name
        best = maxOf(best, levenshteinSimilarity(normQuery, normFull))

        // (c) Query vs each name token (first name / last name)
        for (part in nameParts) {
            best = maxOf(best, levenshteinSimilarity(normQuery, part))
        }

        return best
    }

    // ── Levenshtein ───────────────────────────────────────────────────────────

    private fun levenshteinSimilarity(a: String, b: String): Float {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1f
        return 1f - levenshteinDistance(a, b).toFloat() / maxLen
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length; val n = s2.length
        // Optimise: keep only two rows
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                curr[j] = if (s1[i - 1] == s2[j - 1]) prev[j - 1]
                          else 1 + minOf(prev[j - 1], prev[j], curr[j - 1])
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }

    // ── Hebrew phonetic normalisation ─────────────────────────────────────────
    //
    // Groups of letters that are acoustically similar in Modern Hebrew:
    //   Silent / vowel carriers : א ע          → 'ע'
    //   Velar / uvular          : כ ח ק        → 'ח'
    //   Dental stops            : ת ט          → 'ט'
    //   Sibilants               : ש שׁ שׂ ס ז  → 'ש' (approximate)
    //   Final forms             → base forms
    //   Dagesh / nikud          → stripped

    private fun phoneticallyNormalize(text: String): String {
        return text
            // Handle composed characters for shin/sin (Unicode normalization)
            .replace("\u05E9\u05C1", "ש")   // שׁ (shin with dot) → ש
            .replace("\u05E9\u05C2", "ש")   // שׂ (sin  with dot) → ש
            .map { ch ->
                when (ch) {
                    'א', 'ע'      -> 'ע'    // both are silent/glottal
                    'כ', 'ח', 'ק' -> 'ח'    // all velars/pharyngeals
                    'ת', 'ט'      -> 'ט'    // both dental stops
                    'ס'           -> 'ש'    // samekh ≈ shin
                    // Final forms → base forms
                    'ך'           -> 'כ'
                    'ם'           -> 'מ'
                    'ן'           -> 'נ'
                    'ף'           -> 'פ'
                    'ץ'           -> 'צ'
                    else          -> ch
                }
            }
            // Strip nikud (Hebrew diacritics U+05B0–U+05C7) and spaces
            .filter { ch -> ch.code !in 0x05B0..0x05C7 && ch != ' ' }
            .joinToString("")
    }
}
