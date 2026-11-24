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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tanchjim Bluetrum V2 SPP protocol (`RevoCommand.kt` / `RITACommand.kt`) for:
 * - REVO (`schemeNo == "tanchjim_revo"`, 8 bands, TWS, Game Mode, Custom Buttons)
 * - SOVA (`schemeNo == "tanchjim_sova"`, 9 bands, Headphone, ANC + LDAC + Prompt Lang)
 * - RITA / RITA AT / RITA MIKU (`schemeNo == "tanchjim_rita"`, 12 bands, Headphone, ANC + LDAC + Dual Device + Prompt Lang & Vol)
 *
 * Frame format:
 *   TX: `FF A1 <len> <cmd> [payload] AA`
 *   RX: `FF A2 <len> <cmd> [payload] AA`
 */
class TanchjimBluetrumV2Protocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.BLUETRUM_V2
    override val displayName: String = "Tanchjim Bluetrum V2 (FF A1)"

    @Volatile
    private var cachedBands: List<EqBand> = emptyList()

    @Volatile
    private var cachedButtons: IntArray = intArrayOf(
        0x01, 0x02, // Prev: L Double?
        0x03, 0x02, // Play
        0x02, 0x02, // Next
        0x01, 0x01, // Vol+
        0x02, 0x01, // Vol-
        0x03, 0x03  // Voice
    )

    private fun isRevo(profile: DacProfile): Boolean = profile.schemeNo == SCHEME_REVO
    private fun isSova(profile: DacProfile): Boolean = profile.schemeNo == SCHEME_SOVA
    private fun isRita(profile: DacProfile): Boolean = !isRevo(profile) && !isSova(profile)

    override fun buildCapabilities(profile: DacProfile): DacCapabilities {
        val bandCount = when {
            isRevo(profile) -> 8
            isSova(profile) -> 9
            else -> profile.bandCount.coerceIn(8, 12)
        }
        val revo = isRevo(profile)
        val rita = isRita(profile)
        val extras = buildList {
            if (!revo) {
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
            }
            if (revo) {
                add(
                    DacControlDescriptor(
                        key = KEY_HEADPHONE_MODE,
                        label = "Bluetooth Codec / Connection Mode",
                        category = ControlCategory.BLUETOOTH,
                        kind = ControlKind.SINGLE_CHOICE,
                        options = listOf(
                            NamedOption(0, "Standard (AAC/SBC)"),
                            NamedOption(1, "LDAC High Quality"),
                            NamedOption(2, "Dual-Device Connection")
                        )
                    )
                )
                add(
                    DacControlDescriptor(
                        key = KEY_GAME_MODE,
                        label = "Low-Latency Game Mode",
                        category = ControlCategory.AUDIO,
                        kind = ControlKind.SWITCH
                    )
                )
            } else {
                add(
                    DacControlDescriptor(
                        key = KEY_LDAC_MODE,
                        label = "LDAC High-Quality Mode",
                        category = ControlCategory.BLUETOOTH,
                        kind = ControlKind.SWITCH
                    )
                )
                if (rita) {
                    add(
                        DacControlDescriptor(
                            key = KEY_DUAL_DEVICE,
                            label = "Dual-Device Connection",
                            category = ControlCategory.BLUETOOTH,
                            kind = ControlKind.SWITCH
                        )
                    )
                }
            }
            add(
                DacControlDescriptor(
                    key = KEY_PROMPT_LANG,
                    label = "Voice Prompt Language",
                    category = ControlCategory.GENERAL,
                    kind = ControlKind.SINGLE_CHOICE,
                    options = listOf(
                        NamedOption(1, "Pure Tone"),
                        NamedOption(2, "Chinese"),
                        NamedOption(3, "English"),
                        NamedOption(4, "Japanese")
                    )
                )
            )
            if (rita) {
                add(
                    DacControlDescriptor(
                        key = KEY_PROMPT_VOL,
                        label = "Voice Prompt Volume",
                        category = ControlCategory.GENERAL,
                        kind = ControlKind.SLIDER,
                        min = 0,
                        max = 16,
                        step = 1
                    )
                )
            }
            add(
                DacControlDescriptor(
                    key = KEY_COLOR,
                    label = "Device Color Variant",
                    category = ControlCategory.DISPLAY,
                    kind = ControlKind.SINGLE_CHOICE,
                    isWritable = false,
                    options = when {
                        revo -> listOf(NamedOption(1, "White"), NamedOption(3, "Black"))
                        isSova(profile) -> listOf(NamedOption(1, "White"), NamedOption(2, "Black"))
                        else -> listOf(NamedOption(1, "White"), NamedOption(2, "Blue"))
                    }
                )
            )
            if (revo) {
                REVO_BUTTON_KEYS.forEach { (key, label) ->
                    add(
                        DacControlDescriptor(
                            key = key,
                            label = label,
                            category = ControlCategory.BUTTONS,
                            kind = ControlKind.SINGLE_CHOICE,
                            options = REVO_BUTTON_OPTIONS
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
            add(
                DacControlDescriptor(
                    key = KEY_RESET_ALL,
                    label = "Factory Reset Settings",
                    category = ControlCategory.GENERAL,
                    kind = ControlKind.ACTION,
                    isReadable = false
                )
            )
        }

        return DacCapabilities(
            maxBands = bandCount,
            minGainDb = -12.0,
            maxGainDb = 12.0,
            minQ = 0.01,
            maxQ = 16.0,
            supportedFilters = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF),
            sampleRate = 48_000,
            hasEqSwitch = false,
            hasGlobalGain = true,
            globalGainRange = -12.0..12.0,
            hasBattery = true,
            supportsRamRefresh = false,
            supportsFlashSave = false,
            extraControls = extras
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val extras = LinkedHashMap<String, DacControlValue>()
        val revo = isRevo(profile)
        val rita = isRita(profile)
        val bandCount = when {
            revo -> 8
            isSova(profile) -> 9
            else -> profile.bandCount.coerceIn(8, 12)
        }

        val fwResp = queryV2(transport, CMD_GET_VERSION)
        val firmware = fwResp?.takeIf { it.size >= 8 }?.let {
            val a = it[4].toInt() and 0xFF
            val b = it[5].toInt() and 0xFF
            val c = it[6].toInt() and 0xFF
            "V$a.$b.$c"
        }
        delay(20)

        var batteryPct: Int? = null
        var batteryNote: String? = null
        val batResp = queryV2(transport, CMD_GET_BATTERY)
        if (batResp != null) {
            if (revo && batResp.size >= 10) {
                val left = (batResp[5].toInt() and 0xFF).coerceIn(0, 100)
                val right = (batResp[7].toInt() and 0xFF).coerceIn(0, 100)
                val box = (batResp[8].toInt() and 0xFF).coerceIn(0, 100)
                batteryPct = minOf(left, right)
                batteryNote = "Battery L $left% · R $right% · Case $box%"
            } else if (!revo && batResp.size >= 7) {
                val charging = (batResp[4].toInt() and 0xFF) == 1
                val pct = (batResp[5].toInt() and 0xFF).coerceIn(0, 100)
                batteryPct = pct
                batteryNote = if (charging) "Charging ($pct%)" else "Battery $pct%"
            }
        }
        delay(20)

        val colorCode = queryV2(transport, CMD_GET_COLOR)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 1
        extras[KEY_COLOR] = DacControlValue(intValue = colorCode)
        delay(20)

        val langCode = queryV2(transport, CMD_GET_LANGUAGE)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 1
        extras[KEY_PROMPT_LANG] = DacControlValue(intValue = langCode.coerceIn(1, 4))
        delay(20)

        if (!revo) {
            val ancCode = queryV2(transport, CMD_GET_ANC)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0
            extras[KEY_ANC_MODE] = DacControlValue(intValue = ancCode.coerceIn(0, 2))
            delay(20)

            val ldacMode = (queryV2(transport, CMD_GET_HEADPHONE_MODE)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) == 1
            extras[KEY_LDAC_MODE] = DacControlValue(boolValue = ldacMode)
            delay(20)

            if (rita) {
                val dual = (queryV2(transport, CMD_GET_DUAL_DEVICE)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) == 1
                extras[KEY_DUAL_DEVICE] = DacControlValue(boolValue = dual)
                delay(20)

                val pVol = (queryV2(transport, CMD_GET_PROMPT_VOL)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 8)
                    .coerceIn(0, 16)
                extras[KEY_PROMPT_VOL] = DacControlValue(intValue = pVol)
                delay(20)
            }
        } else {
            val hpMode = (queryV2(transport, CMD_GET_HEADPHONE_MODE)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0)
                .coerceIn(0, 2)
            extras[KEY_HEADPHONE_MODE] = DacControlValue(intValue = hpMode)
            delay(20)

            val gameOn = (queryV2(transport, CMD_GET_GAME_MODE)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) == 1
            extras[KEY_GAME_MODE] = DacControlValue(boolValue = gameOn)
            delay(20)

            val btnResp = queryV2(transport, CMD_GET_BUTTONS)
            if (btnResp != null && btnResp.size >= 17) {
                for (i in 0 until 12) {
                    cachedButtons[i] = btnResp[4 + i].toInt() and 0xFF
                }
            }
            REVO_BUTTON_KEYS.forEachIndexed { slot, (key, _) ->
                val ear = cachedButtons[slot * 2]
                val op = cachedButtons[slot * 2 + 1]
                extras[key] = DacControlValue(intValue = encodeRevoButton(ear, op))
            }
            delay(20)
        }

        val dacResp = queryV2(transport, CMD_GET_DAC)
        val dacGainDb = if (dacResp != null && dacResp.size >= 8) {
            val sign = if ((dacResp[4].toInt() and 0xFF) == 0x02) -1.0 else 1.0
            val intPart = dacResp[5].toInt() and 0xFF
            val tenths = dacResp[6].toInt() and 0xFF
            (sign * (intPart + tenths / 10.0)).coerceIn(-12.0, 12.0)
        } else {
            0.0
        }
        delay(20)

        val eqResp = queryV2(transport, CMD_GET_ALL_EQ)
        val flat = DefaultBands.flatBands(bandCount)
        val parsedBands = if (eqResp != null && eqResp.size >= 6 + bandCount * 7) {
            flat.mapIndexed { idx, fallback ->
                val off = 5 + idx * 7
                val fType = eqResp[off].toInt() and 0xFF
                val gRaw = ((eqResp[off + 1].toInt() and 0xFF) shl 8) or (eqResp[off + 2].toInt() and 0xFF)
                val gSigned = gRaw.toShort().toInt() / 100.0
                val gainDb = (gSigned * 10.0).roundToInt() / 10.0
                val freq = ((eqResp[off + 3].toInt() and 0xFF) shl 8) or (eqResp[off + 4].toInt() and 0xFF)
                val qRaw = ((eqResp[off + 5].toInt() and 0xFF) shl 8) or (eqResp[off + 6].toInt() and 0xFF)
                val q = (qRaw / 100.0).coerceIn(0.01, 16.0)
                EqBand(
                    index = idx,
                    frequencyHz = if (freq in 10..24000) freq else fallback.frequencyHz,
                    gainDb = gainDb.coerceIn(-12.0, 12.0),
                    q = (q * 100.0).roundToInt() / 100.0,
                    filterType = when (fType) {
                        2 -> FilterType.LOW_SHELF
                        3 -> FilterType.HIGH_SHELF
                        else -> FilterType.PEAK
                    }
                )
            }
        } else {
            flat
        }
        cachedBands = parsedBands

        return DacDeviceState(
            firmwareVersion = firmware,
            eqEnabled = true,
            activePresetId = 0,
            globalGainDb = dacGainDb,
            bands = parsedBands,
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
        val bandCount = when {
            isRevo(profile) -> 8
            isSova(profile) -> 9
            else -> profile.bandCount.coerceIn(8, 12)
        }
        val base = if (cachedBands.size == bandCount) cachedBands else DefaultBands.flatBands(bandCount)
        val updated = base.map { if (it.index == band.index) band else it }
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
        val bandCount = when {
            isRevo(profile) -> 8
            isSova(profile) -> 9
            else -> profile.bandCount.coerceIn(8, 12)
        }
        val flat = DefaultBands.flatBands(bandCount)
        val full = flat.mapIndexed { idx, fallback -> bands.getOrNull(idx) ?: fallback }
        cachedBands = full

        val payload = ByteArray(1 + bandCount * 7)
        payload[0] = bandCount.toByte()
        full.forEachIndexed { idx, band ->
            val off = 1 + idx * 7
            payload[off] = when (band.filterType) {
                FilterType.LOW_SHELF -> 2
                FilterType.HIGH_SHELF -> 3
                else -> 1
            }.toByte()
            val gainScaled = (band.gainDb.coerceIn(-12.0, 12.0) * 100.0).roundToInt()
            val gainU16 = if (gainScaled < 0) 65536 + gainScaled else gainScaled
            val freq = band.frequencyHz.coerceIn(10, 24000)
            val qScaled = (band.q.coerceIn(0.01, 16.0) * 100.0).roundToInt()
            payload[off + 1] = ((gainU16 ushr 8) and 0xFF).toByte()
            payload[off + 2] = (gainU16 and 0xFF).toByte()
            payload[off + 3] = ((freq ushr 8) and 0xFF).toByte()
            payload[off + 4] = (freq and 0xFF).toByte()
            payload[off + 5] = ((qScaled ushr 8) and 0xFF).toByte()
            payload[off + 6] = (qScaled and 0xFF).toByte()
        }
        return sendV2(transport, CMD_SET_ALL_EQ, payload)
    }

    override suspend fun setGlobalGain(transport: DacTransport, profile: DacProfile, gainDb: Double): Boolean {
        val clamped = gainDb.coerceIn(-12.0, 12.0)
        val signByte = if (clamped >= 0.0) 0x01 else 0x02
        val absVal = abs(clamped)
        var intPart = absVal.toInt()
        var tenths = ((absVal - intPart) * 10.0).roundToInt()
        if (tenths >= 10) {
            intPart += 1
            tenths = 0
        }
        return sendV2(
            transport,
            CMD_SET_DAC,
            byteArrayOf(signByte.toByte(), intPart.toByte(), tenths.toByte())
        )
    }

    override suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean =
        sendV2(transport, CMD_RESET_EQ)

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean {
        return when (key) {
            KEY_ANC_MODE -> sendV2(transport, CMD_SET_ANC, byteArrayOf(value.intValue.coerceIn(0, 2).toByte()))
            KEY_LDAC_MODE -> sendV2(
                transport,
                CMD_SET_HEADPHONE_MODE,
                byteArrayOf(if (value.boolValue) 0x01 else 0x00)
            )
            KEY_HEADPHONE_MODE -> sendV2(
                transport,
                CMD_SET_HEADPHONE_MODE,
                byteArrayOf(value.intValue.coerceIn(0, 2).toByte())
            )
            KEY_GAME_MODE -> sendV2(
                transport,
                CMD_SET_GAME_MODE,
                byteArrayOf(if (value.boolValue) 0x01 else 0x00)
            )
            KEY_DUAL_DEVICE -> sendV2(
                transport,
                CMD_SET_DUAL_DEVICE,
                byteArrayOf(if (value.boolValue) 0x01 else 0xFF.toByte())
            )
            KEY_PROMPT_LANG -> sendV2(
                transport,
                CMD_SET_LANGUAGE,
                byteArrayOf(value.intValue.coerceIn(1, 4).toByte())
            )
            KEY_PROMPT_VOL -> sendV2(
                transport,
                CMD_SET_PROMPT_VOL,
                byteArrayOf(value.intValue.coerceIn(0, 16).toByte())
            )
            KEY_BTN_RESET -> sendV2(transport, CMD_RESET_BUTTONS)
            KEY_RESET_ALL -> sendV2(transport, CMD_RESET_ALL)
            else -> {
                val slot = REVO_BUTTON_KEYS.indexOfFirst { it.first == key }
                if (slot < 0) return false
                val (ear, op) = decodeRevoButton(value.intValue)
                cachedButtons[slot * 2] = ear
                cachedButtons[slot * 2 + 1] = op
                val payload = ByteArray(12) { i -> cachedButtons[i].toByte() }
                sendV2(transport, CMD_SET_BUTTONS, payload)
            }
        }
    }

    private fun buildV2Packet(cmd: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val len = 1 + payload.size
        val pkt = ByteArray(4 + payload.size + 1)
        pkt[0] = 0xFF.toByte()
        pkt[1] = 0xA1.toByte()
        pkt[2] = (len and 0xFF).toByte()
        pkt[3] = (cmd and 0xFF).toByte()
        payload.copyInto(pkt, destinationOffset = 4)
        pkt[pkt.lastIndex] = 0xAA.toByte()
        return pkt
    }

    private suspend fun sendV2(transport: DacTransport, cmd: Int, payload: ByteArray = byteArrayOf()): Boolean =
        transport.send(buildV2Packet(cmd, payload))

    private suspend fun queryV2(
        transport: DacTransport,
        cmd: Int,
        payload: ByteArray = byteArrayOf(),
        timeoutMs: Int = 500
    ): ByteArray? {
        val resp = transport.transceive(buildV2Packet(cmd, payload), expectedSize = 5, timeoutMs = timeoutMs)
            ?: return null
        return if (resp.size >= 5 &&
            (resp[0].toInt() and 0xFF) == 0xFF &&
            (resp[1].toInt() and 0xFF) == 0xA2 &&
            (resp[3].toInt() and 0xFF) == (cmd and 0xFF) &&
            (resp.last().toInt() and 0xFF) == 0xAA
        ) {
            resp
        } else {
            null
        }
    }

    companion object {
        const val SCHEME_REVO = "tanchjim_revo"
        const val SCHEME_SOVA = "tanchjim_sova"
        const val SCHEME_RITA = "tanchjim_rita"

        private const val CMD_GET_VERSION = 0x01
        private const val CMD_GET_BATTERY = 0x02
        private const val CMD_GET_COLOR = 0x03
        private const val CMD_GET_ANC = 0x04
        private const val CMD_GET_BUTTONS = 0x05
        private const val CMD_GET_GAME_MODE = 0x07
        private const val CMD_GET_HEADPHONE_MODE = 0x08
        private const val CMD_GET_LANGUAGE = 0x0A
        private const val CMD_GET_ALL_EQ = 0x0B
        private const val CMD_GET_DAC = 0x0F
        private const val CMD_GET_DUAL_DEVICE = 0x10
        private const val CMD_GET_PROMPT_VOL = 0x11

        private const val CMD_RESET_BUTTONS = 0x21
        private const val CMD_SET_ANC = 0x24
        private const val CMD_SET_BUTTONS = 0x25
        private const val CMD_SET_GAME_MODE = 0x27
        private const val CMD_SET_HEADPHONE_MODE = 0x28
        private const val CMD_SET_LANGUAGE = 0x2A
        private const val CMD_SET_ALL_EQ = 0x2B
        private const val CMD_RESET_EQ = 0x2D
        private const val CMD_RESET_ALL = 0x2E
        private const val CMD_SET_DAC = 0x2F
        private const val CMD_SET_DUAL_DEVICE = 0x30
        private const val CMD_SET_PROMPT_VOL = 0x31

        private const val KEY_ANC_MODE = "v2_anc_mode"
        private const val KEY_LDAC_MODE = "v2_ldac_mode"
        private const val KEY_HEADPHONE_MODE = "v2_headphone_mode"
        private const val KEY_GAME_MODE = "v2_game_mode"
        private const val KEY_DUAL_DEVICE = "v2_dual_device"
        private const val KEY_PROMPT_LANG = "v2_prompt_lang"
        private const val KEY_PROMPT_VOL = "v2_prompt_vol"
        private const val KEY_COLOR = "v2_color"
        private const val KEY_BTN_RESET = "v2_btn_reset"
        private const val KEY_RESET_ALL = "v2_reset_all"

        private val REVO_BUTTON_KEYS = listOf(
            "revo_btn_prev" to "Previous Track Gesture",
            "revo_btn_play" to "Play / Pause Gesture",
            "revo_btn_next" to "Next Track Gesture",
            "revo_btn_vol_up" to "Volume Up Gesture",
            "revo_btn_vol_down" to "Volume Down Gesture",
            "revo_btn_voice" to "Voice Assistant Gesture"
        )

        internal fun encodeRevoButton(ear: Int, op: Int): Int =
            if (ear == 0 || op == 0) 0 else ((ear and 0xFF) shl 8) or (op and 0xFF)

        internal fun decodeRevoButton(code: Int): Pair<Int, Int> {
            val ear = (code ushr 8) and 0xFF
            val op = code and 0xFF
            return if (ear in 1..3 && op in 1..3) ear to op else 0 to 0
        }

        private val REVO_BUTTON_OPTIONS = listOf(
            NamedOption(0x0000, "Disabled"),
            NamedOption(0x0101, "Left · 1s Hold"),
            NamedOption(0x0102, "Left · Double Tap"),
            NamedOption(0x0103, "Left · Triple Tap"),
            NamedOption(0x0201, "Right · 1s Hold"),
            NamedOption(0x0202, "Right · Double Tap"),
            NamedOption(0x0203, "Right · Triple Tap"),
            NamedOption(0x0301, "Any · 1s Hold"),
            NamedOption(0x0302, "Any · Double Tap"),
            NamedOption(0x0303, "Any · Triple Tap")
        )
    }
}
