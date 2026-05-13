package com.kala.arichta.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kala.arichta.AppPreferences
import com.kala.arichta.R
import com.kala.arichta.audio.AudioRecorder
import com.kala.arichta.databinding.ActivityDisambiguationBinding
import com.kala.arichta.nlp.HebrewNumberParser
import com.kala.arichta.whisper.WhisperEngine
import kotlinx.coroutines.launch

class DisambiguationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DisambiguationActivity"
    }

    private lateinit var binding: ActivityDisambiguationBinding
    private lateinit var whisper: WhisperEngine
    private lateinit var recorder: AudioRecorder

    private var names:   Array<String> = emptyArray()
    private var numbers: Array<String> = emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDisambiguationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        names   = intent.getStringArrayExtra("names")   ?: emptyArray()
        numbers = intent.getStringArrayExtra("numbers") ?: emptyArray()

        whisper  = WhisperEngine()
        recorder = AudioRecorder(this)

        buildContactList()
        startListeningForSelection()
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder.stopRecording()
    }

    // ── Build UI list ─────────────────────────────────────────────────────────

    private fun buildContactList() {
        binding.promptText.text = getString(R.string.disambiguation_prompt)
        binding.contactListContainer.removeAllViews()

        names.forEachIndexed { idx, name ->
            val number = numbers.getOrNull(idx) ?: ""
            val itemView = layoutInflater.inflate(
                R.layout.item_disambiguation, binding.contactListContainer, false
            )
            itemView.findViewById<android.widget.TextView>(R.id.tvIndex).text = "${idx + 1}"
            itemView.findViewById<android.widget.TextView>(R.id.tvName).text   = name
            itemView.findViewById<android.widget.TextView>(R.id.tvNumber).text = number

            itemView.setOnClickListener {
                returnResult(name, number)
            }

            binding.contactListContainer.addView(itemView)
        }
    }

    // ── Voice selection ───────────────────────────────────────────────────────

    private fun startListeningForSelection() {
        lifecycleScope.launch {
            try {
                binding.statusText.text = getString(R.string.say_number_prompt)

                // Reload the same whisper model
                val prefs = AppPreferences(this@DisambiguationActivity)
                if (prefs.modelPath.isNotEmpty() && !whisper.isModelLoaded) {
                    whisper.loadModel(prefs.modelPath)
                }

                val pcmShorts = recorder.recordUntilSilence()
                if (pcmShorts.isEmpty()) return@launch

                val floats = whisper.shortToFloat(pcmShorts)
                val text   = whisper.transcribe(floats).trim()

                Log.i(TAG, "Selection transcript: '$text'")

                val selection = HebrewNumberParser.parseListSelection(text)
                if (selection != null && selection in 1..names.size) {
                    val idx = selection - 1
                    returnResult(names[idx], numbers[idx])
                } else {
                    binding.statusText.text = getString(R.string.selection_not_understood, text)
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "Permission error: ${e.message}")
            }
        }
    }

    private fun returnResult(name: String, number: String) {
        val data = Intent().apply {
            putExtra("selected_name", name)
            putExtra("selected_number", number)
        }
        setResult(RESULT_OK, data)
        finish()
    }
}
