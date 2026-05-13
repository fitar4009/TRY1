package com.kala.arichta.contacts

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads contacts with priority:
 *   1. Bluetooth PBAP-synced contacts (via ContactsContract if phone is paired+synced)
 *   2. Local contacts.txt file in app external files dir
 *
 * Format of contacts.txt:
 *   שם=מספר
 *   Moshe Cohen=0521234567
 *   חנה לוי=0531112233
 */
class ContactRepository(private val context: Context) {

    companion object {
        private const val TAG = "ContactRepository"
        const val CONTACTS_FILE_NAME = "contacts.txt"
    }

    /**
     * Load contacts using priority logic.
     * Returns empty list if nothing is available.
     */
    suspend fun loadContacts(): List<Contact> = withContext(Dispatchers.IO) {
        // Priority 1: Bluetooth synced contacts
        val btContacts = loadBluetoothContacts()
        if (btContacts.isNotEmpty()) {
            Log.i(TAG, "Using ${btContacts.size} Bluetooth contacts")
            return@withContext btContacts
        }

        // Priority 2: Local file
        val localContacts = loadLocalFileContacts()
        Log.i(TAG, "Using ${localContacts.size} local file contacts")
        localContacts
    }

    /**
     * Attempt to read contacts from Bluetooth-synced account via ContentProvider.
     * Works when the HU or phone has paired and PBAP sync is active.
     */
    private fun loadBluetoothContacts(): List<Contact> {
        return try {
            val contacts = mutableListOf<Contact>()

            // Check if any Bluetooth device is connected
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                    as? BluetoothManager
            val btAdapter = btManager?.adapter

            if (btAdapter == null || !btAdapter.isEnabled) {
                Log.d(TAG, "Bluetooth not enabled")
                return emptyList()
            }

            // Query synced contacts from the system ContentProvider.
            // On Android Head Units, PBAP sync populates ContactsContract.
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx  = c.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (c.moveToNext()) {
                    val name   = c.getString(nameIdx) ?: continue
                    val number = c.getString(numIdx)  ?: continue
                    val cleaned = cleanNumber(number)
                    if (name.isNotBlank() && cleaned.isNotBlank()) {
                        contacts.add(Contact(name.trim(), cleaned, Contact.Source.BLUETOOTH))
                    }
                }
            }

            contacts
        } catch (e: SecurityException) {
            Log.w(TAG, "No READ_CONTACTS permission: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "BT contact load error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Load contacts from [package_external_files]/contacts.txt
     */
    private fun loadLocalFileContacts(): List<Contact> {
        val dir  = context.getExternalFilesDir(null) ?: return emptyList()
        val file = File(dir, CONTACTS_FILE_NAME)

        if (!file.exists()) {
            Log.d(TAG, "contacts.txt not found at ${file.absolutePath}")
            return emptyList()
        }

        return try {
            file.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx < 1) return@mapNotNull null
                    val name   = trimmed.substring(0, eqIdx).trim()
                    val number = trimmed.substring(eqIdx + 1).trim()
                    val cleaned = cleanNumber(number)
                    if (name.isNotBlank() && cleaned.isNotBlank())
                        Contact(name, cleaned, Contact.Source.LOCAL)
                    else null
                }
        } catch (e: Exception) {
            Log.e(TAG, "Local file read error: ${e.message}")
            emptyList()
        }
    }

    private fun cleanNumber(raw: String): String {
        // Keep digits, +, *, #
        return raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }

    fun getContactsFilePath(): String {
        val dir = context.getExternalFilesDir(null)
        return "${dir?.absolutePath}/$CONTACTS_FILE_NAME"
    }
}
