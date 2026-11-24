package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.protocol.DacCapabilities
import com.suprcharg3d.dongleCTRL.protocol.DacDeviceState
import com.suprcharg3d.dongleCTRL.protocol.DacProfile
import com.suprcharg3d.dongleCTRL.protocol.DacProtocol
import com.suprcharg3d.dongleCTRL.protocol.DacTransport
import com.suprcharg3d.dongleCTRL.protocol.NamedOption
import com.suprcharg3d.dongleCTRL.protocol.ProtocolFamily
import kotlinx.coroutines.delay

/**
 * Moondrop ComTrue `CT7601` Vendor EP0 hardware control protocol (`0xC0 0xA5`).
 * Uses 7-byte Vendor Control transfers (`0x43/0xA0` write, `0xC3/0xA1` read, `wIndex = 0x09A0`).
 * Exposes DAC Filter (`1..5`), Gain Level (`Low`/`High`), Hardware Volume (`0..100`), and LED (`0`/`1`).
 */
class MoondropComtureProtocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.MOONDROP_COMTURE
    override val displayName: String = "Moondrop ComTrue EP0 (0xC0/0xA5)"

    override fun buildCapabilities(profile: DacProfile): DacCapabilities = DacCapabilities(
        maxBands = 0,
        sampleRate = profile.sampleRate.takeIf { it > 0 } ?: 384_000,
        hasEqSwitch = false,
        hasGlobalGain = false,
        hasDacFilter = true,
        dacFilterOptions = listOf(
            NamedOption(1, "FAST-LL: Steep Drop Low Latency"),
            NamedOption(2, "FAST-PC: Steep Drop Phase Comp"),
            NamedOption(3, "SLOW-LL: Slow Roll-off Low Latency"),
            NamedOption(4, "SLOW-PC: Slow Roll-off Phase Comp"),
            NamedOption(5, "NOS: Non-Oversampling Filter")
        ),
        hasOutputMode = false,
        hasWorkMode = true,
        workModeOptions = listOf(
            NamedOption(0, "Low Gain"),
            NamedOption(1, "High Gain")
        ),
        hasVolume = true,
        maxVolume = 100,
        hasChannelBalance = false,
        hasMicGain = false,
        hasLedControl = true,
        ledOptions = listOf(NamedOption(0, "Off"), NamedOption(1, "On")),
        supportsRamRefresh = false,
        supportsFlashSave = false
    )

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        val status = queryEp0(transport, byteArrayOf(MAGIC_0, MAGIC_1, CMD_QUERY_STATUS, 0, 0, 0, 0))
        val filter = (status?.getOrNull(3)?.toInt()?.and(0xFF) ?: 1).coerceIn(1, 5)
        val gain = (status?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0).coerceIn(0, 1)
        val led = (status?.getOrNull(5)?.toInt()?.and(0xFF) ?: 1).coerceIn(0, 1)

        val volResp = queryEp0(transport, byteArrayOf(MAGIC_0, MAGIC_1, CMD_QUERY_VOLUME, 0, 0, 0, 0))
        val rawVol = volResp?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0xFF
        val volume = if (rawVol == 0xFF) 0 else (100 - rawVol).coerceIn(0, 100)

        return DacDeviceState(
            firmwareVersion = "ComTrue EP0",
            eqEnabled = false,
            bands = emptyList(),
            dacFilterIndex = filter,
            workModeIndex = gain,
            volume = volume,
            maxVolume = 100,
            ledMode = led
        )
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean = false

    override suspend fun setDacFilter(transport: DacTransport, profile: DacProfile, filterIndex: Int): Boolean =
        sendEp0(transport, byteArrayOf(MAGIC_0, MAGIC_1, CMD_SET_FILTER, filterIndex.coerceIn(1, 5).toByte(), 0, 0, 0))

    override suspend fun setWorkMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean =
        sendEp0(transport, byteArrayOf(MAGIC_0, MAGIC_1, CMD_SET_GAIN, modeIndex.coerceIn(0, 1).toByte(), 0, 0, 0))

    override suspend fun setVolume(transport: DacTransport, profile: DacProfile, volume: Int): Boolean {
        val clamped = volume.coerceIn(0, 100)
        val wireVol = if (clamped == 0) 0xFF else (100 - clamped)
        return sendEp0(transport, byteArrayOf(MAGIC_0, MAGIC_1, CMD_SET_VOLUME, wireVol.toByte(), 0, 0, 0))
    }

    override suspend fun setLedMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean =
        sendEp0(transport, byteArrayOf(MAGIC_0, MAGIC_1, CMD_SET_LED, mode.coerceIn(0, 1).toByte(), 0, 0, 0))

    private suspend fun sendEp0(transport: DacTransport, pkt7: ByteArray): Boolean {
        val buf = pkt7.copyOf(7)
        return transport.controlTransfer(REQ_TYPE_OUT, REQ_OUT, 0, WINDEX_COMTURE, buf, timeoutMs = 150) >= 0
    }

    private suspend fun queryEp0(transport: DacTransport, pkt7: ByteArray): ByteArray? {
        if (!sendEp0(transport, pkt7)) return null
        delay(45)
        val rx = ByteArray(7)
        val got = transport.controlTransfer(REQ_TYPE_IN, REQ_IN, 0, WINDEX_COMTURE, rx, timeoutMs = 150)
        return if (got >= 5) rx else null
    }

    companion object {
        const val REQ_TYPE_OUT = 0x43
        const val REQ_OUT = 0xA0
        const val REQ_TYPE_IN = 0xC3
        const val REQ_IN = 0xA1
        const val WINDEX_COMTURE = 0x09A0

        const val MAGIC_0 = 0xC0.toByte()
        const val MAGIC_1 = 0xA5.toByte()

        private const val CMD_SET_FILTER: Byte = 0x01
        private const val CMD_SET_GAIN: Byte = 0x02
        private const val CMD_SET_VOLUME: Byte = 0x04
        private const val CMD_SET_LED: Byte = 0x06
        private const val CMD_QUERY_STATUS: Byte = 0xA3.toByte()
        private const val CMD_QUERY_VOLUME: Byte = 0xA2.toByte()
    }
}
