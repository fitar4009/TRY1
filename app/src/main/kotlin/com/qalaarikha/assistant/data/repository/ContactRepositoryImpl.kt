package com.qalaarikha.assistant.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.qalaarikha.assistant.data.model.Contact
import com.qalaarikha.assistant.data.model.ContactSource
import com.qalaarikha.assistant.data.preferences.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ContactRepository"

/**
 * Merges contacts from three independent sources:
 *  1. Android ContactsContract (device phonebook)
 *  2. Bluetooth-synced contacts (PBAP account type in the same DB)
 *  3. A user-owned TXT file: Name=PhoneNumber per line
 *
 * Deduplication is performed on normalized phone numbers so that the same
 * contact imported from multiple sources appears only once, preferring
 * ANDROID_CONTACTS > BLUETOOTH > TXT_FILE.
 */
class ContactRepositoryImpl(
    private val context: Context,
    private val prefs: PreferencesManager,
) : ContactRepository {

    override suspend fun getAllContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val android   = readAndroidContacts()
        val bluetooth = readBluetoothSyncedContacts()
        val file      = readFileContacts()

        // Merge with priority: Android > Bluetooth > File
        // Key = normalised phone number; first-seen wins.
        val seen  = mutableSetOf<String>()
        val merged = mutableListOf<Contact>()

        for (contact in android + bluetooth + file) {
            val key = contact.normalizedPhone()
            if (key.isNotBlank() && seen.add(key)) {
                merged.add(contact)
            }
        }
        merged
    }

    override suspend fun reloadFileContacts(): List<Contact> = withContext(Dispatchers.IO) {
        readFileContacts()
    }

    // ── Source 1 – Android ContactsContract ──────────────────────────────────

    private fun readAndroidContacts(): List<Contact> {
        if (!hasContactsPermission()) return emptyList()

        val contacts = mutableListOf<Contact>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
        ) ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                val id    = it.getString(0) ?: continue
                val name  = it.getString(1)?.trim() ?: continue
                val phone = it.getString(2)?.trim() ?: continue
                if (name.isBlank() || phone.isBlank()) continue
                contacts.add(Contact("ac_$id", name, phone, ContactSource.ANDROID_CONTACTS))
            }
        }
        return contacts
    }

    // ── Source 2 – Bluetooth PBAP synced contacts ─────────────────────────────

    /**
     * Bluetooth phones synced via PBAP appear in ContactsContract with an
     * account type containing "bluetooth".  We query raw_contacts to find
     * those IDs, then look up names and numbers in the Data table.
     */
    private fun readBluetoothSyncedContacts(): List<Contact> {
        if (!hasContactsPermission()) return emptyList()

        val contacts = mutableListOf<Contact>()
        try {
            // Step 1: find raw_contact IDs with a bluetooth account type
            val rawIds = mutableSetOf<Long>()
            val rawCursor = context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID,
                        ContactsContract.RawContacts.ACCOUNT_TYPE),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} LIKE ?",
                arrayOf("%bluetooth%"),
                null
            )
            rawCursor?.use { c -> while (c.moveToNext()) rawIds.add(c.getLong(0)) }

            if (rawIds.isEmpty()) return emptyList()

            // Step 2: query phone numbers for those contact IDs
            val inClause = rawIds.joinToString(",")
            val phoneCursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($inClause)",
                null, null
            )
            phoneCursor?.use { c ->
                while (c.moveToNext()) {
                    val id    = c.getString(0) ?: continue
                    val name  = c.getString(1)?.trim() ?: continue
                    val phone = c.getString(2)?.trim() ?: continue
                    if (name.isBlank() || phone.isBlank()) continue
                    contacts.add(Contact("bt_$id", name, phone, ContactSource.BLUETOOTH))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth contacts read failed", e)
        }
        return contacts
    }

    // ── Source 3 – Local TXT file ─────────────────────────────────────────────

    /**
     * Reads a plain-text file in the format:
     *   Name=PhoneNumber
     * One contact per line; blank lines and lines without '=' are ignored.
     *
     * Default path: Android/data/com.qalaarikha.assistant/files/contacts.txt
     * (accessible without extra permissions on the device that owns the app)
     */
    private suspend fun readFileContacts(): List<Contact> {
        val customPath = prefs.contactsFilePath.first()
        val file = if (customPath.isNotBlank()) File(customPath)
                   else File(context.getExternalFilesDir(null), "contacts.txt")

        if (!file.exists()) {
            Log.i(TAG, "Contacts file not found: ${file.absolutePath}")
            return emptyList()
        }

        return try {
            file.readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() && it.contains('=') }
                .mapIndexedNotNull { idx, line ->
                    val eqIdx = line.indexOf('=')
                    val name  = line.substring(0, eqIdx).trim()
                    val phone = line.substring(eqIdx + 1).trim()
                    if (name.isBlank() || phone.isBlank()) null
                    else Contact("file_$idx", name, phone, ContactSource.TXT_FILE)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read contacts file", e)
            emptyList()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
}
