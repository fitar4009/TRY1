package com.kala.arichta.engine

import kotlin.math.*

/**
 * Computes the log-mel spectrogram expected by OpenAI Whisper ONNX models.
 *
 * Parameters match whisper exactly:
 *   sample rate : 16 000 Hz
 *   FFT size    : 512  (25 ms window)
 *   hop length  : 160  (10 ms stride)
 *   mel bins    : 80
 *   output      : 3 000 frames  →  30 s of audio
 *
 * Output layout: flat FloatArray of shape [MEL_BINS × MAX_FRAMES], row-major,
 * ready to be wrapped in an ONNX tensor as [1, MEL_BINS, MAX_FRAMES].
 */
object MelSpectrogram {

    const val MEL_BINS  = 80
    const val MAX_FRAMES = 3000

    private const val SAMPLE_RATE = 16_000
    private const val N_FFT       = 512
    private const val HOP_LENGTH  = 160
    private const val F_MAX       = 8_000f

    // Pre-computed at class-load time (cheap, done once)
    private val hannWindow: FloatArray = FloatArray(N_FFT) { n ->
        (0.5 * (1.0 - cos(2.0 * PI * n / N_FFT))).toFloat()
    }

    // mel filterbank [MEL_BINS × (N_FFT/2 + 1)]
    private val melFilters: Array<FloatArray> = buildFilters()

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * @param samples  raw 16 kHz mono PCM in range [−1, 1]
     * @return flat FloatArray [MEL_BINS * MAX_FRAMES], normalised to [−1, 1]
     */
    fun compute(samples: FloatArray): FloatArray {
        // Pad / trim to exactly 30 s
        val audio = FloatArray(SAMPLE_RATE * 30)
        samples.copyInto(audio, endIndex = minOf(samples.size, audio.size))

        val nFrames = minOf((audio.size - N_FFT) / HOP_LENGTH + 1, MAX_FRAMES)

        // Allocate frame buffers once and reuse
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)

        // logMel[m][t]
        val logMel = Array(MEL_BINS) { FloatArray(MAX_FRAMES) { -10f } }

        for (t in 0 until nFrames) {
            val offset = t * HOP_LENGTH
            for (n in 0 until N_FFT) {
                re[n] = audio[offset + n] * hannWindow[n]
                im[n] = 0f
            }
            fft(re, im)

            for (m in 0 until MEL_BINS) {
                var energy = 0f
                for (k in 0..N_FFT / 2) {
                    energy += melFilters[m][k] * (re[k] * re[k] + im[k] * im[k])
                }
                logMel[m][t] = log10(energy.coerceAtLeast(1e-10f))
            }
        }

        // Global normalisation: (x − max + 4) / 4, clamped to [−1, 1]
        var maxVal = Float.NEGATIVE_INFINITY
        for (m in 0 until MEL_BINS) {
            for (t in 0 until nFrames) {
                if (logMel[m][t] > maxVal) maxVal = logMel[m][t]
            }
        }

        val result = FloatArray(MEL_BINS * MAX_FRAMES)
        for (m in 0 until MEL_BINS) {
            for (t in 0 until MAX_FRAMES) {
                val v = if (t < nFrames) (logMel[m][t] - maxVal + 4f) / 4f else -1f
                result[m * MAX_FRAMES + t] = v.coerceIn(-1f, 1f)
            }
        }
        return result
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun buildFilters(): Array<FloatArray> {
        val fftFreqs = FloatArray(N_FFT / 2 + 1) { k ->
            k * SAMPLE_RATE.toFloat() / N_FFT
        }
        val melMin = hzToMel(0f)
        val melMax = hzToMel(F_MAX)
        // N_MELS + 2 centre points
        val centres = FloatArray(MEL_BINS + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (MEL_BINS + 1))
        }
        return Array(MEL_BINS) { m ->
            FloatArray(N_FFT / 2 + 1) { k ->
                val f  = fftFreqs[k]
                val f0 = centres[m]; val f1 = centres[m + 1]; val f2 = centres[m + 2]
                when {
                    f < f0 || f > f2 -> 0f
                    f <= f1          -> (f - f0) / (f1 - f0)
                    else             -> (f2 - f) / (f2 - f1)
                }
            }
        }
    }

    private fun hzToMel(f: Float) = (2595.0 * log10(1.0 + f / 700.0)).toFloat()
    private fun melToHz(m: Float) = (700.0 * (10.0.pow(m / 2595.0) - 1.0)).toFloat()

    /** In-place radix-2 Cooley–Tukey FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        // Butterfly stages
        var len = 2
        while (len <= n) {
            val ang  = -2.0 * PI / len
            val wRe  = cos(ang).toFloat()
            val wIm  = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cRe = 1f; var cIm = 0f
                for (jj in 0 until len / 2) {
                    val uRe = re[i + jj];      val uIm = im[i + jj]
                    val vRe = re[i+jj+len/2]*cRe - im[i+jj+len/2]*cIm
                    val vIm = re[i+jj+len/2]*cIm + im[i+jj+len/2]*cRe
                    re[i + jj]       = uRe + vRe;  im[i + jj]       = uIm + vIm
                    re[i+jj+len/2]   = uRe - vRe;  im[i+jj+len/2]   = uIm - vIm
                    val nr = cRe*wRe - cIm*wIm;    cIm = cRe*wIm + cIm*wRe;  cRe = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
