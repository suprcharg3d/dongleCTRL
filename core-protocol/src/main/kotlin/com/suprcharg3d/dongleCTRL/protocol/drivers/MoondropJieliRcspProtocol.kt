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
import kotlin.math.roundToInt

/**
 * Moondrop Jieli RCSP (`FE DC BA ... EF`) Bluetooth protocol driver (`0000AE00..02` / Classic SPP).
 * Supports target info / battery, 10-band EQ (`ATTR_EQ 0x04`), ANC (`ATTR_ANC_MODE 0x0D`),
 * Game / Low-Latency work mode (`ATTR_WORK_MODE 0x0E`), and Key Gesture settings (`ATTR_KEY 0x05`).
 */
class MoondropJieliRcspProtocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.MOONDROP_JIELI_RCSP
    override val displayName: String = "Moondrop Jieli RCSP"

    @Volatile
    private var cachedBands: List<EqBand> = emptyList()

    @Volatile
    private var seqCounter: Int = 0

    override fun buildCapabilities(profile: DacProfile): DacCapabilities = DacCapabilities(
        maxBands = profile.bandCount.coerceIn(5, 10),
        minGainDb = -12.0,
        maxGainDb = 12.0,
        minQ = 0.5,
        maxQ = 4.0,
        supportedFilters = listOf(FilterType.PEAK),
        sampleRate = 48_000,
        hasEqSwitch = true,
        hasGlobalGain = false,
        hasBattery = true,
        presets = listOf(
            NamedOption(0, "Default"),
            NamedOption(1, "Rock"),
            NamedOption(2, "Pop"),
            NamedOption(3, "Classic"),
            NamedOption(4, "Jazz"),
            NamedOption(5, "Country"),
            NamedOption(6, "Custom")
        ),
        supportsRamRefresh = false,
        supportsFlashSave = false,
        extraControls = listOf(
            DacControlDescriptor(KEY_ANC_MODE, "Noise Control", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_MODES),
            DacControlDescriptor(KEY_WORK_MODE, "Game / Low-Latency Mode", ControlCategory.AUDIO, ControlKind.SWITCH)
        )
    )

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val extras = LinkedHashMap<String, DacControlValue>()

        val eqResp = queryRcsp(transport, OP_GET_SYS_INFO, byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, (1 shl ATTR_EQ).toByte()))
        val bandCount = profile.bandCount.coerceIn(5, 10)
        var presetId = 0
        val bands = if (eqResp != null && eqResp.size >= 4 + bandCount) {
            presetId = (eqResp[2].toInt() and 0x7F).coerceIn(0, 6)
            DefaultBands.flatBands(bandCount).mapIndexed { i, b ->
                b.copy(gainDb = eqResp[3 + i].toInt().coerceIn(-12, 12).toDouble())
            }
        } else {
            DefaultBands.flatBands(bandCount)
        }
        cachedBands = bands

        val ancResp = queryRcsp(transport, OP_GET_SYS_INFO, byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, ATTR_ANC_MODE.toByte()))
        extras[KEY_ANC_MODE] = DacControlValue(intValue = ancResp?.getOrNull(2)?.toInt()?.and(0xFF) ?: 0)

        val workResp = queryRcsp(transport, OP_GET_SYS_INFO, byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, ATTR_WORK_MODE.toByte()))
        extras[KEY_WORK_MODE] = DacControlValue(boolValue = (workResp?.getOrNull(2)?.toInt()?.and(0xFF) ?: 0) == 2)

        val batResp = queryRcsp(transport, OP_GET_TARGET_INFO, byteArrayOf(0x00, 0x00, 0x00, 0x02))
        val bat = batResp?.getOrNull(2)?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }

        return DacDeviceState(
            firmwareVersion = "RCSP",
            eqEnabled = true,
            activePresetId = presetId,
            bands = bands,
            batteryPercent = bat,
            extraControlValues = extras
        )
    }

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        val gains = cachedBands.ifEmpty { DefaultBands.flatBands(profile.bandCount.coerceIn(5, 10)) }
        return writeRcspEq(transport, presetId.coerceIn(0, 6), gains)
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val base = cachedBands.ifEmpty { DefaultBands.flatBands(profile.bandCount.coerceIn(5, 10)) }
        val updated = if (base.any { it.index == band.index }) base.map { if (it.index == band.index) band else it } else base + band
        cachedBands = updated
        return writeRcspEq(transport, 6, updated)
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        cachedBands = bands
        return writeRcspEq(transport, presetId.coerceIn(0, 6), bands)
    }

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean = when (key) {
        KEY_ANC_MODE -> setSysInfoAttr(transport, ATTR_ANC_MODE, byteArrayOf((value.intValue and 0xFF).toByte()))
        KEY_WORK_MODE -> setSysInfoAttr(transport, ATTR_WORK_MODE, byteArrayOf(if (value.boolValue) 0x02 else 0x01))
        else -> false
    }

    private suspend fun writeRcspEq(transport: DacTransport, presetId: Int, bands: List<EqBand>): Boolean {
        val data = ByteArray(2 + bands.size)
        data[0] = (presetId or 0x80).toByte() // bit7 = custom EQ data attached
        data[1] = (bands.size and 0xFF).toByte()
        bands.forEachIndexed { idx, band ->
            data[2 + idx] = band.gainDb.roundToInt().coerceIn(-12, 12).toByte()
        }
        return setSysInfoAttr(transport, ATTR_EQ, data)
    }

    private suspend fun setSysInfoAttr(transport: DacTransport, attrType: Int, value: ByteArray): Boolean {
        val tlv = ByteArray(3 + value.size)
        tlv[0] = 0xFF.toByte() // function = public/system
        tlv[1] = (1 + value.size).toByte()
        tlv[2] = (attrType and 0xFF).toByte()
        value.copyInto(tlv, destinationOffset = 3)
        return transport.send(buildRcspFrame(OP_SET_SYS_INFO, tlv))
    }

    internal fun buildRcspFrame(opCode: Int, payload: ByteArray): ByteArray {
        val seq = (seqCounter++) and 0xFF
        val body = byteArrayOf(seq.toByte()) + payload
        val out = ByteArray(8 + body.size)
        out[0] = 0xFE.toByte()
        out[1] = 0xDC.toByte()
        out[2] = 0xBA.toByte()
        out[3] = 0xC0.toByte()
        out[4] = (opCode and 0xFF).toByte()
        out[5] = ((body.size ushr 8) and 0xFF).toByte()
        out[6] = (body.size and 0xFF).toByte()
        body.copyInto(out, destinationOffset = 7)
        out[out.lastIndex] = 0xEF.toByte()
        return out
    }

    internal fun parseRcspFrame(pkt: ByteArray): Pair<Int, ByteArray>? {
        if (pkt.size < 8 ||
            pkt[0] != 0xFE.toByte() ||
            pkt[1] != 0xDC.toByte() ||
            pkt[2] != 0xBA.toByte() ||
            pkt.last() != 0xEF.toByte()
        ) return null
        val op = pkt[4].toInt() and 0xFF
        val len = ((pkt[5].toInt() and 0xFF) shl 8) or (pkt[6].toInt() and 0xFF)
        if (pkt.size < 8 + len) return null
        return op to pkt.copyOfRange(7, 7 + len)
    }

    private suspend fun queryRcsp(transport: DacTransport, opCode: Int, payload: ByteArray): ByteArray? {
        val resp = transport.transceive(buildRcspFrame(opCode, payload), expectedSize = 64, timeoutMs = 450) ?: return null
        val (op, body) = parseRcspFrame(resp) ?: return null
        return if (op == opCode && body.size > 1) body.copyOfRange(1, body.size) else null
    }

    companion object {
        private const val OP_GET_TARGET_INFO = 0x02
        private const val OP_GET_SYS_INFO = 0x07
        private const val OP_SET_SYS_INFO = 0x08

        private const val ATTR_EQ = 0x04
        private const val ATTR_ANC_MODE = 0x0D
        private const val ATTR_WORK_MODE = 0x0E

        private const val KEY_ANC_MODE = "jieli_anc"
        private const val KEY_WORK_MODE = "jieli_game"

        private val ANC_MODES = listOf(
            NamedOption(0, "Normal (Off)"),
            NamedOption(1, "ANC On"),
            NamedOption(2, "Transparency")
        )
    }
}
