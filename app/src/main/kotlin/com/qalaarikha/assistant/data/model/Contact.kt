package com.qalaarikha.assistant.data.model

/**
 * Unified contact record merged from any of the three sources.
 *
 * @param id          Unique key (prefixed by source: "ac_", "bt_", "file_").
 * @param name        Display name as read from the source.
 * @param phoneNumber Raw phone string from the source.
 * @param source      Which source provided this record.
 */
data class Contact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val source: ContactSource
) {
    /** Phone number stripped of spaces, dashes, parentheses for dialing. */
    fun normalizedPhone(): String =
        phoneNumber.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
}

enum class ContactSource {
    /** Android ContactsContract database. */
    ANDROID_CONTACTS,

    /** Contacts synced via Bluetooth PBAP profile. */
    BLUETOOTH,

    /** Local TXT file at Android/data/<app>/files/contacts.txt. */
    TXT_FILE
}
