package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.DefaultBands
import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.FilterType
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
import kotlin.math.roundToInt

/**
 * Tanchjim BES SPP protocol (`MinoCommand.kt`) for MINO, MINO i, and MINO AT.
 * Frame format:
 *   TX: `11 00 00 <cmd> 00 <len> 00 00 00 [payload]`
 *   RX: `01 01 00 <cmd> 00 <len> 00 00 00 [payload]`
 */
class TanchjimBesMinoProtocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.BES_MINO
    override val displayName: String = "Tanchjim BES (MINO)"

    @Volatile
    private var cachedVolMemory: Boolean = false

    @Volatile
    private var cachedVolume: Int = 8

    @Volatile
    private var cachedAncCycleLeft: Int = 3

    @Volatile
    private var cachedAncCycleRight: Int = 3

    @Volatile
    private var cachedButtons: IntArray = intArrayOf(0x21, 0x11, 0x31, 0x22, 0x12, 0x33)

    override fun buildCapabilities(profile: DacProfile): DacCapabilities {
        val bands = profile.bandCount.coerceIn(5, 10)
        val extras = buildList {
            add(
                DacControlDescriptor(
                    key = KEY_ANC_MODE,
                    label = "Noise Control Mode",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SINGLE_CHOICE,
                    options = listOf(
                        NamedOption(0, "Normal"),
                        NamedOption(1, "ANC On"),
                        NamedOption(2, "Transparency")
                    )
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_ANC_CYCLE_L,
                    label = "Left Ear ANC Cycle Modes",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SINGLE_CHOICE,
                    options = ANC_CYCLE_OPTIONS
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_ANC_CYCLE_R,
                    label = "Right Ear ANC Cycle Modes",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SINGLE_CHOICE,
                    options = ANC_CYCLE_OPTIONS
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_VOL_MEMORY,
                    label = "Volume Memory in Case",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_IN_EAR,
                    label = "In-Ear Detection (PleasantEar)",
                    category = ControlCategory.GENERAL,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_COLOR,
                    label = "Device Color Theme",
                    category = ControlCategory.DISPLAY,
                    kind = ControlKind.SINGLE_CHOICE,
                    options = listOf(
                        NamedOption(1, "Black"),
                        NamedOption(2, "White"),
                        NamedOption(3, "Pink")
                    )
                )
            )
            BUTTON_KEYS.forEach { (key, label) ->
                add(
                    DacControlDescriptor(
                        key = key,
                        label = label,
                        category = ControlCategory.BUTTONS,
                        kind = ControlKind.SINGLE_CHOICE,
                        options = MINO_BUTTON_OPTIONS
                    )
                )
            }
            add(
                DacControlDescriptor(
                    key = KEY_BTN_RESET,
                    label = "Reset Touch Controls",
                    category = ControlCategory.BUTTONS,
                    kind = ControlKind.ACTION,
                    isReadable = false
                )
            )
        }
        return DacCapabilities(
            maxBands = bands,
            minGainDb = -11.0,
            maxGainDb = 12.0,
            minQ = 0.1,
            maxQ = 16.0,
            supportedFilters = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF),
            sampleRate = 48_000,
            hasEqSwitch = false,
            hasGlobalGain = false,
            hasVolume = true,
            maxVolume = 16,
            hasBattery = true,
            presets = PRESETS,
            supportsRamRefresh = false,
            supportsFlashSave = false,
            extraControls = extras
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val extras = LinkedHashMap<String, DacControlValue>()

        var firmware: String? = null
        var batteryPct: Int? = null
        var batteryNote: String? = null
        val verBatResp = queryBes(transport, CMD_GET_VERSION_BATTERY)
        if (verBatResp != null && verBatResp.size >= 18) {
            val v0 = verBatResp[10].toInt() and 0xFF
            val v1 = verBatResp[11].toInt() and 0xFF
            val v2 = verBatResp[12].toInt() and 0xFF
            val leftBat = (verBatResp[13].toInt() and 0xFF).coerceIn(0, 100)
            val rightBat = (verBatResp[17].toInt() and 0xFF).coerceIn(0, 100)
            firmware = "V$v0.$v1.$v2"
            batteryPct = minOf(leftBat, rightBat)
            batteryNote = "Battery L $leftBat% · R $rightBat%"
        }
        delay(20)

        val ancMode = queryBes(transport, CMD_GET_ANC_MODE)?.getOrNull(9)?.toInt()?.and(0xFF) ?: 0
        extras[KEY_ANC_MODE] = DacControlValue(intValue = ancMode.coerceIn(0, 2))
        delay(20)

        val cycleResp = queryBes(transport, CMD_GET_ANC_CYCLE)
        if (cycleResp != null && cycleResp.size >= 11) {
            cachedAncCycleLeft = (cycleResp[9].toInt() and 0xFF).coerceIn(1, 4)
            cachedAncCycleRight = (cycleResp[10].toInt() and 0xFF).coerceIn(1, 4)
        }
        extras[KEY_ANC_CYCLE_L] = DacControlValue(intValue = cachedAncCycleLeft)
        extras[KEY_ANC_CYCLE_R] = DacControlValue(intValue = cachedAncCycleRight)
        delay(20)

        val volResp = queryBes(transport, CMD_GET_VOL)
        if (volResp != null && volResp.size >= 11) {
            cachedVolume = (volResp[9].toInt() and 0xFF).coerceIn(0, 16)
            cachedVolMemory = (volResp[10].toInt() and 0xFF) == 1
        }
        extras[KEY_VOL_MEMORY] = DacControlValue(boolValue = cachedVolMemory)
        delay(20)

        val inEarResp = queryBes(transport, CMD_GET_PLEASANT_EAR)
        val inEarOn = (inEarResp?.getOrNull(9)?.toInt()?.and(0xFF) ?: 0) == 1
        extras[KEY_IN_EAR] = DacControlValue(boolValue = inEarOn)
        delay(20)

        val colorCode = queryBes(transport, CMD_GET_COLOR)?.getOrNull(9)?.toInt()?.and(0xFF) ?: 1
        extras[KEY_COLOR] = DacControlValue(intValue = if (colorCode in 1..3) colorCode else 1)
        delay(20)

        val btnResp = queryBes(transport, CMD_GET_BUTTONS)
        if (btnResp != null && btnResp.size >= 15) {
            for (i in 0 until 6) {
                cachedButtons[i] = btnResp[9 + i].toInt() and 0xFF
            }
        }
        BUTTON_KEYS.forEachIndexed { idx, (key, _) ->
            extras[key] = DacControlValue(intValue = cachedButtons[idx])
        }
        delay(20)

        val presetIdx = queryBes(transport, CMD_GET_PRESET_EQ)?.let { resp ->
            resp.lastOrNull()?.toInt()?.and(0xFF)
        }?.coerceIn(0, PRESETS.lastIndex) ?: 0
        delay(20)

        val bandCount = profile.bandCount.coerceIn(5, 10)
        val flat = DefaultBands.flatBands(bandCount)
        val eqResp = queryBes(transport, CMD_GET_USER_EQ, byteArrayOf(0, 0, 0, 0, 0, 0, 0))
        val parsedBands = if (eqResp != null && eqResp.size >= 9 + 7) {
            val payloadLen = (eqResp.size - 9).coerceAtMost(eqResp[5].toInt() and 0xFF)
            val availBands = (payloadLen / 7).coerceAtMost(bandCount)
            flat.mapIndexed { idx, fallback ->
                if (idx >= availBands) {
                    fallback
                } else {
                    val off = 9 + idx * 7
                    val fCode = eqResp[off].toInt() and 0xFF
                    val gainRaw = ((eqResp[off + 1].toInt() and 0xFF) shl 8) or (eqResp[off + 2].toInt() and 0xFF)
                    val gainDb = (((gainRaw / 100.0) - 12.0) * 10.0).roundToInt() / 10.0
                    val freq = ((eqResp[off + 3].toInt() and 0xFF) shl 8) or (eqResp[off + 4].toInt() and 0xFF)
                    val qRaw = ((eqResp[off + 5].toInt() and 0xFF) shl 8) or (eqResp[off + 6].toInt() and 0xFF)
                    val q = ((qRaw / 100.0).coerceIn(0.1, 16.0) * 10.0).roundToInt() / 10.0
                    EqBand(
                        index = idx,
                        frequencyHz = if (freq in 10..24000) freq else fallback.frequencyHz,
                        gainDb = gainDb.coerceIn(-11.0, 12.0),
                        q = q,
                        filterType = when (fCode) {
                            2 -> FilterType.LOW_SHELF
                            3 -> FilterType.HIGH_SHELF
                            else -> FilterType.PEAK
                        }
                    )
                }
            }
        } else {
            flat
        }

        return DacDeviceState(
            firmwareVersion = firmware,
            eqEnabled = true,
            activePresetId = presetIdx,
            globalGainDb = 0.0,
            bands = parsedBands,
            volume = cachedVolume,
            maxVolume = 16,
            batteryPercent = batteryPct,
            extraControlValues = extras,
            diagnosticNote = batteryNote
        )
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val bands = DefaultBands.flatBands(profile.bandCount.coerceIn(5, 10)).map {
            if (it.index == band.index) band else it
        }
        return writeAllEqBands(transport, profile, bands, globalGainDb, presetId)
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val targetCount = profile.bandCount.coerceIn(5, 10)
        val payload = ByteArray(10 * 7)
        val trimmed = bands.take(targetCount)
        val padded = buildList {
            addAll(trimmed)
            if (size < 10) {
                for (dummyFreq in DUMMY_PAD_FREQS) {
                    if (size >= 10) break
                    add(EqBand(size, dummyFreq, 0.0, 0.1, FilterType.PEAK))
                }
                while (size < 10) {
                    add(EqBand(size, 80, 0.0, 0.1, FilterType.PEAK))
                }
            }
        }
        padded.forEachIndexed { idx, band ->
            val off = idx * 7
            payload[off] = when (band.filterType) {
                FilterType.LOW_SHELF -> 2
                FilterType.HIGH_SHELF -> 3
                else -> 1
            }.toByte()
            val gainEncoded = ((band.gainDb.coerceIn(-11.0, 12.0) + 12.0) * 100.0).roundToInt().coerceIn(100, 2400)
            val freqEncoded = band.frequencyHz.coerceIn(10, 24000)
            val qEncoded = (band.q.coerceIn(0.1, 16.0) * 100.0).roundToInt().coerceIn(10, 1600)
            payload[off + 1] = ((gainEncoded ushr 8) and 0xFF).toByte()
            payload[off + 2] = (gainEncoded and 0xFF).toByte()
            payload[off + 3] = ((freqEncoded ushr 8) and 0xFF).toByte()
            payload[off + 4] = (freqEncoded and 0xFF).toByte()
            payload[off + 5] = ((qEncoded ushr 8) and 0xFF).toByte()
            payload[off + 6] = (qEncoded and 0xFF).toByte()
        }
        return sendBes(transport, CMD_SET_USER_EQ, payload)
    }

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean =
        sendBes(transport, CMD_SET_PRESET_EQ, byteArrayOf(presetId.coerceIn(0, PRESETS.lastIndex).toByte()))

    override suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean {
        val ok = sendBes(transport, CMD_SET_PRESET_EQ, byteArrayOf(0x00))
        delay(30)
        val flat = DefaultBands.flatBands(profile.bandCount.coerceIn(5, 10))
        return writeAllEqBands(transport, profile, flat, 0.0, 0) && ok
    }

    override suspend fun setVolume(transport: DacTransport, profile: DacProfile, volume: Int): Boolean {
        val v = volume.coerceIn(0, 16)
        cachedVolume = v
        return sendBes(
            transport,
            CMD_SET_VOL,
            byteArrayOf(v.toByte(), if (cachedVolMemory) 0x01 else 0x00)
        )
    }

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean {
        return when (key) {
            KEY_ANC_MODE -> sendBes(
                transport,
                CMD_SET_ANC_MODE,
                byteArrayOf(value.intValue.coerceIn(0, 2).toByte())
            )
            KEY_ANC_CYCLE_L -> {
                cachedAncCycleLeft = value.intValue.coerceIn(1, 4)
                sendBes(
                    transport,
                    CMD_SET_ANC_CYCLE,
                    byteArrayOf(cachedAncCycleLeft.toByte(), cachedAncCycleRight.toByte())
                )
            }
            KEY_ANC_CYCLE_R -> {
                cachedAncCycleRight = value.intValue.coerceIn(1, 4)
                sendBes(
                    transport,
                    CMD_SET_ANC_CYCLE,
                    byteArrayOf(cachedAncCycleLeft.toByte(), cachedAncCycleRight.toByte())
                )
            }
            KEY_VOL_MEMORY -> {
                cachedVolMemory = value.boolValue
                sendBes(
                    transport,
                    CMD_SET_VOL,
                    byteArrayOf(cachedVolume.coerceIn(0, 16).toByte(), if (cachedVolMemory) 0x01 else 0x00)
                )
            }
            KEY_IN_EAR -> sendBes(
                transport,
                CMD_SET_PLEASANT_EAR,
                byteArrayOf(if (value.boolValue) 0x01 else 0x00)
            )
            KEY_COLOR -> sendBes(
                transport,
                CMD_SET_COLOR,
                byteArrayOf(value.intValue.coerceIn(1, 3).toByte())
            )
            KEY_BTN_RESET -> sendBes(transport, CMD_RESET_BUTTONS)
            else -> {
                val btnIdx = BUTTON_KEYS.indexOfFirst { it.first == key }
                if (btnIdx < 0) return false
                cachedButtons[btnIdx] = value.intValue and 0xFF
                val payload = ByteArray(6) { i -> cachedButtons[i].toByte() }
                sendBes(transport, CMD_SET_BUTTONS, payload)
            }
        }
    }

    private fun buildBesPacket(cmd: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val pkt = ByteArray(9 + payload.size)
        pkt[0] = 0x11
        pkt[1] = 0x00
        pkt[2] = 0x00
        pkt[3] = (cmd and 0xFF).toByte()
        pkt[4] = 0x00
        pkt[5] = (payload.size and 0xFF).toByte()
        pkt[6] = 0x00
        pkt[7] = 0x00
        pkt[8] = 0x00
        payload.copyInto(pkt, destinationOffset = 9)
        return pkt
    }

    private suspend fun sendBes(transport: DacTransport, cmd: Int, payload: ByteArray = byteArrayOf()): Boolean =
        transport.send(buildBesPacket(cmd, payload))

    private suspend fun queryBes(
        transport: DacTransport,
        cmd: Int,
        payload: ByteArray = byteArrayOf(),
        timeoutMs: Int = 500
    ): ByteArray? {
        val resp = transport.transceive(buildBesPacket(cmd, payload), expectedSize = 9, timeoutMs = timeoutMs)
            ?: return null
        return if (resp.size >= 9 &&
            (resp[0].toInt() and 0xFF) == 0x01 &&
            (resp[1].toInt() and 0xFF) == 0x01 &&
            (resp[3].toInt() and 0xFF) == (cmd and 0xFF)
        ) {
            resp
        } else {
            null
        }
    }

    companion object {
        private const val CMD_GET_VERSION_BATTERY = 0x4E
        private const val CMD_SET_USER_EQ = 0x53
        private const val CMD_GET_USER_EQ = 0x54
        private const val CMD_SET_PRESET_EQ = 0x69
        private const val CMD_GET_PRESET_EQ = 0x6A
        private const val CMD_SET_BUTTONS = 0x84
        private const val CMD_RESET_BUTTONS = 0x86
        private const val CMD_GET_BUTTONS = 0x87
        private const val CMD_GET_COLOR = 0x90
        private const val CMD_SET_COLOR = 0x91
        private const val CMD_SET_ANC_CYCLE = 0x92
        private const val CMD_GET_ANC_CYCLE = 0x93
        private const val CMD_GET_VOL = 0x95
        private const val CMD_SET_VOL = 0x96
        private const val CMD_GET_ANC_MODE = 0x97
        private const val CMD_SET_ANC_MODE = 0x98
        private const val CMD_GET_PLEASANT_EAR = 0x99
        private const val CMD_SET_PLEASANT_EAR = 0x9A

        private const val KEY_ANC_MODE = "mino_anc_mode"
        private const val KEY_ANC_CYCLE_L = "mino_anc_cycle_left"
        private const val KEY_ANC_CYCLE_R = "mino_anc_cycle_right"
        private const val KEY_VOL_MEMORY = "mino_vol_memory"
        private const val KEY_IN_EAR = "mino_in_ear"
        private const val KEY_COLOR = "mino_color"
        private const val KEY_BTN_RESET = "mino_btn_reset"

        private val BUTTON_KEYS = listOf(
            "mino_btn_prev" to "Previous Track Gesture",
            "mino_btn_play" to "Play / Pause Gesture",
            "mino_btn_next" to "Next Track Gesture",
            "mino_btn_vol_up" to "Volume Up Gesture",
            "mino_btn_vol_down" to "Volume Down Gesture",
            "mino_btn_voice" to "Voice Assistant Gesture"
        )

        private val DUMMY_PAD_FREQS = intArrayOf(40, 50, 60, 70, 80)

        private val PRESETS = listOf(
            NamedOption(0, "Default"),
            NamedOption(1, "Musical"),
            NamedOption(2, "Popular"),
            NamedOption(3, "Vocal Enhancement"),
            NamedOption(4, "Balanced"),
            NamedOption(5, "Natural")
        )

        private val ANC_CYCLE_OPTIONS = listOf(
            NamedOption(1, "ANC + Off"),
            NamedOption(2, "ANC + Transparency"),
            NamedOption(3, "ANC + Transparency + Off"),
            NamedOption(4, "Transparency + Off")
        )

        private val MINO_BUTTON_OPTIONS = listOf(
            NamedOption(0x00, "Disabled"),
            NamedOption(0x11, "Right · Single Tap"),
            NamedOption(0x12, "Right · Double Tap"),
            NamedOption(0x13, "Right · Triple Tap"),
            NamedOption(0x21, "Left · Single Tap"),
            NamedOption(0x22, "Left · Double Tap"),
            NamedOption(0x23, "Left · Triple Tap"),
            NamedOption(0x31, "Any · Single Tap"),
            NamedOption(0x32, "Any · Double Tap"),
            NamedOption(0x33, "Any · Triple Tap")
        )
    }
}
