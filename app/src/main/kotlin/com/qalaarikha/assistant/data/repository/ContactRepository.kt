package com.qalaarikha.assistant.data.repository

import com.qalaarikha.assistant.data.model.Contact

/**
 * Single entry-point for all contact data regardless of source.
 * Implementations merge Android contacts, Bluetooth-synced contacts,
 * and a user-specified TXT file.
 */
interface ContactRepository {

    /**
     * Returns a deduplicated, merged list of contacts from all available sources.
     * Safe to call from a background (IO) coroutine.
     */
    suspend fun getAllContacts(): List<Contact>

    /**
     * Re-reads only the TXT file contacts without touching the Android DB.
     * Useful when the user has edited the file and taps "Reload" in Settings.
     */
    suspend fun reloadFileContacts(): List<Contact>
}
