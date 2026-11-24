package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.protocol.DacCapabilities
import com.suprcharg3d.dongleCTRL.protocol.DacDeviceState
import com.suprcharg3d.dongleCTRL.protocol.DacProfile
import com.suprcharg3d.dongleCTRL.protocol.DacProtocol
import com.suprcharg3d.dongleCTRL.protocol.DacTransport
import com.suprcharg3d.dongleCTRL.protocol.ProtocolFamily

/**
 * NICEHCK `ttgk_info_v1` read-only UAC 2.0 audio specification driver
 * for NK1, NK1 Ultra, and NK1 Max (CX31993 + MAX97220, no HID DSP EQ/controls).
 */
class TtkInfoProtocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.TTGK_INFO
    override val displayName: String = "NICEHCK UAC 2.0 Info (ttgk_info_v1)"

    override fun buildCapabilities(profile: DacProfile): DacCapabilities = DacCapabilities(
        maxBands = 0,
        sampleRate = 384_000,
        hasEqSwitch = false,
        hasGlobalGain = false,
        hasDacFilter = false,
        hasOutputMode = false,
        hasWorkMode = false,
        hasVolume = false,
        hasChannelBalance = false,
        hasMicGain = false,
        hasMicEnc = false,
        hasLedControl = false,
        supportsRamRefresh = false,
        supportsFlashSave = false
    )

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState =
        DacDeviceState(
            firmwareVersion = "UAC 2.0",
            eqEnabled = false,
            bands = emptyList(),
            diagnosticNote = "CX31993 + MAX97220 · PCM 8–384 kHz (16/24/32-bit) · ADC 8–96 kHz (16/24-bit)"
        )

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean = false
}
