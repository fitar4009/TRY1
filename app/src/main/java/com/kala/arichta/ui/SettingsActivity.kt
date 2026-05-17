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
import com.kala.arichta.engine.EngineFactory
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
        setupAudioToggles()
    }

    // ── Model picker ─────────────────────────────────────────────────────────

    private fun setupModelPicker() {
        findPreference<Preference>("pick_model")?.setOnPreferenceClickListener {
            openFilePicker()
            true
        }
        refreshModelSummary()
    }

    private fun openFilePicker() {
        filePickerLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
            }
        )
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val fileName = resolveFileName(uri) ?: "model.bin"
        val ext = fileName.substringAfterLast('.', "").lowercase()

        when (ext) {
            "onnx" -> handleOnnxPick(uri, fileName)
            "bin", "gguf" -> handleGgmlPick(uri, fileName)
            else -> Toast.makeText(
                requireContext(),
                "Select encoder_model.onnx  or a  .bin / .gguf  GGML file",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── GGML path ────────────────────────────────────────────────────────────

    private fun handleGgmlPick(uri: Uri, fileName: String) {
        setPickerSummary("Copying GGML model…")
        lifecycleScope.launch {
            val dest = copyFile(uri, fileName)
            if (dest != null) {
                prefs.modelPath = dest.absolutePath
                refreshModelSummary()
                Toast.makeText(requireContext(), "GGML model ready: ${dest.name}", Toast.LENGTH_SHORT).show()
            } else {
                refreshModelSummary()
                Toast.makeText(requireContext(), "Copy failed — try again", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── ONNX path ────────────────────────────────────────────────────────────

    private fun handleOnnxPick(uri: Uri, fileName: String) {
        if (!fileName.contains("encoder", ignoreCase = true)) {
            Toast.makeText(
                requireContext(),
                "Please pick encoder_model.onnx.\n" +
                "Place encoder_model.onnx, decoder_model.onnx and vocab.json in the same folder first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setPickerSummary("Copying ONNX model files…")

        lifecycleScope.launch {
            val encoderDest = copyFile(uri, "encoder_model.onnx")
            if (encoderDest == null) {
                refreshModelSummary()
                Toast.makeText(requireContext(), "Failed to copy encoder_model.onnx", Toast.LENGTH_LONG).show()
                return@launch
            }

            val siblings      = findSiblingFiles(uri)
            val decoderCopied = copySibling(siblings, "decoder_model.onnx")
            val vocabCopied   = copySibling(siblings, "vocab.json")

            prefs.modelPath = encoderDest.absolutePath
            refreshModelSummary()

            val missing = buildList {
                if (!decoderCopied) add("decoder_model.onnx")
                if (!vocabCopied)   add("vocab.json")
            }

            if (missing.isEmpty()) {
                Toast.makeText(requireContext(),
                    "ONNX model ready (encoder + decoder + vocab)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(),
                    "Encoder copied ✓\nMissing: ${missing.joinToString(", ")}\n" +
                    "Copy them manually to:\n${encoderDest.parent}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun findSiblingFiles(uri: Uri): Map<String, Uri> =
        withContext(Dispatchers.IO) {
            try {
                val uriStr   = uri.toString()
                val sep      = "%2F"
                val lastSep  = uriStr.lastIndexOf(sep).takeIf { it >= 0 }
                    ?: return@withContext emptyMap()
                val dirPart  = uriStr.substring(0, lastSep + sep.length)

                listOf("decoder_model.onnx", "vocab.json").mapNotNull { name ->
                    try {
                        val sibUri = Uri.parse(dirPart + name)
                        requireContext().contentResolver.openInputStream(sibUri)?.close()
                        name.lowercase() to sibUri
                    } catch (e: Exception) { null }
                }.toMap()
            } catch (e: Exception) { emptyMap() }
        }

    private suspend fun copySibling(siblings: Map<String, Uri>, name: String): Boolean {
        val uri = siblings[name.lowercase()] ?: return false
        return copyFile(uri, name) != null
    }

    // ── Generic file copy ────────────────────────────────────────────────────

    private suspend fun copyFile(uri: Uri, destName: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir  = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
                val dest = File(dir, destName)
                requireContext().contentResolver.openInputStream(uri)?.use { i ->
                    dest.outputStream().use { o -> i.copyTo(o) }
                }
                dest
            } catch (e: Exception) { null }
        }

    // ── Audio toggles ────────────────────────────────────────────────────────

    private fun setupAudioToggles() {
        findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_AUDIO_FOCUS)?.apply {
            isChecked = prefs.audioFocusEnabled
            setOnPreferenceChangeListener { _, v -> prefs.audioFocusEnabled = v as Boolean; true }
        }
        findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_AUDIO_DUCK)?.apply {
            isChecked = prefs.audioDuckOnly
            setOnPreferenceChangeListener { _, v -> prefs.audioDuckOnly = v as Boolean; true }
        }
    }

    // ── Summary helpers ──────────────────────────────────────────────────────

    private fun refreshModelSummary() {
        val path = prefs.modelPath
        val summary = if (path.isEmpty()) getString(R.string.no_model_selected)
        else "${File(path).name}\nEngine: ${EngineFactory.describe(path)}"
        setPickerSummary(summary)
    }

    private fun setPickerSummary(text: String) {
        findPreference<Preference>("pick_model")?.summary = text
    }

    private fun resolveFileName(uri: Uri): String? =
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                c.moveToFirst(); c.getString(col)
            }
        } catch (e: Exception) { uri.lastPathSegment }
}
