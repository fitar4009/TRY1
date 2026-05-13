package com.kala.arichta.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Manages AudioRecord lifecycle with simple energy-based VAD.
 * Produces a full ShortArray buffer when silence is detected or
 * stopRecording() is called.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 1024          // samples per read
        private const val MAX_RECORD_SECONDS = 30    // safety cap

        // VAD thresholds
        private const val VAD_SILENCE_THRESHOLD = 500f   // RMS below this = silence
        private const val VAD_SILENCE_DURATION_MS = 1500 // ms of silence before auto-stop
    }

    enum class State { IDLE, RECORDING, PROCESSING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _volumeLevel = MutableStateFlow(0f)
    val volumeLevel: StateFlow<Float> = _volumeLevel  // 0-1 for UI VU meter

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    /**
     * Start recording and return when finished (silence or manual stop).
     * Returns ShortArray of recorded PCM data.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun recordUntilSilence(
        onSilenceDetected: (() -> Unit)? = null
    ): ShortArray = withContext(Dispatchers.IO) {

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        ).coerceAtLeast(CHUNK_SIZE * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            record.release()
            return@withContext ShortArray(0)
        }

        audioRecord = record
        val collectedSamples = mutableListOf<Short>()
        val chunk = ShortArray(CHUNK_SIZE)
        val maxSamples = SAMPLE_RATE * MAX_RECORD_SECONDS

        var silenceSamples = 0
        val silenceSamplesThreshold =
            (VAD_SILENCE_DURATION_MS * SAMPLE_RATE / 1000).toInt()

        try {
            record.startRecording()
            isRecording = true
            _state.value = State.RECORDING
            Log.i(TAG, "Recording started")

            while (isRecording && collectedSamples.size < maxSamples) {
                val read = record.read(chunk, 0, CHUNK_SIZE)
                if (read <= 0) continue

                // Add to buffer
                for (i in 0 until read) {
                    collectedSamples.add(chunk[i])
                }

                // RMS energy for VAD + UI
                val rms = computeRms(chunk, read)
                val normalized = (rms / Short.MAX_VALUE).coerceIn(0f, 1f)
                _volumeLevel.value = normalized

                if (rms < VAD_SILENCE_THRESHOLD) {
                    silenceSamples += read
                    if (silenceSamples >= silenceSamplesThreshold &&
                        collectedSamples.size > SAMPLE_RATE  // at least 1s of audio
                    ) {
                        Log.i(TAG, "VAD: silence detected, stopping")
                        onSilenceDetected?.invoke()
                        break
                    }
                } else {
                    silenceSamples = 0
                }
            }

        } finally {
            record.stop()
            record.release()
            audioRecord = null
            isRecording = false
            _volumeLevel.value = 0f
            _state.value = State.PROCESSING
        }

        collectedSamples.toShortArray()
    }

    /** Manually stop an ongoing recording. */
    fun stopRecording() {
        if (isRecording) {
            Log.i(TAG, "stopRecording() called manually")
            isRecording = false
        }
    }

    fun reset() {
        _state.value = State.IDLE
    }

    private fun computeRms(buffer: ShortArray, count: Int): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return sqrt(sum / count).toFloat()
    }
}
