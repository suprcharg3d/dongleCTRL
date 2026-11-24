package com.suprcharg3d.dongleCTRL.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DspTests {

    @Test
    fun `flat peak filter produces unity biquad coefficients`() {
        val band = EqBand(index = 0, frequencyHz = 1000, gainDb = 0.0, q = 1.41, filterType = FilterType.PEAK)
        val c = BiquadCalculator.compute(band, sampleRate = 96_000)
        assertEquals(1.0, c.b0, 1e-9)
        assertEquals(0.0, c.b2 - c.a2, 1e-9)
        assertEquals(0.0, BiquadCalculator.evaluateMagnitudeDb(listOf(band), 1000.0, sampleRate = 96_000), 1e-6)
    }

    @Test
    fun `boosted peak filter hits target gain at center frequency`() {
        val band = EqBand(index = 0, frequencyHz = 1000, gainDb = 6.0, q = 2.0, filterType = FilterType.PEAK)
        val dbAtCenter = BiquadCalculator.evaluateMagnitudeDb(listOf(band), 1000.0, sampleRate = 96_000)
        val dbFar = BiquadCalculator.evaluateMagnitudeDb(listOf(band), 20.0, sampleRate = 96_000)
        assertEquals(6.0, dbAtCenter, 0.05)
        assertTrue("Expected minimal boost at 20 Hz, got $dbFar", abs(dbFar) < 0.1)
    }

    @Test
    fun `Q2_30 quantization scales unity filter b0 to 2 pow 30`() {
        val q30 = BiquadCalculator.quantizeCbQ2_30(BiquadCoeffs.BYPASS)
        assertEquals(1 shl 30, q30[0])
        assertEquals(0, q30[1])
        assertEquals(0, q30[2])
        assertEquals(0, q30[3])
        assertEquals(0, q30[4])
    }

    @Test
    fun `Q2_30 high shelf at +12 dB stays in range and keeps a shelf shape`() {
        val band = EqBand(
            index = 0,
            frequencyHz = 8000,
            gainDb = 12.0,
            q = 0.7,
            filterType = FilterType.HIGH_SHELF
        )
        val designed = BiquadCalculator.compute(band, sampleRate = 96_000)
        assertTrue(
            "designed High Shelf +12 dB should overflow Q2.30 (true b0=${designed.b0}, b1=${designed.b1})",
            !BiquadCalculator.isRepresentableInQ2_30(designed)
        )

        val fitted = BiquadCalculator.computeForCbQ2_30(band, sampleRate = 96_000)
        assertTrue(
            "fitted coefficients must be representable in Q2.30 (b0=${fitted.b0}, b1=${fitted.b1})",
            BiquadCalculator.isRepresentableInQ2_30(fitted)
        )
        // Use almost the full signed range rather than collapsing to a tiny shelf.
        val maxAbs = maxOf(abs(fitted.b0), abs(fitted.b1), abs(fitted.b2), abs(fitted.a1), abs(fitted.a2))
        assertTrue("fitted max |coeff| $maxAbs should be close to the Q2.30 limit of 2", maxAbs in 1.9..2.0)

        val q30 = BiquadCalculator.quantizeCbQ2_30(fitted)
        val scale = 1073741824.0 // 2^30
        q30.forEachIndexed { i, word ->
            val reconstructed = word / scale
            assertTrue("packed q30[$i]=$word is outside Q2.30", reconstructed in -2.0..2.0)
        }
        assertEquals(fitted.b0, q30[0] / scale, 1.5e-9)
        assertEquals(fitted.b1, q30[1] / scale, 1.5e-9)
        assertEquals(fitted.b2, q30[2] / scale, 1.5e-9)

        val lowDb = evaluateCoeffsDb(fitted, freqHz = 100.0, sampleRate = 96_000)
        val highDb = evaluateCoeffsDb(fitted, freqHz = 16_000.0, sampleRate = 96_000)
        assertTrue("low-frequency response should stay near 0 dB, was $lowDb", abs(lowDb) < 1.0)
        assertTrue("high-frequency shelf should still boost, was $highDb", highDb in 3.0..8.0)
    }

    private fun evaluateCoeffsDb(c: BiquadCoeffs, freqHz: Double, sampleRate: Int): Double {
        val w = 2.0 * Math.PI * freqHz / sampleRate.toDouble()
        val cosW = kotlin.math.cos(w)
        val sinW = kotlin.math.sin(w)
        val cos2W = kotlin.math.cos(2.0 * w)
        val sin2W = kotlin.math.sin(2.0 * w)
        val numRe = c.b0 + c.b1 * cosW + c.b2 * cos2W
        val numIm = -(c.b1 * sinW + c.b2 * sin2W)
        val denRe = 1.0 + c.a1 * cosW + c.a2 * cos2W
        val denIm = -(c.a1 * sinW + c.a2 * sin2W)
        val numSq = numRe * numRe + numIm * numIm
        val denSq = kotlin.math.max(1e-18, denRe * denRe + denIm * denIm)
        return 10.0 * kotlin.math.log10(kotlin.math.max(1e-18, numSq / denSq))
    }

    @Test
    fun `CX31993 24-bit quantizer fits within signed 24-bit bounds`() {
        val band = EqBand(index = 0, frequencyHz = 8000, gainDb = 12.0, q = 0.7, filterType = FilterType.HIGH_SHELF)
        val q = BiquadCalculator.quantizeCx31993(BiquadCalculator.compute(band, 48_000))
        val min24 = -8388608
        val max24 = 8388607
        assertTrue(q.b0 in min24..max24)
        assertTrue(q.b1 in min24..max24)
        assertTrue(q.b2 in min24..max24)
        assertTrue(q.negA1 in min24..max24)
        assertTrue(q.negA2 in min24..max24)
    }

    @Test
    fun `CRCs match known vectors`() {
        // CRC8-Maxim of ASCII "123456789" = 0xA1
        val vec = "123456789".encodeToByteArray()
        assertEquals(0xA1, CrcUtils.crc8Maxim(vec))
        // CRC16/CCITT-FALSE of ASCII "123456789" = 0x29B1
        assertEquals(0x29B1, CrcUtils.crc16Ccitt(vec))
        // CRC16/MODBUS of ASCII "123456789" = 0x4B37
        assertEquals(0x4B37, CrcUtils.crc16Ibm(vec))
    }

    @Test
    fun `AutoEQ parser roundtrips parametric bands and preamp`() {
        val sample = """
            Preamp: -5.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 4.5 dB Q 0.71
            Filter 2: ON PK Fc 3200 Hz Gain -3.0 dB Q 2.20
            Filter 3: OFF HSC Fc 10000 Hz Gain 2.0 dB Q 0.70
        """.trimIndent()
        val parsed = PeqTextParser.parse(sample)
        assertEquals(-5.4, parsed.preampDb, 1e-6)
        assertEquals(3, parsed.bands.size)
        assertEquals(FilterType.LOW_SHELF, parsed.bands[0].filterType)
        assertEquals(105, parsed.bands[0].frequencyHz)
        assertEquals(4.5, parsed.bands[0].gainDb, 1e-6)
        assertEquals(0.71, parsed.bands[0].q, 1e-6)
        assertTrue(!parsed.bands[2].enabled)

        val exported = PeqTextParser.exportAutoEq(parsed.bands, parsed.preampDb)
        val reparsed = PeqTextParser.parse(exported)
        assertEquals(parsed.bands.size, reparsed.bands.size)
        assertEquals(3200, reparsed.bands[1].frequencyHz)
    }

    @Test
    fun `normalizing 5-band preset pads remaining slots up to 10 with 0 dB`() {
        val fiveBands = List(5) { i ->
            EqBand(index = i, frequencyHz = 100 * (i + 1), gainDb = 2.5, q = 1.4, filterType = FilterType.PEAK)
        }
        val res = PresetBandHelper.resolveStandardTenBands(fiveBands)
        assertTrue(res is PresetBandHelper.BandResolution.Ready)
        val ready = res as PresetBandHelper.BandResolution.Ready
        assertEquals(10, ready.bands.size)
        assertEquals(5, PresetBandHelper.activeBands(ready.bands).size)
        for (i in 5 until 10) {
            assertEquals(0.0, ready.bands[i].gainDb, 1e-9)
        }
    }

    @Test
    fun `importing 20 active bands triggers selection up to 10 and saves selected`() {
        val twentyBands = List(20) { i ->
            EqBand(index = i, frequencyHz = 50 * (i + 1), gainDb = (i + 1) * 0.3, q = 1.41, filterType = FilterType.PEAK)
        }
        val initial = PresetBandHelper.resolveStandardTenBands(twentyBands)
        assertTrue(initial is PresetBandHelper.BandResolution.NeedsSelection)
        val prompt = initial as PresetBandHelper.BandResolution.NeedsSelection
        assertEquals(20, prompt.candidateBands.size)
        assertEquals(10, prompt.maxSelectable)

        val picked = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18)
        val finalRes = PresetBandHelper.resolveStandardTenBands(twentyBands, picked)
        assertTrue(finalRes is PresetBandHelper.BandResolution.Ready)
        val saved = (finalRes as PresetBandHelper.BandResolution.Ready).bands
        assertEquals(10, saved.size)
        assertEquals(50, saved[0].frequencyHz)
        assertEquals(150, saved[1].frequencyHz)
    }

    @Test
    fun `10-band preset with only 5 active bands applies cleanly to 5-band DAC without popup`() {
        val tenWithFiveActive = DefaultBands.flatBands(10).mapIndexed { idx, band ->
            if (idx in listOf(1, 3, 5, 7, 9)) band.copy(gainDb = -2.5) else band
        }
        val res = PresetBandHelper.resolveBandsForTarget(tenWithFiveActive, targetMaxBands = 5)
        assertTrue("Expected auto-fit Ready when only 5 active bands exist", res is PresetBandHelper.BandResolution.Ready)
        val adapted = (res as PresetBandHelper.BandResolution.Ready).bands
        assertEquals(5, adapted.size)
        assertTrue(adapted.all { it.gainDb == -2.5 })
    }

    @Test
    fun `10-band preset with 8 active bands requires selection for 5-band DAC`() {
        val tenWithEightActive = DefaultBands.flatBands(10).mapIndexed { idx, band ->
            if (idx < 8) band.copy(gainDb = 1.5) else band
        }
        val res = PresetBandHelper.resolveBandsForTarget(tenWithEightActive, targetMaxBands = 5)
        assertTrue(res is PresetBandHelper.BandResolution.NeedsSelection)
        val sel = res as PresetBandHelper.BandResolution.NeedsSelection
        assertEquals(8, sel.candidateBands.size)
        assertEquals(5, sel.maxSelectable)

        val resolved = PresetBandHelper.resolveBandsForTarget(tenWithEightActive, 5, listOf(0, 2, 4, 6, 7))
        assertTrue(resolved is PresetBandHelper.BandResolution.Ready)
        assertEquals(5, (resolved as PresetBandHelper.BandResolution.Ready).bands.size)
    }

    @Test
    fun `flatBands returns empty list for zero or negative count and 32 bands for Echo-BP`() {
        assertTrue(DefaultBands.flatBands(0).isEmpty())
        assertTrue(DefaultBands.flatBands(-1).isEmpty())
        assertEquals(32, DefaultBands.flatBands(32).size)
    }
}
