package com.suprcharg3d.dongleCTRL.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class BiquadCoeffs(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
) {
    companion object {
        val BYPASS = BiquadCoeffs(b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0)
    }
}

data class Cx31993Quantized(
    val gainShift: Int,
    val b0: Int,
    val b1: Int,
    val b2: Int,
    val negA1: Int,
    val negA2: Int
)

/**
 * Pure Kotlin RBJ biquad design, fixed-point hardware coefficient quantizers,
 * and continuous magnitude-response evaluator.
 */
object BiquadCalculator {

    private const val Q2_30_SCALE = 1073741824.0 // 2^30
    private const val Q5_27_SCALE = 134217728.0  // 2^27
    // Signed Q2.30 range: [-2, 2 - 2^-30]. Int.MIN_VALUE is exactly -2.0.
    private const val Q2_30_MIN = -2.0
    private const val Q2_30_MAX = Int.MAX_VALUE / Q2_30_SCALE

    fun compute(band: EqBand, sampleRate: Int): BiquadCoeffs {
        if (!band.enabled) return BiquadCoeffs.BYPASS
        val fs = sampleRate.coerceAtLeast(8000).toDouble()
        val fc = band.frequencyHz.toDouble().coerceIn(5.0, fs * 0.49)
        val q = band.q.coerceIn(0.05, 128.0)

        val w0 = 2.0 * PI * fc / fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)
        val aAmp = 10.0.pow(band.gainDb / 40.0)

        val rawB0: Double
        val rawB1: Double
        val rawB2: Double
        val rawA0: Double
        val rawA1: Double
        val rawA2: Double

        when (band.filterType) {
            FilterType.PEAK -> {
                rawB0 = 1.0 + alpha * aAmp
                rawB1 = -2.0 * cosW
                rawB2 = 1.0 - alpha * aAmp
                rawA0 = 1.0 + alpha / aAmp
                rawA1 = -2.0 * cosW
                rawA2 = 1.0 - alpha / aAmp
            }
            FilterType.LOW_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(aAmp) * alpha
                val ap1 = aAmp + 1.0
                val am1 = aAmp - 1.0
                rawB0 = aAmp * (ap1 - am1 * cosW + twoSqrtAAlpha)
                rawB1 = 2.0 * aAmp * (am1 - ap1 * cosW)
                rawB2 = aAmp * (ap1 - am1 * cosW - twoSqrtAAlpha)
                rawA0 = ap1 + am1 * cosW + twoSqrtAAlpha
                rawA1 = -2.0 * (am1 + ap1 * cosW)
                rawA2 = ap1 + am1 * cosW - twoSqrtAAlpha
            }
            FilterType.HIGH_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(aAmp) * alpha
                val ap1 = aAmp + 1.0
                val am1 = aAmp - 1.0
                rawB0 = aAmp * (ap1 + am1 * cosW + twoSqrtAAlpha)
                rawB1 = -2.0 * aAmp * (am1 + ap1 * cosW)
                rawB2 = aAmp * (ap1 + am1 * cosW - twoSqrtAAlpha)
                rawA0 = ap1 - am1 * cosW + twoSqrtAAlpha
                rawA1 = 2.0 * (am1 - ap1 * cosW)
                rawA2 = ap1 - am1 * cosW - twoSqrtAAlpha
            }
            FilterType.LOW_PASS -> {
                rawB0 = (1.0 - cosW) / 2.0
                rawB1 = 1.0 - cosW
                rawB2 = (1.0 - cosW) / 2.0
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW
                rawA2 = 1.0 - alpha
            }
            FilterType.HIGH_PASS -> {
                rawB0 = (1.0 + cosW) / 2.0
                rawB1 = -(1.0 + cosW)
                rawB2 = (1.0 + cosW) / 2.0
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW
                rawA2 = 1.0 - alpha
            }
            FilterType.BAND_PASS -> {
                rawB0 = alpha
                rawB1 = 0.0
                rawB2 = -alpha
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW
                rawA2 = 1.0 - alpha
            }
            FilterType.ALL_PASS -> {
                rawB0 = 1.0 - alpha
                rawB1 = -2.0 * cosW
                rawB2 = 1.0 + alpha
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW
                rawA2 = 1.0 - alpha
            }
        }

        return BiquadCoeffs(
            b0 = rawB0 / rawA0,
            b1 = rawB1 / rawA0,
            b2 = rawB2 / rawA0,
            a1 = rawA1 / rawA0,
            a2 = rawA2 / rawA0
        )
    }

    /**
     * True when every a0-normalized coefficient fits in signed Q2.30.
     */
    fun isRepresentableInQ2_30(c: BiquadCoeffs): Boolean {
        val values = doubleArrayOf(c.b0, c.b1, c.b2, c.a1, c.a2)
        return values.all { it.isFinite() && it in Q2_30_MIN..Q2_30_MAX }
    }

    /**
     * RBJ coefficients for the WalkPlay / EPZ CB wire format.
     *
     * The HID packet carries five Q2.30 words [b0, b1, b2, -a1, -a2] and has no
     * gain-shift field (unlike [quantizeCx31993]). Coefficients outside
     * [-2, 2 - 2^-30] cannot be sent as-is; saturating each word independently
     * would destroy the b/a ratio and produce the wrong filter (a typical
     * High Shelf at +12 dB overflows, with |b1| ≈ 4.56 at 8 kHz / 96 kHz).
     *
     * When the designed biquad overflows, this keeps frequency and Q and
     * reduces |gain| to the largest value that still fits, so the hardware
     * gets a valid same-type shelf rather than a clipped, misshapen one.
     * Non-finite input yields [BiquadCoeffs.BYPASS] and does not throw.
     */
    fun computeForCbQ2_30(band: EqBand, sampleRate: Int): BiquadCoeffs {
        if (!band.enabled) return BiquadCoeffs.BYPASS
        if (!band.gainDb.isFinite()) return BiquadCoeffs.BYPASS
        val direct = compute(band, sampleRate)
        if (isRepresentableInQ2_30(direct)) return direct

        var loT = 0.0
        var hiT = 1.0
        var best = compute(band.copy(gainDb = 0.0), sampleRate)
        repeat(40) {
            val midT = (loT + hiT) / 2.0
            val candidate = compute(band.copy(gainDb = midT * band.gainDb), sampleRate)
            if (isRepresentableInQ2_30(candidate)) {
                best = candidate
                loT = midT
            } else {
                hiT = midT
            }
        }
        return best
    }

    /**
     * WalkPlay / EPZ CB family quantization: Q2.30 signed 32-bit in order [b0, b1, b2, -a1, -a2].
     * Non-finite values become 0 (does not throw). Out-of-range values are clamped
     * as a last resort — callers that write CB hardware should use [computeForCbQ2_30] first.
     */
    fun quantizeCbQ2_30(c: BiquadCoeffs): IntArray = intArrayOf(
        toQ2_30(c.b0),
        toQ2_30(c.b1),
        toQ2_30(c.b2),
        toQ2_30(-c.a1),
        toQ2_30(-c.a2)
    )

    private fun toQ2_30(value: Double): Int {
        if (!value.isFinite()) return 0
        val scaled = value * Q2_30_SCALE
        if (!scaled.isFinite()) return 0
        return scaled.roundToInt().coerceIn(Int.MIN_VALUE, Int.MAX_VALUE)
    }

    /**
     * CB1100AU / GM55 quantization: Q5.27 signed 32-bit in order [b0, b2, -a1, b1, -a2].
     */
    fun quantizeQ5_27(c: BiquadCoeffs): IntArray = intArrayOf(
        (c.b0 * Q5_27_SCALE).roundToInt(),
        (c.b2 * Q5_27_SCALE).roundToInt(),
        (-c.a1 * Q5_27_SCALE).roundToInt(),
        (c.b1 * Q5_27_SCALE).roundToInt(),
        (-c.a2 * Q5_27_SCALE).roundToInt()
    )

    /**
     * CX31993 24-bit dynamic shift quantizer derived from WalkPlay Web Scheme 27.
     */
    fun quantizeCx31993(c: BiquadCoeffs): Cx31993Quantized {
        val raw = doubleArrayOf(c.b0, c.b1, c.b2, -c.a1, -c.a2)
        val maxAbs = raw.maxOf { abs(it) }.coerceAtLeast(1e-12)
        var chosenShift = 0
        for (shift in 0..8) {
            val scale = (1 shl (22 - shift)).toDouble()
            if (maxAbs * scale < 8388607.0) {
                chosenShift = shift
                break
            }
            chosenShift = shift
        }
        val scale = (1 shl (22 - chosenShift)).toDouble()
        fun clamp24(v: Double): Int = v.roundToInt().coerceIn(-8388608, 8388607)
        return Cx31993Quantized(
            gainShift = chosenShift,
            b0 = clamp24(c.b0 * scale),
            b1 = clamp24(c.b1 * scale),
            b2 = clamp24(c.b2 * scale),
            negA1 = clamp24(-c.a1 * scale),
            negA2 = clamp24(-c.a2 * scale)
        )
    }

    /**
     * Evaluates combined frequency response in dB at [freqHz] for preview curve drawing.
     */
    fun evaluateMagnitudeDb(
        bands: List<EqBand>,
        freqHz: Double,
        globalGainDb: Double = 0.0,
        sampleRate: Int = 96_000
    ): Double {
        val w = 2.0 * PI * freqHz / sampleRate.toDouble()
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        var magSq = 1.0
        for (band in bands) {
            if (!band.enabled) continue
            val c = compute(band, sampleRate)
            val numRe = c.b0 + c.b1 * cosW + c.b2 * cos2W
            val numIm = -(c.b1 * sinW + c.b2 * sin2W)
            val denRe = 1.0 + c.a1 * cosW + c.a2 * cos2W
            val denIm = -(c.a1 * sinW + c.a2 * sin2W)
            val numSq = numRe * numRe + numIm * numIm
            val denSq = max(1e-18, denRe * denRe + denIm * denIm)
            magSq *= (numSq / denSq)
        }
        val db = 10.0 * log10(max(1e-18, magSq))
        return db + globalGainDb
    }

    /**
     * Evaluates a single band's isolated magnitude response in dB at [freqHz].
     */
    fun evaluateBandDb(
        band: EqBand,
        freqHz: Double,
        sampleRate: Int = 96_000
    ): Double = evaluateMagnitudeDb(listOf(band), freqHz, globalGainDb = 0.0, sampleRate = sampleRate)

    fun frequencyForNormalizedX(normX: Double, minHz: Double = 20.0, maxHz: Double = 20000.0): Int {
        val t = normX.coerceIn(0.0, 1.0)
        return (minHz * (maxHz / minHz).pow(t)).roundToInt().coerceIn(minHz.toInt(), maxHz.toInt())
    }

    fun normalizedXForFrequency(freqHz: Double, minHz: Double = 20.0, maxHz: Double = 20000.0): Double {
        val clamped = freqHz.coerceIn(minHz, maxHz)
        return (ln(clamped / minHz) / ln(maxHz / minHz)).coerceIn(0.0, 1.0)
    }
}
