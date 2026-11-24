package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.DefaultBands
import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.FilterType
import com.suprcharg3d.dongleCTRL.protocol.BytePack
import com.suprcharg3d.dongleCTRL.protocol.ControlCategory
import com.suprcharg3d.dongleCTRL.protocol.ControlKind
import com.suprcharg3d.dongleCTRL.protocol.DacCapabilities
import com.suprcharg3d.dongleCTRL.protocol.DacControlDescriptor
import com.suprcharg3d.dongleCTRL.protocol.DacControlValue
import com.suprcharg3d.dongleCTRL.protocol.DacDeviceState
import com.suprcharg3d.dongleCTRL.protocol.DacProfile
import com.suprcharg3d.dongleCTRL.protocol.DacProtocol
import com.suprcharg3d.dongleCTRL.protocol.DacTransport
import com.suprcharg3d.dongleCTRL.protocol.NamedOption
import com.suprcharg3d.dongleCTRL.protocol.ProtocolFamily
import com.suprcharg3d.dongleCTRL.protocol.TransportType
import kotlin.math.roundToInt

/**
 * Qualcomm GAIA V3 (`vendor = 0x001D`) Bluetooth protocol driver for Moondrop TWS/headphones
 * (Bluetrum, Qualcomm QCC, and Jieli runtime stacks via `s74`/`ss1`/`k74`).
 *
 * Dynamically discovers supported GAIA features (`FEAT_BASIC 0x00` PDUs `0x01`/`0x02`) and
 * hides PEQ / optional controls that the connected device does not advertise (e.g. Gen-1 Space Travel
 * only exposes Noise Control + Battery, whereas Space Travel 2 / Robin / Pill / EVO 2 expose Music Processing PEQ).
 */
class MoondropGaiaV3Protocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.QUALCOMM_GAIA_V3
    override val displayName: String = "Moondrop GAIA v3"

    @Volatile
    private var cachedBands: List<EqBand> = emptyList()

    @Volatile
    private var cachedGlobalGain: Double = 0.0

    @Volatile
    private var cachedTouchBytes: ByteArray = ByteArray(7)

    @Volatile
    private var cachedTouchFeature: Int = FEAT_TOUCH_V4

    @Volatile
    private var cachedPowerTimeouts: IntArray = intArrayOf(0, 10, 0, 10)

    @Volatile
    private var cachedAncSwitchConf: ByteArray = byteArrayOf(1, 1, 1, 1, 0)

    override fun buildCapabilities(profile: DacProfile): DacCapabilities =
        buildCaps(profile, state = null)

    override fun resolveCapabilities(profile: DacProfile, state: DacDeviceState): DacCapabilities =
        buildCaps(profile, state = state)

    private fun buildCaps(profile: DacProfile, state: DacDeviceState?): DacCapabilities {
        val extras = state?.extraControlValues.orEmpty()
        val featMask = if (state != null) extractFeatMask(extras) else 0L
        val hasEnumeratedFeatures = featMask != 0L

        fun supportsFeat(featId: Int, fallbackKey: String? = null): Boolean = when {
            state == null -> false
            hasEnumeratedFeatures -> ((featMask ushr featId) and 1L) != 0L
            fallbackKey != null -> extras.containsKey(fallbackKey)
            else -> false
        }

        val hwBandCount = extras[KEY_HW_BANDS]?.intValue
        val maxBands = when {
            profile.bandCount <= 0 -> 0
            state != null && hwBandCount == 0 -> 0
            state != null && hasEnumeratedFeatures && !supportsFeat(FEAT_MUSIC_PROCESSING) -> 0
            hwBandCount != null && hwBandCount in 1..10 -> hwBandCount
            else -> profile.bandCount.coerceIn(0, 10)
        }

        val hasPeq = maxBands > 0
        val presetIds = extras[KEY_PRESETS_CSV]?.rawHex
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }

        val presets = when {
            presetIds != null -> presetIds.map { id ->
                NamedOption(id, if (id == PRESET_USER_PEQ) "Custom PEQ" else "Preset ${id + 1}")
            }
            hasPeq -> listOf(0, 1, 2, PRESET_USER_PEQ).map { id ->
                NamedOption(id, if (id == PRESET_USER_PEQ) "Custom PEQ" else "Preset ${id + 1}")
            }
            else -> emptyList()
        }

        val controls = if (state == null) {
            emptyList()
        } else {
            buildList {
                when {
                    supportsFeat(FEAT_ANC_V3, KEY_ANC_V3) -> {
                        add(DacControlDescriptor(KEY_ANC_V3, "Noise Control Mode", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_V3_MODES))
                        if (extras.containsKey(KEY_ANC_V3_WIND)) {
                            add(DacControlDescriptor(KEY_ANC_V3_WIND, "Wind Noise Reduction", ControlCategory.AUDIO, ControlKind.SWITCH))
                        }
                        if (extras.containsKey(KEY_ANC_V3_STRENGTH)) {
                            add(DacControlDescriptor(KEY_ANC_V3_STRENGTH, "ANC Depth", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_STRENGTH_OPTIONS))
                        }
                    }
                    supportsFeat(FEAT_ANC_V2, KEY_ANC_V2) -> {
                        add(DacControlDescriptor(KEY_ANC_V2, "Noise Control Mode", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_BASIC_MODES))
                    }
                    supportsFeat(FEAT_AUDIO_CURATION, KEY_AUDIO_CURATION) ||
                        supportsFeat(FEAT_AUDIO_CURATION, KEY_ANC_AC_STATE) ||
                        supportsFeat(FEAT_AUDIO_CURATION, KEY_ANC_AC_ORDER) -> {
                        if (extras.containsKey(KEY_AUDIO_CURATION)) {
                            add(DacControlDescriptor(KEY_AUDIO_CURATION, "Noise Control Mode", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_BASIC_MODES))
                        } else if (extras.containsKey(KEY_ANC_AC_STATE)) {
                            add(DacControlDescriptor(KEY_ANC_AC_STATE, "Noise Control", ControlCategory.AUDIO, ControlKind.SWITCH))
                        }
                        if (extras.containsKey(KEY_ANC_AC_ORDER)) {
                            add(DacControlDescriptor(KEY_ANC_AC_ORDER, "ANC Toggle Cycle Order", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_CYCLE_ORDERS))
                        }
                    }
                    supportsFeat(FEAT_ANC, KEY_ANC_V1_STATE) || supportsFeat(FEAT_ANC, KEY_ANC_V1_MODE) -> {
                        if (extras.containsKey(KEY_ANC_V1_STATE)) {
                            add(DacControlDescriptor(KEY_ANC_V1_STATE, "Noise Control", ControlCategory.AUDIO, ControlKind.SWITCH))
                        }
                        if (extras.containsKey(KEY_ANC_V1_MODE)) {
                            add(DacControlDescriptor(KEY_ANC_V1_MODE, "Noise Control Mode", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_BASIC_MODES))
                        }
                    }
                }
                if (supportsFeat(FEAT_DAC_GAIN, KEY_DAC_GAIN)) {
                    add(DacControlDescriptor(KEY_DAC_GAIN, "Tuning Preset / Gain Index", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = DAC_GAIN_OPTIONS))
                }
                if (supportsFeat(FEAT_DYBASS, KEY_DYBASS)) {
                    add(DacControlDescriptor(KEY_DYBASS, "Dynamic Bass", ControlCategory.AUDIO, ControlKind.SWITCH))
                }
                if (supportsFeat(FEAT_SPATIAL_AUDIO, KEY_SPATIAL)) {
                    add(DacControlDescriptor(KEY_SPATIAL, "Spatial Audio", ControlCategory.AUDIO, ControlKind.SWITCH))
                    if (extras.containsKey(KEY_HEAD_TRACK)) {
                        add(DacControlDescriptor(KEY_HEAD_TRACK, "Head Tracking", ControlCategory.AUDIO, ControlKind.SWITCH))
                    }
                }
                if (supportsFeat(FEAT_DUAL_MIC_ENC, KEY_DUAL_ENC)) {
                    add(DacControlDescriptor(KEY_DUAL_ENC, "Dual-Mic ENC", ControlCategory.AUDIO, ControlKind.SWITCH))
                }
                if (supportsFeat(FEAT_EQ_POST_GAIN, KEY_EQ_POST_GAIN)) {
                    add(DacControlDescriptor(KEY_EQ_POST_GAIN_SW, "EQ Post-Gain Switch", ControlCategory.AUDIO, ControlKind.SWITCH))
                    add(DacControlDescriptor(KEY_EQ_POST_GAIN, "EQ Post-Gain (0.1 dB)", ControlCategory.AUDIO, ControlKind.SLIDER, min = 0, max = 150, unit = "×0.1 dB"))
                }
                if (supportsFeat(FEAT_LR_CHANNEL, KEY_LR_SWAP)) {
                    add(DacControlDescriptor(KEY_LR_SWAP, "Swap L / R Channels", ControlCategory.AUDIO, ControlKind.SWITCH))
                }
                if (supportsFeat(FEAT_LED, KEY_LED)) {
                    add(DacControlDescriptor(KEY_LED, "Indicator LED", ControlCategory.DISPLAY, ControlKind.SWITCH))
                }
                if (supportsFeat(FEAT_LIGHT_SENSOR, KEY_IN_EAR)) {
                    add(DacControlDescriptor(KEY_IN_EAR, "In-Ear Detection", ControlCategory.GENERAL, ControlKind.SWITCH))
                }
                if (supportsFeat(FEAT_ONEBRINGTWO, KEY_ONE_BRING_TWO)) {
                    add(DacControlDescriptor(KEY_ONE_BRING_TWO, "Dual-Device Connection", ControlCategory.BLUETOOTH, ControlKind.SWITCH))
                }
                if (supportsFeat(FEAT_POWER_TIMEOUT, KEY_POWER_OFF_EN)) {
                    add(DacControlDescriptor(KEY_POWER_OFF_EN, "Auto Power-Off", ControlCategory.POWER, ControlKind.SWITCH))
                    add(DacControlDescriptor(KEY_POWER_OFF_MIN, "Auto Power-Off Timeout", ControlCategory.POWER, ControlKind.SLIDER, min = 1, max = 255, unit = "min"))
                    add(DacControlDescriptor(KEY_STANDBY_EN, "Auto Standby", ControlCategory.POWER, ControlKind.SWITCH))
                    add(DacControlDescriptor(KEY_STANDBY_MIN, "Auto Standby Timeout", ControlCategory.POWER, ControlKind.SLIDER, min = 1, max = 255, unit = "min"))
                }
                val hasTouchV4 = supportsFeat(FEAT_TOUCH_V4, TOUCH_SLOTS.first().key)
                val hasTouchV3 = supportsFeat(FEAT_TOUCH_V3, TOUCH_SLOTS.first().key)
                val hasTouchV2 = supportsFeat(FEAT_TOUCH_V2, TOUCH_SLOTS.first().key)
                if ((hasTouchV4 || hasTouchV3 || hasTouchV2) && extras.containsKey(TOUCH_SLOTS.first().key)) {
                    if (hasTouchV4 && extras.containsKey(KEY_TOUCH_KNOCK)) {
                        add(DacControlDescriptor(KEY_TOUCH_KNOCK, "Touch Control Enabled", ControlCategory.BUTTONS, ControlKind.SWITCH))
                    }
                    TOUCH_SLOTS.forEach { slot ->
                        add(DacControlDescriptor(slot.key, slot.label, ControlCategory.BUTTONS, ControlKind.SINGLE_CHOICE, options = TOUCH_ACTIONS))
                    }
                    if (hasTouchV3 && extras.containsKey(TOUCH_V3_EXTRA_SLOTS.first().key)) {
                        TOUCH_V3_EXTRA_SLOTS.forEach { slot ->
                            add(DacControlDescriptor(slot.key, slot.label, ControlCategory.BUTTONS, ControlKind.SINGLE_CHOICE, options = TOUCH_ACTIONS))
                        }
                    }
                }
                if (supportsFeat(FEAT_FIND_EARBUD)) {
                    add(DacControlDescriptor(KEY_FIND_EARBUD, "Find Earbuds Tone", ControlCategory.GENERAL, ControlKind.ACTION, isReadable = false))
                }
            }
        }

        val hasBat = if (state != null) supportsFeat(FEAT_BATTERY) || state.batteryPercent != null else true
        return DacCapabilities(
            maxBands = maxBands,
            minGainDb = -12.0,
            maxGainDb = 12.0,
            minQ = 0.25,
            maxQ = 8.0,
            supportedFilters = if (hasPeq) {
                listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS)
            } else emptyList(),
            sampleRate = 48_000,
            hasEqSwitch = hasPeq,
            hasGlobalGain = hasPeq,
            globalGainRange = -12.0..12.0,
            hasBattery = hasBat,
            presets = presets,
            supportsRamRefresh = false,
            supportsFlashSave = false,
            extraControls = controls
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val extras = LinkedHashMap<String, DacControlValue>()

        val discovered = querySupportedFeatures(transport)
        var mask = 0L
        discovered?.forEach { fid ->
            if (fid in 0..62) mask = mask or (1L shl fid)
        }
        fun isFeatWanted(featId: Int): Boolean = discovered == null || featId in discovered
        fun markFeat(featId: Int) {
            if (featId in 0..62) mask = mask or (1L shl featId)
        }

        val fw = (queryGaia(transport, FEAT_BASIC, PDU_BASIC_GET_APP_VERSION)
            ?: queryGaia(transport, FEAT_BASIC, PDU_BASIC_GET_SERIAL))
            ?.let { raw ->
                raw.takeWhile { it != 0.toByte() }.toByteArray().decodeToString().trim().ifEmpty { null }
            }

        var eqEnabled = false
        var activePreset = 0
        var parsedBands: List<EqBand> = emptyList()

        if (isFeatWanted(FEAT_MUSIC_PROCESSING)) {
            val eqStateResp = queryGaia(transport, FEAT_MUSIC_PROCESSING, PDU_MP_GET_EQ_STATE)
            if (eqStateResp != null) markFeat(FEAT_MUSIC_PROCESSING)
            eqEnabled = (eqStateResp?.getOrNull(0)?.toInt()?.and(0xFF) ?: 1) != 0

            queryGaia(transport, FEAT_MUSIC_PROCESSING, PDU_MP_GET_AVAILABLE_PRESETS)?.let { resp ->
                markFeat(FEAT_MUSIC_PROCESSING)
                if (resp.isNotEmpty()) {
                    val cnt = (resp[0].toInt() and 0xFF).coerceAtMost(resp.size - 1)
                    val list = (0 until cnt).map { i -> resp[1 + i].toInt() and 0xFF }
                    if (list.isNotEmpty()) {
                        extras[KEY_PRESETS_CSV] = DacControlValue(rawHex = list.joinToString(","))
                    }
                }
            }

            val selEq = queryGaia(transport, FEAT_MUSIC_PROCESSING, PDU_MP_GET_SELECTED_EQ)
            if (selEq != null) markFeat(FEAT_MUSIC_PROCESSING)
            activePreset = selEq?.getOrNull(0)?.toInt()?.and(0xFF)
                ?: if (profile.bandCount > 0) PRESET_USER_PEQ else 0
            if (selEq != null && selEq.size >= 2 && eqStateResp == null) {
                eqEnabled = (selEq[1].toInt() and 0xFF) != 0
            }

            if (profile.bandCount > 0) {
                val hwNumResp = queryGaia(transport, FEAT_MUSIC_PROCESSING, PDU_MP_GET_BANDS_NUM)
                val hwCount = hwNumResp?.getOrNull(0)?.toInt()?.and(0xFF)
                val bandCount = when {
                    hwCount != null && hwCount == 0 -> 0
                    hwCount != null && hwCount in 1..10 -> hwCount
                    else -> profile.bandCount.coerceIn(1, 10)
                }
                extras[KEY_HW_BANDS] = DacControlValue(intValue = bandCount)
                if (bandCount > 0) {
                    parsedBands = readUserEqBands(transport, bandCount)
                    markFeat(FEAT_MUSIC_PROCESSING)
                }
            } else {
                extras[KEY_HW_BANDS] = DacControlValue(intValue = 0)
            }
        } else {
            extras[KEY_HW_BANDS] = DacControlValue(intValue = 0)
        }
        cachedBands = parsedBands

        val notes = mutableListOf<String>()
        var batteryPct: Int? = null
        if (isFeatWanted(FEAT_BATTERY)) {
            val (bat, batNote) = readBatteries(transport)
            batteryPct = bat
            if (bat != null) markFeat(FEAT_BATTERY)
            if (!batNote.isNullOrBlank()) notes += batNote
        }

        when {
            discovered != null && FEAT_ANC_V3 in discovered -> readAncV3(transport, extras)
            discovered != null && FEAT_ANC_V2 in discovered -> readAncV2(transport, extras)
            discovered != null && FEAT_AUDIO_CURATION in discovered -> readAudioCuration(transport, extras)
            discovered != null && FEAT_ANC in discovered -> readAncV1(transport, extras)
            discovered == null -> {
                if (readAncV3(transport, extras)) markFeat(FEAT_ANC_V3)
                else if (readAncV2(transport, extras)) markFeat(FEAT_ANC_V2)
                else if (readAudioCuration(transport, extras)) markFeat(FEAT_AUDIO_CURATION)
                else if (readAncV1(transport, extras)) markFeat(FEAT_ANC)
            }
        }

        if (isFeatWanted(FEAT_DAC_GAIN)) {
            queryGaia(transport, FEAT_DAC_GAIN, 0x01)?.getOrNull(0)?.let {
                extras[KEY_DAC_GAIN] = DacControlValue(intValue = (it.toInt() and 0xFF).coerceIn(0, 2))
                markFeat(FEAT_DAC_GAIN)
            }
        }

        if (isFeatWanted(FEAT_DYBASS)) {
            queryGaia(transport, FEAT_DYBASS, 0x01)?.getOrNull(0)?.let {
                extras[KEY_DYBASS] = DacControlValue(boolValue = (it.toInt() and 0xFF) != 0)
                markFeat(FEAT_DYBASS)
            }
        }
        if (isFeatWanted(FEAT_SPATIAL_AUDIO)) {
            queryGaia(transport, FEAT_SPATIAL_AUDIO, 0x01)?.getOrNull(0)?.let {
                extras[KEY_SPATIAL] = DacControlValue(boolValue = (it.toInt() and 0xFF) != 0)
                markFeat(FEAT_SPATIAL_AUDIO)
            }
            queryGaia(transport, FEAT_SPATIAL_AUDIO, 0x03)?.getOrNull(0)?.let {
                extras[KEY_HEAD_TRACK] = DacControlValue(boolValue = (it.toInt() and 0xFF) != 0)
            }
        }
        if (isFeatWanted(FEAT_DUAL_MIC_ENC)) {
            queryGaia(transport, FEAT_DUAL_MIC_ENC, 0x01)?.getOrNull(0)?.let {
                extras[KEY_DUAL_ENC] = DacControlValue(boolValue = (it.toInt() and 0xFF) != 0)
                markFeat(FEAT_DUAL_MIC_ENC)
            }
        }
        if (isFeatWanted(FEAT_EQ_POST_GAIN)) {
            queryGaia(transport, FEAT_EQ_POST_GAIN, 0x01)?.getOrNull(0)?.let {
                extras[KEY_EQ_POST_GAIN] = DacControlValue(intValue = (it.toInt() and 0xFF).coerceIn(0, 150))
                markFeat(FEAT_EQ_POST_GAIN)
            }
            queryGaia(transport, FEAT_EQ_POST_GAIN, 0x03)?.getOrNull(0)?.let {
                extras[KEY_EQ_POST_GAIN_SW] = DacControlValue(boolValue = (it.toInt() and 0xFF) == 1)
            }
        }
        if (isFeatWanted(FEAT_LR_CHANNEL)) {
            queryGaia(transport, FEAT_LR_CHANNEL, 0x01)?.getOrNull(0)?.let {
                extras[KEY_LR_SWAP] = DacControlValue(boolValue = (it.toInt() and 0xFF) == 1)
                markFeat(FEAT_LR_CHANNEL)
            }
        }
        if (isFeatWanted(FEAT_LED)) {
            queryGaia(transport, FEAT_LED, 0x01)?.getOrNull(0)?.let {
                extras[KEY_LED] = DacControlValue(boolValue = (it.toInt() and 0xFF) == 1)
                markFeat(FEAT_LED)
            }
        }
        if (isFeatWanted(FEAT_LIGHT_SENSOR)) {
            queryGaia(transport, FEAT_LIGHT_SENSOR, 0x01)?.getOrNull(0)?.let {
                extras[KEY_IN_EAR] = DacControlValue(boolValue = (it.toInt() and 0xFF) == 1)
                markFeat(FEAT_LIGHT_SENSOR)
            }
        }
        if (isFeatWanted(FEAT_ONEBRINGTWO)) {
            queryGaia(transport, FEAT_ONEBRINGTWO, 0x01)?.getOrNull(0)?.let {
                extras[KEY_ONE_BRING_TWO] = DacControlValue(boolValue = (it.toInt() and 0xFF) == 1)
                markFeat(FEAT_ONEBRINGTWO)
            }
        }
        if (isFeatWanted(FEAT_POWER_TIMEOUT)) {
            val pOff = queryGaia(transport, FEAT_POWER_TIMEOUT, 0x01)
            val stdby = queryGaia(transport, FEAT_POWER_TIMEOUT, 0x03)
            if ((pOff != null && pOff.size >= 2) || (stdby != null && stdby.size >= 2)) {
                markFeat(FEAT_POWER_TIMEOUT)
                val pEn = if ((pOff?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1) 1 else 0
                val pMin = (pOff?.getOrNull(1)?.toInt()?.and(0xFF) ?: 10).coerceIn(1, 255)
                val sEn = if ((stdby?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1) 1 else 0
                val sMin = (stdby?.getOrNull(1)?.toInt()?.and(0xFF) ?: 10).coerceIn(1, 255)
                cachedPowerTimeouts = intArrayOf(pEn, pMin, sEn, sMin)
                extras[KEY_POWER_OFF_EN] = DacControlValue(boolValue = pEn == 1)
                extras[KEY_POWER_OFF_MIN] = DacControlValue(intValue = pMin)
                extras[KEY_STANDBY_EN] = DacControlValue(boolValue = sEn == 1)
                extras[KEY_STANDBY_MIN] = DacControlValue(intValue = sMin)
            }
        }
        if (discovered != null && FEAT_TOUCH_V4 in discovered) {
            cachedTouchFeature = FEAT_TOUCH_V4
            queryGaia(transport, FEAT_TOUCH_V4, 0x01, byteArrayOf(0xFF.toByte()))?.getOrNull(0)?.let {
                extras[KEY_TOUCH_KNOCK] = DacControlValue(boolValue = (it.toInt() and 0xFF) == 1)
            }
            queryGaia(transport, FEAT_TOUCH_V4, 0x04)?.takeIf { it.size >= 5 }?.let { raw ->
                unpackTouchNibbles(raw, extras, includeV3Extra = false)
            }
        } else if (discovered != null && FEAT_TOUCH_V3 in discovered) {
            cachedTouchFeature = FEAT_TOUCH_V3
            queryGaia(transport, FEAT_TOUCH_V3, 0x02)?.takeIf { it.size >= 7 }?.let { raw ->
                unpackTouchNibbles(raw, extras, includeV3Extra = true)
            }
        } else if (discovered != null && FEAT_TOUCH_V2 in discovered) {
            cachedTouchFeature = FEAT_TOUCH_V2
            queryGaia(transport, FEAT_TOUCH_V2, 0x02)?.takeIf { it.size >= 5 }?.let { raw ->
                unpackTouchNibbles(raw, extras, includeV3Extra = false)
            }
        }
        if (isFeatWanted(FEAT_FIND_EARBUD)) {
            markFeat(FEAT_FIND_EARBUD)
        }

        storeFeatMask(extras, mask)

        return DacDeviceState(
            firmwareVersion = fw,
            eqEnabled = eqEnabled,
            activePresetId = activePreset,
            globalGainDb = cachedGlobalGain,
            bands = parsedBands,
            batteryPercent = batteryPct,
            extraControlValues = extras,
            diagnosticNote = notes.joinToString(" · ").ifBlank { null }
        )
    }

    private suspend fun querySupportedFeatures(transport: DacTransport): Set<Int>? {
        val first = queryGaia(transport, FEAT_BASIC, PDU_BASIC_GET_SUPPORTED_FEATURES) ?: return null
        if (first.size < 3) return null
        val features = LinkedHashSet<Int>()
        var chunk = first
        repeat(8) {
            val hasMore = (chunk[0].toInt() and 0xFF) == 1
            var i = 1
            while (i + 1 < chunk.size) {
                features += (chunk[i].toInt() and 0xFF)
                i += 2
            }
            if (!hasMore) return features
            chunk = queryGaia(transport, FEAT_BASIC, PDU_BASIC_GET_SUPPORTED_FEATURES_NEXT) ?: return features
            if (chunk.size < 3) return features
        }
        return features
    }

    private suspend fun readUserEqBands(transport: DacTransport, bandCount: Int): List<EqBand> {
        val flat = DefaultBands.flatBands(bandCount)
        val out = flat.toMutableList()
        var start = 0
        var anyRead = false
        while (start < bandCount) {
            val end = minOf(start + EQ_CHUNK_BANDS - 1, bandCount - 1)
            val resp = queryGaia(transport, FEAT_MUSIC_PROCESSING, PDU_MP_GET_USER_EQ, byteArrayOf(start.toByte(), end.toByte()))
            if (resp != null && resp.size >= 4) {
                val rStart = resp[0].toInt() and 0xFF
                val rEnd = resp[1].toInt() and 0xFF
                val expectedCount = if (rEnd >= rStart) (rEnd - rStart + 1) else (end - start + 1)
                val hasGlobalGainHeader = resp.size >= 4 + expectedCount * 7
                val bandStartOff = if (hasGlobalGainHeader) {
                    cachedGlobalGain = (BytePack.getI16Be(resp, 2) / 60.0).coerceIn(-12.0, 12.0)
                    4
                } else {
                    2
                }
                val parsedCount = ((resp.size - bandStartOff) / 7).coerceAtMost(expectedCount)
                for (i in 0 until parsedCount) {
                    val idx = rStart + i
                    if (idx in out.indices) {
                        val off = bandStartOff + i * 7
                        out[idx] = EqBand(
                            index = idx,
                            frequencyHz = BytePack.getU16Be(resp, off).coerceIn(20, 20000),
                            q = (BytePack.getU16Be(resp, off + 2) / 4096.0).coerceIn(0.25, 8.0),
                            filterType = FilterType.fromFiioCode(resp[off + 4].toInt() and 0xFF),
                            gainDb = (BytePack.getI16Be(resp, off + 5) / 60.0).coerceIn(-12.0, 12.0)
                        )
                        anyRead = true
                    }
                }
            }
            start = end + 1
        }
        return if (anyRead) out else flat
    }

    private suspend fun readBatteries(transport: DacTransport): Pair<Int?, String?> {
        val sup = queryGaia(transport, FEAT_BATTERY, 0x00)
        // Legacy test/device response: [statusOrType, level] or [0, pct]
        val ids = if (sup != null && sup.isNotEmpty() && sup.all { (it.toInt() and 0xFF) in 0..3 } && sup.size <= 4) {
            sup
        } else {
            byteArrayOf(0, 1, 2, 3)
        }
        val levels = queryGaia(transport, FEAT_BATTERY, 0x01, ids)
        if (levels != null && levels.size >= 2 && levels.size % 2 == 0) {
            var single: Int? = null
            var left: Int? = null
            var right: Int? = null
            var case: Int? = null
            var i = 0
            while (i + 1 < levels.size) {
                val id = levels[i].toInt() and 0xFF
                val pct = levels[i + 1].toInt() and 0xFF
                if (pct in 0..100) {
                    when (id) {
                        0 -> single = pct
                        1 -> left = pct
                        2 -> right = pct
                        3 -> case = pct
                    }
                }
                i += 2
            }
            val headline = single ?: listOfNotNull(left, right).minOrNull() ?: case
            val parts = buildList {
                left?.let { add("L $it%") }
                right?.let { add("R $it%") }
                case?.let { add("Case $it%") }
                if (isEmpty()) single?.let { add("$it%") }
            }
            if (headline != null) {
                return headline to parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { "Battery $it" }
            }
        }
        val fallback = levels?.getOrNull(1)?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }
            ?: sup?.getOrNull(1)?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }
        return fallback to null
    }

    private suspend fun readAncV3(transport: DacTransport, extras: MutableMap<String, DacControlValue>): Boolean {
        val mode = queryGaia(transport, FEAT_ANC_V3, 0x03)?.getOrNull(0)?.toInt()?.and(0xFF) ?: return false
        extras[KEY_ANC_V3] = DacControlValue(intValue = mode.coerceIn(0, 4))
        queryGaia(transport, FEAT_ANC_V3, 0x05)?.getOrNull(0)?.let {
            extras[KEY_ANC_V3_WIND] = DacControlValue(boolValue = (it.toInt() and 0xFF) == 1)
        }
        queryGaia(transport, FEAT_ANC_V3, 0x08)?.getOrNull(0)?.let { v ->
            val s = v.toInt() and 0xFF
            if (s in 0..1) extras[KEY_ANC_V3_STRENGTH] = DacControlValue(intValue = s)
        }
        return true
    }

    private suspend fun readAncV2(transport: DacTransport, extras: MutableMap<String, DacControlValue>): Boolean {
        val mode = queryGaia(transport, FEAT_ANC_V2, 0x03)?.getOrNull(0)?.toInt()?.and(0xFF) ?: return false
        extras[KEY_ANC_V2] = DacControlValue(intValue = mode.coerceIn(0, 2))
        return true
    }

    private suspend fun readAudioCuration(transport: DacTransport, extras: MutableMap<String, DacControlValue>): Boolean {
        queryGaia(transport, FEAT_AUDIO_CURATION, 0x03)?.getOrNull(0)?.let {
            extras[KEY_AUDIO_CURATION] = DacControlValue(intValue = (it.toInt() and 0xFF).coerceIn(0, 2))
        }
        queryGaia(transport, FEAT_AUDIO_CURATION, 0x00)?.takeIf { it.size >= 2 }?.let { resp ->
            extras[KEY_ANC_AC_STATE] = DacControlValue(boolValue = (resp[1].toInt() and 0xFF) == 1)
        }
        queryGaia(transport, FEAT_AUDIO_CURATION, 41)?.takeIf { it.size >= 5 }?.let { raw ->
            cachedAncSwitchConf = raw.copyOf(5)
            val order = (raw[4].toInt() and 0xFF).coerceIn(0, 5)
            extras[KEY_ANC_AC_ORDER] = DacControlValue(intValue = order)
        }
        return extras.containsKey(KEY_AUDIO_CURATION) ||
            extras.containsKey(KEY_ANC_AC_STATE) ||
            extras.containsKey(KEY_ANC_AC_ORDER)
    }

    private suspend fun readAncV1(transport: DacTransport, extras: MutableMap<String, DacControlValue>): Boolean {
        queryGaia(transport, FEAT_ANC, 0x01)?.getOrNull(0)?.let {
            extras[KEY_ANC_V1_STATE] = DacControlValue(boolValue = (it.toInt() and 0xFF) == 1)
        }
        queryGaia(transport, FEAT_ANC, 0x03)?.getOrNull(0)?.let {
            extras[KEY_ANC_V1_MODE] = DacControlValue(intValue = (it.toInt() and 0xFF).coerceIn(0, 2))
        }
        return extras.containsKey(KEY_ANC_V1_STATE) || extras.containsKey(KEY_ANC_V1_MODE)
    }

    private fun unpackTouchNibbles(
        raw: ByteArray,
        extras: MutableMap<String, DacControlValue>,
        includeV3Extra: Boolean
    ) {
        val len = if (includeV3Extra) 7 else 5
        raw.copyInto(cachedTouchBytes, endIndex = minOf(len, raw.size))
        val slots = if (includeV3Extra) TOUCH_SLOTS + TOUCH_V3_EXTRA_SLOTS else TOUCH_SLOTS
        slots.forEach { slot ->
            val b = cachedTouchBytes[slot.byteIndex].toInt() and 0xFF
            val act = if (slot.isLeft) (b ushr 4) and 0x0F else b and 0x0F
            extras[slot.key] = DacControlValue(intValue = act.coerceIn(0, 15))
        }
    }

    override suspend fun setEqEnabled(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean =
        sendGaia(transport, FEAT_MUSIC_PROCESSING, PDU_MP_SET_SELECTED_EQ, byteArrayOf((if (enabled) PRESET_USER_PEQ else 0).toByte()))

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean =
        sendGaia(transport, FEAT_MUSIC_PROCESSING, PDU_MP_SET_SELECTED_EQ, byteArrayOf((presetId and 0xFF).toByte()))

    override suspend fun setGlobalGain(transport: DacTransport, profile: DacProfile, gainDb: Double): Boolean {
        val count = (profile.bandCount.takeIf { it > 0 } ?: return false).coerceIn(1, 10)
        cachedGlobalGain = gainDb.coerceIn(-12.0, 12.0)
        val bands = if (cachedBands.isNotEmpty()) cachedBands else DefaultBands.flatBands(count)
        return writeAllEqBands(transport, profile, bands, cachedGlobalGain, PRESET_USER_PEQ)
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val count = (profile.bandCount.takeIf { it > 0 } ?: return false).coerceIn(1, 10)
        val base = if (cachedBands.isNotEmpty()) cachedBands else DefaultBands.flatBands(count)
        val updated = if (base.any { it.index == band.index }) base.map { if (it.index == band.index) band else it } else base + band
        cachedBands = updated
        return writeAllEqBands(transport, profile, updated, globalGainDb, presetId)
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        if (bands.isEmpty()) return false
        cachedBands = bands
        cachedGlobalGain = globalGainDb.coerceIn(-12.0, 12.0)
        // Moondrop Link bp4.java splits PEQ writes into 7-band chunks on FEAT_MUSIC_PROCESSING PDU 0x06.
        var offset = 0
        while (offset < bands.size) {
            val chunkCount = minOf(EQ_CHUNK_BANDS, bands.size - offset)
            val startIdx = bands[offset].index.coerceAtLeast(offset)
            val endIdx = startIdx + chunkCount - 1
            val payload = ByteArray(4 + chunkCount * 7)
            payload[0] = (startIdx and 0xFF).toByte()
            payload[1] = (endIdx and 0xFF).toByte()
            BytePack.u16Be(payload, 2, (cachedGlobalGain * 60.0).roundToInt())
            for (i in 0 until chunkCount) {
                val b = bands[offset + i]
                val pOff = 4 + i * 7
                BytePack.u16Be(payload, pOff, b.frequencyHz.coerceIn(20, 20000))
                BytePack.u16Be(payload, pOff + 2, (b.q * 4096.0).roundToInt().coerceIn(256, 65535))
                payload[pOff + 4] = b.filterType.fiioCode.toByte()
                BytePack.u16Be(payload, pOff + 5, (b.gainDb.coerceIn(-12.0, 12.0) * 60.0).roundToInt())
            }
            if (!sendGaia(transport, FEAT_MUSIC_PROCESSING, PDU_MP_SET_USER_EQ, payload)) return false
            offset += chunkCount
        }
        return true
    }

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean = when (key) {
        KEY_ANC_V3 -> sendGaia(transport, FEAT_ANC_V3, 0x04, byteArrayOf(value.intValue.coerceIn(0, 4).toByte()))
        KEY_ANC_V3_WIND -> sendGaia(transport, FEAT_ANC_V3, 0x06, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_ANC_V3_STRENGTH -> sendGaia(transport, FEAT_ANC_V3, 0x07, byteArrayOf(value.intValue.coerceIn(0, 1).toByte()))
        KEY_ANC_V2 -> sendGaia(transport, FEAT_ANC_V2, 0x04, byteArrayOf(value.intValue.coerceIn(0, 2).toByte()))
        KEY_AUDIO_CURATION -> sendGaia(transport, FEAT_AUDIO_CURATION, 0x04, byteArrayOf((value.intValue and 0xFF).toByte()))
        KEY_ANC_AC_STATE -> sendGaia(transport, FEAT_AUDIO_CURATION, 0x01, byteArrayOf(0x01, if (value.boolValue) 1 else 0))
        KEY_ANC_AC_ORDER -> {
            cachedAncSwitchConf[4] = value.intValue.coerceIn(0, 5).toByte()
            sendGaia(transport, FEAT_AUDIO_CURATION, 42, cachedAncSwitchConf.copyOf(5))
        }
        KEY_ANC_V1_STATE -> sendGaia(transport, FEAT_ANC, 0x02, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_ANC_V1_MODE -> sendGaia(transport, FEAT_ANC, 0x04, byteArrayOf(value.intValue.coerceIn(0, 2).toByte()))
        KEY_DAC_GAIN -> sendGaia(transport, FEAT_DAC_GAIN, 0x02, byteArrayOf(value.intValue.coerceIn(0, 2).toByte()))
        KEY_DYBASS -> sendGaia(transport, FEAT_DYBASS, 0x02, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_SPATIAL -> sendGaia(transport, FEAT_SPATIAL_AUDIO, 0x02, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_HEAD_TRACK -> sendGaia(transport, FEAT_SPATIAL_AUDIO, 0x04, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_DUAL_ENC -> sendGaia(transport, FEAT_DUAL_MIC_ENC, 0x02, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_EQ_POST_GAIN -> sendGaia(transport, FEAT_EQ_POST_GAIN, 0x02, byteArrayOf(value.intValue.coerceIn(0, 150).toByte()))
        KEY_EQ_POST_GAIN_SW -> sendGaia(transport, FEAT_EQ_POST_GAIN, 0x04, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_LR_SWAP -> sendGaia(transport, FEAT_LR_CHANNEL, 0x02, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_LED -> sendGaia(transport, FEAT_LED, 0x02, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_IN_EAR -> sendGaia(transport, FEAT_LIGHT_SENSOR, 0x02, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_ONE_BRING_TWO -> sendGaia(transport, FEAT_ONEBRINGTWO, 0x02, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_POWER_OFF_EN -> {
            cachedPowerTimeouts[0] = if (value.boolValue) 1 else 0
            sendGaia(transport, FEAT_POWER_TIMEOUT, 0x02, byteArrayOf(cachedPowerTimeouts[0].toByte(), cachedPowerTimeouts[1].toByte()))
        }
        KEY_POWER_OFF_MIN -> {
            cachedPowerTimeouts[1] = value.intValue.coerceIn(1, 255)
            sendGaia(transport, FEAT_POWER_TIMEOUT, 0x02, byteArrayOf(cachedPowerTimeouts[0].toByte(), cachedPowerTimeouts[1].toByte()))
        }
        KEY_STANDBY_EN -> {
            cachedPowerTimeouts[2] = if (value.boolValue) 1 else 0
            sendGaia(transport, FEAT_POWER_TIMEOUT, 0x04, byteArrayOf(cachedPowerTimeouts[2].toByte(), cachedPowerTimeouts[3].toByte()))
        }
        KEY_STANDBY_MIN -> {
            cachedPowerTimeouts[3] = value.intValue.coerceIn(1, 255)
            sendGaia(transport, FEAT_POWER_TIMEOUT, 0x04, byteArrayOf(cachedPowerTimeouts[2].toByte(), cachedPowerTimeouts[3].toByte()))
        }
        KEY_TOUCH_KNOCK -> sendGaia(transport, FEAT_TOUCH_V4, 0x02, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_FIND_EARBUD -> sendGaia(transport, FEAT_FIND_EARBUD, 0x01, byteArrayOf(0x02))
        else -> {
            val slot = (TOUCH_SLOTS + TOUCH_V3_EXTRA_SLOTS).firstOrNull { it.key == key } ?: return false
            val act = value.intValue.coerceIn(0, 15)
            val old = cachedTouchBytes[slot.byteIndex].toInt() and 0xFF
            val updated = if (slot.isLeft) ((act shl 4) or (old and 0x0F)) else ((old and 0xF0) or act)
            cachedTouchBytes[slot.byteIndex] = updated.toByte()
            val setPdu = if (cachedTouchFeature == FEAT_TOUCH_V4) 0x05 else 0x03
            val touchLen = if (cachedTouchFeature == FEAT_TOUCH_V3) 7 else 5
            sendGaia(transport, cachedTouchFeature, setPdu, cachedTouchBytes.copyOf(touchLen))
        }
    }

    internal fun buildGaiaV3Packet(
        featureId: Int,
        pduId: Int,
        payload: ByteArray = byteArrayOf(),
        isBle: Boolean
    ): ByteArray {
        val cmd16 = ((featureId and 0x7F) shl 9) or (pduId and 0x7F)
        return if (isBle) {
            ByteArray(4 + payload.size).also { out ->
                BytePack.u16Be(out, 0, VENDOR_QUALCOMM_V3)
                BytePack.u16Be(out, 2, cmd16)
                payload.copyInto(out, 4)
            }
        } else {
            ByteArray(8 + payload.size).also { out ->
                out[0] = 0xFF.toByte()
                out[1] = 0x03
                out[2] = 0x00
                out[3] = (payload.size and 0xFF).toByte()
                BytePack.u16Be(out, 4, VENDOR_QUALCOMM_V3)
                BytePack.u16Be(out, 6, cmd16)
                payload.copyInto(out, 8)
            }
        }
    }

    internal fun parseGaiaV3Packet(pkt: ByteArray): Pair<Int, ByteArray>? {
        val start = if (pkt.size >= 8 && (pkt[0].toInt() and 0xFF) == 0xFF && pkt[1] == 0x03.toByte()) 4 else 0
        if (pkt.size < start + 4) return null
        if (BytePack.getU16Be(pkt, start) != VENDOR_QUALCOMM_V3) return null
        val cmd16 = BytePack.getU16Be(pkt, start + 2)
        return cmd16 to pkt.copyOfRange(start + 4, pkt.size)
    }

    private suspend fun sendGaia(transport: DacTransport, featureId: Int, pduId: Int, payload: ByteArray): Boolean {
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        return transport.send(buildGaiaV3Packet(featureId, pduId, payload, isBle))
    }

    private suspend fun queryGaia(
        transport: DacTransport,
        featureId: Int,
        pduId: Int,
        payload: ByteArray = byteArrayOf()
    ): ByteArray? {
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val resp = transport.transceive(buildGaiaV3Packet(featureId, pduId, payload, isBle), expectedSize = 64, timeoutMs = 450)
            ?: return null
        val (rxCmd, body) = parseGaiaV3Packet(resp) ?: return null
        if (((rxCmd ushr 9) and 0x7F) != (featureId and 0x7F)) return null
        if ((rxCmd and 0x7F) != (pduId and 0x7F)) return null
        if (isErrorOrNotification(rxCmd)) return null
        return body
    }

    private fun isErrorOrNotification(cmd16: Int): Boolean {
        // GAIA v3 PDU type is bits 7..8: 0 = Command, 1 = Notification, 2 = Response, 3 = Error
        val pduType = (cmd16 ushr 7) and 0x03
        return pduType == 1 || pduType == 3
    }

    private fun storeFeatMask(extras: MutableMap<String, DacControlValue>, mask: Long) {
        extras[KEY_FEAT_LO] = DacControlValue(intValue = (mask and 0x7FFFFFFFL).toInt())
        extras[KEY_FEAT_HI] = DacControlValue(intValue = ((mask ushr 31) and 0x7FFFFFFFL).toInt())
    }

    private fun extractFeatMask(extras: Map<String, DacControlValue>): Long {
        val lo = (extras[KEY_FEAT_LO]?.intValue ?: 0).toLong() and 0x7FFFFFFFL
        val hi = (extras[KEY_FEAT_HI]?.intValue ?: 0).toLong() and 0x7FFFFFFFL
        return lo or (hi shl 31)
    }

    companion object {
        const val VENDOR_QUALCOMM_V3 = 0x001D
        private const val PRESET_USER_PEQ = 63
        private const val EQ_CHUNK_BANDS = 7

        private const val FEAT_BASIC = 0x00
        private const val FEAT_ANC = 0x02
        private const val FEAT_MUSIC_PROCESSING = 0x05
        private const val FEAT_AUDIO_CURATION = 0x08
        private const val FEAT_BATTERY = 0x0D
        private const val FEAT_DAC_GAIN = 0x0F
        private const val FEAT_LIGHT_SENSOR = 0x11
        private const val FEAT_SPATIAL_AUDIO = 0x12
        private const val FEAT_LED = 0x13
        private const val FEAT_ONEBRINGTWO = 0x14
        private const val FEAT_TOUCH_V2 = 0x16
        private const val FEAT_POWER_TIMEOUT = 0x19
        private const val FEAT_TOUCH_V3 = 0x1A
        private const val FEAT_DYBASS = 0x1B
        private const val FEAT_LR_CHANNEL = 0x1E
        private const val FEAT_TOUCH_V4 = 0x1F
        private const val FEAT_ANC_V2 = 0x20
        private const val FEAT_ANC_V3 = 0x21
        private const val FEAT_FIND_EARBUD = 0x22
        private const val FEAT_DUAL_MIC_ENC = 0x23
        private const val FEAT_EQ_POST_GAIN = 0x24

        private const val PDU_BASIC_GET_SUPPORTED_FEATURES = 0x01
        private const val PDU_BASIC_GET_SUPPORTED_FEATURES_NEXT = 0x02
        private const val PDU_BASIC_GET_SERIAL = 0x03
        private const val PDU_BASIC_GET_APP_VERSION = 0x05

        private const val PDU_MP_GET_EQ_STATE = 0x00
        private const val PDU_MP_GET_AVAILABLE_PRESETS = 0x01
        private const val PDU_MP_GET_SELECTED_EQ = 0x02
        private const val PDU_MP_SET_SELECTED_EQ = 0x03
        private const val PDU_MP_GET_BANDS_NUM = 0x04
        private const val PDU_MP_GET_USER_EQ = 0x05
        private const val PDU_MP_SET_USER_EQ = 0x06

        private const val KEY_FEAT_LO = "_gaia_feat_lo"
        private const val KEY_FEAT_HI = "_gaia_feat_hi"
        private const val KEY_HW_BANDS = "_gaia_hw_bands"
        private const val KEY_PRESETS_CSV = "_gaia_presets"

        private const val KEY_ANC_V3 = "gaia_anc_v3"
        private const val KEY_ANC_V3_WIND = "gaia_anc_v3_wind"
        private const val KEY_ANC_V3_STRENGTH = "gaia_anc_v3_strength"
        private const val KEY_ANC_V2 = "gaia_anc_v2"
        private const val KEY_AUDIO_CURATION = "gaia_audio_curation"
        private const val KEY_ANC_AC_STATE = "gaia_ac_state"
        private const val KEY_ANC_AC_ORDER = "gaia_ac_order"
        private const val KEY_ANC_V1_STATE = "gaia_anc_v1_state"
        private const val KEY_ANC_V1_MODE = "gaia_anc_v1_mode"
        private const val KEY_DAC_GAIN = "gaia_dac_gain"
        private const val KEY_DYBASS = "gaia_dybass"
        private const val KEY_SPATIAL = "gaia_spatial"
        private const val KEY_HEAD_TRACK = "gaia_head_track"
        private const val KEY_DUAL_ENC = "gaia_dual_enc"
        private const val KEY_EQ_POST_GAIN = "gaia_post_gain"
        private const val KEY_EQ_POST_GAIN_SW = "gaia_post_gain_sw"
        private const val KEY_LR_SWAP = "gaia_lr_swap"
        private const val KEY_LED = "gaia_led"
        private const val KEY_IN_EAR = "gaia_in_ear"
        private const val KEY_ONE_BRING_TWO = "gaia_one_bring_two"
        private const val KEY_POWER_OFF_EN = "gaia_power_off_en"
        private const val KEY_POWER_OFF_MIN = "gaia_power_off_min"
        private const val KEY_STANDBY_EN = "gaia_standby_en"
        private const val KEY_STANDBY_MIN = "gaia_standby_min"
        private const val KEY_TOUCH_KNOCK = "gaia_touch_knock"
        private const val KEY_FIND_EARBUD = "gaia_find_earbud"

        private val ANC_V3_MODES = listOf(
            NamedOption(0, "Normal (Off)"),
            NamedOption(1, "ANC On"),
            NamedOption(2, "Transparency"),
            NamedOption(3, "Adaptive ANC"),
            NamedOption(4, "Wind Noise")
        )

        private val ANC_BASIC_MODES = listOf(
            NamedOption(0, "Normal (Off)"),
            NamedOption(1, "ANC On"),
            NamedOption(2, "Transparency")
        )

        private val ANC_CYCLE_ORDERS = listOf(
            NamedOption(0, "ANC On · Off · Transparency"),
            NamedOption(1, "Off · ANC On · Transparency"),
            NamedOption(2, "Transparency · ANC On · Off"),
            NamedOption(3, "ANC On · Transparency · Off"),
            NamedOption(4, "Off · Transparency · ANC On"),
            NamedOption(5, "Transparency · Off · ANC On")
        )

        private val ANC_STRENGTH_OPTIONS = listOf(
            NamedOption(0, "Standard"),
            NamedOption(1, "Deep / High")
        )

        private val DAC_GAIN_OPTIONS = listOf(
            NamedOption(0, "Preset / Mode 1"),
            NamedOption(1, "Preset / Mode 2"),
            NamedOption(2, "Preset / Mode 3")
        )

        private data class TouchSlot(val key: String, val label: String, val byteIndex: Int, val isLeft: Boolean)

        private val TOUCH_SLOTS = listOf(
            TouchSlot("gaia_touch_l_0", "Left · Single Tap", 0, true),
            TouchSlot("gaia_touch_l_1", "Left · Double Tap", 1, true),
            TouchSlot("gaia_touch_l_2", "Left · Triple Tap", 2, true),
            TouchSlot("gaia_touch_l_3", "Left · 1s Hold", 3, true),
            TouchSlot("gaia_touch_l_4", "Left · 3s Hold", 4, true),
            TouchSlot("gaia_touch_r_0", "Right · Single Tap", 0, false),
            TouchSlot("gaia_touch_r_1", "Right · Double Tap", 1, false),
            TouchSlot("gaia_touch_r_2", "Right · Triple Tap", 2, false),
            TouchSlot("gaia_touch_r_3", "Right · 1s Hold", 3, false),
            TouchSlot("gaia_touch_r_4", "Right · 3s Hold", 4, false)
        )

        private val TOUCH_V3_EXTRA_SLOTS = listOf(
            TouchSlot("gaia_touch_l_5", "Left · Double Tap Hold", 5, true),
            TouchSlot("gaia_touch_l_6", "Left · Triple Tap Hold", 6, true),
            TouchSlot("gaia_touch_r_5", "Right · Double Tap Hold", 5, false),
            TouchSlot("gaia_touch_r_6", "Right · Triple Tap Hold", 6, false)
        )

        private val TOUCH_ACTIONS = listOf(
            NamedOption(0, "Disabled"),
            NamedOption(1, "Play / Pause"),
            NamedOption(2, "Previous Track"),
            NamedOption(3, "Next Track"),
            NamedOption(4, "Volume Down"),
            NamedOption(5, "Volume Up"),
            NamedOption(6, "Voice Assistant"),
            NamedOption(7, "Noise Control Cycle")
        )
    }
}
