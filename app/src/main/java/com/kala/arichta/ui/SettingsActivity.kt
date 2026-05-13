package com.kala.arichta.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.kala.arichta.AppPreferences
import com.kala.arichta.R

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

            // Persist permission so we can read on next launch
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            // Get actual file path if possible, otherwise store URI string
            val path = getRealPathFromUri(uri) ?: uri.toString()
            prefs.modelPath = path

            Toast.makeText(requireContext(),
                getString(R.string.model_selected, path),
                Toast.LENGTH_SHORT
            ).show()
            updateModelSummary()
        }
    }

    private fun updateModelSummary() {
        val path = prefs.modelPath
        findPreference<Preference>("pick_model")?.summary =
            if (path.isNotEmpty()) path
            else getString(R.string.no_model_selected)
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        return try {
            // For file:// URIs
            if (uri.scheme == "file") return uri.path

            // For content:// check if it's in our external files
            val extDir = requireContext().getExternalFilesDir(null)
            if (extDir != null && uri.path?.contains(extDir.name) == true) {
                DocumentsContract.getDocumentId(uri)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
