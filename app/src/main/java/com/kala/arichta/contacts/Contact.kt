package com.kala.arichta.contacts

/**
 * Represents a single contact entry from either Bluetooth PBAP or local file.
 */
data class Contact(
    val name: String,
    val phoneNumber: String,
    val source: Source = Source.LOCAL
) {
    enum class Source { BLUETOOTH, LOCAL }

    override fun toString(): String = "$name ($phoneNumber) [${source.name}]"
}
