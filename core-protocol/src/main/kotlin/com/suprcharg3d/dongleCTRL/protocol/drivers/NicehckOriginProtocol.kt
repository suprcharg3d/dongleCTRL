package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.CrcUtils
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * NICEHCK `origin_v1` Bluetooth protocol (`YUANDAO OriG in`).
 * Supports BLE GATT (`0000a100-...`) and Classic SPP (`66666666-...`).
 *
 * Packet framing:
 *   `[0x4E, lenLo, lenHi, crcFlag, cmdLo, cmdHi, ...body..., (optional CRC16-MODBUS u16le)]`
 *   where `len = 3 + body.size` (flag + cmdId + body).
 */
class NicehckOriginProtocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.NICEHCK_ORIGIN_BT
    override val displayName: String = "NICEHCK Origin BT (origin_v1)"

    @Volatile
    private var activePeqSlot: Int = CUSTOM_PEQ_SLOT

    @Volatile
    private var cachedBands: List<EqBand> = emptyList()

    @Volatile
    private var lastTouchMap: MutableMap<Int, Int> = LinkedHashMap()

    override fun buildCapabilities(profile: DacProfile): DacCapabilities =
        buildCaps(profile, fwVersionNum = 999)

    override fun resolveCapabilities(profile: DacProfile, state: DacDeviceState): DacCapabilities {
        val fwNum = state.extraControlValues[KEY_FW_NUM]?.intValue ?: 999
        return buildCaps(profile, fwNum)
    }

    private fun buildCaps(profile: DacProfile, fwVersionNum: Int): DacCapabilities {
        val maxBands = profile.bandCount.coerceIn(5, 10)
        val hasFw407Features = fwVersionNum >= 407
        val activePresets = buildList {
            add(NamedOption(0, "Pop"))
            add(NamedOption(1, "Balance"))
            add(NamedOption(2, "Rock"))
            add(NamedOption(3, "Natural"))
            add(NamedOption(4, "Gaming"))
            if (hasFw407Features) {
                add(NamedOption(5, "Fine Tune"))
                add(NamedOption(6, "Velvet Voice"))
            }
            add(NamedOption(CUSTOM_PEQ_SLOT, "Custom PEQ"))
        }
        val codecOptions = buildList {
            add(NamedOption(1, "LHDC"))
            add(NamedOption(0, "AAC"))
            if (hasFw407Features) {
                add(NamedOption(2, "SBC"))
            }
        }
        val extras = buildList {
            add(
                DacControlDescriptor(
                    key = KEY_ANC_MODE,
                    label = "Noise Control Mode",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SINGLE_CHOICE,
                    options = listOf(
                        NamedOption(0, "Normal (Off)"),
                        NamedOption(1, "Transparency"),
                        NamedOption(2, "ANC On"),
                        NamedOption(3, "Deep ANC"),
                        NamedOption(16, "Experimental ANC"),
                        NamedOption(17, "Wind Noise Canceling")
                    )
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_WIND_NOISE,
                    label = "Wind Noise Reduction (Exp.)",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_ANC_CYCLE,
                    label = "ANC Touch Cycle Modes",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.BITMASK,
                    options = listOf(
                        NamedOption(BIT_ANC_OFF, "Normal (Off)"),
                        NamedOption(BIT_ANC_TRANSPARENCY, "Transparency"),
                        NamedOption(BIT_ANC_NR, "ANC On")
                    )
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_BASS_BOOST,
                    label = "Bass Boost",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_SPATIAL_AUDIO,
                    label = "Spatial Audio",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_VOL_ADAPTIVE,
                    label = "Volume-Adaptive Sound",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_GAME_MODE,
                    label = "Game Mode",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_LOW_LATENCY,
                    label = "Low-Latency Mode",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_CODEC,
                    label = "Bluetooth Audio Codec",
                    category = ControlCategory.BLUETOOTH,
                    kind = ControlKind.SINGLE_CHOICE,
                    options = codecOptions
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_DUAL_CONN,
                    label = "Dual-Device Connection",
                    category = ControlCategory.BLUETOOTH,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_IN_EAR,
                    label = "In-Ear Detection",
                    category = ControlCategory.GENERAL,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_TONE_VOL,
                    label = "Prompt Tone Volume",
                    category = ControlCategory.GENERAL,
                    kind = ControlKind.SLIDER,
                    min = 0,
                    max = 16,
                    step = 1
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_FIND_EARBUDS,
                    label = "Find Earbuds Tone",
                    category = ControlCategory.GENERAL,
                    kind = ControlKind.SWITCH
                )
            )
            TOUCH_GESTURE_SLOTS.forEach { slot ->
                add(
                    DacControlDescriptor(
                        key = slot.key,
                        label = slot.label,
                        category = ControlCategory.BUTTONS,
                        kind = ControlKind.SINGLE_CHOICE,
                        options = TOUCH_ACTIONS
                    )
                )
            }
        }

        return DacCapabilities(
            maxBands = maxBands,
            minGainDb = -12.0,
            maxGainDb = 12.0,
            minQ = 0.1,
            maxQ = 10.0,
            supportedFilters = listOf(FilterType.PEAK, FilterType.HIGH_PASS, FilterType.LOW_PASS),
            sampleRate = 48_000,
            hasEqSwitch = false,
            hasGlobalGain = false,
            hasBattery = true,
            presets = activePresets,
            supportsRamRefresh = false,
            supportsFlashSave = false,
            extraControls = extras
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val extras = LinkedHashMap<String, DacControlValue>()

        val fwBody = queryOrigin(transport, CMD_GET_FIRMWARE)
        val firmware = fwBody?.takeIf { it.isNotEmpty() }
            ?.takeWhile { it != 0.toByte() }
            ?.toByteArray()
            ?.decodeToString()
            ?.trim()
            ?.ifEmpty { null }
        val fwNum = firmware?.filter { it.isDigit() }?.toIntOrNull() ?: 999
        extras[KEY_FW_NUM] = DacControlValue(intValue = fwNum)
        delay(20)

        var batteryPct: Int? = null
        var note: String? = null
        val batBody = queryOrigin(transport, CMD_GET_BATTERY)
        if (batBody != null && batBody.size >= 3) {
            val l = (batBody[0].toInt() and 0xFF).coerceIn(0, 100)
            val r = (batBody[1].toInt() and 0xFF).coerceIn(0, 100)
            val caseBat = (batBody[2].toInt() and 0xFF).coerceIn(0, 100)
            batteryPct = minOf(l, r)
            note = "Battery L $l% · R $r% · Case $caseBat%"
        }
        delay(20)

        val ancMode = queryOrigin(transport, CMD_GET_ANC)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
        extras[KEY_ANC_MODE] = DacControlValue(intValue = ancMode)
        delay(20)

        val windOn = (queryOrigin(transport, CMD_GET_WIND)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1
        extras[KEY_WIND_NOISE] = DacControlValue(boolValue = windOn)
        delay(20)

        val cycleBody = queryOrigin(transport, CMD_GET_ANC_CYCLE)
        var cycleMask = 0
        if (cycleBody != null && cycleBody.size >= 2) {
            var i = 0
            while (i + 1 < cycleBody.size) {
                val modeByte = cycleBody[i].toInt() and 0xFF
                val enabled = (cycleBody[i + 1].toInt() and 0xFF) == 1
                if (enabled) {
                    cycleMask = cycleMask or when (modeByte) {
                        0 -> BIT_ANC_OFF
                        1 -> BIT_ANC_TRANSPARENCY
                        2 -> BIT_ANC_NR
                        else -> 0
                    }
                }
                i += 2
            }
        }
        if (cycleMask == 0) cycleMask = BIT_ANC_OFF or BIT_ANC_TRANSPARENCY or BIT_ANC_NR
        extras[KEY_ANC_CYCLE] = DacControlValue(intValue = cycleMask)
        delay(20)

        val bassOn = (queryOrigin(transport, CMD_GET_BASS_BOOST)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1
        extras[KEY_BASS_BOOST] = DacControlValue(boolValue = bassOn)
        delay(20)

        val spatialBody = queryOrigin(transport, CMD_GET_SPATIAL_AUDIO)
        val spatialOn = spatialBody?.let {
            when {
                it.size >= 2 -> (it[0].toInt() and 0xFF) == 1
                it.size == 1 -> (it[0].toInt() and 0xFF) == 1
                else -> false
            }
        } ?: false
        extras[KEY_SPATIAL_AUDIO] = DacControlValue(boolValue = spatialOn)
        delay(20)

        val volAdaptOn = (queryOrigin(transport, CMD_GET_VOL_ADAPTIVE)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1
        extras[KEY_VOL_ADAPTIVE] = DacControlValue(boolValue = volAdaptOn)
        delay(20)

        val gameOn = (queryOrigin(transport, CMD_GET_GAME_MODE)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1
        extras[KEY_GAME_MODE] = DacControlValue(boolValue = gameOn)
        delay(20)

        val latencyOn = (queryOrigin(transport, CMD_GET_LOW_LATENCY)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1
        extras[KEY_LOW_LATENCY] = DacControlValue(boolValue = latencyOn)
        delay(20)

        val codecCode = (queryOrigin(transport, CMD_GET_CODEC)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 1).coerceIn(0, 2)
        extras[KEY_CODEC] = DacControlValue(intValue = codecCode)
        delay(20)

        val dualOn = (queryOrigin(transport, CMD_GET_DUAL_CONN)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1
        extras[KEY_DUAL_CONN] = DacControlValue(boolValue = dualOn)
        delay(20)

        val inEarOn = (queryOrigin(transport, CMD_GET_IN_EAR)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1
        extras[KEY_IN_EAR] = DacControlValue(boolValue = inEarOn)
        delay(20)

        val toneVol = (queryOrigin(transport, CMD_GET_TONE_VOL)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 8).coerceIn(0, 16)
        extras[KEY_TONE_VOL] = DacControlValue(intValue = toneVol)
        delay(20)

        extras[KEY_FIND_EARBUDS] = DacControlValue(boolValue = false)

        val touchBody = queryOrigin(transport, CMD_GET_TOUCH)
        lastTouchMap.clear()
        if (touchBody != null && touchBody.size >= 4) {
            var i = 0
            while (i + 3 < touchBody.size) {
                val ear = touchBody[i].toInt() and 0xFF
                val gesture = touchBody[i + 2].toInt() and 0xFF
                val action = touchBody[i + 3].toInt() and 0xFF
                lastTouchMap[(ear shl 8) or gesture] = action
                i += 4
            }
        }
        TOUCH_GESTURE_SLOTS.forEach { slot ->
            val action = lastTouchMap[(slot.ear shl 8) or slot.gesture] ?: 0
            extras[slot.key] = DacControlValue(intValue = action.coerceIn(0, 8))
        }
        delay(20)

        val eqBody = queryOrigin(transport, CMD_GET_EQ)
        val bandCount = profile.bandCount.coerceIn(5, 10)
        val flat = DefaultBands.flatBands(bandCount)
        var presetId = 0
        val parsedBands = if (eqBody != null && eqBody.isNotEmpty()) {
            val head = eqBody[0].toInt() and 0xFF
            if (head < CUSTOM_PEQ_SLOT) {
                presetId = head
                flat
            } else {
                activePeqSlot = head
                presetId = head
                val bandsDecoded = parsePeqBody(eqBody)
                if (bandsDecoded.isNotEmpty()) bandsDecoded else flat
            }
        } else {
            flat
        }
        cachedBands = parsedBands

        val codecLabel = when (codecCode) {
            0 -> "AAC"
            1 -> "LHDC"
            2 -> "SBC"
            else -> null
        }

        return DacDeviceState(
            firmwareVersion = firmware,
            eqEnabled = true,
            activePresetId = presetId,
            globalGainDb = 0.0,
            bands = parsedBands,
            batteryPercent = batteryPct,
            bluetoothCodec = codecLabel,
            extraControlValues = extras,
            diagnosticNote = note
        )
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val base = if (cachedBands.isNotEmpty()) cachedBands else DefaultBands.flatBands(profile.bandCount.coerceIn(5, 10))
        val updated = if (base.any { it.index == band.index }) {
            base.map { if (it.index == band.index) band else it }
        } else {
            base + band
        }
        cachedBands = updated
        val slot = if (activePeqSlot in CUSTOM_PEQ_SLOT..255) activePeqSlot else CUSTOM_PEQ_SLOT
        return sendOrigin(transport, CMD_SET_EQ, encodeBandPayload(band, slot))
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val slot = if (activePeqSlot in CUSTOM_PEQ_SLOT..255) activePeqSlot else CUSTOM_PEQ_SLOT
        cachedBands = bands
        var allOk = true
        bands.forEach { band ->
            if (!sendOrigin(transport, CMD_SET_EQ, encodeBandPayload(band, slot))) {
                allOk = false
            }
            delay(20)
        }
        return allOk
    }

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        val clamped = presetId.coerceIn(0, 255)
        if (clamped >= CUSTOM_PEQ_SLOT) {
            activePeqSlot = clamped
        }
        return sendOrigin(transport, CMD_SET_EQ, byteArrayOf(clamped.toByte()))
    }

    override suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean {
        val flat = (if (cachedBands.isNotEmpty()) cachedBands else DefaultBands.flatBands(profile.bandCount.coerceIn(5, 10)))
            .map { it.copy(gainDb = 0.0, q = 0.75, filterType = FilterType.PEAK) }
        return writeAllEqBands(transport, profile, flat, 0.0, activePeqSlot)
    }

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean {
        return when (key) {
            KEY_ANC_MODE -> sendOrigin(transport, CMD_SET_ANC, byteArrayOf(value.intValue.toByte(), 0x00))
            KEY_WIND_NOISE -> sendOrigin(transport, CMD_SET_WIND, byteArrayOf(if (value.boolValue) 1 else 0))
            KEY_ANC_CYCLE -> {
                val mask = value.intValue
                val payload = byteArrayOf(
                    0x00, if ((mask and BIT_ANC_OFF) != 0) 0x01 else 0x00,
                    0x01, if ((mask and BIT_ANC_TRANSPARENCY) != 0) 0x01 else 0x00,
                    0x02, if ((mask and BIT_ANC_NR) != 0) 0x01 else 0x00
                )
                sendOrigin(transport, CMD_SET_ANC_CYCLE, payload)
            }
            KEY_BASS_BOOST -> sendOrigin(transport, CMD_SET_BASS_BOOST, byteArrayOf(if (value.boolValue) 1 else 0))
            KEY_SPATIAL_AUDIO -> sendOrigin(transport, CMD_SET_SPATIAL_AUDIO, byteArrayOf(if (value.boolValue) 1 else 0, 0x00))
            KEY_VOL_ADAPTIVE -> sendOrigin(transport, CMD_SET_VOL_ADAPTIVE, byteArrayOf(if (value.boolValue) 1 else 0))
            KEY_GAME_MODE -> sendOrigin(transport, CMD_SET_GAME_MODE, byteArrayOf(if (value.boolValue) 1 else 0))
            KEY_LOW_LATENCY -> sendOrigin(transport, CMD_SET_LOW_LATENCY, byteArrayOf(if (value.boolValue) 1 else 0))
            KEY_CODEC -> sendOrigin(transport, CMD_SET_CODEC, byteArrayOf(value.intValue.coerceIn(0, 2).toByte()))
            KEY_DUAL_CONN -> sendOrigin(transport, CMD_SET_DUAL_CONN, byteArrayOf(if (value.boolValue) 1 else 0))
            KEY_IN_EAR -> sendOrigin(transport, CMD_SET_IN_EAR, byteArrayOf(if (value.boolValue) 1 else 0))
            KEY_TONE_VOL -> sendOrigin(transport, CMD_SET_TONE_VOL, byteArrayOf(value.intValue.coerceIn(0, 16).toByte()))
            KEY_FIND_EARBUDS -> sendOrigin(transport, CMD_FIND_EARBUDS, byteArrayOf(if (value.boolValue) 1 else 0))
            else -> {
                val slot = TOUCH_GESTURE_SLOTS.firstOrNull { it.key == key } ?: return false
                val action = value.intValue.coerceIn(0, 8)
                lastTouchMap[(slot.ear shl 8) or slot.gesture] = action
                sendOrigin(
                    transport,
                    CMD_SET_TOUCH,
                    byteArrayOf(slot.ear.toByte(), 0x00, slot.gesture.toByte(), action.toByte())
                )
            }
        }
    }

    private fun parsePeqBody(body: ByteArray): List<EqBand> {
        val entries: List<ByteArray> = when {
            body.size >= 9 && body.size % 9 == 0 &&
                (0 until body.size step 9).all { (body[it].toInt() and 0xFF) == (body[0].toInt() and 0xFF) } ->
                (0 until body.size step 9).map { body.copyOfRange(it + 1, it + 9) }
            body.size > 1 && (body.size - 1) % 8 == 0 ->
                (1 until body.size step 8).map { body.copyOfRange(it, it + 8) }
            else -> emptyList()
        }
        return entries.mapIndexed { pos, chunk ->
            val bandIdx = chunk[0].toInt() and 0xFF
            val modeCode = chunk[1].toInt() and 0xFF
            val freq = BytePack.getU16Le(chunk, 2).coerceIn(20, 20000)
            val gainDb = (BytePack.getI16Le(chunk, 4) / 256.0).coerceIn(-12.0, 12.0)
            val q = (BytePack.getU16Le(chunk, 6) / 256.0).coerceIn(0.1, 10.0)
            EqBand(
                index = if (entries.size > 1) bandIdx else pos,
                frequencyHz = freq,
                gainDb = (gainDb * 10.0).roundToInt() / 10.0,
                q = (q * 100.0).roundToInt() / 100.0,
                filterType = when (modeCode) {
                    1 -> FilterType.HIGH_PASS
                    2 -> FilterType.LOW_PASS
                    else -> FilterType.PEAK
                }
            )
        }.sortedBy { it.index }
    }

    private fun encodeBandPayload(band: EqBand, slot: Int): ByteArray {
        val modeCode = when (band.filterType) {
            FilterType.HIGH_PASS -> 1
            FilterType.LOW_PASS -> 2
            else -> 0
        }
        val freq = band.frequencyHz.coerceIn(20, 20000)
        val gainRaw = (band.gainDb.coerceIn(-12.0, 12.0) * 256.0).roundToInt()
        val qRaw = (band.q.coerceIn(0.1, 10.0) * 256.0).roundToInt().coerceIn(26, 2560)
        return byteArrayOf(
            (slot and 0xFF).toByte(),
            (band.index and 0xFF).toByte(),
            modeCode.toByte(),
            (freq and 0xFF).toByte(),
            ((freq ushr 8) and 0xFF).toByte(),
            (gainRaw and 0xFF).toByte(),
            ((gainRaw ushr 8) and 0xFF).toByte(),
            (qRaw and 0xFF).toByte(),
            ((qRaw ushr 8) and 0xFF).toByte()
        )
    }

    internal fun buildOriginPacket(cmdId: Int, body: ByteArray = byteArrayOf(), includeCrc: Boolean = false): ByteArray {
        val len = 3 + body.size
        val pkt = ByteArray(6 + body.size + if (includeCrc) 2 else 0)
        pkt[0] = HEADER_MAGIC.toByte()
        pkt[1] = (len and 0xFF).toByte()
        pkt[2] = ((len ushr 8) and 0xFF).toByte()
        pkt[3] = if (includeCrc) 0x01 else 0x00
        pkt[4] = (cmdId and 0xFF).toByte()
        pkt[5] = ((cmdId ushr 8) and 0xFF).toByte()
        body.copyInto(pkt, destinationOffset = 6)
        if (includeCrc) {
            val crc = CrcUtils.crc16Ibm(pkt, 0, pkt.size - 2)
            pkt[pkt.size - 2] = (crc and 0xFF).toByte()
            pkt[pkt.size - 1] = ((crc ushr 8) and 0xFF).toByte()
        }
        return pkt
    }

    internal fun parseOriginPacket(pkt: ByteArray): Pair<Int, ByteArray>? {
        if (pkt.size < 6 || (pkt[0].toInt() and 0xFF) != HEADER_MAGIC) return null
        val len = BytePack.getU16Le(pkt, 1)
        val hasCrc = pkt[3] != 0.toByte()
        val expectedLen = 3 + len + if (hasCrc) 2 else 0
        if (pkt.size != expectedLen) return null
        if (hasCrc) {
            val expectedCrc = CrcUtils.crc16Ibm(pkt, 0, pkt.size - 2)
            val actualCrc = BytePack.getU16Le(pkt, pkt.size - 2)
            if (expectedCrc != actualCrc) return null
        }
        val cmdId = BytePack.getU16Le(pkt, 4)
        val bodyEnd = pkt.size - if (hasCrc) 2 else 0
        val body = if (bodyEnd > 6) pkt.copyOfRange(6, bodyEnd) else byteArrayOf()
        return cmdId to body
    }

    private suspend fun sendOrigin(transport: DacTransport, cmdId: Int, body: ByteArray = byteArrayOf()): Boolean =
        transport.send(buildOriginPacket(cmdId, body))

    private suspend fun queryOrigin(
        transport: DacTransport,
        cmdId: Int,
        body: ByteArray = byteArrayOf(),
        timeoutMs: Int = 550
    ): ByteArray? {
        val resp = transport.transceive(buildOriginPacket(cmdId, body), expectedSize = 6, timeoutMs = timeoutMs)
            ?: return null
        val parsed = parseOriginPacket(resp) ?: return null
        return if (parsed.first == (cmdId and 0xFFFF)) parsed.second else null
    }

    companion object {
        const val HEADER_MAGIC = 0x4E
        const val CUSTOM_PEQ_SLOT = 0x21

        const val SPP_UUID = "66666666-6666-6666-6666-666666666666"
        const val GATT_SERVICE_UUID = "0000a100-1000-8000-4e48-434b4354524c"

        private const val CMD_GET_FIRMWARE = 0x0003
        private const val CMD_GET_BATTERY = 0x0005
        private const val CMD_GET_ANC = 0x0101
        private const val CMD_GET_TONE_VOL = 0x0102
        private const val CMD_GET_TOUCH = 0x0103
        private const val CMD_GET_CODEC = 0x0104
        private const val CMD_GET_DUAL_CONN = 0x0105
        private const val CMD_GET_LOW_LATENCY = 0x0106
        private const val CMD_GET_EQ = 0x0107
        private const val CMD_GET_GAME_MODE = 0x0108
        private const val CMD_GET_IN_EAR = 0x0109
        private const val CMD_GET_ANC_CYCLE = 0x010A
        private const val CMD_GET_BASS_BOOST = 0x010B
        private const val CMD_GET_SPATIAL_AUDIO = 0x010C
        private const val CMD_GET_VOL_ADAPTIVE = 0x010E
        private const val CMD_GET_WIND = 0x01E1

        private const val CMD_SET_ANC = 0x0201
        private const val CMD_SET_TONE_VOL = 0x0202
        private const val CMD_SET_TOUCH = 0x0203
        private const val CMD_SET_CODEC = 0x0204
        private const val CMD_SET_DUAL_CONN = 0x0205
        private const val CMD_SET_LOW_LATENCY = 0x0206
        private const val CMD_SET_EQ = 0x0207
        private const val CMD_SET_GAME_MODE = 0x0208
        private const val CMD_SET_IN_EAR = 0x0209
        private const val CMD_SET_ANC_CYCLE = 0x020A
        private const val CMD_SET_BASS_BOOST = 0x020B
        private const val CMD_SET_SPATIAL_AUDIO = 0x020C
        private const val CMD_SET_VOL_ADAPTIVE = 0x020E
        private const val CMD_SET_WIND = 0x02E1
        private const val CMD_FIND_EARBUDS = 0x03A2

        private const val KEY_FW_NUM = "_origin_fw_num"
        private const val KEY_ANC_MODE = "origin_anc_mode"
        private const val KEY_WIND_NOISE = "origin_wind_noise"
        private const val KEY_ANC_CYCLE = "origin_anc_cycle"
        private const val KEY_BASS_BOOST = "origin_bass_boost"
        private const val KEY_SPATIAL_AUDIO = "origin_spatial_audio"
        private const val KEY_VOL_ADAPTIVE = "origin_vol_adaptive"
        private const val KEY_GAME_MODE = "origin_game_mode"
        private const val KEY_LOW_LATENCY = "origin_low_latency"
        private const val KEY_CODEC = "origin_codec"
        private const val KEY_DUAL_CONN = "origin_dual_conn"
        private const val KEY_IN_EAR = "origin_in_ear"
        private const val KEY_TONE_VOL = "origin_tone_vol"
        private const val KEY_FIND_EARBUDS = "origin_find_earbuds"

        private const val BIT_ANC_OFF = 1 shl 0
        private const val BIT_ANC_TRANSPARENCY = 1 shl 1
        private const val BIT_ANC_NR = 1 shl 2

        private data class TouchSlot(val key: String, val label: String, val ear: Int, val gesture: Int)

        private val TOUCH_GESTURE_SLOTS = listOf(
            TouchSlot("origin_btn_l_1", "Left · Single Tap", ear = 1, gesture = 1),
            TouchSlot("origin_btn_l_2", "Left · Double Tap", ear = 1, gesture = 2),
            TouchSlot("origin_btn_l_3", "Left · Triple Tap", ear = 1, gesture = 3),
            TouchSlot("origin_btn_l_5", "Left · Long Press", ear = 1, gesture = 5),
            TouchSlot("origin_btn_r_1", "Right · Single Tap", ear = 2, gesture = 1),
            TouchSlot("origin_btn_r_2", "Right · Double Tap", ear = 2, gesture = 2),
            TouchSlot("origin_btn_r_3", "Right · Triple Tap", ear = 2, gesture = 3),
            TouchSlot("origin_btn_r_5", "Right · Long Press", ear = 2, gesture = 5)
        )

        private val TOUCH_ACTIONS = listOf(
            NamedOption(0, "Disabled"),
            NamedOption(1, "Play / Pause"),
            NamedOption(2, "Previous Track"),
            NamedOption(3, "Next Track"),
            NamedOption(4, "Noise Control Cycle"),
            NamedOption(5, "Volume Down"),
            NamedOption(6, "Volume Up"),
            NamedOption(7, "Game Mode"),
            NamedOption(8, "Voice Assistant")
        )
    }
}
