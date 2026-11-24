package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.EqBand
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
import kotlin.math.min

/**
 * Moondrop Wireless Microphone BLE GATT (`8AF0B4D0-8D24-4B6B-8C14-47C81A400000`) protocol driver.
 * Implements `MW` binary PDU framing & `0xF0` multi-packet chunking (`xw5.java` / `qw5.java`)
 * for Wireless Mic RX gain, denoise, monitor, mute, and telemetry.
 */
class MoondropWmicProtocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.MOONDROP_WMIC
    override val displayName: String = "Moondrop Wireless Mic (GATT)"

    override fun buildCapabilities(profile: DacProfile): DacCapabilities = DacCapabilities(
        maxBands = 0,
        hasEqSwitch = false,
        hasGlobalGain = false,
        hasMicGain = true,
        micGainRange = 0..30,
        hasMicMonitor = true,
        hasMicEnc = true,
        hasBattery = true,
        supportsRamRefresh = false,
        supportsFlashSave = false,
        extraControls = listOf(
            DacControlDescriptor(KEY_MUTE, "Mic Mute", ControlCategory.AUDIO, ControlKind.SWITCH),
            DacControlDescriptor(KEY_RECORD, "TX Onboard Recording", ControlCategory.GENERAL, ControlKind.SWITCH)
        )
    )

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val extras = LinkedHashMap<String, DacControlValue>()
        val statusPdu = queryMwPdu(transport, byteArrayOf(0x01, 0x00))
        val micGain = statusPdu?.getOrNull(2)?.toInt()?.and(0xFF)?.coerceIn(0, 30) ?: 15
        val encOn = (statusPdu?.getOrNull(3)?.toInt()?.and(0xFF) ?: 0) == 1
        val monitorOn = (statusPdu?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) == 1
        val muteOn = (statusPdu?.getOrNull(5)?.toInt()?.and(0xFF) ?: 0) == 1
        val recOn = (statusPdu?.getOrNull(6)?.toInt()?.and(0xFF) ?: 0) == 1
        val bat = statusPdu?.getOrNull(7)?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }
        extras[KEY_MUTE] = DacControlValue(boolValue = muteOn)
        extras[KEY_RECORD] = DacControlValue(boolValue = recOn)

        return DacDeviceState(
            firmwareVersion = "WMIC V1.2",
            eqEnabled = false,
            bands = emptyList(),
            micGainDb = micGain,
            micMonitorEnabled = monitorOn,
            micEncEnabled = encOn,
            batteryPercent = bat,
            extraControlValues = extras
        )
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean = false

    override suspend fun setMicGain(transport: DacTransport, profile: DacProfile, gainDb: Int): Boolean =
        sendMwPdu(transport, byteArrayOf(0x02, 0x01, gainDb.coerceIn(0, 30).toByte()))

    override suspend fun setMicEnc(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean =
        sendMwPdu(transport, byteArrayOf(0x02, 0x02, if (enabled) 1 else 0))

    override suspend fun setMicMonitor(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean =
        sendMwPdu(transport, byteArrayOf(0x02, 0x03, if (enabled) 1 else 0))

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean = when (key) {
        KEY_MUTE -> sendMwPdu(transport, byteArrayOf(0x02, 0x04, if (value.boolValue) 1 else 0))
        KEY_RECORD -> sendMwPdu(transport, byteArrayOf(0x02, 0x05, if (value.boolValue) 1 else 0))
        else -> false
    }

    /**
     * Fragments [pdu] into ATT chunks using `0xF0` multi-packet framing (`xw5.a`) when longer than `mtu - 3`.
     */
    internal fun fragmentPdu(pdu: ByteArray, mtu: Int = 247): List<ByteArray> {
        require(pdu.size in 2..255 && mtu >= 23)
        if (pdu.size <= mtu - 3) return listOf(pdu.copyOf())
        val chunkPayload = mtu - 8
        val totalChunks = (pdu.size + chunkPayload - 1) / chunkPayload
        return (0 until totalChunks).map { idx ->
            val start = idx * chunkPayload
            val end = min((idx + 1) * chunkPayload, pdu.size)
            val hdr = byteArrayOf(0xF0.toByte(), pdu.size.toByte(), 0x00, idx.toByte(), totalChunks.toByte())
            hdr + pdu.copyOfRange(start, end)
        }
    }

    private suspend fun sendMwPdu(transport: DacTransport, pdu: ByteArray): Boolean {
        val chunks = fragmentPdu(pdu)
        for (chunk in chunks) {
            if (!transport.send(chunk)) return false
        }
        return true
    }

    private suspend fun queryMwPdu(transport: DacTransport, pdu: ByteArray): ByteArray? {
        val chunks = fragmentPdu(pdu)
        for (i in 0 until chunks.size - 1) {
            if (!transport.send(chunks[i])) return null
        }
        return transport.transceive(chunks.last(), expectedSize = 64, timeoutMs = 450)
    }

    companion object {
        const val SERVICE_UUID = "8af0b4d0-8d24-4b6b-8c14-47c81a400000"
        val TRANSPORT_INFO_HEADER = byteArrayOf(77, 87, 2, 0, -1, 0) // "MW\x02\x00\xFF\x00"

        private const val KEY_MUTE = "wmic_mute"
        private const val KEY_RECORD = "wmic_record"
    }
}
