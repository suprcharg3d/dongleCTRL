package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.FilterType
import com.suprcharg3d.dongleCTRL.protocol.NamedOption

/**
 * Factory EQ presets and game/sleep/diffusion modes extracted from official NICEHCK device profiles.
 */
internal object NicehckPresets {

    data class PresetSpec(
        val id: Int,
        val label: String,
        val category: String? = null,
        val offsetDb: Double = 0.0,
        val bands: List<EqBand>
    )

    private fun b(idx: Int, freq: Int, gain: Double, q: Double): EqBand =
        EqBand(index = idx, frequencyHz = freq, gainDb = gain, q = q, filterType = FilterType.PEAK)

    private val STRINGSNOW_PRESETS = listOf(
        PresetSpec(1, "Default", null, 0.0, listOf(
            b(0, 20, 1.5, 2.0), b(1, 500, 1.1, 0.5), b(2, 2300, -5.0, 1.5), b(3, 4500, 2.5, 2.7),
            b(4, 7000, 1.5, 1.5), b(5, 8800, 4.5, 3.8), b(6, 12500, -4.0, 4.5), b(7, 14500, 2.0, 4.0)
        )),
        PresetSpec(2, "Tears", null, 0.0, listOf(
            b(0, 32, 1.8, 0.5), b(1, 150, -1.1, 2.0), b(2, 160, 0.6, 0.8), b(3, 330, 2.6, 0.5),
            b(4, 460, -1.4, 2.0), b(5, 2250, -3.4, 2.4), b(6, 5700, 1.7, 2.5), b(7, 15000, 3.6, 1.0)
        )),
        PresetSpec(3, "Balanced", null, 0.0, listOf(
            b(0, 62, 2.9, 0.7), b(1, 340, 0.4, 2.0), b(2, 460, 3.2, 0.5), b(3, 1100, -0.9, 2.0),
            b(4, 2300, -8.7, 1.1), b(5, 3000, 6.7, 1.5), b(6, 6000, 1.7, 6.2), b(7, 10000, 0.7, 0.5)
        )),
        PresetSpec(4, "Rock", null, 0.0, listOf(
            b(0, 52, 2.4, 0.7), b(1, 160, 0.7, 2.0), b(2, 370, 1.7, 2.0), b(3, 760, 1.5, 2.0),
            b(4, 1200, -0.8, 2.0), b(5, 2200, -7.1, 1.3), b(6, 6100, 7.9, 0.6), b(7, 12000, -6.2, 2.0)
        )),
        PresetSpec(5, "Game Mode", "Mode", 0.0, listOf(
            b(0, 20, -2.0, 0.2), b(1, 200, -0.8, 1.35), b(2, 330, 0.6, 1.31), b(3, 1500, 0.1, 1.73),
            b(4, 2300, -3.7, 1.96), b(5, 4500, -1.2, 0.2), b(6, 6000, -1.2, 1.38), b(7, 16000, 4.2, 4.2)
        )),
        PresetSpec(6, "Sleep Mode", "Mode", 0.0, listOf(
            b(0, 20, 1.5, 2.0), b(1, 400, 1.5, 0.7), b(2, 1000, 0.5, 0.5), b(3, 2300, -10.0, 1.5),
            b(4, 4500, -2.5, 2.7), b(5, 7000, 5.0, 2.0), b(6, 8800, -6.0, 3.5), b(7, 12500, -8.0, 4.5)
        ))
    )

    private val TEARS_PRESETS = listOf(
        PresetSpec(1, "Tears", null, 0.0, listOf(
            b(0, 20, 0.0, 0.75), b(1, 200, 0.0, 0.75), b(2, 750, 0.0, 0.75), b(3, 1000, 0.0, 0.75),
            b(4, 2000, 0.0, 0.75), b(5, 3000, 0.0, 0.75), b(6, 4000, 0.0, 0.75), b(7, 7000, 0.0, 0.75)
        )),
        PresetSpec(2, "Balanced", null, 0.0, listOf(
            b(0, 22, -1.6, 1.3), b(1, 72, 1.2, 1.2), b(2, 390, 2.6, 1.6), b(3, 1700, -2.5, 1.2),
            b(4, 3300, 2.9, 2.0), b(5, 4500, -3.1, 2.0), b(6, 9000, 4.5, 2.0), b(7, 14000, -2.9, 2.0)
        )),
        PresetSpec(3, "Rock", null, 0.0, listOf(
            b(0, 21, -1.4, 1.9), b(1, 240, -1.6, 2.0), b(2, 810, 0.8, 2.0), b(3, 1600, -2.7, 0.9),
            b(4, 4600, -3.2, 2.0), b(5, 5200, 4.0, 0.8), b(6, 6400, 4.1, 1.4), b(7, 11000, -3.0, 0.8)
        )),
        PresetSpec(4, "Game Mode", "Mode", 0.0, listOf(
            b(0, 22, -9.0, 1.4), b(1, 200, 4.0, 1.5), b(2, 400, 2.5, 2.0), b(3, 720, 1.0, 2.0),
            b(4, 1800, -2.7, 1.3), b(5, 3200, 5.1, 2.0), b(6, 4600, -3.2, 2.0), b(7, 8800, 1.3, 2.0)
        )),
        PresetSpec(5, "Diffusion Mode", "Mode", 0.0, listOf(
            b(0, 210, -1.5, 2.0), b(1, 950, 2.5, 2.0), b(2, 1700, -5.2, 1.4), b(3, 3400, 1.5, 5.0),
            b(4, 6000, 3.5, 5.0), b(5, 9000, 9.0, 4.0), b(6, 10200, 6.0, 5.0), b(7, 12000, -6.0, 5.0)
        ))
    )

    private val TENTH_PRESETS = listOf(
        PresetSpec(1, "Default", null, 0.0, listOf(
            b(0, 50, 0.0, 0.75), b(1, 200, 0.0, 0.75), b(2, 500, 0.0, 0.75), b(3, 1000, 0.0, 0.75),
            b(4, 2000, 0.0, 0.75), b(5, 5000, 0.0, 0.75), b(6, 10000, 0.0, 0.75), b(7, 15000, 0.0, 0.75)
        )),
        PresetSpec(2, "Tears", null, -2.0, listOf(
            b(0, 50, 8.9, 0.5), b(1, 200, 5.5, 0.6), b(2, 600, -3.5, 0.65), b(3, 2000, -7.8, 1.5),
            b(4, 2800, 5.5, 0.75), b(5, 7500, -1.0, 1.2), b(6, 11000, 3.0, 0.6), b(7, 16000, 4.0, 0.8)
        )),
        PresetSpec(3, "Rock Live", null, -2.0, listOf(
            b(0, 60, 4.5, 0.35), b(1, 250, 2.8, 0.35), b(2, 1000, -2.2, 0.65), b(3, 1800, -1.3, 0.8),
            b(4, 3000, 5.6, 0.87), b(5, 4700, -4.4, 1.7), b(6, 8700, 1.2, 0.4), b(7, 16000, 2.9, 0.6)
        )),
        PresetSpec(4, "POP", null, -2.0, listOf(
            b(0, 65, 3.2, 0.45), b(1, 200, 1.9, 0.65), b(2, 550, 0.8, 0.65), b(3, 880, -1.2, 0.75),
            b(4, 1750, -1.3, 0.8), b(5, 2800, 5.4, 0.85), b(6, 4800, -6.0, 2.4), b(7, 9800, 4.2, 0.75)
        )),
        PresetSpec(5, "ACG", null, -2.0, listOf(
            b(0, 40, 3.3, 0.75), b(1, 100, 4.3, 0.75), b(2, 550, 0.6, 0.75), b(3, 850, -4.5, 0.75),
            b(4, 1750, -2.1, 0.75), b(5, 2770, 2.2, 0.75), b(6, 4500, -2.3, 3.4), b(7, 15000, 4.0, 0.75)
        )),
        PresetSpec(6, "Balance", null, 3.0, listOf(
            b(0, 50, 3.5, 0.75), b(1, 200, 2.0, 0.6), b(2, 600, -4.0, 0.6), b(3, 750, 2.0, 0.75),
            b(4, 1300, -3.0, 0.55), b(5, 3500, -1.8, 0.75), b(6, 5500, 0.8, 0.43), b(7, 15000, -5.0, 0.75)
        )),
        PresetSpec(7, "Tuned by DSP Master", null, 2.0, listOf(
            b(0, 50, 6.0, 0.7), b(1, 200, -2.0, 0.7), b(2, 600, -5.0, 0.7), b(3, 1500, -8.0, 1.0),
            b(4, 3000, 7.0, 1.0), b(5, 5000, -5.0, 2.0), b(6, 8000, -2.0, 1.0), b(7, 12000, 2.5, 0.7)
        ))
    )

    private val OCTAVE_PRESETS = listOf(
        PresetSpec(1, "Balanced", null, 0.0, listOf(
            b(0, 20, 0.0, 0.75), b(1, 50, 0.0, 0.75), b(2, 100, 0.0, 0.75), b(3, 200, 0.0, 0.75), b(4, 4500, 0.0, 0.75),
            b(5, 7000, 0.0, 0.75), b(6, 8000, 0.0, 0.75), b(7, 12500, 0.0, 0.75), b(8, 15000, 0.0, 0.75), b(9, 18000, 0.0, 0.75)
        )),
        PresetSpec(2, "Rock", null, 0.0, listOf(
            b(0, 30, 2.0, 0.5), b(1, 100, 1.5, 0.5), b(2, 200, 1.5, 0.5), b(3, 500, 0.5, 0.75), b(4, 900, -1.0, 0.75),
            b(5, 2500, 1.5, 1.5), b(6, 3800, 2.0, 0.75), b(7, 7500, -2.0, 1.5), b(8, 10000, 0.5, 0.75), b(9, 13000, 1.5, 0.6)
        )),
        PresetSpec(3, "Instrumental", null, 0.0, listOf(
            b(0, 20, 2.0, 0.5), b(1, 50, 2.0, 0.5), b(2, 200, 1.0, 0.75), b(3, 500, -0.5, 0.75), b(4, 800, -1.5, 1.0),
            b(5, 1700, -0.5, 0.5), b(6, 2700, 0.5, 0.75), b(7, 4500, 2.5, 0.75), b(8, 6500, 2.5, 0.75), b(9, 18000, -1.0, 0.6)
        )),
        PresetSpec(4, "Vocal", null, 0.0, listOf(
            b(0, 20, 1.5, 0.5), b(1, 50, 1.0, 0.5), b(2, 200, 0.5, 0.75), b(3, 500, -0.5, 0.75), b(4, 800, -1.5, 1.0),
            b(5, 1700, -2.0, 0.75), b(6, 2700, 1.5, 0.75), b(7, 4500, 2.0, 0.75), b(8, 6500, -3.0, 1.5), b(9, 18000, -1.5, 0.6)
        ))
    )

    private val PUREAURAL_PRESETS = listOf(
        PresetSpec(1, "Default", null, 0.0, listOf(
            b(0, 20, 0.0, 0.75), b(1, 50, 0.0, 0.75), b(2, 100, 0.0, 0.75), b(3, 200, 0.0, 0.75), b(4, 4500, 0.0, 0.75),
            b(5, 7000, 0.0, 0.75), b(6, 8000, 0.0, 0.75), b(7, 12500, 0.0, 0.75), b(8, 15000, 0.0, 0.75), b(9, 18000, 0.0, 0.75)
        )),
        PresetSpec(2, "Rock", null, 0.0, listOf(
            b(0, 80, 2.5, 0.45), b(1, 250, 1.7, 0.5), b(2, 550, 1.0, 0.75), b(3, 850, -1.8, 0.75), b(4, 1800, -0.8, 0.75),
            b(5, 2800, 1.4, 1.5), b(6, 3800, 2.4, 0.75), b(7, 8000, -1.8, 1.4), b(8, 10000, 0.6, 1.7), b(9, 15000, 0.8, 0.75)
        )),
        PresetSpec(3, "Vocal", null, 0.0, listOf(
            b(0, 80, 2.2, 0.5), b(1, 250, 0.8, 0.6), b(2, 500, -0.8, 0.75), b(3, 1000, -0.4, 0.6), b(4, 1750, -1.0, 1.2),
            b(5, 2200, 0.3, 1.5), b(6, 3650, 3.5, 0.75), b(7, 5000, 2.0, 0.8), b(8, 6800, -1.8, 1.0), b(9, 12000, -2.0, 0.5)
        )),
        PresetSpec(4, "Instrumental", null, 0.0, listOf(
            b(0, 50, 1.5, 0.6), b(1, 200, 0.4, 0.6), b(2, 550, -0.8, 0.75), b(3, 800, -1.3, 0.75), b(4, 2700, 2.0, 1.6),
            b(5, 4500, 2.6, 1.1), b(6, 8000, -3.2, 0.75), b(7, 10000, 0.5, 0.75), b(8, 12000, 0.8, 0.75), b(9, 16000, 0.7, 0.75)
        )),
        PresetSpec(5, "FPS Footsteps", "Game", 0.0, listOf(
            b(0, 60, -2.5, 1.0), b(1, 180, 2.0, 1.0), b(2, 250, 2.5, 1.0), b(3, 500, 1.0, 1.0), b(4, 1700, -2.8, 0.9),
            b(5, 2500, -2.8, 2.0), b(6, 3800, 1.0, 2.0), b(7, 8000, 1.2, 1.5), b(8, 11000, -3.5, 0.9), b(9, 13000, 2.0, 0.9)
        )),
        PresetSpec(6, "FPS Gunshots", "Game", 0.0, listOf(
            b(0, 50, -2.0, 2.0), b(1, 100, -4.0, 1.0), b(2, 250, -3.0, 1.0), b(3, 1300, -1.0, 1.3), b(4, 1800, 3.0, 1.5),
            b(5, 3000, 2.0, 1.5), b(6, 6000, 3.5, 1.2), b(7, 8000, -3.0, 2.5), b(8, 10000, 1.0, 2.5), b(9, 12000, 3.0, 2.0)
        )),
        PresetSpec(7, "Vocal Boost", "Game", 0.0, listOf(
            b(0, 30, -4.0, 0.5), b(1, 100, 1.0, 0.75), b(2, 220, -2.0, 0.75), b(3, 400, -2.0, 1.0), b(4, 550, 1.5, 1.0),
            b(5, 800, -1.5, 1.0), b(6, 2000, -1.8, 0.75), b(7, 4500, -4.0, 1.5), b(8, 9000, 3.0, 0.55), b(9, 11000, 2.0, 0.75)
        )),
        PresetSpec(8, "Immersion Mode", "Game", 0.0, listOf(
            b(0, 50, 4.0, 0.9), b(1, 150, 3.0, 0.9), b(2, 250, 1.5, 0.8), b(3, 450, 0.5, 0.8), b(4, 900, -1.0, 1.0),
            b(5, 2500, -2.0, 1.5), b(6, 3800, 3.0, 1.5), b(7, 8000, 3.0, 1.2), b(8, 11000, -1.0, 0.9), b(9, 16000, 2.0, 0.9)
        ))
    )

    fun presetsFor(schemeNo: String?): List<PresetSpec> = when (schemeNo) {
        CbProtocol.SCHEME_NICEHCK_STRINGSNOW -> STRINGSNOW_PRESETS
        CbProtocol.SCHEME_NICEHCK_TEARS -> TEARS_PRESETS
        CbProtocol.SCHEME_NICEHCK_10TH -> TENTH_PRESETS
        CbProtocol.SCHEME_NICEHCK_OCTAVE -> OCTAVE_PRESETS
        CbProtocol.SCHEME_NICEHCK_PUREAURAL -> PUREAURAL_PRESETS
        else -> emptyList()
    }

    fun optionsFor(schemeNo: String?): List<NamedOption> =
        presetsFor(schemeNo).map { NamedOption(it.id, it.label) }

    fun categoriesFor(schemeNo: String?): Map<Int, String> =
        presetsFor(schemeNo).mapNotNull { spec ->
            spec.category?.let { spec.id to it }
        }.toMap()

    fun find(schemeNo: String?, presetId: Int): PresetSpec? =
        presetsFor(schemeNo).firstOrNull { it.id == presetId }
}
