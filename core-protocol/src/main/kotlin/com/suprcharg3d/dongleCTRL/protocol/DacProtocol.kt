package com.suprcharg3d.dongleCTRL.protocol

import com.suprcharg3d.dongleCTRL.dsp.EqBand

/**
 * Standard protocol template implemented by every DAC family driver.
 * Add future DAC chipsets by implementing this interface and registering it in [ProtocolRegistry].
 */
interface DacProtocol {
    val family: ProtocolFamily
    val displayName: String

    fun buildCapabilities(profile: DacProfile): DacCapabilities

    /**
     * Optional post-sync capability refinement based on runtime device probes stored in [state]
     * (e.g. discovering optional CB 0x4B registers such as hardware volume, DRE, or DAC LED).
     */
    fun resolveCapabilities(profile: DacProfile, state: DacDeviceState): DacCapabilities =
        buildCapabilities(profile)

    suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState

    suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean

    suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        for (band in bands) {
            if (!writeEqBand(transport, profile, band, globalGainDb, presetId)) return false
        }
        return true
    }

    suspend fun refreshEqRam(transport: DacTransport, profile: DacProfile): Boolean = true

    suspend fun saveToFlash(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean = true

    suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean = false

    suspend fun setEqEnabled(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean = false

    suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean = false

    suspend fun setGlobalGain(transport: DacTransport, profile: DacProfile, gainDb: Double): Boolean = false

    suspend fun setDacFilter(transport: DacTransport, profile: DacProfile, filterIndex: Int): Boolean = false

    suspend fun setOutputMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean = false

    suspend fun setWorkMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean = false

    suspend fun setVolume(transport: DacTransport, profile: DacProfile, volume: Int): Boolean = false

    suspend fun setChannelBalance(transport: DacTransport, profile: DacProfile, balance: Int): Boolean = false

    suspend fun setMicGain(transport: DacTransport, profile: DacProfile, gainDb: Int): Boolean = false

    suspend fun setMicMonitor(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean = false

    suspend fun setMicEnc(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean = false

    suspend fun setLedMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean = false

    suspend fun setSurround3d(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean = false

    suspend fun setMicSwitch(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean = false

    suspend fun setUacMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean = false

    suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean = false

    suspend fun setDeviceName(transport: DacTransport, profile: DacProfile, name: String): Boolean = false

    suspend fun setPresetName(transport: DacTransport, profile: DacProfile, presetId: Int, name: String): Boolean = false

    suspend fun sendDeviceAction(transport: DacTransport, profile: DacProfile, cmdId: Int): Boolean = false

    suspend fun refreshLiveTelemetry(
        transport: DacTransport,
        profile: DacProfile,
        currentState: DacDeviceState,
        includeAllControls: Boolean = false
    ): DacDeviceState? = null

    /**
     * Lightweight refresh of preset/EQ state (eqEnabled, globalGainDb, bands) right after switching
     * hardware EQ preset, avoiding a full multi-control device resync.
     */
    suspend fun refreshPresetEqState(
        transport: DacTransport,
        profile: DacProfile,
        currentState: DacDeviceState,
        presetId: Int
    ): DacDeviceState? = readDeviceState(transport, profile)

    fun applyIncomingPacket(
        packet: ByteArray,
        profile: DacProfile,
        currentState: DacDeviceState
    ): DacDeviceState? = null
}
