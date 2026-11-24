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
 * CB / TTGK / WalkPlay / EPZ HID protocol driver (Schemes 1, 10, 11..16, 18, 20, 21, 25, 29, 30).
 * Report ID 0x4B, 64-byte HID packets, Q2.30 biquads.
 */
class CbProtocol(
    private val isSurround3dFamily: Boolean = false
) : DacProtocol {

    @Volatile
    private var lastProbeSummary: String? = null

    override val family: ProtocolFamily =
        if (isSurround3dFamily) ProtocolFamily.CB1300D else ProtocolFamily.CB

    override val displayName: String =
        if (isSurround3dFamily) "CB1300D Surround (0x4B)" else "CB / TTGK DSP (0x4B)"

    private fun isTanchjimStargate2(profile: DacProfile): Boolean =
        profile.schemeNo == SCHEME_TANCHJIM_STARGATE2

    private fun isTanchjimSpace2(profile: DacProfile): Boolean =
        profile.schemeNo == SCHEME_TANCHJIM_SPACE2

    private fun isTanchjimTtgk(profile: DacProfile): Boolean =
        isTanchjimStargate2(profile) || isTanchjimSpace2(profile)

    private fun isNicehckPureAural(profile: DacProfile): Boolean =
        profile.schemeNo == SCHEME_NICEHCK_PUREAURAL

    private fun isNicehckOctaveOrPureAural(profile: DacProfile): Boolean =
        profile.schemeNo == SCHEME_NICEHCK_OCTAVE || profile.schemeNo == SCHEME_NICEHCK_PUREAURAL

    private fun isNicehck(profile: DacProfile): Boolean =
        profile.schemeNo in NICEHCK_SCHEMES

    private fun supportsStageControls(profile: DacProfile): Boolean =
        !isSurround3dFamily && !isNicehck(profile) &&
            (profile.supportsDacFilter || profile.schemeNo in setOf("11", "16", "18") || isTanchjimSpace2(profile))

    override fun buildCapabilities(profile: DacProfile): DacCapabilities =
        buildCapabilitiesWithProbeMask(profile, probeMask = 0)

    override fun resolveCapabilities(profile: DacProfile, state: DacDeviceState): DacCapabilities {
        val mask = state.extraControlValues[KEY_PROBE_MASK]?.intValue ?: 0
        return buildCapabilitiesWithProbeMask(profile, probeMask = mask)
    }

    private fun buildCapabilitiesWithProbeMask(profile: DacProfile, probeMask: Int): DacCapabilities {
        val nicehck = isNicehck(profile)
        val nicehckFilterOrMic = isNicehckOctaveOrPureAural(profile)
        val pureAural = isNicehckPureAural(profile)
        val stage = supportsStageControls(profile)
        val isSpace2 = isTanchjimSpace2(profile)
        val isTtgk = isTanchjimTtgk(profile)
        val effectiveMask = if (isSpace2) (probeMask or PROBE_VOL or PROBE_DRE) else probeMask
        val hasProbedVol = (effectiveMask and PROBE_VOL) != 0
        val hasProbedLed = (effectiveMask and PROBE_LED) != 0
        val extras = buildList {
            if ((effectiveMask and PROBE_DRE) != 0) {
                add(
                    DacControlDescriptor(
                        key = KEY_DRE,
                        label = "DRE (Dynamic Range Enhancement)",
                        category = ControlCategory.AUDIO,
                        kind = ControlKind.SWITCH
                    )
                )
            }
            if ((effectiveMask and PROBE_SWAP) != 0) {
                add(
                    DacControlDescriptor(
                        key = KEY_CHANNEL_SWAP,
                        label = "Swap L / R Channels",
                        category = ControlCategory.AUDIO,
                        kind = ControlKind.SWITCH
                    )
                )
            }
            if ((effectiveMask and PROBE_PRE_GAIN) != 0) {
                add(
                    DacControlDescriptor(
                        key = KEY_PEQ_PRE_GAIN,
                        label = "PEQ Pre-Gain",
                        category = ControlCategory.AUDIO,
                        kind = ControlKind.SLIDER,
                        min = -12,
                        max = 12,
                        step = 1,
                        unit = "dB"
                    )
                )
            }
            if (stage && !profile.isHeadset) {
                add(
                    DacControlDescriptor(
                        key = KEY_PROBE_DIAG,
                        label = "Probe Extended CB Registers",
                        category = ControlCategory.GENERAL,
                        kind = ControlKind.ACTION,
                        isReadable = false
                    )
                )
            }
        }
        return DacCapabilities(
            maxBands = profile.bandCount.coerceIn(1, 15),
            minGainDb = profile.minGainDb,
            maxGainDb = profile.maxGainDb,
            minQ = 0.25,
            maxQ = 16.0,
            supportedFilters = if (nicehck) {
                listOf(FilterType.PEAK)
            } else {
                listOf(
                    FilterType.PEAK,
                    FilterType.LOW_SHELF,
                    FilterType.HIGH_SHELF,
                    FilterType.LOW_PASS,
                    FilterType.HIGH_PASS
                )
            },
            sampleRate = profile.sampleRate,
            presets = if (nicehck) NicehckPresets.optionsFor(profile.schemeNo) else emptyList(),
            presetCategories = if (nicehck) NicehckPresets.categoriesFor(profile.schemeNo) else emptyMap(),
            hasEqSwitch = false,
            hasGlobalGain = true,
            globalGainRange = if (nicehck) -12.0..8.0 else -12.0..12.0,
            hasDacFilter = if (nicehck) nicehckFilterOrMic else stage,
            dacFilterOptions = listOf(
                NamedOption(1, "FAST-LL: Steep-Drop-Low-Latency Filter"),
                NamedOption(2, "FAST-PC: Steep Drop-Phase Compensation Filter"),
                NamedOption(3, "SLOW-LL: Slow descent-Low Latency Filter"),
                NamedOption(4, "SLOW-PC: Slow Decay-Phase Compensation Filter"),
                NamedOption(5, "NON OS: Non-Oversampling Filter")
            ),
            hasOutputMode = if (nicehck) pureAural else stage,
            outputModeOptions = if (pureAural) {
                listOf(
                    NamedOption(0, "Class H"),
                    NamedOption(1, "Class AB")
                )
            } else {
                listOf(
                    NamedOption(0, "Class AB"),
                    NamedOption(1, "Class H")
                )
            },
            hasWorkMode = if (nicehck) pureAural else stage,
            workModeOptions = if (pureAural) {
                listOf(
                    NamedOption(0, "Low Gain"),
                    NamedOption(1, "Medium Gain"),
                    NamedOption(2, "High Gain")
                )
            } else {
                listOf(
                    NamedOption(0, "Low Gain"),
                    NamedOption(1, "High Gain")
                )
            },
            hasVolume = if (nicehck) pureAural else hasProbedVol,
            maxVolume = if (profile.brand.equals("TANCHJIM", ignoreCase = true)) 60 else 100,
            hasChannelBalance = if (nicehck) nicehckFilterOrMic else true,
            channelBalanceMax = if (nicehck) 50 else 12,
            hasMicGain = if (nicehck) nicehckFilterOrMic else profile.supportsMic,
            micGainRange = if (nicehck) -12..12 else -15..15,
            hasMicMonitor = false,
            hasMicEnc = profile.supportsMic && !isTtgk && !nicehck,
            hasLedControl = hasProbedLed,
            ledOptions = if (hasProbedLed) listOf(NamedOption(0, "Off"), NamedOption(1, "On")) else emptyList(),
            hasSurround3d = isSurround3dFamily || profile.supportsSurround3d,
            // CB DACs store only a single active EQ bank in hardware RAM/Flash (no hardware preset slots);
            // pkt[36] is just an app-side eqTag metadata byte. Keep presets empty so the UI hides fake slots.
            supportsRamRefresh = true,
            supportsFlashSave = true,
            extraControls = extras
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()

        val fwResp = query(transport, CMD_FW_VERSION, 0)
        val firmware = fwResp?.let { buf ->
            val len = (buf.getOrNull(3)?.toInt() ?: 0) and 0xFF
            if (len in 1..20 && 4 + len <= buf.size) {
                buf.copyOfRange(4, 4 + len)
                    .takeWhile { it != 0.toByte() }
                    .toByteArray()
                    .decodeToString()
                    .trim()
                    .ifEmpty { null }
            } else {
                null
            }
        }

        val isTtgk = isTanchjimTtgk(profile)
        val isNicehckDevice = isNicehck(profile)
        val isNicehckFilter = isNicehckOctaveOrPureAural(profile)
        val isPureAural = isNicehckPureAural(profile)

        val dacOffsetResp = query(transport, CMD_DAC_OFFSET, if (isTtgk) 2 else 1)
        val maxDacGainDb = if (isNicehckDevice) 8.0 else 12.0
        val dacGainDb = dacOffsetResp?.let { buf ->
            if (isTtgk) {
                if (buf.size >= 6) buf[5].toInt().toDouble().coerceIn(-12.0, 12.0) else 0.0
            } else {
                if (buf.size >= 6) (BytePack.getI16Le(buf, 4) / 256.0).coerceIn(-12.0, maxDacGainDb) else 0.0
            }
        } ?: 0.0

        val filterIdx = if (!isNicehckDevice || isNicehckFilter) {
            val filterResp = query(transport, CMD_DAC_FILTER, 1)
            val rawFilterIdx = filterResp?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0
            if (rawFilterIdx in 1..5) rawFilterIdx else 1
        } else {
            1
        }

        val outMode = if (!isNicehckDevice || isPureAural) {
            query(transport, CMD_OUTPUT_MODE, 1)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0
        } else {
            0
        }

        val workMode = if (!isNicehckDevice || isPureAural) {
            query(transport, CMD_WORK_MODE, 1)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0
        } else {
            0
        }

        val encOn = if (!isTtgk && !isNicehckDevice && profile.supportsMic) {
            (query(transport, CMD_ENC, 1)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) == 1
        } else if (!isTtgk && !isNicehckDevice) {
            (query(transport, CMD_ENC, 1)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) == 1
        } else {
            false
        }

        val maxMicGain = if (isNicehckDevice) 12 else 15
        val micGain = if (!isNicehckDevice || isNicehckFilter) {
            val adcResp = query(transport, CMD_ADC_OFFSET, 1)
            adcResp?.let { buf ->
                if (isTtgk) {
                    if (buf.size >= 6) buf[5].toInt().coerceIn(-maxMicGain, maxMicGain) else 0
                } else {
                    if (buf.size >= 6) (BytePack.getI16Le(buf, 4) / 256.0).roundToInt().coerceIn(-maxMicGain, maxMicGain) else 0
                }
            } ?: 0
        } else {
            0
        }

        val balance = if (!isNicehckDevice || isNicehckFilter) {
            val balQueryLen = if (profile.balanceEncoding == BalanceEncoding.Q8_8_LE_AT_5) 2 else 4
            val leftBalResp = query(transport, CMD_CHANNEL_BALANCE, balQueryLen, extra = byteArrayOf(0, 0))
            val rightBalResp = query(transport, CMD_CHANNEL_BALANCE, balQueryLen, extra = byteArrayOf(1, 0))
            parseBalanceSlider(profile, leftBalResp, rightBalResp)
        } else {
            0
        }

        var activeTag = 0
        val flat = DefaultBands.flatBands(profile.bandCount)
        val readBands = flat.mapIndexed { idx, defaultBand ->
            val resp = query(transport, CMD_EQ_DATA, 0, byteArrayOf(0, idx.toByte()))
            if (resp != null && resp.size > 36) {
                activeTag = resp[36].toInt() and 0xFF
                val freq = BytePack.getU16Le(resp, 28)
                val qRaw = BytePack.getU16Le(resp, 30)
                val gainRaw = BytePack.getI16Le(resp, 32)
                val fType = resp[34].toInt() and 0xFF
                if (freq in 10..24000 && qRaw > 0) {
                    EqBand(
                        index = idx,
                        frequencyHz = freq,
                        gainDb = (gainRaw / 256.0).coerceIn(profile.minGainDb, profile.maxGainDb),
                        q = (qRaw / 256.0).coerceIn(0.25, 16.0),
                        filterType = FilterType.fromCbCode(fType)
                    )
                } else {
                    defaultBand
                }
            } else {
                defaultBand
            }
        }

        val surroundOn = if (isSurround3dFamily || profile.supportsSurround3d) {
            (query(transport, CMD_3D_SWITCH, 1)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) == 1
        } else {
            false
        }

        val probeExtras = LinkedHashMap<String, DacControlValue>()
        var probeMask = 0
        var hwVolume = 0
        var ledMode = 0
        var chipInfo: String? = null

        if (!isSurround3dFamily) {
            val chipResp = query(transport, CMD_CHIP_ID, 0)
            if (isValidCbReply(chipResp, CMD_CHIP_ID, minLen = 2)) {
                val cidLen = (chipResp!![3].toInt() and 0xFF).coerceIn(1, 8)
                if (4 + cidLen <= chipResp.size) {
                    chipInfo = "Chip " + (4 until 4 + cidLen).joinToString("") { "%02X".format(chipResp[it]) }
                }
            }
            if (isPureAural) {
                val volResp = query(transport, CMD_HW_VOLUME, 1)
                val v = volResp?.getOrNull(4)?.toInt()?.and(0xFF)
                if (v != null && v in 0..100) {
                    hwVolume = v
                }
            } else if (supportsStageControls(profile) && !profile.isHeadset) {
                val volResp = query(transport, CMD_HW_VOLUME, 1)
                if (isValidCbReply(volResp, CMD_HW_VOLUME, minLen = 1)) {
                    val v = volResp!![4].toInt() and 0xFF
                    if (v in 0..100) {
                        probeMask = probeMask or PROBE_VOL
                        hwVolume = v
                    }
                }
                val dreResp = query(transport, CMD_DRE, 1)
                if (isValidCbReply(dreResp, CMD_DRE, minLen = 1)) {
                    val d = dreResp!![4].toInt() and 0xFF
                    if (d in 0..1) {
                        probeMask = probeMask or PROBE_DRE
                        probeExtras[KEY_DRE] = DacControlValue(boolValue = d == 0)
                    }
                }
                val ledResp = query(transport, CMD_DAC_LED, 1)
                if (isValidCbReply(ledResp, CMD_DAC_LED, minLen = 1)) {
                    val l = ledResp!![4].toInt() and 0xFF
                    if (l in 0..2) {
                        probeMask = probeMask or PROBE_LED
                        ledMode = l
                    }
                }
                val swapResp = query(transport, CMD_CHANNEL_SWAP, 1)
                if (isValidCbReply(swapResp, CMD_CHANNEL_SWAP, minLen = 1)) {
                    val s = swapResp!![4].toInt() and 0xFF
                    if (s in 0..1) {
                        probeMask = probeMask or PROBE_SWAP
                        probeExtras[KEY_CHANNEL_SWAP] = DacControlValue(boolValue = s == 1)
                    }
                }
                val preResp = query(transport, CMD_PEQ_PRE_GAIN, 2)
                if (isValidCbReply(preResp, CMD_PEQ_PRE_GAIN, minLen = 2) && preResp!!.size >= 6) {
                    val db = (BytePack.getI16Le(preResp, 4) / 256.0).roundToInt()
                    if (db in -12..12) {
                        probeMask = probeMask or PROBE_PRE_GAIN
                        probeExtras[KEY_PEQ_PRE_GAIN] = DacControlValue(intValue = db)
                    }
                }
            }
        }
        if (isTanchjimSpace2(profile)) {
            probeMask = probeMask or PROBE_VOL or PROBE_DRE
        }
        if (probeMask != 0) {
            probeExtras[KEY_PROBE_MASK] = DacControlValue(intValue = probeMask)
        }
        val maxVol = if (profile.brand.equals("TANCHJIM", ignoreCase = true)) 60 else 100
        val maxWorkMode = if (isPureAural) 2 else 1
        val maxBalance = if (isNicehckDevice) 50 else 12

        return DacDeviceState(
            firmwareVersion = firmware,
            eqEnabled = true,
            activePresetId = activeTag,
            globalGainDb = dacGainDb,
            bands = readBands,
            dacFilterIndex = filterIdx,
            outputModeIndex = outMode.coerceIn(0, 1),
            workModeIndex = workMode.coerceIn(0, maxWorkMode),
            volume = hwVolume.coerceIn(0, maxVol),
            maxVolume = maxVol,
            channelBalance = balance.coerceIn(-maxBalance, maxBalance),
            micGainDb = micGain,
            micEncEnabled = encOn,
            surround3dEnabled = surroundOn,
            ledMode = ledMode,
            extraControlValues = probeExtras,
            diagnosticNote = lastProbeSummary ?: chipInfo
        )
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val sampleRate = if (profile.sampleRate > 0) profile.sampleRate else 96_000
        val coeffs = BiquadCalculator.computeForCbQ2_30(band, sampleRate)
        val q30 = BiquadCalculator.quantizeCbQ2_30(coeffs)

        val pkt = ByteArray(64)
        pkt[0] = REPORT_ID.toByte()
        pkt[1] = 0x01
        pkt[2] = CMD_EQ_DATA.toByte()
        pkt[3] = 24 // 4 + 20 coefficient bytes
        pkt[4] = 0x00
        pkt[5] = (band.index and 0xFF).toByte()
        pkt[6] = 0x00
        pkt[7] = 0x00
        var off = 8
        for (c in q30) {
            BytePack.i32Le(pkt, off, c)
            off += 4
        }
        val q256 = (band.q * 256.0).roundToInt().coerceIn(25, 16 * 256)
        val gain256 = (band.gainDb * 256.0).roundToInt()
        BytePack.u16Le(pkt, 28, band.frequencyHz.coerceIn(10, 24000))
        BytePack.u16Le(pkt, 30, q256)
        BytePack.u16Le(pkt, 32, gain256)
        pkt[34] = band.filterType.cbCode.toByte()
        pkt[35] = (globalGainDb.roundToInt() and 0xFF).toByte()
        pkt[36] = (presetId and 0xFF).toByte()

        return transport.send(pkt)
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        if (!setGlobalGain(transport, profile, globalGainDb)) return false
        delay(50)
        for (band in bands) {
            if (!writeEqBand(transport, profile, band, globalGainDb, presetId)) return false
            delay(50)
        }
        val refreshed = refreshEqRam(transport, profile)
        delay(50)
        return refreshed
    }

    override suspend fun refreshEqRam(transport: DacTransport, profile: DacProfile): Boolean {
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_REFRESH_EQ, 0x04, 0x00, 0x00, 0xFF, 0xFF)
        return transport.send(pkt)
    }

    override suspend fun saveToFlash(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        if (!refreshEqRam(transport, profile)) return false
        delay(120)
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_SAVE_FLASH, 0x00)
        var ok = transport.send(pkt)
        if (ok && (isTanchjimSpace2(profile) || isNicehck(profile))) {
            delay(40)
            ok = transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_POST_COMMIT, 0x00))
        }
        delay(80)
        return ok
    }

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        if (!isNicehck(profile)) return false
        val spec = NicehckPresets.find(profile.schemeNo, presetId) ?: return false
        return writeAllEqBands(transport, profile, spec.bands, spec.offsetDb, spec.id)
    }

    override suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean {
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_RESET_EQ, 0x01, 0x04)
        return transport.send(pkt)
    }

    override suspend fun setGlobalGain(transport: DacTransport, profile: DacProfile, gainDb: Double): Boolean {
        if (isTanchjimTtgk(profile)) {
            val signedByte = gainDb.roundToInt().coerceIn(-12, 12) and 0xFF
            return transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_OFFSET, 0x02, 0x00, signedByte))
        }
        if (isNicehck(profile)) {
            val clamped = gainDb.coerceIn(-12.0, 8.0)
            val raw = (clamped * 256.0).roundToInt()
            val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_OFFSET, 0x02)
            BytePack.u16Le(pkt, 4, raw)
            if (!transport.send(pkt)) return false
            delay(15)
            return transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_POST_COMMIT, 0x00))
        }
        val raw = (gainDb * 256.0).roundToInt()
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_OFFSET, 0x02)
        BytePack.u16Le(pkt, 4, raw)
        return transport.send(pkt)
    }

    override suspend fun setDacFilter(transport: DacTransport, profile: DacProfile, filterIndex: Int): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_FILTER, 0x01, filterIndex.coerceIn(1, 5)))

    override suspend fun setOutputMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_OUTPUT_MODE, 0x01, modeIndex))

    override suspend fun setWorkMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean {
        val maxMode = if (isNicehckPureAural(profile)) 2 else 1
        return transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_WORK_MODE, 0x01, modeIndex.coerceIn(0, maxMode)))
    }

    override suspend fun setChannelBalance(transport: DacTransport, profile: DacProfile, balance: Int): Boolean {
        val maxBalance = if (isNicehck(profile)) 50 else 12
        val clamped = balance.coerceIn(-maxBalance, maxBalance)
        val leftSlider = if (clamped > 0) -clamped else 0
        val rightSlider = if (clamped < 0) clamped else 0
        val (pktL, pktR) = if (profile.balanceEncoding == BalanceEncoding.Q8_8_LE_AT_5) {
            val lRaw = leftSlider * 256
            val rRaw = rightSlider * 256
            val pL = BytePack.fixed64(REPORT_ID, 0x01, CMD_CHANNEL_BALANCE, 0x04, 0x00)
            BytePack.u16Le(pL, 5, lRaw)
            val pR = BytePack.fixed64(REPORT_ID, 0x01, CMD_CHANNEL_BALANCE, 0x04, 0x01)
            BytePack.u16Le(pR, 5, rRaw)
            pL to pR
        } else {
            val leftDev = deviceValueFromSlider(leftSlider)
            val rightDev = deviceValueFromSlider(rightSlider)
            val pL = BytePack.fixed64(REPORT_ID, 0x01, CMD_CHANNEL_BALANCE, 0x04, 0x00, 0x00)
            BytePack.u16Be(pL, 6, leftDev)
            val pR = BytePack.fixed64(REPORT_ID, 0x01, CMD_CHANNEL_BALANCE, 0x04, 0x01, 0x00)
            BytePack.u16Be(pR, 6, rightDev)
            pL to pR
        }
        val okL = transport.send(pktL)
        delay(15)
        val okR = transport.send(pktR)
        return okL && okR
    }

    override suspend fun setVolume(transport: DacTransport, profile: DacProfile, volume: Int): Boolean {
        val maxVol = if (profile.brand.equals("TANCHJIM", ignoreCase = true)) 60 else 100
        val ok = transport.send(
            BytePack.fixed64(REPORT_ID, 0x01, CMD_HW_VOLUME, 0x01, volume.coerceIn(0, maxVol))
        )
        if (ok && isTanchjimSpace2(profile)) {
            delay(20)
            return transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_POST_COMMIT, 0x00))
        }
        return ok
    }

    override suspend fun setLedMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_LED, 0x01, mode.coerceIn(0, 2)))

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean = when (key) {
        KEY_DRE -> transport.send(
            BytePack.fixed64(REPORT_ID, 0x01, CMD_DRE, 0x01, if (value.boolValue) 0x00 else 0x01)
        )
        KEY_CHANNEL_SWAP -> transport.send(
            BytePack.fixed64(REPORT_ID, 0x01, CMD_CHANNEL_SWAP, 0x01, if (value.boolValue) 0x01 else 0x00)
        )
        KEY_PEQ_PRE_GAIN -> {
            val raw = value.intValue.coerceIn(-12, 12) * 256
            val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_PEQ_PRE_GAIN, 0x02)
            BytePack.u16Le(pkt, 4, raw)
            transport.send(pkt)
        }
        KEY_PROBE_DIAG -> {
            runExtendedProbeDiagnostic(transport)
            true
        }
        else -> false
    }

    override suspend fun refreshLiveTelemetry(
        transport: DacTransport,
        profile: DacProfile,
        currentState: DacDeviceState,
        includeAllControls: Boolean
    ): DacDeviceState {
        if (!includeAllControls) return currentState
        val mask = currentState.extraControlValues[KEY_PROBE_MASK]?.intValue ?: 0
        var nextVol = currentState.volume
        var nextLed = currentState.ledMode
        val nextExtras = currentState.extraControlValues.toMutableMap()
        if ((mask and PROBE_VOL) != 0) {
            val r = query(transport, CMD_HW_VOLUME, 1)
            if (isValidCbReply(r, CMD_HW_VOLUME, minLen = 1)) {
                val v = r!![4].toInt() and 0xFF
                if (v in 0..currentState.maxVolume) nextVol = v
            }
        }
        if ((mask and PROBE_LED) != 0) {
            val r = query(transport, CMD_DAC_LED, 1)
            if (isValidCbReply(r, CMD_DAC_LED, minLen = 1)) {
                val l = r!![4].toInt() and 0xFF
                if (l in 0..2) nextLed = l
            }
        }
        if ((mask and PROBE_DRE) != 0) {
            val r = query(transport, CMD_DRE, 1)
            if (isValidCbReply(r, CMD_DRE, minLen = 1)) {
                val d = r!![4].toInt() and 0xFF
                if (d in 0..1) nextExtras[KEY_DRE] = DacControlValue(boolValue = d == 0)
            }
        }
        if ((mask and PROBE_SWAP) != 0) {
            val r = query(transport, CMD_CHANNEL_SWAP, 1)
            if (isValidCbReply(r, CMD_CHANNEL_SWAP, minLen = 1)) {
                val s = r!![4].toInt() and 0xFF
                if (s in 0..1) nextExtras[KEY_CHANNEL_SWAP] = DacControlValue(boolValue = s == 1)
            }
        }
        return currentState.copy(
            volume = nextVol,
            ledMode = nextLed,
            extraControlValues = nextExtras,
            diagnosticNote = lastProbeSummary ?: currentState.diagnosticNote
        )
    }

    private suspend fun runExtendedProbeDiagnostic(transport: DacTransport) {
        val cmds = intArrayOf(
            CMD_CHIP_ID, CMD_DONGLE_FW, CMD_HW_VOLUME, CMD_DRE,
            CMD_DAC_LED, CMD_CHANNEL_SWAP, CMD_EQ_PRESET_IDX,
            CMD_PEQ_PRE_GAIN, CMD_EQ_POST_GAIN_1, CMD_EQ_POST_GAIN_2
        )
        val parts = ArrayList<String>(cmds.size)
        for (cmd in cmds) {
            val r = query(transport, cmd, 0)
            val hex = if (r != null && r.size >= 8) {
                (0 until 8).joinToString("") { "%02X".format(r[it]) }
            } else {
                "null"
            }
            parts.add("%02X:%s".format(cmd, hex))
            delay(15)
        }
        lastProbeSummary = "CB Probe -> " + parts.joinToString(" ")
    }

    private fun parseBalanceSlider(profile: DacProfile, leftResp: ByteArray?, rightResp: ByteArray?): Int {
        if (profile.balanceEncoding == BalanceEncoding.Q8_8_LE_AT_5) {
            fun decodeQ8_8(buf: ByteArray?): Int {
                if (buf == null || buf.size < 7) return 0
                val raw = if (buf.size >= 8 && buf[5] == 0.toByte() && buf[6] == 0.toByte() && buf[7] != 0.toByte()) {
                    BytePack.getI16Le(buf, 6)
                } else {
                    BytePack.getI16Le(buf, 5)
                }
                return (raw / 256.0).roundToInt()
            }
            val leftDb = decodeQ8_8(leftResp)
            val rightDb = decodeQ8_8(rightResp)
            return if (leftDb < 0) abs(leftDb) else if (rightDb < 0) -abs(rightDb) else 0
        }
        val leftBal = leftResp?.let { sliderFromDevice(BytePack.getU16Be(it, 6)) } ?: 0
        val rightBal = rightResp?.let { sliderFromDevice(BytePack.getU16Be(it, 6)) } ?: 0
        return if (leftBal < 0) abs(leftBal) else if (rightBal < 0) -abs(rightBal) else 0
    }

    private fun isValidCbReply(buf: ByteArray?, cmd: Int, minLen: Int): Boolean {
        if (buf == null || buf.size < 5) return false
        return (buf[0].toInt() and 0xFF) == REPORT_ID &&
            (buf[1].toInt() and 0xFF) == 0x80 &&
            (buf[2].toInt() and 0xFF) == cmd &&
            (buf[3].toInt() and 0xFF) >= minLen
    }

    override suspend fun setMicGain(transport: DacTransport, profile: DacProfile, gainDb: Int): Boolean {
        if (isTanchjimTtgk(profile)) {
            val signedByte = gainDb.coerceIn(-15, 15) and 0xFF
            return transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_ADC_OFFSET, 0x02, 0x00, signedByte))
        }
        if (isNicehck(profile)) {
            val raw = gainDb.coerceIn(-12, 12) * 256
            val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_ADC_OFFSET, 0x02)
            BytePack.u16Le(pkt, 4, raw)
            if (!transport.send(pkt)) return false
            delay(15)
            return transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_POST_COMMIT, 0x00))
        }
        val raw = gainDb.coerceIn(-15, 15) * 256
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_ADC_OFFSET, 0x02)
        BytePack.u16Le(pkt, 4, raw)
        return transport.send(pkt)
    }

    override suspend fun refreshPresetEqState(
        transport: DacTransport,
        profile: DacProfile,
        currentState: DacDeviceState,
        presetId: Int
    ): DacDeviceState? {
        if (isNicehck(profile)) {
            val spec = NicehckPresets.find(profile.schemeNo, presetId)
            if (spec != null) {
                return currentState.copy(
                    bands = spec.bands,
                    globalGainDb = spec.offsetDb,
                    activePresetId = spec.id
                )
            }
        }
        return super.refreshPresetEqState(transport, profile, currentState, presetId)
    }

    override suspend fun setMicEnc(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_ENC, 0x01, if (enabled) 1 else 0))

    override suspend fun setSurround3d(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_3D_SWITCH, 0x01, if (enabled) 1 else 0))

    private suspend fun query(
        transport: DacTransport,
        cmd: Int,
        len: Int,
        extra: ByteArray = byteArrayOf()
    ): ByteArray? {
        val req = BytePack.fixed64(REPORT_ID, 0x80, cmd, len)
        for (i in extra.indices) {
            if (4 + i < req.size) req[4 + i] = extra[i]
        }
        return transport.transceive(req, expectedSize = 64, timeoutMs = 450)
    }

    companion object {
        const val REPORT_ID = 0x4B
        const val SCHEME_TANCHJIM_STARGATE2 = "tanchjim_stargate2"
        const val SCHEME_TANCHJIM_SPACE2 = "tanchjim_space2"
        const val SCHEME_NICEHCK_STRINGSNOW = "nicehck_stringsnow"
        const val SCHEME_NICEHCK_TEARS = "nicehck_tears"
        const val SCHEME_NICEHCK_10TH = "nicehck_10th"
        const val SCHEME_NICEHCK_OCTAVE = "nicehck_octave"
        const val SCHEME_NICEHCK_PUREAURAL = "nicehck_pureaural"

        private val NICEHCK_SCHEMES = setOf(
            SCHEME_NICEHCK_STRINGSNOW,
            SCHEME_NICEHCK_TEARS,
            SCHEME_NICEHCK_10TH,
            SCHEME_NICEHCK_OCTAVE,
            SCHEME_NICEHCK_PUREAURAL
        )

        private const val CMD_SAVE_FLASH = 0x01
        private const val CMD_ADC_OFFSET = 0x02
        private const val CMD_DAC_OFFSET = 0x03
        private const val CMD_POST_COMMIT = 0x04
        private const val CMD_RESET_EQ = 0x05
        private const val CMD_EQ_DATA = 0x09
        private const val CMD_REFRESH_EQ = 0x0A
        private const val CMD_FW_VERSION = 0x0C
        private const val CMD_DONGLE_FW = 0x0D
        private const val CMD_CHIP_ID = 0x0E
        private const val CMD_EQ_PRESET_IDX = 0x0F
        private const val CMD_DAC_FILTER = 0x11
        private const val CMD_CHANNEL_BALANCE = 0x16
        private const val CMD_FACTORY_RESET = 0x17
        private const val CMD_DAC_LED = 0x18
        // CB HID registers verified on EPZ TP35 Pro / TRN Black Pearl:
        // 0x19 = Gain Level (0 = Low Gain, 1 = High Gain) -> UI workModeOptions
        // 0x1D = Amp Work Mode (0 = Class AB, 1 = Class H) -> UI outputModeOptions
        private const val CMD_WORK_MODE = 0x19
        private const val CMD_ENC = 0x1B
        private const val CMD_OUTPUT_MODE = 0x1D
        private const val CMD_3D_SWITCH = 0x22
        private const val CMD_PEQ_PRE_GAIN = 0x23
        private const val CMD_CHANNEL_SWAP = 0x30
        private const val CMD_DRE = 0x32
        private const val CMD_EQ_POST_GAIN_1 = 0x37
        private const val CMD_EQ_POST_GAIN_2 = 0x38
        private const val CMD_HW_VOLUME = 0x85

        private const val KEY_PROBE_MASK = "_cb_probe_mask"
        private const val KEY_DRE = "cb_dre"
        private const val KEY_CHANNEL_SWAP = "cb_channel_swap"
        private const val KEY_PEQ_PRE_GAIN = "cb_pre_gain"
        private const val KEY_PROBE_DIAG = "cb_probe_diag"

        private const val PROBE_VOL = 1 shl 0
        private const val PROBE_DRE = 1 shl 1
        private const val PROBE_LED = 1 shl 2
        private const val PROBE_SWAP = 1 shl 3
        private const val PROBE_PRE_GAIN = 1 shl 4

        private val POSITIVE_BALANCE_MAP = intArrayOf(
            0, 4170, 4180, 4189, 4198, 4208, 4217, 4226, 4236, 4245, 4254, 4264, 4273
        )

        internal fun deviceValueFromSlider(sliderValue: Int): Int = when {
            sliderValue in -12..-1 -> (65536 + sliderValue * 512) and 0xFFFF
            sliderValue in 1..12 -> POSITIVE_BALANCE_MAP[sliderValue]
            else -> 0
        }

        internal fun sliderFromDevice(deviceValue: Int): Int {
            val u16 = deviceValue and 0xFFFF
            POSITIVE_BALANCE_MAP.forEachIndexed { idx, v ->
                if (idx > 0 && v == u16) return idx
            }
            for (s in -12..-1) {
                if (((65536 + s * 512) and 0xFFFF) == u16) return s
            }
            return 0
        }
    }
}
