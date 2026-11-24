package com.suprcharg3d.dongleCTRL.dsp

/**
 * Filter topologies supported across WalkPlay, EPZ, and FiiO hardware DSP engines.
 */
enum class FilterType(
    val shortLabel: String,
    val displayName: String,
    val cbCode: Int,
    val ktCode: Int,
    val fiioCode: Int,
    val gm55Code: Int
) {
    PEAK("PK", "Peaking", cbCode = 2, ktCode = 0, fiioCode = 0, gm55Code = 0),
    LOW_SHELF("LS", "Low Shelf", cbCode = 1, ktCode = 3, fiioCode = 1, gm55Code = 1),
    HIGH_SHELF("HS", "High Shelf", cbCode = 3, ktCode = 4, fiioCode = 2, gm55Code = 2),
    LOW_PASS("LP", "Low Pass", cbCode = 4, ktCode = 1, fiioCode = 4, gm55Code = 0),
    HIGH_PASS("HP", "High Pass", cbCode = 5, ktCode = 2, fiioCode = 5, gm55Code = 0),
    BAND_PASS("BP", "Band Pass", cbCode = 2, ktCode = 0, fiioCode = 3, gm55Code = 0),
    ALL_PASS("AP", "All Pass", cbCode = 2, ktCode = 0, fiioCode = 6, gm55Code = 0);

    companion object {
        fun fromCbCode(code: Int): FilterType =
            entries.firstOrNull { it.cbCode == code } ?: PEAK

        fun fromKtCode(code: Int): FilterType =
            entries.firstOrNull { it.ktCode == code } ?: PEAK

        fun fromFiioCode(code: Int): FilterType =
            entries.firstOrNull { it.fiioCode == code } ?: PEAK

        fun fromGm55Code(code: Int): FilterType =
            entries.firstOrNull { it.gm55Code == code } ?: PEAK
    }
}

data class EqBand(
    val index: Int,
    val frequencyHz: Int,
    val gainDb: Double,
    val q: Double,
    val filterType: FilterType = FilterType.PEAK,
    val enabled: Boolean = true
)

data class EqPreset(
    val id: String,
    val name: String,
    val preampDb: Double = 0.0,
    val bands: List<EqBand>,
    val builtIn: Boolean = false
)

object DefaultBands {
    private val tenBandFreqs = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    private val eightBandFreqs = listOf(31, 62, 125, 250, 500, 1000, 4000, 8000)
    private val sixBandFreqs = listOf(60, 230, 910, 3600, 8000, 14000)
    private val fiveBandFreqs = listOf(60, 230, 910, 3600, 14000)
    private val nineBandFreqs = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000)
    private val fifteenBandFreqs = listOf(
        25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000
    )

    fun flatBands(count: Int): List<EqBand> {
        if (count <= 0) return emptyList()
        val freqs = when (count) {
            5 -> fiveBandFreqs
            6 -> sixBandFreqs
            8 -> eightBandFreqs
            9 -> nineBandFreqs
            10 -> tenBandFreqs
            15 -> fifteenBandFreqs
            else -> List(count.coerceAtLeast(1)) { idx ->
                val t = if (count <= 1) 0.5 else idx.toDouble() / (count - 1)
                (25.0 * Math.pow(16000.0 / 25.0, t)).toInt()
            }
        }
        return freqs.mapIndexed { idx, freq ->
            EqBand(index = idx, frequencyHz = freq, gainDb = 0.0, q = 1.41, filterType = FilterType.PEAK)
        }
    }
}
