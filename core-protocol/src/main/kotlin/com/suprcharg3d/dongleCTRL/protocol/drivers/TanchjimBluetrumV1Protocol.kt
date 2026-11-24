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
 * Tanchjim Bluetrum V1 SPP protocol (`ECHOCommand.kt`) for ECHO TWS.
 * Frame format: `FF <04 TX / 03|04 RX> 00 <len> 00 1D <group> <cmd> [payload]`
 */
class TanchjimBluetrumV1Protocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.BLUETRUM_V1
    override val displayName: String = "Tanchjim Bluetrum V1 (ECHO)"

    @Volatile
    private var cachedButtons: MutableMap<String, Pair<Int, Int>> = LinkedHashMap<String, Pair<Int, Int>>().apply {
        put("03", 2 to 0) // Play/Pause
        put("01", 3 to 4) // Vol+
        put("02", 3 to 4) // Vol-
        put("00", 0 to 1) // Prev
        put("04", 1 to 1) // Next
        put("05", 2 to 2) // Voice
    }

    @Volatile
    private var cachedRepairMode: Boolean = false

    @Volatile
    private var cachedPresetId: Int = 0

    override fun buildCapabilities(profile: DacProfile): DacCapabilities {
        val extras = buildList {
            BUTTON_SLOTS.forEach { (_, key, label) ->
                add(
                    DacControlDescriptor(
                        key = key,
                        label = label,
                        category = ControlCategory.BUTTONS,
                        kind = ControlKind.SINGLE_CHOICE,
                        options = ECHO_BUTTON_OPTIONS
                    )
                )
            }
            add(
                DacControlDescriptor(
                    key = KEY_BTN_REPAIR,
                    label = "Hold Gesture Re-Pair Mode",
                    category = ControlCategory.BUTTONS,
                    kind = ControlKind.SWITCH
                )
            )
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
            maxBands = 5,
            minGainDb = -10.0,
            maxGainDb = 10.0,
            minQ = 1.0,
            maxQ = 15.0,
            supportedFilters = listOf(FilterType.PEAK),
            sampleRate = 48_000,
            hasEqSwitch = false,
            hasGlobalGain = false,
            hasBattery = true,
            presets = PRESETS,
            writablePresetIds = setOf(5),
            supportsRamRefresh = false,
            supportsFlashSave = false,
            extraControls = extras
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val extras = LinkedHashMap<String, DacControlValue>()

        val verResp = queryBluetrumV1(transport, group = 0x02, cmd = 0x10)
        val firmware = verResp?.takeIf { it.size > 8 }?.let { buf ->
            buf.copyOfRange(8, buf.size)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .decodeToString()
                .trim()
                .ifEmpty { null }
        }
        delay(20)

        var batteryPct: Int? = null
        var batteryNote: String? = null
        val batResp = queryBluetrumV1(transport, group = 0x02, cmd = 0x16)
        if (batResp != null && batResp.size >= 13) {
            val isLeftPrimary = (batResp[8].toInt() and 0xFF) == 1
            val vA = ((batResp[9].toInt() and 0xFF) shl 8) or (batResp[10].toInt() and 0xFF)
            val vB = ((batResp[11].toInt() and 0xFF) shl 8) or (batResp[12].toInt() and 0xFF)
            val leftRaw = if (isLeftPrimary) vA else vB
            val rightRaw = if (isLeftPrimary) vB else vA
            fun toPct(v: Int): Int = if (v in 0..100) v else ((v - 3300) / 9).coerceIn(0, 100)
            batteryPct = minOf(toPct(leftRaw), toPct(rightRaw))
            batteryNote = if (leftRaw > 100 || rightRaw > 100) {
                "Battery L ${leftRaw}mV · R ${rightRaw}mV"
            } else {
                "Battery L ${leftRaw}% · R ${rightRaw}%"
            }
        }
        delay(20)

        val eqIdxResp = queryBluetrumV1(transport, group = 0x0A, cmd = 0x02, payload = byteArrayOf(0x00))
        cachedPresetId = eqIdxResp?.lastOrNull()?.toInt()?.and(0xFF)?.let { code ->
            PRESET_HEX_CODES.indexOf(code).takeIf { it >= 0 }
        } ?: 0
        delay(20)

        val btnResp = queryBluetrumV1(transport, group = 0x02, cmd = 0x13)
        if (btnResp != null && btnResp.size >= 24 && (btnResp[8].toInt() and 0xFF) == 0x07) {
            cachedRepairMode = (btnResp[22].toInt() and 0xFF) == 0x01
            parseEchoButtons(btnResp.copyOfRange(23, btnResp.size))
        }
        extras[KEY_BTN_REPAIR] = DacControlValue(boolValue = cachedRepairMode)
        BUTTON_SLOTS.forEach { (slotCode, key, _) ->
            val (ear, op) = cachedButtons[slotCode] ?: (3 to 4)
            extras[key] = DacControlValue(intValue = encodeButtonOption(ear, op))
        }
        delay(20)

        val bandsResp = queryBluetrumV1(
            transport,
            group = 0x0A,
            cmd = 0x05,
            payload = byteArrayOf(0x00, 0x04)
        )
        val flat = DefaultBands.flatBands(5)
        val parsedBands = if (bandsResp != null && bandsResp.size >= 10 + 5 * 7) {
            flat.mapIndexed { idx, fallback ->
                val off = 10 + idx * 7
                val freq = ((bandsResp[off].toInt() and 0xFF) shl 8) or (bandsResp[off + 1].toInt() and 0xFF)
                val q = (((bandsResp[off + 2].toInt() and 0xFF) shl 8) or (bandsResp[off + 3].toInt() and 0xFF))
                    .coerceIn(1, 15)
                val gainHi = bandsResp[off + 5].toInt() and 0xFF
                val gainLo = bandsResp[off + 6].toInt() and 0xFF
                val gainSigned = ((gainHi shl 8) or gainLo).toShort().toInt().coerceIn(-10, 10)
                EqBand(
                    index = idx,
                    frequencyHz = if (freq in 10..24000) freq else fallback.frequencyHz,
                    gainDb = gainSigned.toDouble(),
                    q = q.toDouble(),
                    filterType = FilterType.PEAK
                )
            }
        } else {
            flat
        }

        return DacDeviceState(
            firmwareVersion = firmware,
            eqEnabled = true,
            activePresetId = cachedPresetId,
            globalGainDb = 0.0,
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
        if (cachedPresetId != 5) {
            selectPreset(transport, profile, 5)
            delay(25)
        }
        val idx = band.index.coerceIn(0, 4)
        val qVal = band.q.roundToInt().coerceIn(1, 15)
        val gainVal = band.gainDb.roundToInt().coerceIn(-10, 10)
        val qOk = sendBluetrumV1(
            transport,
            group = 0x0A,
            cmd = 0x07,
            payload = byteArrayOf(idx.toByte(), idx.toByte(), 0x00, qVal.toByte())
        )
        delay(20)
        val gainOk = sendBluetrumV1(
            transport,
            group = 0x0A,
            cmd = 0x06,
            payload = byteArrayOf(
                idx.toByte(),
                idx.toByte(),
                ((gainVal ushr 8) and 0xFF).toByte(),
                (gainVal and 0xFF).toByte()
            )
        )
        return qOk && gainOk
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        selectPreset(transport, profile, 5)
        delay(30)
        var allOk = true
        bands.take(5).forEach { band ->
            if (!writeEqBand(transport, profile, band, globalGainDb, 5)) {
                allOk = false
            }
            delay(20)
        }
        return allOk
    }

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        val clamped = presetId.coerceIn(0, PRESETS.lastIndex)
        cachedPresetId = clamped
        val code = PRESET_HEX_CODES[clamped]
        return sendBluetrumV1(transport, group = 0x0A, cmd = 0x03, payload = byteArrayOf(code.toByte()))
    }

    override suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean {
        val ok = writeAllEqBands(transport, profile, DefaultBands.flatBands(5), 0.0, 5)
        delay(20)
        return selectPreset(transport, profile, 0) && ok
    }

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean {
        return when (key) {
            KEY_BTN_RESET -> sendBluetrumV1(transport, group = 0x02, cmd = 0x15)
            KEY_BTN_REPAIR -> {
                cachedRepairMode = value.boolValue
                sendButtonConfig(transport)
            }
            else -> {
                val slot = BUTTON_SLOTS.firstOrNull { it.second == key } ?: return false
                cachedButtons[slot.first] = decodeButtonOption(value.intValue)
                sendButtonConfig(transport)
            }
        }
    }

    private suspend fun sendButtonConfig(transport: DacTransport): Boolean {
        // payload structure (34 bytes total):
        // [0] = 0x07
        // [1..12] = for each slot (03, 01, 02, 00, 04, 05): <slotByte>, 0x01
        // [13..15] = 0x06, <repairFlag>, 0x06
        // [16..33] = for each slot: <ear>, <op>, <slotByte>
        val payload = ByteArray(34)
        payload[0] = 0x07
        var p1 = 1
        var p3 = 16
        ECHO_WRITE_SLOT_ORDER.forEach { slotCode ->
            val keyByte = slotCode.toInt(16).toByte()
            payload[p1++] = keyByte
            payload[p1++] = 0x01
            val (ear, op) = cachedButtons[slotCode] ?: (3 to 4)
            payload[p3++] = ear.toByte()
            payload[p3++] = op.toByte()
            payload[p3++] = keyByte
        }
        payload[13] = 0x06
        payload[14] = if (cachedRepairMode) 0x01 else 0x00
        payload[15] = 0x06
        return sendBluetrumV1(transport, group = 0x02, cmd = 0x14, payload = payload)
    }

    private fun parseEchoButtons(data: ByteArray) {
        var idx = 0
        while (idx + 1 < data.size) {
            val slotHex = "%02X".format(data[idx].toInt() and 0xFF)
            val lenCode = data[idx + 1].toInt() and 0xFF
            val payloadBytes = when (lenCode) {
                0x00 -> 0
                0x01 -> 2
                0x02 -> 4
                else -> {
                    idx += 2
                    continue
                }
            }
            val end = idx + 2 + payloadBytes
            if (end <= data.size && cachedButtons.containsKey(slotHex)) {
                cachedButtons[slotHex] = when {
                    payloadBytes >= 4 -> 2 to (data[idx + 3].toInt() and 0xFF) // 0x12 (Any · 2s Hold)
                    payloadBytes >= 2 -> (data[idx + 2].toInt() and 0xFF) to (data[idx + 3].toInt() and 0xFF)
                    else -> 3 to 4
                }
            }
            idx += 2 + payloadBytes
        }
    }

    private fun buildBluetrumV1Packet(group: Int, cmd: Int, payload: ByteArray): ByteArray {
        val pkt = ByteArray(8 + payload.size)
        pkt[0] = 0xFF.toByte()
        pkt[1] = 0x04
        pkt[2] = 0x00
        pkt[3] = (payload.size and 0xFF).toByte()
        pkt[4] = 0x00
        pkt[5] = 0x1D
        pkt[6] = (group and 0xFF).toByte()
        pkt[7] = (cmd and 0xFF).toByte()
        payload.copyInto(pkt, destinationOffset = 8)
        return pkt
    }

    private suspend fun sendBluetrumV1(
        transport: DacTransport,
        group: Int,
        cmd: Int,
        payload: ByteArray = byteArrayOf()
    ): Boolean = transport.send(buildBluetrumV1Packet(group, cmd, payload))

    private suspend fun queryBluetrumV1(
        transport: DacTransport,
        group: Int,
        cmd: Int,
        payload: ByteArray = byteArrayOf(),
        timeoutMs: Int = 500
    ): ByteArray? {
        val resp = transport.transceive(
            buildBluetrumV1Packet(group, cmd, payload),
            expectedSize = 8,
            timeoutMs = timeoutMs
        ) ?: return null
        return if (resp.size >= 8 &&
            (resp[0].toInt() and 0xFF) == 0xFF &&
            ((resp[1].toInt() and 0xFF) == 0x03 || (resp[1].toInt() and 0xFF) == 0x04) &&
            (resp[5].toInt() and 0xFF) == 0x1D &&
            (resp[7].toInt() and 0xFF) == (cmd and 0xFF)
        ) {
            resp
        } else {
            null
        }
    }

    companion object {
        private const val KEY_BTN_REPAIR = "echo_btn_repair"
        private const val KEY_BTN_RESET = "echo_btn_reset"

        private val PRESET_HEX_CODES = intArrayOf(0x00, 0x04, 0x01, 0x02, 0x03, 0x3F)
        private val PRESETS = listOf(
            NamedOption(0, "Default"),
            NamedOption(1, "Musical"),
            NamedOption(2, "High Boost"),
            NamedOption(3, "Vocal Enhancement"),
            NamedOption(4, "Low Boost"),
            NamedOption(5, "Custom")
        )

        private val ECHO_WRITE_SLOT_ORDER = listOf("03", "01", "02", "00", "04", "05")
        private val BUTTON_SLOTS = listOf(
            Triple("00", "echo_btn_prev", "Previous Track Gesture"),
            Triple("03", "echo_btn_play", "Play / Pause Gesture"),
            Triple("04", "echo_btn_next", "Next Track Gesture"),
            Triple("01", "echo_btn_vol_up", "Volume Up Gesture"),
            Triple("02", "echo_btn_vol_down", "Volume Down Gesture"),
            Triple("05", "echo_btn_voice", "Voice Assistant Gesture")
        )

        internal fun encodeButtonOption(ear: Int, op: Int): Int =
            if (ear == 3 || op == 4) 0x0304 else ((ear and 0xFF) shl 8) or (op and 0xFF)

        internal fun decodeButtonOption(code: Int): Pair<Int, Int> {
            val ear = (code ushr 8) and 0xFF
            val op = code and 0xFF
            return if (ear in 0..2) ear to op else 3 to 4
        }

        private val ECHO_BUTTON_OPTIONS = listOf(
            NamedOption(0x0304, "Disabled"),
            NamedOption(0x0000, "Left · Single Tap"),
            NamedOption(0x0001, "Left · Double Tap"),
            NamedOption(0x0002, "Left · Triple Tap"),
            NamedOption(0x0003, "Left · 4× Tap"),
            NamedOption(0x0004, "Left · 5× Tap"),
            NamedOption(0x0012, "Left · 2s Hold"),
            NamedOption(0x0100, "Right · Single Tap"),
            NamedOption(0x0101, "Right · Double Tap"),
            NamedOption(0x0102, "Right · Triple Tap"),
            NamedOption(0x0103, "Right · 4× Tap"),
            NamedOption(0x0104, "Right · 5× Tap"),
            NamedOption(0x0112, "Right · 2s Hold"),
            NamedOption(0x0200, "Any · Single Tap"),
            NamedOption(0x0201, "Any · Double Tap"),
            NamedOption(0x0202, "Any · Triple Tap"),
            NamedOption(0x0203, "Any · 4× Tap"),
            NamedOption(0x0204, "Any · 5× Tap"),
            NamedOption(0x0212, "Any · 2s Hold")
        )
    }
}
