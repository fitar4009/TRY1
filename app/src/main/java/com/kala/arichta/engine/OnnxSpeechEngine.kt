package com.kala.arichta.engine

import ai.onnxruntime.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * SpeechEngine backed by ONNX Runtime for Android.
 *
 * Required files in the same directory (point modelPath at encoder_model.onnx):
 *   encoder_model.onnx      – audio features → encoder hidden states
 *   decoder_model.onnx      – token ids + hidden states → next-token logits
 *   vocab.json              – {"token_string": id, ...}  (from HuggingFace)
 *
 * Recommended source (whisper-tiny multilingual, ~85 MB total):
 *   https://huggingface.co/Xenova/whisper-tiny/tree/main/onnx
 *     • encoder_model.onnx
 *     • decoder_model.onnx
 *   https://huggingface.co/Xenova/whisper-tiny/resolve/main/vocab.json
 *
 * ONNX Runtime will automatically use the Android NNAPI execution provider
 * (hardware-accelerated on most devices) before falling back to CPU.
 */
class OnnxSpeechEngine : SpeechEngine {

    companion object {
        private const val TAG = "OnnxSpeechEngine"

        // ── Whisper multilingual special token IDs ────────────────────────
        // Derived from openai/whisper tokenizer.py  (multilingual, 99 languages)
        //   SOT         = 50258
        //   lang tokens = 50259 … 50357  (he = index 20 → 50259+20 = 50279)
        //   TRANSLATE   = 50358
        //   TRANSCRIBE  = 50359
        //   NO_STAMPS   = 50363
        //   EOT         = 50256
        private const val TOKEN_EOT           = 50256
        private const val TOKEN_SOT           = 50258
        private const val TOKEN_HEBREW        = 50279   // <|he|>
        private const val TOKEN_TRANSCRIBE    = 50359
        private const val TOKEN_NO_TIMESTAMPS = 50363

        private const val MAX_DECODE_STEPS    = 224     // whisper's default max
        private const val VOCAB_SIZE          = 51865   // multilingual vocab
    }

    // ONNX Runtime state
    private var env: OrtEnvironment?    = null
    private var encoder: OrtSession?    = null
    private var decoder: OrtSession?    = null

    // id → token-string (reversed from vocab.json)
    private var vocab: Map<Int, String> = emptyMap()

    // GPT-2 / whisper byte-to-unicode reverse map (char → byte)
    private val charToByte: Map<Char, Byte> by lazy { buildCharToByteMap() }

    override var isModelLoaded: Boolean = false
        private set

    // ── Loading ──────────────────────────────────────────────────────────────

    override suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            release()

            val encoderFile = File(modelPath)
            val dir         = encoderFile.parentFile
                ?: return@withContext false.also { Log.e(TAG, "Invalid model path") }

            val decoderFile = File(dir, "decoder_model.onnx")
            val vocabFile   = File(dir, "vocab.json")

            if (!encoderFile.exists()) {
                Log.e(TAG, "encoder_model.onnx not found at $modelPath"); return@withContext false
            }
            if (!decoderFile.exists()) {
                Log.e(TAG, "decoder_model.onnx not found in ${dir.absolutePath}"); return@withContext false
            }

            env = OrtEnvironment.getEnvironment()

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)          // safe on all Android versions
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                try {
                    addNnapi()                   // hardware acceleration if supported
                    Log.i(TAG, "NNAPI execution provider enabled")
                } catch (e: Exception) {
                    Log.i(TAG, "NNAPI not available, using CPU")
                }
            }

            encoder = env!!.createSession(encoderFile.absolutePath, opts)
            decoder = env!!.createSession(decoderFile.absolutePath, opts)

            if (vocabFile.exists()) {
                vocab = loadVocab(vocabFile)
                Log.i(TAG, "Vocab loaded: ${vocab.size} entries")
            } else {
                Log.w(TAG, "vocab.json not found — token IDs will be returned as-is")
            }

            isModelLoaded = true
            Log.i(TAG, "ONNX engine ready")
            true
        } catch (e: OrtException) {
            Log.e(TAG, "OrtException loading model: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading model: ${e.message}")
            false
        }
    }

    // ── Transcription ────────────────────────────────────────────────────────

    override suspend fun transcribe(pcmSamples: FloatArray): String = withContext(Dispatchers.IO) {
        if (!isModelLoaded) return@withContext ""
        try {
            // 1. Mel spectrogram  →  [1, 80, 3000]
            val melData = MelSpectrogram.compute(pcmSamples)
            val inputFeatures = OnnxTensor.createTensor(
                env!!,
                FloatBuffer.wrap(melData),
                longArrayOf(1, MelSpectrogram.MEL_BINS.toLong(), MelSpectrogram.MAX_FRAMES.toLong())
            )

            // 2. Encoder
            val encoderOut = encoder!!.run(mapOf("input_features" to inputFeatures))
            val hiddenTensor = encoderOut["last_hidden_state"] as OnnxTensor

            // Shape [1, 1500, 384] — keep as flat FloatBuffer for decoder reuse
            val hiddenBuf  = hiddenTensor.floatBuffer          // direct, zero-copy
            val hiddenFlat = FloatArray(hiddenBuf.remaining()).also { hiddenBuf.get(it) }
            val seqLen     = 1500
            val hiddenDim  = hiddenFlat.size / seqLen          // 384 for tiny

            // 3. Greedy decode
            val tokens = mutableListOf(
                TOKEN_SOT, TOKEN_HEBREW, TOKEN_TRANSCRIBE, TOKEN_NO_TIMESTAMPS
            )

            repeat(MAX_DECODE_STEPS) {
                val inputIds = LongArray(tokens.size) { i -> tokens[i].toLong() }
                val idTensor = OnnxTensor.createTensor(
                    env!!,
                    LongBuffer.wrap(inputIds),
                    longArrayOf(1, inputIds.size.toLong())
                )
                val encTensor = OnnxTensor.createTensor(
                    env!!,
                    FloatBuffer.wrap(hiddenFlat),
                    longArrayOf(1, seqLen.toLong(), hiddenDim.toLong())
                )

                val decOut   = decoder!!.run(
                    mapOf("input_ids" to idTensor, "encoder_hidden_states" to encTensor)
                )
                val logitsBuf = (decOut["logits"] as OnnxTensor).floatBuffer

                // Logits shape [1, seq, vocabSize] — grab last-position slice
                val lastPos    = tokens.size - 1
                val offset     = lastPos * VOCAB_SIZE
                var maxLogit   = Float.NEGATIVE_INFINITY
                var nextToken  = TOKEN_EOT
                for (v in 0 until VOCAB_SIZE) {
                    val l = logitsBuf.get(offset + v)
                    if (l > maxLogit) { maxLogit = l; nextToken = v }
                }

                if (nextToken == TOKEN_EOT) return@repeat
                tokens.add(nextToken)
            }

            // 4. Decode token IDs → Hebrew text  (drop the 4-token prompt prefix)
            decodeTokens(tokens.drop(4))

        } catch (e: Exception) {
            Log.e(TAG, "ONNX transcription error: ${e.message}")
            ""
        }
    }

    // ── Token → text ─────────────────────────────────────────────────────────

    /**
     * Converts a list of BPE token IDs to a UTF-8 string.
     * Uses the byte-level GPT-2 / whisper BPE encoding:
     * every character in a token string represents one byte via the
     * bytes_to_unicode() mapping; we reverse that map here.
     */
    private fun decodeTokens(tokenIds: List<Int>): String {
        val byteList = mutableListOf<Byte>()
        for (id in tokenIds) {
            val piece = vocab[id] ?: continue
            // Skip whisper special / timestamp tokens (ids ≥ 50256)
            if (id >= TOKEN_EOT) continue
            for (ch in piece) {
                val b = charToByte[ch]
                if (b != null) byteList.add(b)
            }
        }
        return String(byteList.toByteArray(), Charsets.UTF_8).trim()
    }

    /**
     * Reverses GPT-2's bytes_to_unicode() mapping.
     * The forward map sends each byte b to a printable Unicode char c;
     * we build c→b here so we can convert token chars back to raw bytes.
     */
    private fun buildCharToByteMap(): Map<Char, Byte> {
        // Bytes that are kept as-is (printable ASCII + extended Latin)
        val initialBytes = mutableListOf<Int>()
        for (b in '!'.code..'~'.code)  initialBytes.add(b)
        for (b in '¡'.code..'¬'.code) initialBytes.add(b)
        for (b in '®'.code..'ÿ'.code) initialBytes.add(b)

        val charCodes = initialBytes.toMutableList()
        var extra = 0
        for (b in 0 until 256) {
            if (b !in initialBytes) {
                initialBytes.add(b)
                charCodes.add(256 + extra)
                extra++
            }
        }

        return buildMap {
            for (i in initialBytes.indices) {
                put(charCodes[i].toChar(), initialBytes[i].toByte())
            }
        }
    }

    // ── Vocab loading ─────────────────────────────────────────────────────────

    /**
     * Parses vocab.json (token → id) and returns the reversed map (id → token).
     * Uses Android's built-in JSONObject — no extra library needed.
     */
    private fun loadVocab(file: File): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        try {
            val json = JSONObject(file.readText())
            val keys = json.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                val id    = json.getInt(token)
                map[id]   = token
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse vocab.json: ${e.message}")
        }
        return map
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun release() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        runCatching { env?.close() }
        encoder = null; decoder = null; env = null
        isModelLoaded = false
    }
}
