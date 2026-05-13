package com.kala.arichta.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.kala.arichta.AppPreferences
import com.kala.arichta.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: AppPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        prefs = AppPreferences(requireContext())

        setupModelPicker()
        setupAudioFocusToggle()
    }

    private fun setupModelPicker() {
        findPreference<Preference>("pick_model")?.setOnPreferenceClickListener {
            openFilePicker()
            true
        }
        updateModelSummary()
    }

    private fun setupAudioFocusToggle() {
        findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_AUDIO_FOCUS)?.apply {
            isChecked = prefs.audioFocusEnabled
            setOnPreferenceChangeListener { _, newValue ->
                prefs.audioFocusEnabled = newValue as Boolean
                true
            }
        }

        findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_AUDIO_DUCK)?.apply {
            isChecked = prefs.audioDuckOnly
            setOnPreferenceChangeListener { _, newValue ->
                prefs.audioDuckOnly = newValue as Boolean
                true
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Accept both GGUF and GGML (.bin) model files
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "application/gguf",
                "*/*"
            ))
        }
        filePickerLauncher.launch(intent)
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri = result.data?.data ?: return@registerForActivityResult

            val fileName = getFileNameFromUri(uri) ?: "whisper_model.bin"

            // Validate extension — accept .gguf and .bin (GGML) files
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext != "gguf" && ext != "bin") {
                Toast.makeText(requireContext(),
                    "Please select a .gguf or .bin model file",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }

            // Show copying indicator
            findPreference<Preference>("pick_model")?.summary = "Copying model file..."

            // Copy to app's private files directory so C++ can read the real path
            lifecycleScope.launch {
                val destFile = copyModelToAppDir(uri, fileName)
                if (destFile != null) {
                    prefs.modelPath = destFile.absolutePath
                    updateModelSummary()
                    Toast.makeText(requireContext(),
                        getString(R.string.model_selected, destFile.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    updateModelSummary()
                    Toast.makeText(requireContext(),
                        "Failed to copy model file. Try placing it in:\n${requireContext().getExternalFilesDir(null)?.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Copies the picked file (content:// or file://) into the app's external files dir.
     * Returns the destination File on success, null on failure.
     * Supports both GGUF and GGML (.bin) formats.
     */
    private suspend fun copyModelToAppDir(uri: Uri, fileName: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val destDir = requireContext().getExternalFilesDir(null)
                    ?: requireContext().filesDir
                val destFile = File(destDir, fileName)

                // If already in the right place, skip copy
                if (uri.scheme == "file" && uri.path == destFile.absolutePath) {
                    return@withContext destFile
                }

                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destFile
            } catch (e: Exception) {
                null
            }
        }

    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun updateModelSummary() {
        val path = prefs.modelPath
        findPreference<Preference>("pick_model")?.summary =
            if (path.isNotEmpty()) File(path).name
            else getString(R.string.no_model_selected)
    }
}
