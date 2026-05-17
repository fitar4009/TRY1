package com.kala.arichta.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
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
        onBackPressedDispatcher.onBackPressed(); return true
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: AppPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        prefs = AppPreferences(requireContext())
        setupGgmlPicker()
        setupOnnxFolderPicker()
        setupAudioToggles()
    }

    // ── GGML model picker (single .bin / .gguf file) ─────────────────────────

    private fun setupGgmlPicker() {
        findPreference<Preference>("pick_model_ggml")?.setOnPreferenceClickListener {
            ggmlPickerLauncher.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
            )
            true
        }
        refreshGgmlSummary()
    }

    private val ggmlPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri      = result.data?.data ?: return@registerForActivityResult
        val fileName = resolveFileName(uri) ?: "model.bin"
        val ext      = fileName.substringAfterLast('.', "").lowercase()

        if (ext != "bin" && ext != "gguf") {
            Toast.makeText(requireContext(), "Please select a .bin or .gguf file", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        findPreference<Preference>("pick_model_ggml")?.summary = "Copying…"
        lifecycleScope.launch {
            val dest = copyFile(uri, fileName)
            if (dest != null) {
                prefs.modelPath = dest.absolutePath
                refreshGgmlSummary()
                refreshOnnxSummary()
                Toast.makeText(requireContext(), "GGML model ready: ${dest.name}", Toast.LENGTH_SHORT).show()
            } else {
                refreshGgmlSummary()
                Toast.makeText(requireContext(), "Copy failed — try again", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshGgmlSummary() {
        val path = prefs.modelPath
        val active = path.isNotEmpty() && !path.endsWith(".onnx")
        findPreference<Preference>("pick_model_ggml")?.summary = when {
            active -> "✓ Active: ${File(path).name}"
            else   -> "בחר קובץ .bin או .gguf"
        }
    }

    // ── ONNX folder picker (folder containing encoder + decoder + vocab) ──────

    private fun setupOnnxFolderPicker() {
        findPreference<Preference>("pick_model_onnx")?.setOnPreferenceClickListener {
            // ACTION_OPEN_DOCUMENT_TREE grants access to the entire folder —
            // the only correct way to read multiple files from the same directory.
            onnxFolderLauncher.launch(null)
            true
        }
        refreshOnnxSummary()
    }

    private val onnxFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@registerForActivityResult

        // Persist permission so we can re-read the folder after reboot if needed
        requireContext().contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        findPreference<Preference>("pick_model_onnx")?.summary = "Copying ONNX files…"

        lifecycleScope.launch {
            val result = copyOnnxFolder(treeUri)
            refreshOnnxSummary()
            refreshGgmlSummary()

            when {
                result.encoderPath != null && result.missingFiles.isEmpty() ->
                    Toast.makeText(requireContext(),
                        "ONNX model ready ✓ (encoder + decoder + vocab)",
                        Toast.LENGTH_SHORT).show()
                result.encoderPath != null ->
                    Toast.makeText(requireContext(),
                        "Encoder copied ✓\nMissing in folder: ${result.missingFiles.joinToString(", ")}\n" +
                        "Add them to the same folder and pick again.",
                        Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(requireContext(),
                        "encoder_model.onnx not found in the selected folder.\n" +
                        "Make sure the folder contains:\n" +
                        "  • encoder_model.onnx\n  • decoder_model.onnx\n  • vocab.json",
                        Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class OnnxCopyResult(
        val encoderPath: String?,
        val missingFiles: List<String>
    )

    private suspend fun copyOnnxFolder(treeUri: Uri): OnnxCopyResult = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(requireContext(), treeUri)
            ?: return@withContext OnnxCopyResult(null, listOf("encoder_model.onnx", "decoder_model.onnx", "vocab.json"))

        val destDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir

        // Find each required file inside the tree by name (case-insensitive)
        fun findFile(name: String): DocumentFile? =
            tree.listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }

        val encoderDoc = findFile("encoder_model.onnx")
        val decoderDoc = findFile("decoder_model.onnx")
        val vocabDoc   = findFile("vocab.json")

        if (encoderDoc == null) return@withContext OnnxCopyResult(null, listOf("encoder_model.onnx"))

        val missing = mutableListOf<String>()

        // Copy encoder
        val encoderDest = File(destDir, "encoder_model.onnx")
        requireContext().contentResolver.openInputStream(encoderDoc.uri)?.use { i ->
            encoderDest.outputStream().use { o -> i.copyTo(o) }
        }

        // Copy decoder
        if (decoderDoc != null) {
            requireContext().contentResolver.openInputStream(decoderDoc.uri)?.use { i ->
                File(destDir, "decoder_model.onnx").outputStream().use { o -> i.copyTo(o) }
            }
        } else missing.add("decoder_model.onnx")

        // Copy vocab
        if (vocabDoc != null) {
            requireContext().contentResolver.openInputStream(vocabDoc.uri)?.use { i ->
                File(destDir, "vocab.json").outputStream().use { o -> i.copyTo(o) }
            }
        } else missing.add("vocab.json")

        prefs.modelPath = encoderDest.absolutePath
        OnnxCopyResult(encoderDest.absolutePath, missing)
    }

    private fun refreshOnnxSummary() {
        val path   = prefs.modelPath
        val active = path.endsWith("encoder_model.onnx")
        val destDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val hasDecoder = File(destDir, "decoder_model.onnx").exists()
        val hasVocab   = File(destDir, "vocab.json").exists()
        findPreference<Preference>("pick_model_onnx")?.summary = when {
            active && hasDecoder && hasVocab -> "✓ Active: encoder + decoder + vocab"
            active -> "⚠ Active but missing: ${
                listOfNotNull(
                    if (!hasDecoder) "decoder_model.onnx" else null,
                    if (!hasVocab)   "vocab.json" else null
                ).joinToString(", ")}"
            else -> "בחר תיקייה עם encoder_model.onnx, decoder_model.onnx ו-vocab.json"
        }
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

    // ── Utilities ────────────────────────────────────────────────────────────

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

    private fun resolveFileName(uri: Uri): String? =
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                c.moveToFirst(); c.getString(col)
            }
        } catch (e: Exception) { uri.lastPathSegment }
}
