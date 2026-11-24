package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.BiquadCalculator
import com.suprcharg3d.dongleCTRL.dsp.DefaultBands
import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.FilterType
import com.suprcharg3d.dongleCTRL.protocol.BalanceEncoding
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
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Moondrop SPV `0x4B` superset USB protocol driver (`zv4`/`cg5`/`vh5`).
 *
 * Supports:
 * - Standard HID Interrupt & HID Control (`SET_REPORT`/`GET_REPORT` EP0) transports
 * - Vendor EP0 polling (`0x40/0x51` OUT, `0xC0/0x52` IN) for `MD-QT-072` (`0x35D8:0x012F`)
 * - 96 kHz Q2.30 biquads, DAC filter, Class AB/H, DAC/Mic gains, Pre/Post/Preset gain,
 *   ANC / ANC V3 / Anti-Wind / Switch Cycle, Dual-Mic ENC, Spatial + Head Tracking,
 *   5-nibble L/R Touch Actions (`0x24`), Battery (`0x27`), In-Ear, Prompt Tone,
 *   Power/Standby Timeouts, L/R Channel Swap, LED, and Pairing/Shutdown actions.
 */
class MoondropSpvProtocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.MOONDROP_SPV
    override val displayName: String = "Moondrop SPV (0x4B)"

    @Volatile
    private var cachedButtonNibbles: ByteArray = byteArrayOf(0, 0, 0, 0, 0)

    @Volatile
    private var cachedPowerTimeouts: IntArray = intArrayOf(0, 0, 0, 0) // powerOffEn, powerOffMin, standbyEn, standbyMin

    @Volatile
    private var cachedPromptTone: IntArray = intArrayOf(1, 50, 0) // en, vol, conf

    @Volatile
    private var cachedSpatial: IntArray = intArrayOf(0, 0) // spatialEn, headTrack

    private fun isVendorEp0(profile: DacProfile): Boolean =
        profile.schemeNo == SCHEME_SPV_VENDOR_EP0 ||
            (profile.vendorId == 0x35D8 && profile.productId == 0x012F)

    override fun buildCapabilities(profile: DacProfile): DacCapabilities =
        buildCaps(profile, supportMask = 0)

    override fun resolveCapabilities(profile: DacProfile, state: DacDeviceState): DacCapabilities =
        buildCaps(profile, supportMask = state.extraControlValues[KEY_MASK]?.intValue ?: 0)

    private fun buildCaps(profile: DacProfile, supportMask: Int): DacCapabilities {
        val extras = buildList {
            if ((supportMask and MASK_PRE_GAIN) != 0) {
                add(DacControlDescriptor(KEY_PRE_GAIN, "PEQ Pre-Gain", ControlCategory.AUDIO, ControlKind.SLIDER, min = -12, max = 12, unit = "dB"))
            }
            if ((supportMask and MASK_PRESET_GAIN) != 0) {
                add(DacControlDescriptor(KEY_PRESET_GAIN, "Preset Gain", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = PRESET_GAIN_OPTIONS))
            }
            if ((supportMask and MASK_POST_GAIN_SW) != 0) {
                add(DacControlDescriptor(KEY_POST_GAIN_SW, "EQ Post-Gain Switch", ControlCategory.AUDIO, ControlKind.SWITCH))
            }
            if ((supportMask and MASK_POST_GAIN) != 0) {
                add(DacControlDescriptor(KEY_POST_GAIN, "EQ Post-Gain (0.1 dB)", ControlCategory.AUDIO, ControlKind.SLIDER, min = 0, max = 150, unit = "×0.1 dB"))
            }
            if ((supportMask and MASK_LR_SWAP) != 0) {
                add(DacControlDescriptor(KEY_LR_SWAP, "Swap L / R Channels", ControlCategory.AUDIO, ControlKind.SWITCH))
            }
            if ((supportMask and MASK_ANC_V3) != 0) {
                add(DacControlDescriptor(KEY_ANC_V3, "ANC V3 Mode", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_V3_OPTIONS))
            } else if ((supportMask and MASK_ANC) != 0) {
                add(DacControlDescriptor(KEY_ANC, "Noise Control", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_BASIC_OPTIONS))
            }
            if ((supportMask and MASK_ANTI_WIND) != 0) {
                add(DacControlDescriptor(KEY_ANTI_WIND, "Wind Noise Reduction", ControlCategory.AUDIO, ControlKind.SWITCH))
            }
            if ((supportMask and MASK_ANC_CYCLE) != 0) {
                add(DacControlDescriptor(KEY_ANC_CYCLE, "ANC Switch Cycle", ControlCategory.AUDIO, ControlKind.BITMASK, options = ANC_CYCLE_OPTIONS))
            }
            if ((supportMask and MASK_DUAL_ENC) != 0) {
                add(DacControlDescriptor(KEY_DUAL_ENC, "Dual-Mic ENC", ControlCategory.AUDIO, ControlKind.SWITCH))
            }
            if ((supportMask and MASK_SPATIAL) != 0) {
                add(DacControlDescriptor(KEY_SPATIAL, "Spatial Audio", ControlCategory.AUDIO, ControlKind.SWITCH))
                add(DacControlDescriptor(KEY_HEAD_TRACK, "Head Tracking", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = HEAD_TRACK_OPTIONS))
            }
            if ((supportMask and MASK_HEADSET_LED) != 0) {
                add(DacControlDescriptor(KEY_HEADSET_LED, "Headset LED", ControlCategory.DISPLAY, ControlKind.SWITCH))
            }
            if ((supportMask and MASK_IN_EAR) != 0) {
                add(DacControlDescriptor(KEY_IN_EAR, "In-Ear Detection", ControlCategory.GENERAL, ControlKind.SWITCH))
            }
            if ((supportMask and MASK_PROMPT_TONE) != 0) {
                add(DacControlDescriptor(KEY_PROMPT_EN, "Prompt Tone", ControlCategory.GENERAL, ControlKind.SWITCH))
                add(DacControlDescriptor(KEY_PROMPT_VOL, "Prompt Tone Volume", ControlCategory.GENERAL, ControlKind.SLIDER, min = 0, max = 100))
            }
            if ((supportMask and MASK_AUDIO_RES) != 0) {
                add(DacControlDescriptor(KEY_AUDIO_RES, "Audio Resource", ControlCategory.BLUETOOTH, ControlKind.SINGLE_CHOICE, options = AUDIO_RES_OPTIONS))
            }
            if ((supportMask and MASK_POWER_TIMEOUT) != 0) {
                add(DacControlDescriptor(KEY_POWER_OFF_EN, "Auto Power-Off", ControlCategory.POWER, ControlKind.SWITCH))
                add(DacControlDescriptor(KEY_POWER_OFF_MIN, "Auto Power-Off Timeout", ControlCategory.POWER, ControlKind.SLIDER, min = 1, max = 255, unit = "min"))
                add(DacControlDescriptor(KEY_STANDBY_EN, "Auto Standby", ControlCategory.POWER, ControlKind.SWITCH))
                add(DacControlDescriptor(KEY_STANDBY_MIN, "Auto Standby Timeout", ControlCategory.POWER, ControlKind.SLIDER, min = 1, max = 255, unit = "min"))
            }
            if ((supportMask and MASK_BUTTONS) != 0) {
                BUTTON_SLOTS.forEach { slot ->
                    add(DacControlDescriptor(slot.key, slot.label, ControlCategory.BUTTONS, ControlKind.SINGLE_CHOICE, options = BUTTON_ACTIONS))
                }
            }
            if (profile.isHeadset) {
                add(DacControlDescriptor(KEY_CLEAR_PAIRING, "Clear Dongle Pairing", ControlCategory.BLUETOOTH, ControlKind.ACTION, isReadable = false))
                add(DacControlDescriptor(KEY_SHUTDOWN, "Power Off Headset", ControlCategory.POWER, ControlKind.ACTION, isReadable = false))
            }
            add(DacControlDescriptor(KEY_FACTORY_RESET, "Factory Reset SPV Device", ControlCategory.GENERAL, ControlKind.ACTION, isReadable = false))
        }

        return DacCapabilities(
            maxBands = profile.bandCount.coerceIn(5, 32),
            minGainDb = profile.minGainDb,
            maxGainDb = profile.maxGainDb,
            minQ = 0.25,
            maxQ = 16.0,
            supportedFilters = listOf(
                FilterType.PEAK,
                FilterType.LOW_SHELF,
                FilterType.HIGH_SHELF,
                FilterType.LOW_PASS,
                FilterType.HIGH_PASS
            ),
            sampleRate = 96_000,
            hasEqSwitch = false,
            hasGlobalGain = true,
            globalGainRange = -12.0..12.0,
            hasDacFilter = profile.supportsDacFilter || !profile.isHeadset,
            dacFilterOptions = listOf(
                NamedOption(1, "FAST-LL: Steep Drop Low Latency"),
                NamedOption(2, "FAST-PC: Steep Drop Phase Comp"),
                NamedOption(3, "SLOW-LL: Slow Roll-off Low Latency"),
                NamedOption(4, "SLOW-PC: Slow Roll-off Phase Comp"),
                NamedOption(5, "NOS: Non-Oversampling Filter")
            ),
            hasOutputMode = profile.supportsDacFilter || !profile.isHeadset,
            outputModeOptions = listOf(NamedOption(0, "Class AB"), NamedOption(1, "Class H")),
            hasChannelBalance = !profile.isHeadset,
            channelBalanceMax = 12,
            hasMicGain = profile.supportsMic,
            micGainRange = -15..15,
            hasLedControl = (supportMask and MASK_DAC_LED) != 0,
            ledOptions = if ((supportMask and MASK_DAC_LED) != 0) listOf(NamedOption(0, "Off"), NamedOption(1, "On")) else emptyList(),
            hasBattery = (supportMask and MASK_BATTERY) != 0 || profile.isHeadset,
            presets = if ((supportMask and MASK_PRESETS) != 0) {
                listOf(NamedOption(0, "Preset 0"), NamedOption(1, "Preset 1"), NamedOption(2, "Preset 2"), NamedOption(7, "Custom PEQ"))
            } else emptyList(),
            supportsRamRefresh = true,
            supportsFlashSave = true,
            extraControls = extras
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        var mask = 0
        val extras = LinkedHashMap<String, DacControlValue>()

        val fw = querySpv(transport, profile, CMD_FW_VERSION)?.let { parseAsciiPayload(it) }
        val dongleFw = if (profile.isHeadset) querySpv(transport, profile, CMD_DONGLE_FW)?.let { parseAsciiPayload(it) } else null

        val dacGainDb = querySpv(transport, profile, CMD_DAC_OFFSET)?.let {
            if (it.size >= 6) (BytePack.getI16Le(it, 4) / 256.0).coerceIn(-12.0, 12.0) else 0.0
        } ?: 0.0

        val filterIdx = querySpv(transport, profile, CMD_DAC_FILTER)?.let {
            if (it.size >= 5) {
                val v = it[4].toInt() and 0xFF
                if (v in 1..5) v else 1
            } else 1
        } ?: 1

        val outMode = (querySpv(transport, profile, CMD_OUTPUT_MODE)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0).coerceIn(0, 1)

        val micGain = if (profile.supportsMic) {
            querySpv(transport, profile, CMD_ADC_OFFSET)?.let {
                if (it.size >= 6) (BytePack.getI16Le(it, 4) / 256.0).roundToInt().coerceIn(-15, 15) else 0
            } ?: 0
        } else 0

        val balance = if (!profile.isHeadset) {
            val lResp = querySpv(transport, profile, CMD_CHANNEL_BALANCE, byteArrayOf(0, 0), reqLen = 2)
            val rResp = querySpv(transport, profile, CMD_CHANNEL_BALANCE, byteArrayOf(1, 0), reqLen = 2)
            parseBalanceQ8_8(lResp, rResp)
        } else 0

        val presetResp = querySpv(transport, profile, CMD_EQ_PRESET_IDX)
        val activePreset = if (isValidSpv(presetResp, CMD_EQ_PRESET_IDX, 1)) {
            mask = mask or MASK_PRESETS
            presetResp!![4].toInt() and 0xFF
        } else 0

        var ledMode = 0
        val dacLedResp = querySpv(transport, profile, CMD_DAC_LED)
        if (isValidSpv(dacLedResp, CMD_DAC_LED, 1)) {
            val v = dacLedResp!![4].toInt() and 0xFF
            if (v in 0..1) {
                mask = mask or MASK_DAC_LED
                ledMode = v
            }
        }

        querySpv(transport, profile, CMD_PEQ_PRE_GAIN)?.takeIf { isValidSpv(it, CMD_PEQ_PRE_GAIN, 2) && it.size >= 6 }?.let {
            val db = (BytePack.getI16Le(it, 4) / 256.0).roundToInt()
            if (db in -12..12) {
                mask = mask or MASK_PRE_GAIN
                extras[KEY_PRE_GAIN] = DacControlValue(intValue = db)
            }
        }

        querySpv(transport, profile, CMD_PRESET_GAIN)?.takeIf { isValidSpv(it, CMD_PRESET_GAIN, 1) }?.let {
            val v = it[4].toInt() and 0xFF
            if (v in 0..2) {
                mask = mask or MASK_PRESET_GAIN
                extras[KEY_PRESET_GAIN] = DacControlValue(intValue = v)
            }
        }

        querySpv(transport, profile, CMD_LR_SWAP)?.takeIf { isValidSpv(it, CMD_LR_SWAP, 1) }?.let {
            val v = it[4].toInt() and 0xFF
            if (v in 0..1) {
                mask = mask or MASK_LR_SWAP
                extras[KEY_LR_SWAP] = DacControlValue(boolValue = v == 1)
            }
        }

        querySpv(transport, profile, CMD_EQ_POST_GAIN_SW)?.takeIf { isValidSpv(it, CMD_EQ_POST_GAIN_SW, 1) }?.let {
            val v = it[4].toInt() and 0xFF
            if (v in 0..1) {
                mask = mask or MASK_POST_GAIN_SW
                extras[KEY_POST_GAIN_SW] = DacControlValue(boolValue = v == 1)
            }
        }

        querySpv(transport, profile, CMD_EQ_POST_GAIN)?.takeIf { isValidSpv(it, CMD_EQ_POST_GAIN, 1) }?.let {
            val v = it[4].toInt() and 0xFF
            if (v in 0..150) {
                mask = mask or MASK_POST_GAIN
                extras[KEY_POST_GAIN] = DacControlValue(intValue = v)
            }
        }

        var batteryPct: Int? = null
        val notes = mutableListOf<String>()
        if (!dongleFw.isNullOrBlank()) notes += "Dongle FW $dongleFw"

        if (profile.isHeadset) {
            querySpv(transport, profile, CMD_ANC_V3)?.takeIf { isValidSpv(it, CMD_ANC_V3, 1) }?.let {
                val v = it[4].toInt() and 0xFF
                if (v in 0..4) {
                    mask = mask or MASK_ANC_V3
                    extras[KEY_ANC_V3] = DacControlValue(intValue = v)
                }
            }
            if ((mask and MASK_ANC_V3) == 0) {
                querySpv(transport, profile, CMD_ANC_MODE)?.takeIf { isValidSpv(it, CMD_ANC_MODE, 1) }?.let {
                    mask = mask or MASK_ANC
                    extras[KEY_ANC] = DacControlValue(intValue = it[4].toInt() and 0xFF)
                }
            }
            querySpv(transport, profile, CMD_ANC_V3_WIND)?.takeIf { isValidSpv(it, CMD_ANC_V3_WIND, 1) }?.let {
                mask = mask or MASK_ANTI_WIND
                extras[KEY_ANTI_WIND] = DacControlValue(boolValue = (it[4].toInt() and 0xFF) == 1)
            }
            querySpv(transport, profile, CMD_ANC_V3_SWITCH_CONF)?.takeIf { isValidSpv(it, CMD_ANC_V3_SWITCH_CONF, 5) && it.size >= 9 }?.let {
                mask = mask or MASK_ANC_CYCLE
                val cycleMask = (if ((it[5].toInt() and 0xFF) == 1) BIT_CYCLE_ANC else 0) or
                    (if ((it[6].toInt() and 0xFF) == 1) BIT_CYCLE_OFF else 0) or
                    (if ((it[7].toInt() and 0xFF) == 1) BIT_CYCLE_TP else 0)
                extras[KEY_ANC_CYCLE] = DacControlValue(intValue = cycleMask)
            }
            querySpv(transport, profile, CMD_DUAL_MIC_ENC)?.takeIf { isValidSpv(it, CMD_DUAL_MIC_ENC, 1) }?.let {
                mask = mask or MASK_DUAL_ENC
                extras[KEY_DUAL_ENC] = DacControlValue(boolValue = (it[4].toInt() and 0xFF) == 1)
            }
            querySpv(transport, profile, CMD_BATTERY)?.takeIf { isValidSpv(it, CMD_BATTERY, 6) && it.size >= 10 }?.let {
                mask = mask or MASK_BATTERY
                val len = it[3].toInt() and 0xFF
                if (len == 8 && it.size >= 12) {
                    val l = BytePack.getU16Le(it, 4).coerceIn(0, 100)
                    val r = BytePack.getU16Le(it, 8).coerceIn(0, 100)
                    batteryPct = minOf(l, r)
                    notes += "Battery L $l% · R $r%"
                } else {
                    val l = (it[5].toInt() and 0xFF).coerceIn(0, 100)
                    val r = (it[7].toInt() and 0xFF).coerceIn(0, 100)
                    val c = (it[9].toInt() and 0xFF).coerceIn(0, 100)
                    batteryPct = minOf(l, r)
                    notes += "Battery L $l% · R $r% · Case $c%"
                }
            }
            querySpv(transport, profile, CMD_HEADSET_LED)?.takeIf { isValidSpv(it, CMD_HEADSET_LED, 1) }?.let {
                mask = mask or MASK_HEADSET_LED
                extras[KEY_HEADSET_LED] = DacControlValue(boolValue = (it[4].toInt() and 0xFF) == 1)
            }
            querySpv(transport, profile, CMD_IN_EAR)?.takeIf { isValidSpv(it, CMD_IN_EAR, 1) }?.let {
                mask = mask or MASK_IN_EAR
                extras[KEY_IN_EAR] = DacControlValue(boolValue = (it[4].toInt() and 0xFF) == 1)
            }
            querySpv(transport, profile, CMD_SPATIAL_AUDIO)?.takeIf { isValidSpv(it, CMD_SPATIAL_AUDIO, 2) && it.size >= 6 }?.let {
                mask = mask or MASK_SPATIAL
                val spEn = it[4].toInt() and 0xFF
                val trk = it[5].toInt() and 0xFF
                cachedSpatial = intArrayOf(spEn, trk)
                extras[KEY_SPATIAL] = DacControlValue(boolValue = spEn == 1)
                extras[KEY_HEAD_TRACK] = DacControlValue(intValue = trk)
            }
            querySpv(transport, profile, CMD_PROMPT_TONE)?.takeIf { isValidSpv(it, CMD_PROMPT_TONE, 2) && it.size >= 6 }?.let {
                mask = mask or MASK_PROMPT_TONE
                val en = it[4].toInt() and 0xFF
                val vol = (it[5].toInt() and 0xFF).coerceIn(0, 100)
                val conf = if (it.size >= 7 && (it[3].toInt() and 0xFF) >= 3) (it[6].toInt() and 0xFF) else 0
                cachedPromptTone = intArrayOf(en, vol, conf)
                extras[KEY_PROMPT_EN] = DacControlValue(boolValue = en == 1)
                extras[KEY_PROMPT_VOL] = DacControlValue(intValue = vol)
            }
            querySpv(transport, profile, CMD_AUDIO_RESOURCE)?.takeIf { isValidSpv(it, CMD_AUDIO_RESOURCE, 1) }?.let {
                mask = mask or MASK_AUDIO_RES
                extras[KEY_AUDIO_RES] = DacControlValue(intValue = it[4].toInt() and 0xFF)
            }
            querySpv(transport, profile, CMD_POWER_TIMEOUT)?.takeIf { isValidSpv(it, CMD_POWER_TIMEOUT, 4) && it.size >= 8 }?.let {
                mask = mask or MASK_POWER_TIMEOUT
                val pEn = it[4].toInt() and 0xFF
                val pMin = (it[5].toInt() and 0xFF).coerceIn(1, 255)
                val sEn = it[6].toInt() and 0xFF
                val sMin = (it[7].toInt() and 0xFF).coerceIn(1, 255)
                cachedPowerTimeouts = intArrayOf(pEn, pMin, sEn, sMin)
                extras[KEY_POWER_OFF_EN] = DacControlValue(boolValue = pEn == 1)
                extras[KEY_POWER_OFF_MIN] = DacControlValue(intValue = pMin)
                extras[KEY_STANDBY_EN] = DacControlValue(boolValue = sEn == 1)
                extras[KEY_STANDBY_MIN] = DacControlValue(intValue = sMin)
            }
            querySpv(transport, profile, CMD_BUTTON_ACTIONS, byteArrayOf(0x01), reqLen = 1)
                ?.takeIf { isValidSpv(it, CMD_BUTTON_ACTIONS, 6) && it.size >= 10 }
                ?.let {
                    mask = mask or MASK_BUTTONS
                    val packed = it.copyOfRange(5, 10)
                    cachedButtonNibbles = packed
                    BUTTON_SLOTS.forEachIndexed { _, slot ->
                        val b = packed[slot.byteIndex].toInt() and 0xFF
                        val action = if (slot.isLeft) (b ushr 4) and 0x0F else b and 0x0F
                        extras[slot.key] = DacControlValue(intValue = action.coerceIn(0, 7))
                    }
                }
        }

        extras[KEY_MASK] = DacControlValue(intValue = mask)

        val bandCount = profile.bandCount.coerceIn(5, 32)
        val flat = DefaultBands.flatBands(bandCount)
        val bands = flat.mapIndexed { idx, defaultBand ->
            val resp = querySpv(transport, profile, CMD_EQ_DATA, byteArrayOf(0x00, idx.toByte()), reqLen = 2)
            if (resp != null && resp.size >= 35) {
                val off = if ((resp[3].toInt() and 0xFF) >= 31 || BytePack.getU16Le(resp, 28) in 20..20000) 28 else 8
                if (off + 7 <= resp.size) {
                    val freq = BytePack.getU16Le(resp, off)
                    val qRaw = BytePack.getU16Le(resp, off + 2)
                    val gainRaw = BytePack.getI16Le(resp, off + 4)
                    val fType = resp[off + 6].toInt() and 0xFF
                    if (freq in 20..20000 && qRaw > 0) {
                        EqBand(
                            index = idx,
                            frequencyHz = freq,
                            gainDb = (gainRaw / 256.0).coerceIn(profile.minGainDb, profile.maxGainDb),
                            q = (qRaw / 256.0).coerceIn(0.25, 16.0),
                            filterType = FilterType.fromCbCode(fType)
                        )
                    } else defaultBand
                } else defaultBand
            } else defaultBand
        }

        return DacDeviceState(
            firmwareVersion = fw,
            eqEnabled = true,
            activePresetId = activePreset,
            globalGainDb = dacGainDb,
            bands = bands,
            dacFilterIndex = filterIdx,
            outputModeIndex = outMode,
            channelBalance = balance.coerceIn(-12, 12),
            micGainDb = micGain,
            ledMode = ledMode,
            batteryPercent = batteryPct,
            extraControlValues = extras,
            diagnosticNote = notes.joinToString(" · ").ifBlank { null }
        )
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val coeffs = BiquadCalculator.computeForCbQ2_30(band, 96_000)
        val q30 = BiquadCalculator.quantizeCbQ2_30(coeffs)
        val pkt = ByteArray(64)
        pkt[0] = REPORT_ID.toByte()
        pkt[1] = 0x01
        pkt[2] = CMD_EQ_DATA.toByte()
        pkt[3] = 33
        pkt[4] = 0x00
        pkt[5] = (band.index and 0xFF).toByte()
        var off = 8
        for (c in q30) {
            BytePack.i32Le(pkt, off, c)
            off += 4
        }
        BytePack.u16Le(pkt, 28, band.frequencyHz.coerceIn(20, 20000))
        BytePack.u16Le(pkt, 30, (band.q * 256.0).roundToInt().coerceIn(25, 16 * 256))
        BytePack.u16Le(pkt, 32, (band.gainDb * 256.0).roundToInt())
        pkt[34] = band.filterType.cbCode.toByte()
        pkt[36] = 0x07 // SpvProtocol marker verified from cg5.B
        return sendSpv(transport, profile, pkt)
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        setGlobalGain(transport, profile, globalGainDb)
        delay(30)
        for (band in bands) {
            if (!writeEqBand(transport, profile, band, globalGainDb, presetId)) return false
            delay(35)
        }
        return refreshEqRam(transport, profile)
    }

    override suspend fun refreshEqRam(transport: DacTransport, profile: DacProfile): Boolean =
        sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_REFRESH_EQ, 0x04, 0x00, 0x00, 0xFF, 0xFF))

    override suspend fun saveToFlash(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        if (!refreshEqRam(transport, profile)) return false
        delay(80)
        return sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_SAVE_FLASH, 0x00))
    }

    override suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean =
        sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_RESET_EQ, 0x00))

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean =
        sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_EQ_PRESET_IDX, 0x01, presetId and 0xFF))

    override suspend fun setGlobalGain(transport: DacTransport, profile: DacProfile, gainDb: Double): Boolean {
        val raw = (gainDb.coerceIn(-12.0, 12.0) * 256.0).roundToInt()
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_OFFSET, 0x02)
        BytePack.u16Le(pkt, 4, raw)
        return sendSpv(transport, profile, pkt)
    }

    override suspend fun setMicGain(transport: DacTransport, profile: DacProfile, gainDb: Int): Boolean {
        val raw = gainDb.coerceIn(-15, 15) * 256
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_ADC_OFFSET, 0x02)
        BytePack.u16Le(pkt, 4, raw)
        return sendSpv(transport, profile, pkt)
    }

    override suspend fun setDacFilter(transport: DacTransport, profile: DacProfile, filterIndex: Int): Boolean =
        sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_FILTER, 0x01, filterIndex.coerceIn(1, 5)))

    override suspend fun setOutputMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean =
        sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_OUTPUT_MODE, 0x01, modeIndex.coerceIn(0, 1)))

    override suspend fun setLedMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean =
        sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_LED, 0x01, mode.coerceIn(0, 1)))

    override suspend fun setChannelBalance(transport: DacTransport, profile: DacProfile, balance: Int): Boolean {
        val clamped = balance.coerceIn(-12, 12)
        val leftSlider = if (clamped > 0) -clamped else 0
        val rightSlider = if (clamped < 0) clamped else 0
        val pL = BytePack.fixed64(REPORT_ID, 0x01, CMD_CHANNEL_BALANCE, 0x04, 0x00)
        BytePack.u16Le(pL, 5, leftSlider * 256)
        val pR = BytePack.fixed64(REPORT_ID, 0x01, CMD_CHANNEL_BALANCE, 0x04, 0x01)
        BytePack.u16Le(pR, 5, rightSlider * 256)
        val okL = sendSpv(transport, profile, pL)
        delay(15)
        return okL && sendSpv(transport, profile, pR)
    }

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean = when (key) {
        KEY_PRE_GAIN -> {
            val raw = value.intValue.coerceIn(-12, 12) * 256
            val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_PEQ_PRE_GAIN, 0x02)
            BytePack.u16Le(pkt, 4, raw)
            if (!sendSpv(transport, profile, pkt)) false else {
                delay(40)
                sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_COMMIT_PRE_GAIN, 0x00))
            }
        }
        KEY_PRESET_GAIN -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_PRESET_GAIN, 0x01, value.intValue.coerceIn(0, 2)))
        KEY_POST_GAIN_SW -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_EQ_POST_GAIN_SW, 0x01, if (value.boolValue) 1 else 0))
        KEY_POST_GAIN -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_EQ_POST_GAIN, 0x01, value.intValue.coerceIn(0, 150)))
        KEY_LR_SWAP -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_LR_SWAP, 0x01, if (value.boolValue) 1 else 0))
        KEY_ANC_V3 -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_ANC_V3, 0x01, value.intValue.coerceIn(0, 4)))
        KEY_ANC -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_ANC_MODE, 0x01, value.intValue))
        KEY_ANTI_WIND -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_ANC_V3_WIND, 0x01, if (value.boolValue) 1 else 0))
        KEY_ANC_CYCLE -> {
            val m = value.intValue
            sendSpv(
                transport,
                profile,
                BytePack.fixed64(
                    REPORT_ID, 0x01, CMD_ANC_V3_SWITCH_CONF, 0x05,
                    0xFF,
                    if ((m and BIT_CYCLE_ANC) != 0) 1 else 0,
                    if ((m and BIT_CYCLE_OFF) != 0) 1 else 0,
                    if ((m and BIT_CYCLE_TP) != 0) 1 else 0,
                    0xFF
                )
            )
        }
        KEY_DUAL_ENC -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_DUAL_MIC_ENC, 0x01, if (value.boolValue) 1 else 0))
        KEY_HEADSET_LED -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_HEADSET_LED, 0x01, if (value.boolValue) 1 else 0))
        KEY_IN_EAR -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_IN_EAR, 0x01, if (value.boolValue) 1 else 0))
        KEY_SPATIAL -> {
            cachedSpatial[0] = if (value.boolValue) 1 else 0
            sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_SPATIAL_AUDIO, 0x02, cachedSpatial[0], cachedSpatial[1]))
        }
        KEY_HEAD_TRACK -> {
            cachedSpatial[1] = value.intValue.coerceIn(0, 2)
            sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_SPATIAL_AUDIO, 0x02, cachedSpatial[0], cachedSpatial[1]))
        }
        KEY_PROMPT_EN -> {
            cachedPromptTone[0] = if (value.boolValue) 1 else 0
            sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_PROMPT_TONE, 0x03, cachedPromptTone[0], cachedPromptTone[1], cachedPromptTone[2]))
        }
        KEY_PROMPT_VOL -> {
            cachedPromptTone[1] = value.intValue.coerceIn(0, 100)
            sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_PROMPT_TONE, 0x03, cachedPromptTone[0], cachedPromptTone[1], cachedPromptTone[2]))
        }
        KEY_AUDIO_RES -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_AUDIO_RESOURCE, 0x01, value.intValue and 0xFF))
        KEY_POWER_OFF_EN -> {
            cachedPowerTimeouts[0] = if (value.boolValue) 1 else 0
            sendPowerTimeout(transport, profile)
        }
        KEY_POWER_OFF_MIN -> {
            cachedPowerTimeouts[1] = value.intValue.coerceIn(1, 255)
            sendPowerTimeout(transport, profile)
        }
        KEY_STANDBY_EN -> {
            cachedPowerTimeouts[2] = if (value.boolValue) 1 else 0
            sendPowerTimeout(transport, profile)
        }
        KEY_STANDBY_MIN -> {
            cachedPowerTimeouts[3] = value.intValue.coerceIn(1, 255)
            sendPowerTimeout(transport, profile)
        }
        KEY_CLEAR_PAIRING -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_CLEAR_PAIRING, 0x00))
        KEY_SHUTDOWN -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_SHUTDOWN, 0x00))
        KEY_FACTORY_RESET -> sendSpv(transport, profile, BytePack.fixed64(REPORT_ID, 0x01, CMD_FACTORY_RESET, 0x00))
        else -> {
            val slot = BUTTON_SLOTS.firstOrNull { it.key == key } ?: return false
            val act = value.intValue.coerceIn(0, 7)
            val old = cachedButtonNibbles[slot.byteIndex].toInt() and 0xFF
            val updated = if (slot.isLeft) ((act shl 4) or (old and 0x0F)) else ((old and 0xF0) or act)
            cachedButtonNibbles[slot.byteIndex] = updated.toByte()
            sendSpv(
                transport,
                profile,
                BytePack.fixed64(
                    REPORT_ID, 0x01, CMD_BUTTON_ACTIONS, 0x06, 0x01,
                    cachedButtonNibbles[0].toInt() and 0xFF,
                    cachedButtonNibbles[1].toInt() and 0xFF,
                    cachedButtonNibbles[2].toInt() and 0xFF,
                    cachedButtonNibbles[3].toInt() and 0xFF,
                    cachedButtonNibbles[4].toInt() and 0xFF
                )
            )
        }
    }

    private suspend fun sendPowerTimeout(transport: DacTransport, profile: DacProfile): Boolean =
        sendSpv(
            transport,
            profile,
            BytePack.fixed64(
                REPORT_ID, 0x01, CMD_POWER_TIMEOUT, 0x04,
                cachedPowerTimeouts[0], cachedPowerTimeouts[1], cachedPowerTimeouts[2], cachedPowerTimeouts[3]
            )
        )

    private suspend fun sendSpv(transport: DacTransport, profile: DacProfile, pkt: ByteArray): Boolean {
        if (!isVendorEp0(profile)) return transport.send(pkt)
        return transport.controlTransfer(0x40, 0x51, 0, 0, pkt.copyOf(64), timeoutMs = 1000) >= 0
    }

    private suspend fun querySpv(
        transport: DacTransport,
        profile: DacProfile,
        cmd: Int,
        extra: ByteArray = byteArrayOf(),
        reqLen: Int = 0
    ): ByteArray? {
        val req = BytePack.fixed64(REPORT_ID, 0x80, cmd, reqLen)
        extra.copyInto(req, destinationOffset = 4, startIndex = 0, endIndex = extra.size.coerceAtMost(60))
        if (!isVendorEp0(profile)) {
            return transport.transceive(req, expectedSize = 64, timeoutMs = 450)
        }
        val sent = transport.controlTransfer(0x40, 0x51, 0, 0, req, timeoutMs = 1000)
        if (sent < 0) return null
        val rx = ByteArray(64)
        repeat(6) {
            delay(12)
            val got = transport.controlTransfer(0xC0, 0x52, 0, 0, rx, timeoutMs = 1000)
            if (got >= 4 && (rx[0].toInt() and 0xFF) == REPORT_ID && (rx[2].toInt() and 0xFF) == cmd) {
                return rx.copyOf(got)
            }
        }
        return null
    }

    private fun isValidSpv(buf: ByteArray?, cmd: Int, minLen: Int): Boolean {
        if (buf == null || buf.size < 4 + minLen) return false
        return (buf[0].toInt() and 0xFF) == REPORT_ID &&
            (buf[1].toInt() and 0xFF) == 0x80 &&
            (buf[2].toInt() and 0xFF) == cmd &&
            (buf[3].toInt() and 0xFF) >= minLen
    }

    private fun parseAsciiPayload(buf: ByteArray): String? {
        val len = (buf.getOrNull(3)?.toInt() ?: 0) and 0xFF
        if (len !in 1..58 || 4 + len > buf.size) return null
        return buf.copyOfRange(4, 4 + len).takeWhile { it != 0.toByte() }.toByteArray().decodeToString().trim().ifEmpty { null }
    }

    private fun parseBalanceQ8_8(leftResp: ByteArray?, rightResp: ByteArray?): Int {
        fun dec(buf: ByteArray?): Int {
            if (buf == null || buf.size < 7) return 0
            val raw = if (buf.size >= 8 && buf[5] == 0.toByte() && buf[6] == 0.toByte() && buf[7] != 0.toByte()) {
                BytePack.getI16Le(buf, 6)
            } else {
                BytePack.getI16Le(buf, 5)
            }
            return (raw / 256.0).roundToInt()
        }
        val l = dec(leftResp)
        val r = dec(rightResp)
        return if (l < 0) abs(l) else if (r < 0) -abs(r) else 0
    }

    companion object {
        const val REPORT_ID = 0x4B
        const val SCHEME_SPV_VENDOR_EP0 = "spv_vendor_ep0"

        private const val CMD_SAVE_FLASH = 0x01
        private const val CMD_ADC_OFFSET = 0x02
        private const val CMD_DAC_OFFSET = 0x03
        private const val CMD_COMMIT_PRE_GAIN = 0x04
        private const val CMD_RESET_EQ = 0x05
        private const val CMD_EQ_DATA = 0x09
        private const val CMD_REFRESH_EQ = 0x0A
        private const val CMD_FW_VERSION = 0x0C
        private const val CMD_DONGLE_FW = 0x0D
        private const val CMD_EQ_PRESET_IDX = 0x0F
        private const val CMD_DAC_FILTER = 0x11
        private const val CMD_CHANNEL_BALANCE = 0x16
        private const val CMD_FACTORY_RESET = 0x17
        private const val CMD_DAC_LED = 0x18
        private const val CMD_OUTPUT_MODE = 0x1D
        private const val CMD_PEQ_PRE_GAIN = 0x23
        private const val CMD_BUTTON_ACTIONS = 0x24
        private const val CMD_ANC_MODE = 0x25
        private const val CMD_PRESET_GAIN = 0x26
        private const val CMD_BATTERY = 0x27
        private const val CMD_HEADSET_LED = 0x28
        private const val CMD_IN_EAR = 0x29
        private const val CMD_SPATIAL_AUDIO = 0x2A
        private const val CMD_PROMPT_TONE = 0x2B
        private const val CMD_AUDIO_RESOURCE = 0x2D
        private const val CMD_POWER_TIMEOUT = 0x2E
        private const val CMD_SHUTDOWN = 0x2F
        private const val CMD_LR_SWAP = 0x30
        private const val CMD_ANC_V3_WIND = 0x32
        private const val CMD_ANC_V3 = 0x33
        private const val CMD_DUAL_MIC_ENC = 0x34
        private const val CMD_ANC_V3_SWITCH_CONF = 0x35
        private const val CMD_CLEAR_PAIRING = 0x36
        private const val CMD_EQ_POST_GAIN = 0x37
        private const val CMD_EQ_POST_GAIN_SW = 0x38

        private const val MASK_PRE_GAIN = 1 shl 0
        private const val MASK_PRESET_GAIN = 1 shl 1
        private const val MASK_POST_GAIN_SW = 1 shl 2
        private const val MASK_POST_GAIN = 1 shl 3
        private const val MASK_LR_SWAP = 1 shl 4
        private const val MASK_DAC_LED = 1 shl 5
        private const val MASK_ANC = 1 shl 6
        private const val MASK_ANC_V3 = 1 shl 7
        private const val MASK_ANTI_WIND = 1 shl 8
        private const val MASK_ANC_CYCLE = 1 shl 9
        private const val MASK_DUAL_ENC = 1 shl 10
        private const val MASK_BATTERY = 1 shl 11
        private const val MASK_HEADSET_LED = 1 shl 12
        private const val MASK_IN_EAR = 1 shl 13
        private const val MASK_SPATIAL = 1 shl 14
        private const val MASK_PROMPT_TONE = 1 shl 15
        private const val MASK_AUDIO_RES = 1 shl 16
        private const val MASK_POWER_TIMEOUT = 1 shl 17
        private const val MASK_BUTTONS = 1 shl 18
        private const val MASK_PRESETS = 1 shl 19

        private const val KEY_MASK = "_spv_mask"
        private const val KEY_PRE_GAIN = "spv_pre_gain"
        private const val KEY_PRESET_GAIN = "spv_preset_gain"
        private const val KEY_POST_GAIN_SW = "spv_post_gain_sw"
        private const val KEY_POST_GAIN = "spv_post_gain"
        private const val KEY_LR_SWAP = "spv_lr_swap"
        private const val KEY_ANC = "spv_anc"
        private const val KEY_ANC_V3 = "spv_anc_v3"
        private const val KEY_ANTI_WIND = "spv_anti_wind"
        private const val KEY_ANC_CYCLE = "spv_anc_cycle"
        private const val KEY_DUAL_ENC = "spv_dual_enc"
        private const val KEY_HEADSET_LED = "spv_headset_led"
        private const val KEY_IN_EAR = "spv_in_ear"
        private const val KEY_SPATIAL = "spv_spatial"
        private const val KEY_HEAD_TRACK = "spv_head_track"
        private const val KEY_PROMPT_EN = "spv_prompt_en"
        private const val KEY_PROMPT_VOL = "spv_prompt_vol"
        private const val KEY_AUDIO_RES = "spv_audio_res"
        private const val KEY_POWER_OFF_EN = "spv_power_off_en"
        private const val KEY_POWER_OFF_MIN = "spv_power_off_min"
        private const val KEY_STANDBY_EN = "spv_standby_en"
        private const val KEY_STANDBY_MIN = "spv_standby_min"
        private const val KEY_CLEAR_PAIRING = "spv_clear_pairing"
        private const val KEY_SHUTDOWN = "spv_shutdown"
        private const val KEY_FACTORY_RESET = "spv_factory_reset"

        private const val BIT_CYCLE_ANC = 1 shl 0
        private const val BIT_CYCLE_OFF = 1 shl 1
        private const val BIT_CYCLE_TP = 1 shl 2

        private val PRESET_GAIN_OPTIONS = listOf(NamedOption(0, "Low"), NamedOption(1, "Medium"), NamedOption(2, "High"))
        private val ANC_BASIC_OPTIONS = listOf(NamedOption(0, "Off"), NamedOption(1, "ANC"), NamedOption(2, "Transparency"))
        private val ANC_V3_OPTIONS = listOf(NamedOption(0, "Off"), NamedOption(1, "ANC"), NamedOption(2, "Transparency"), NamedOption(3, "Adaptive ANC"), NamedOption(4, "Wind Noise"))
        private val ANC_CYCLE_OPTIONS = listOf(NamedOption(BIT_CYCLE_ANC, "ANC On"), NamedOption(BIT_CYCLE_OFF, "Off"), NamedOption(BIT_CYCLE_TP, "Transparency"))
        private val HEAD_TRACK_OPTIONS = listOf(NamedOption(0, "Off"), NamedOption(1, "Static"), NamedOption(2, "Head Tracking"))
        private val AUDIO_RES_OPTIONS = listOf(NamedOption(0, "Bluetooth"), NamedOption(1, "USB Audio"))

        private data class SpvBtnSlot(val key: String, val label: String, val byteIndex: Int, val isLeft: Boolean)

        private val BUTTON_SLOTS = listOf(
            SpvBtnSlot("spv_btn_l_0", "Left · Single Tap", 0, true),
            SpvBtnSlot("spv_btn_l_1", "Left · Double Tap", 1, true),
            SpvBtnSlot("spv_btn_l_2", "Left · Triple Tap", 2, true),
            SpvBtnSlot("spv_btn_l_3", "Left · 1s Hold", 3, true),
            SpvBtnSlot("spv_btn_l_4", "Left · 3s Hold", 4, true),
            SpvBtnSlot("spv_btn_r_0", "Right · Single Tap", 0, false),
            SpvBtnSlot("spv_btn_r_1", "Right · Double Tap", 1, false),
            SpvBtnSlot("spv_btn_r_2", "Right · Triple Tap", 2, false),
            SpvBtnSlot("spv_btn_r_3", "Right · 1s Hold", 3, false),
            SpvBtnSlot("spv_btn_r_4", "Right · 3s Hold", 4, false)
        )

        private val BUTTON_ACTIONS = listOf(
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
