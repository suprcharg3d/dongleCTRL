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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Moondrop `bleSource` custom BLE GATT protocol (`9ECA0000-7F3A-4F32-9A38-A91B2C6E0100`).
 * Frame format (`ak6.java` / `l10.java` / `e30.java`):
 * - TX: `[0xA5, 0x01, cmdId (u8), payloadLen (u8), ...payload]`
 * - RX: `[0xA5, 0x01, cmdId (u8), payloadLen (u8), status (u8), ...data]`
 */
class MoondropBleSourceProtocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.MOONDROP_BLE_SOURCE
    override val displayName: String = "Moondrop BLE Source (0xA5)"

    @Volatile
    private var cachedRevision: Int = 0

    override fun buildCapabilities(profile: DacProfile): DacCapabilities = DacCapabilities(
        maxBands = profile.bandCount.coerceIn(5, 32),
        minGainDb = -12.0,
        maxGainDb = 12.0,
        minQ = 0.1,
        maxQ = 16.0,
        supportedFilters = listOf(
            FilterType.PEAK,
            FilterType.LOW_SHELF,
            FilterType.HIGH_SHELF,
            FilterType.LOW_PASS,
            FilterType.HIGH_PASS,
            FilterType.BAND_PASS,
            FilterType.ALL_PASS
        ),
        sampleRate = 48_000,
        hasEqSwitch = false,
        hasGlobalGain = true,
        globalGainRange = -12.0..12.0,
        hasVolume = true,
        maxVolume = 100,
        hasMicGain = true,
        micGainRange = -12..12,
        presets = (0..7).map { idx -> NamedOption(idx, if (idx == 7) "Custom PEQ" else "Preset ${idx + 1}") },
        supportsRamRefresh = false,
        supportsFlashSave = true,
        extraControls = listOf(
            DacControlDescriptor(
                key = KEY_AUDIO_SOURCE,
                label = "Audio Source",
                category = ControlCategory.AUDIO,
                kind = ControlKind.SINGLE_CHOICE,
                options = listOf(NamedOption(0, "Bluetooth"), NamedOption(1, "USB Audio"), NamedOption(2, "Aux / Line"))
            ),
            DacControlDescriptor(
                key = KEY_POWER_OFF,
                label = "Power Off Device",
                category = ControlCategory.POWER,
                kind = ControlKind.ACTION,
                isReadable = false
            )
        )
    )

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val extras = LinkedHashMap<String, DacControlValue>()

        val fwData = queryBleSource(transport, CMD_GET_FW_VERSION)
        val firmware = if (fwData != null && fwData.size >= 4) {
            "${fwData[1].toInt() and 0xFF}.${fwData[2].toInt() and 0xFF}.${fwData[3].toInt() and 0xFF}"
        } else null

        val sourceData = queryBleSource(transport, CMD_GET_AUDIO_SOURCE)
        val audioSource = sourceData?.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
        extras[KEY_AUDIO_SOURCE] = DacControlValue(intValue = audioSource)

        val volData = queryBleSource(transport, CMD_GET_DEVICE_VOLUME)
        val volume = (volData?.getOrNull(1)?.toInt()?.and(0xFF) ?: 50).coerceIn(0, 100)

        val presetData = queryBleSource(transport, CMD_GET_PRESET_EQ)
        val currentPreset = (presetData?.getOrNull(1)?.toInt()?.and(0xFF) ?: 0).coerceIn(0, 7)

        val peqCfg = queryBleSource(transport, CMD_GET_PEQ_CONFIG)
        var bandCount = profile.bandCount.coerceIn(5, 32)
        var preGainDb = 0.0
        var activePreset = currentPreset
        var eqEnabled = true
        if (peqCfg != null && peqCfg.size >= 8) {
            activePreset = (peqCfg[1].toInt() and 0xFF).coerceIn(0, 7)
            val hwCount = peqCfg[2].toInt() and 0xFF
            if (hwCount in 1..32) bandCount = hwCount
            cachedRevision = BytePack.getU16Le(peqCfg, 3)
            preGainDb = (BytePack.getI16Le(peqCfg, 5) / 100.0).coerceIn(-12.0, 12.0)
            eqEnabled = peqCfg[7].toInt() != 0
        }

        val micData = queryBleSource(transport, CMD_GET_MIC_GAIN)
        val micGainDb = if (micData != null && micData.size >= 3) {
            (BytePack.getI16Le(micData, 1) / 10.0).roundToInt().coerceIn(-12, 12)
        } else 0

        val flat = DefaultBands.flatBands(bandCount)
        val bands = flat.mapIndexed { idx, defaultBand ->
            val pt = queryBleSource(transport, CMD_GET_PEQ_POINT, byteArrayOf(idx.toByte()))
            if (pt != null && pt.size >= 9) {
                val freq = BytePack.getU16Le(pt, 2).coerceIn(20, 20000)
                val gain = (BytePack.getI16Le(pt, 4) / 100.0).coerceIn(-12.0, 12.0)
                val q = (BytePack.getU16Le(pt, 6) / 1000.0).coerceIn(0.1, 16.0)
                val fType = fromBleSourceFilterId(pt[8].toInt() and 0xFF)
                EqBand(
                    index = pt[1].toInt() and 0xFF,
                    frequencyHz = freq,
                    gainDb = (gain * 100.0).roundToInt() / 100.0,
                    q = (q * 1000.0).roundToInt() / 1000.0,
                    filterType = fType
                )
            } else {
                defaultBand
            }
        }

        val infoParts = mutableListOf<String>()
        queryBleSource(transport, CMD_GET_EARBUD_SN_LEFT)?.let { parseSn(it) }?.let { infoParts += "L SN: $it" }
        queryBleSource(transport, CMD_GET_EARBUD_SN_RIGHT)?.let { parseSn(it) }?.let { infoParts += "R SN: $it" }

        return DacDeviceState(
            firmwareVersion = firmware,
            eqEnabled = eqEnabled,
            activePresetId = activePreset,
            globalGainDb = preGainDb,
            bands = bands,
            volume = volume,
            maxVolume = 100,
            micGainDb = micGainDb,
            extraControlValues = extras,
            diagnosticNote = infoParts.joinToString(" · ").ifBlank { null }
        )
    }

    override suspend fun setVolume(transport: DacTransport, profile: DacProfile, volume: Int): Boolean {
        val resp = queryBleSource(transport, CMD_SET_DEVICE_VOLUME, byteArrayOf(volume.coerceIn(0, 100).toByte()))
        return isStatusOk(resp)
    }

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        val resp = queryBleSource(transport, CMD_SET_PRESET_EQ, byteArrayOf(presetId.coerceIn(0, 7).toByte()))
        return isStatusOk(resp)
    }

    override suspend fun setGlobalGain(transport: DacTransport, profile: DacProfile, gainDb: Double): Boolean {
        val raw = (gainDb.coerceIn(-12.0, 12.0) * 100.0).roundToInt()
        val payload = byteArrayOf(0x07, (raw and 0xFF).toByte(), ((raw ushr 8) and 0xFF).toByte())
        return isStatusOk(queryBleSource(transport, CMD_SET_PEQ_PREGAIN, payload))
    }

    override suspend fun setMicGain(transport: DacTransport, profile: DacProfile, gainDb: Int): Boolean {
        val raw = gainDb.coerceIn(-12, 12) * 10
        val payload = byteArrayOf((raw and 0xFF).toByte(), ((raw ushr 8) and 0xFF).toByte())
        return isStatusOk(queryBleSource(transport, CMD_SET_MIC_GAIN, payload))
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val freq = band.frequencyHz.coerceIn(20, 20000)
        val gainRaw = (band.gainDb.coerceIn(-12.0, 12.0) * 100.0).roundToInt()
        val qRaw = (band.q.coerceIn(0.1, 16.0) * 1000.0).roundToInt().coerceIn(100, 65535)
        val fId = toBleSourceFilterId(band.filterType)
        val payload = byteArrayOf(
            (band.index and 0x1F).toByte(),
            (freq and 0xFF).toByte(),
            ((freq ushr 8) and 0xFF).toByte(),
            (gainRaw and 0xFF).toByte(),
            ((gainRaw ushr 8) and 0xFF).toByte(),
            (qRaw and 0xFF).toByte(),
            ((qRaw ushr 8) and 0xFF).toByte(),
            fId.toByte()
        )
        return isStatusOk(queryBleSource(transport, CMD_SET_PEQ_POINT, payload))
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        setGlobalGain(transport, profile, globalGainDb)
        delay(20)
        for (band in bands) {
            if (!writeEqBand(transport, profile, band, globalGainDb, presetId)) return false
            delay(20)
        }
        return saveToFlash(transport, profile, presetId)
    }

    override suspend fun saveToFlash(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        val payload = byteArrayOf(
            0x01, // action = commit/save
            (cachedRevision and 0xFF).toByte(),
            ((cachedRevision ushr 8) and 0xFF).toByte()
        )
        val resp = queryBleSource(transport, CMD_COMMIT_PEQ, payload)
        if (resp != null && resp.size >= 3 && isStatusOk(resp)) {
            cachedRevision = BytePack.getU16Le(resp, 1)
            return true
        }
        return isStatusOk(resp)
    }

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean = when (key) {
        KEY_AUDIO_SOURCE -> isStatusOk(queryBleSource(transport, CMD_SET_AUDIO_SOURCE, byteArrayOf((value.intValue and 0xFF).toByte())))
        KEY_POWER_OFF -> isStatusOk(queryBleSource(transport, CMD_SET_POWER_OFF))
        else -> false
    }

    override fun applyIncomingPacket(
        packet: ByteArray,
        profile: DacProfile,
        currentState: DacDeviceState
    ): DacDeviceState? {
        val parsed = parseFrame(packet) ?: return null
        val (cmdId, data) = parsed
        return when (cmdId) {
            NOTIF_PRESET_EQ_CHANGED -> if (data.size >= 4) {
                currentState.copy(activePresetId = data[1].toInt() and 0xFF)
            } else null
            NOTIF_PEQ_COMMITTED -> if (data.size >= 4) {
                cachedRevision = BytePack.getU16Le(data, 1)
                currentState.copy(activePresetId = data[3].toInt() and 0xFF)
            } else null
            NOTIF_MIC_GAIN_CHANGED -> if (data.size >= 3) {
                val db = (BytePack.getI16Le(data, 1) / 10.0).roundToInt().coerceIn(-12, 12)
                currentState.copy(micGainDb = db)
            } else null
            else -> null
        }
    }

    internal fun buildFrame(cmdId: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val pkt = ByteArray(4 + payload.size)
        pkt[0] = MAGIC_0
        pkt[1] = VERSION_1
        pkt[2] = (cmdId and 0xFF).toByte()
        pkt[3] = (payload.size and 0xFF).toByte()
        payload.copyInto(pkt, destinationOffset = 4)
        return pkt
    }

    internal fun parseFrame(pkt: ByteArray): Pair<Int, ByteArray>? {
        if (pkt.size < 4 || pkt[0] != MAGIC_0 || pkt[1] != VERSION_1) return null
        val len = pkt[3].toInt() and 0xFF
        if (pkt.size < 4 + len) return null
        return (pkt[2].toInt() and 0xFF) to pkt.copyOfRange(4, 4 + len)
    }

    private suspend fun queryBleSource(
        transport: DacTransport,
        cmdId: Int,
        payload: ByteArray = byteArrayOf()
    ): ByteArray? {
        val resp = transport.transceive(buildFrame(cmdId, payload), expectedSize = 64, timeoutMs = 550) ?: return null
        val parsed = parseFrame(resp) ?: return null
        return if (parsed.first == cmdId) parsed.second else null
    }

    private fun isStatusOk(data: ByteArray?): Boolean {
        if (data == null || data.isEmpty()) return false
        val code = data[0].toInt() and 0xFF
        return code == 0 || code == 0x05 // OK or SAME_SOURCE
    }

    private fun parseSn(data: ByteArray): String? {
        if (data.size < 5) return null
        return data.copyOfRange(4, data.size).takeWhile { it != 0.toByte() }.toByteArray().decodeToString().trim().ifEmpty { null }
    }

    private fun toBleSourceFilterId(f: FilterType): Int = when (f) {
        FilterType.PEAK -> 0
        FilterType.LOW_SHELF -> 1
        FilterType.HIGH_SHELF -> 2
        FilterType.LOW_PASS -> 3
        FilterType.HIGH_PASS -> 4
        FilterType.BAND_PASS -> 5
        FilterType.ALL_PASS -> 7
    }

    private fun fromBleSourceFilterId(id: Int): FilterType = when (id) {
        0 -> FilterType.PEAK
        1 -> FilterType.LOW_SHELF
        2 -> FilterType.HIGH_SHELF
        3 -> FilterType.LOW_PASS
        4 -> FilterType.HIGH_PASS
        5 -> FilterType.BAND_PASS
        7 -> FilterType.ALL_PASS
        else -> FilterType.PEAK
    }

    companion object {
        const val SERVICE_UUID = "9ECA0000-7F3A-4F32-9A38-A91B2C6E0100"
        private const val MAGIC_0 = 0xA5.toByte()
        private const val VERSION_1 = 0x01.toByte()

        private const val CMD_GET_FW_VERSION = 0
        private const val CMD_GET_AUDIO_SOURCE = 1
        private const val CMD_SET_AUDIO_SOURCE = 2
        private const val CMD_GET_CAPABILITY = 3
        private const val CMD_SET_POWER_OFF = 4
        private const val CMD_GET_DEVICE_VOLUME = 5
        private const val CMD_SET_DEVICE_VOLUME = 6
        private const val CMD_GET_PRESET_EQ = 7
        private const val CMD_SET_PRESET_EQ = 8
        private const val CMD_GET_PEQ_CONFIG = 9
        private const val CMD_SET_PEQ_PREGAIN = 10
        private const val CMD_GET_PEQ_POINT = 11
        private const val CMD_SET_PEQ_POINT = 12
        private const val CMD_COMMIT_PEQ = 13
        private const val CMD_GET_MIC_GAIN = 14
        private const val CMD_SET_MIC_GAIN = 15
        private const val CMD_GET_EARBUD_SN_LEFT = 21
        private const val CMD_GET_EARBUD_SN_RIGHT = 23

        private const val NOTIF_PRESET_EQ_CHANGED = 134
        private const val NOTIF_PEQ_COMMITTED = 135
        private const val NOTIF_MIC_GAIN_CHANGED = 136

        private const val KEY_AUDIO_SOURCE = "blesrc_audio_source"
        private const val KEY_POWER_OFF = "blesrc_power_off"
    }
}
