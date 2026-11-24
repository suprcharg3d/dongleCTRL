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
import kotlin.math.roundToInt

/**
 * Moondrop Airoha AB156x/AB158x Race protocol driver (`0x05 0x5A`) over SPP / BLE GATT
 * (`5052494D-2DAB-0341-6972-6F6861424C45`).
 */
class MoondropAirohaProtocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.MOONDROP_AIROHA
    override val displayName: String = "Moondrop Airoha Race"

    @Volatile
    private var cachedBands: List<EqBand> = emptyList()

    override fun buildCapabilities(profile: DacProfile): DacCapabilities = DacCapabilities(
        maxBands = profile.bandCount.coerceIn(5, 10),
        minGainDb = -12.0,
        maxGainDb = 12.0,
        minQ = 0.25,
        maxQ = 10.0,
        supportedFilters = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF),
        sampleRate = 48_000,
        hasEqSwitch = true,
        hasGlobalGain = false,
        hasBattery = true,
        presets = listOf(
            NamedOption(0, "Default"),
            NamedOption(1, "Bass Boost"),
            NamedOption(2, "Vocal"),
            NamedOption(0x65, "Custom PEQ")
        ),
        supportsRamRefresh = false,
        supportsFlashSave = true,
        extraControls = listOf(
            DacControlDescriptor(KEY_ANC_MODE, "Noise Control", ControlCategory.AUDIO, ControlKind.SINGLE_CHOICE, options = ANC_MODES),
            DacControlDescriptor(KEY_GAME_MODE, "Low-Latency Game Mode", ControlCategory.AUDIO, ControlKind.SWITCH),
            DacControlDescriptor(KEY_DUAL_CONN, "Dual-Device Connection", ControlCategory.BLUETOOTH, ControlKind.SWITCH)
        )
    )

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val extras = LinkedHashMap<String, DacControlValue>()

        val fw = queryRace(transport, RACE_GET_FW_VERSION)?.let {
            it.takeWhile { b -> b != 0.toByte() }.toByteArray().decodeToString().trim().ifEmpty { null }
        }
        val bat = queryRace(transport, RACE_GET_BATTERY)?.getOrNull(0)?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }
        val anc = queryRace(transport, RACE_GET_ANC)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
        val game = (queryRace(transport, RACE_GET_GAME_MODE)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1
        val dual = (queryRace(transport, RACE_GET_DUAL_CONN)?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) == 1

        extras[KEY_ANC_MODE] = DacControlValue(intValue = anc)
        extras[KEY_GAME_MODE] = DacControlValue(boolValue = game)
        extras[KEY_DUAL_CONN] = DacControlValue(boolValue = dual)

        val bandCount = profile.bandCount.coerceIn(5, 10)
        val eqBody = queryRace(transport, RACE_GET_PEQ)
        val parsedBands = if (eqBody != null && eqBody.size >= 2 + bandCount * 6) {
            (0 until bandCount).map { i ->
                val off = 2 + i * 6
                EqBand(
                    index = i,
                    frequencyHz = BytePack.getU16Le(eqBody, off).coerceIn(20, 20000),
                    gainDb = (BytePack.getI16Le(eqBody, off + 2) / 100.0).coerceIn(-12.0, 12.0),
                    q = (BytePack.getU16Le(eqBody, off + 4) / 100.0).coerceIn(0.25, 10.0),
                    filterType = FilterType.PEAK
                )
            }
        } else {
            DefaultBands.flatBands(bandCount)
        }
        cachedBands = parsedBands

        return DacDeviceState(
            firmwareVersion = fw,
            eqEnabled = true,
            activePresetId = eqBody?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0x65,
            bands = parsedBands,
            batteryPercent = bat,
            extraControlValues = extras
        )
    }

    override suspend fun setEqEnabled(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean =
        sendRace(transport, RACE_SET_PEQ, byteArrayOf(0x00, if (enabled) 1 else 0))

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean =
        sendRace(transport, RACE_SET_PEQ, byteArrayOf(0x01, (presetId and 0xFF).toByte()))

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val base = if (cachedBands.isNotEmpty()) cachedBands else DefaultBands.flatBands(profile.bandCount.coerceIn(5, 10))
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
        cachedBands = bands
        val body = ByteArray(2 + bands.size * 6)
        body[0] = (presetId and 0xFF).toByte()
        body[1] = (bands.size and 0xFF).toByte()
        bands.forEachIndexed { idx, band ->
            val off = 2 + idx * 6
            BytePack.u16Le(body, off, band.frequencyHz.coerceIn(20, 20000))
            BytePack.u16Le(body, off + 2, (band.gainDb.coerceIn(-12.0, 12.0) * 100.0).roundToInt())
            BytePack.u16Le(body, off + 4, (band.q.coerceIn(0.25, 10.0) * 100.0).roundToInt())
        }
        return sendRace(transport, RACE_SET_PEQ, body)
    }

    override suspend fun saveToFlash(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean =
        sendRace(transport, RACE_SAVE_PEQ, byteArrayOf((presetId and 0xFF).toByte()))

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean = when (key) {
        KEY_ANC_MODE -> sendRace(transport, RACE_SET_ANC, byteArrayOf((value.intValue and 0xFF).toByte()))
        KEY_GAME_MODE -> sendRace(transport, RACE_SET_GAME_MODE, byteArrayOf(if (value.boolValue) 1 else 0))
        KEY_DUAL_CONN -> sendRace(transport, RACE_SET_DUAL_CONN, byteArrayOf(if (value.boolValue) 1 else 0))
        else -> false
    }

    internal fun buildRaceFrame(cmdId: Int, body: ByteArray = byteArrayOf()): ByteArray {
        val len = 2 + body.size
        val pkt = ByteArray(4 + len)
        pkt[0] = HEADER_MAGIC
        pkt[1] = TYPE_CMD_NEED_RESP
        pkt[2] = (len and 0xFF).toByte()
        pkt[3] = ((len ushr 8) and 0xFF).toByte()
        pkt[4] = (cmdId and 0xFF).toByte()
        pkt[5] = ((cmdId ushr 8) and 0xFF).toByte()
        body.copyInto(pkt, destinationOffset = 6)
        return pkt
    }

    internal fun parseRaceFrame(pkt: ByteArray): Pair<Int, ByteArray>? {
        if (pkt.size < 6 || pkt[0] != HEADER_MAGIC) return null
        val len = BytePack.getU16Le(pkt, 2)
        if (len < 2 || pkt.size < 4 + len) return null
        val cmdId = BytePack.getU16Le(pkt, 4)
        return cmdId to pkt.copyOfRange(6, 4 + len)
    }

    private suspend fun sendRace(transport: DacTransport, cmdId: Int, body: ByteArray): Boolean =
        transport.send(buildRaceFrame(cmdId, body))

    private suspend fun queryRace(transport: DacTransport, cmdId: Int, body: ByteArray = byteArrayOf()): ByteArray? {
        val resp = transport.transceive(buildRaceFrame(cmdId, body), expectedSize = 64, timeoutMs = 450) ?: return null
        val parsed = parseRaceFrame(resp) ?: return null
        return if (parsed.first == cmdId) parsed.second else null
    }

    companion object {
        const val HEADER_MAGIC = 0x05.toByte()
        const val TYPE_CMD_NEED_RESP = 0x5A.toByte()

        private const val RACE_GET_PEQ = 0x0A04
        private const val RACE_SET_PEQ = 0x0A05
        private const val RACE_SAVE_PEQ = 0x0A06
        private const val RACE_GET_GAME_MODE = 0x0A00
        private const val RACE_SET_GAME_MODE = 0x0A01
        private const val RACE_GET_DUAL_CONN = 0x0A0A
        private const val RACE_SET_DUAL_CONN = 0x0A0B
        private const val RACE_GET_FW_VERSION = 0x0C02
        private const val RACE_GET_BATTERY = 0x0CD4
        private const val RACE_GET_ANC = 0x1200
        private const val RACE_SET_ANC = 0x1201

        private const val KEY_ANC_MODE = "airoha_anc"
        private const val KEY_GAME_MODE = "airoha_game"
        private const val KEY_DUAL_CONN = "airoha_dual"

        private val ANC_MODES = listOf(
            NamedOption(0, "Off"),
            NamedOption(1, "ANC On"),
            NamedOption(2, "Transparency")
        )
    }
}
