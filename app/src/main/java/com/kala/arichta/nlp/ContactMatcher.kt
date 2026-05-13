package com.kala.arichta.nlp

import android.util.Log
import com.kala.arichta.contacts.Contact
import me.xdrop.fuzzywuzzy.FuzzySearch

/**
 * Fuzzy contact matching for Hebrew names.
 *
 * Uses token-set ratio to handle partial names, re-ordered words,
 * and nickname/shortened versions.
 */
object ContactMatcher {

    private const val TAG = "ContactMatcher"
    private const val MATCH_THRESHOLD = 60  // minimum score (0-100)

    data class MatchResult(
        val contact: Contact,
        val score: Int
    )

    /**
     * Find contacts matching the spoken name.
     * Returns sorted list (best match first), filtered by threshold.
     *
     * If the list has 1 item → auto-call it.
     * If multiple → show disambiguation UI.
     * If empty → no match found.
     */
    fun findMatches(query: String, contacts: List<Contact>): List<MatchResult> {
        if (query.isBlank() || contacts.isEmpty()) return emptyList()

        val cleanQuery = query.trim()
        Log.d(TAG, "Matching '$cleanQuery' against ${contacts.size} contacts")

        val results = contacts.mapNotNull { contact ->
            // Use token-set ratio: handles "משה כהן" matching "כהן משה" or just "משה"
            val score = FuzzySearch.tokenSetRatio(
                cleanQuery.lowercase(),
                contact.name.lowercase()
            )
            if (score >= MATCH_THRESHOLD) MatchResult(contact, score) else null
        }

        return results
            .sortedByDescending { it.score }
            .distinctBy { it.contact.name.lowercase() }
            .take(5) // max 5 disambiguation options
    }

    /**
     * Find exact or near-exact match (score >= 90).
     * Used to skip disambiguation for very confident matches.
     */
    fun findExactMatch(query: String, contacts: List<Contact>): Contact? {
        val matches = findMatches(query, contacts)
        val top = matches.firstOrNull() ?: return null
        return if (top.score >= 90) top.contact else null
    }
}
