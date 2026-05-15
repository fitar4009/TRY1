package com.kala.arichta.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.kala.arichta.AppPreferences
import com.kala.arichta.R
import com.kala.arichta.audio.AudioFocusManager
import com.kala.arichta.audio.AudioRecorder
import com.kala.arichta.contacts.Contact
import com.kala.arichta.contacts.ContactRepository
import com.kala.arichta.databinding.ActivityMainBinding
import com.kala.arichta.nlp.ContactMatcher
import com.kala.arichta.nlp.HebrewNumberParser
import com.kala.arichta.engine.EngineFactory
import com.kala.arichta.engine.SpeechEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_CONTACTS    = "extra_contacts_json"
        const val EXTRA_QUERY       = "extra_query"
        const val RESULT_SELECTED   = "result_selected_contact"
    }

    // View binding
    private lateinit var binding: ActivityMainBinding

    // Core components
    private lateinit var prefs: AppPreferences
    private lateinit var whisper: SpeechEngine
    private lateinit var recorder: AudioRecorder
    private lateinit var focusManager: AudioFocusManager
    private lateinit var contactRepo: ContactRepository

    private var contacts: List<Contact> = emptyList()
    private var recordingJob: Job? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs         = AppPreferences(this)
        whisper       = EngineFactory.create(prefs.modelPath)
        recorder      = AudioRecorder(this)
        focusManager  = AudioFocusManager(this)
        contactRepo   = ContactRepository(this)

        setupToolbar()
        setupButtons()
        observeRecorderState()

        // Check permissions then boot
        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Reload contacts in case BT state changed
        lifecycleScope.launch {
            contacts = contactRepo.loadContacts()
            Log.i(TAG, "Contacts loaded: ${contacts.size}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingJob?.cancel()
        recorder.stopRecording()
        focusManager.abandonFocus()
        whisper.release()
    }

    // ── Menu ─────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupButtons() {
        binding.btnStopListening.setOnClickListener {
            recorder.stopRecording()
        }

        binding.btnRetry.setOnClickListener {
            startListeningSession()
        }
    }

    private fun observeRecorderState() {
        lifecycleScope.launch {
            recorder.state.collect { state ->
                updateUiForState(state)
            }
        }

        lifecycleScope.launch {
            recorder.volumeLevel.collect { level ->
                binding.vuMeter.progress = (level * 100).toInt()
            }
        }
    }

    private fun updateUiForState(state: AudioRecorder.State) {
        when (state) {
            AudioRecorder.State.IDLE -> {
                binding.statusText.text     = getString(R.string.status_ready)
                binding.btnStopListening.visibility = View.GONE
                binding.btnRetry.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.vuMeter.visibility  = View.GONE
                binding.listeningPulse.visibility = View.GONE
            }
            AudioRecorder.State.RECORDING -> {
                binding.statusText.text     = getString(R.string.status_listening)
                binding.btnStopListening.visibility = View.VISIBLE
                binding.btnRetry.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                binding.vuMeter.visibility  = View.VISIBLE
                binding.listeningPulse.visibility = View.VISIBLE
            }
            AudioRecorder.State.PROCESSING -> {
                binding.statusText.text     = getString(R.string.status_processing)
                binding.btnStopListening.visibility = View.GONE
                binding.btnRetry.visibility = View.GONE
                binding.progressBar.visibility = View.VISIBLE
                binding.vuMeter.visibility  = View.GONE
                binding.listeningPulse.visibility = View.GONE
            }
        }
    }

    // ── Permission handling ──────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            bootApp()
        } else {
            showPermissionError()
        }
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.CALL_PHONE)
        }

        val notGranted = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            bootApp()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun showPermissionError() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_required_title)
            .setMessage(R.string.perm_required_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ── App boot ─────────────────────────────────────────────────────────────

    private fun bootApp() {
        lifecycleScope.launch {
            // Load model
            val modelPath = prefs.modelPath
            if (modelPath.isEmpty()) {
                showNoModelDialog()
                return@launch
            }

            binding.statusText.text = getString(R.string.status_loading_model)
            binding.progressBar.visibility = View.VISIBLE

            val loaded = whisper.loadModel(modelPath)
            binding.progressBar.visibility = View.GONE

            if (!loaded) {
                showModelLoadError(modelPath)
                return@launch
            }

            // Load contacts
            contacts = contactRepo.loadContacts()

            // Start listening immediately
            startListeningSession()
        }
    }

    private fun showNoModelDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.no_model_title)
            .setMessage(getString(R.string.no_model_message,
                context.getExternalFilesDir(null)?.absolutePath ?: ""))
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showModelLoadError(path: String) {
        Snackbar.make(binding.root,
            getString(R.string.model_load_error, path),
            Snackbar.LENGTH_LONG
        ).show()
        binding.btnRetry.visibility = View.VISIBLE
    }

    // ── Listening session ────────────────────────────────────────────────────

    private fun startListeningSession() {
        recordingJob?.cancel()
        recorder.reset()

        // Audio focus
        if (prefs.audioFocusEnabled) {
            focusManager.requestFocus(duck = prefs.audioDuckOnly)
        }

        recordingJob = lifecycleScope.launch {
            try {
                binding.transcriptText.text = ""
                binding.transcriptText.hint = getString(R.string.transcript_hint)

                val pcmShorts = recorder.recordUntilSilence {
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.status_processing)
                    }
                }

                if (pcmShorts.isEmpty()) {
                    handleNoAudio()
                    return@launch
                }

                // Transcribe
                val floats  = whisper.shortToFloat(pcmShorts)
                val rawText = whisper.transcribe(floats)

                Log.i(TAG, "Transcript: '$rawText'")
                binding.transcriptText.text = rawText

                if (rawText.isBlank()) {
                    handleNoSpeechDetected()
                    return@launch
                }

                // Route: number or name?
                processTranscript(rawText)

            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception during recording: ${e.message}")
                showPermissionError()
            } finally {
                focusManager.abandonFocus()
                recorder.reset()
            }
        }
    }

    // ── NLP routing ──────────────────────────────────────────────────────────

    private fun processTranscript(text: String) {
        val trimmed = text.trim()

        // Is it a phone number?
        if (HebrewNumberParser.looksLikeNumber(trimmed)) {
            val number = HebrewNumberParser.parsePhoneNumber(trimmed)
            if (number != null) {
                confirmAndDial(number, number)
                return
            }
        }

        // Otherwise treat as contact name search
        if (contacts.isEmpty()) {
            showNoContactsDialog()
            return
        }

        val matches = ContactMatcher.findMatches(trimmed, contacts)
        when {
            matches.isEmpty() -> handleNoMatch(trimmed)
            matches.size == 1 -> confirmAndDial(matches[0].contact.name, matches[0].contact.phoneNumber)
            else -> showDisambiguationActivity(trimmed, matches.map { it.contact })
        }
    }

    private fun confirmAndDial(displayName: String, number: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_call_title)
            .setMessage(getString(R.string.confirm_call_message, displayName, number))
            .setPositiveButton(R.string.call) { _, _ -> dialNumber(number) }
            .setNegativeButton(R.string.cancel_try_again) { _, _ -> startListeningSession() }
            .show()
    }

    private fun dialNumber(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            startActivity(intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "CALL_PHONE permission missing: ${e.message}")
            Snackbar.make(binding.root, R.string.call_permission_error, Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dial: ${e.message}")
        }
    }

    private fun showDisambiguationActivity(query: String, options: List<Contact>) {
        val intent = Intent(this, DisambiguationActivity::class.java).apply {
            putExtra(EXTRA_QUERY, query)
            val nameArray = options.map { it.name }.toTypedArray()
            val numArray  = options.map { it.phoneNumber }.toTypedArray()
            putExtra("names", nameArray)
            putExtra("numbers", numArray)
        }
        disambiguationLauncher.launch(intent)
    }

    private val disambiguationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val number = result.data?.getStringExtra("selected_number") ?: return@registerForActivityResult
            val name   = result.data?.getStringExtra("selected_name")   ?: number
            confirmAndDial(name, number)
        } else {
            startListeningSession()
        }
    }

    // ── Error states ─────────────────────────────────────────────────────────

    private fun handleNoAudio() {
        binding.statusText.text = getString(R.string.no_audio_detected)
        binding.btnRetry.visibility = View.VISIBLE
    }

    private fun handleNoSpeechDetected() {
        binding.statusText.text = getString(R.string.no_speech_detected)
        binding.btnRetry.visibility = View.VISIBLE
    }

    private fun handleNoMatch(query: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.no_match_title)
            .setMessage(getString(R.string.no_match_message, query))
            .setPositiveButton(R.string.try_again) { _, _ -> startListeningSession() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNoContactsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.no_contacts_title)
            .setMessage(getString(R.string.no_contacts_message,
                contactRepo.getContactsFilePath()))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // Workaround: access context from companion usage
    private val context get() = this
}
