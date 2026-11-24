package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.EqBand
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
 * Tanchjim ComTrue EP0 protocol (`LUNACommand.kt`) for LUNA, LUNA AT, and SPACE.
 * Uses 7-byte vendor control transfers (`0x43 / 0xA0` OUT, `0xC3 / 0xA1` IN, `wValue=0`, `wIndex=0x09A0`).
 */
class ComtrueEp0Protocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.COMTRUE_EP0
    override val displayName: String = "ComTrue EP0 (0xC1/0xA5)"

    override fun buildCapabilities(profile: DacProfile): DacCapabilities =
        buildCaps(profile, fwVersionNum = null)

    override fun resolveCapabilities(profile: DacProfile, state: DacDeviceState): DacCapabilities {
        val fwNum = state.extraControlValues[KEY_FW_NUM]?.intValue
        return buildCaps(profile, fwNum)
    }

    private fun buildCaps(profile: DacProfile, fwVersionNum: Int?): DacCapabilities {
        val isSpace = profile.model.contains("SPACE", ignoreCase = true)
        val hasDre = when {
            fwVersionNum == null || fwVersionNum <= 0 -> true
            isSpace -> fwVersionNum >= SPACE_DRE_MIN_FW
            else -> fwVersionNum >= LUNA_DRE_MIN_FW
        }
        val extras = buildList {
            if (hasDre) {
                add(
                    DacControlDescriptor(
                        key = KEY_DRE,
                        label = "DRE (Dynamic Range Enhancement)",
                        category = ControlCategory.AUDIO,
                        kind = ControlKind.SWITCH
                    )
                )
            }
            add(
                DacControlDescriptor(
                    key = KEY_DEPOP,
                    label = "Hardware DEPOP Switch",
                    category = ControlCategory.AUDIO,
                    kind = ControlKind.SWITCH
                )
            )
            add(
                DacControlDescriptor(
                    key = KEY_REFRESH_SR,
                    label = "Refresh Live Sample Rate",
                    category = ControlCategory.GENERAL,
                    kind = ControlKind.ACTION,
                    isReadable = false
                )
            )
        }
        return DacCapabilities(
            maxBands = 0,
            sampleRate = 384_000,
            hasEqSwitch = false,
            hasGlobalGain = false,
            hasDacFilter = true,
            dacFilterOptions = listOf(
                NamedOption(0, "FAST-LL (Low Latency)"),
                NamedOption(1, "FAST-PC (Fast Steep)"),
                NamedOption(2, "SLOW-LL (Slow Descent)"),
                NamedOption(3, "SLOW-PC (Slow Steep)"),
                NamedOption(4, "NON-OS (Non-Oversampling)")
            ),
            hasOutputMode = true,
            outputModeOptions = listOf(
                NamedOption(0, "Class AB"),
                NamedOption(1, "Class H")
            ),
            hasWorkMode = true,
            workModeOptions = listOf(
                NamedOption(0, "Low Gain"),
                NamedOption(1, "High Gain")
            ),
            hasVolume = true,
            maxVolume = 60,
            hasChannelBalance = true,
            channelBalanceMax = 12,
            hasLedControl = true,
            ledOptions = listOf(
                NamedOption(0, "On"),
                NamedOption(2, "Off")
            ),
            supportsRamRefresh = false,
            supportsFlashSave = false,
            extraControls = extras
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        val extras = LinkedHashMap<String, DacControlValue>()

        val fwResp = queryEp0(transport, 0xA0)
        val fwInt = fwResp?.getOrNull(3)?.toInt()?.and(0xFF)
        if (fwInt != null) {
            extras[KEY_FW_NUM] = DacControlValue(intValue = fwInt)
        }
        val fwText = fwInt?.let { "V$it" }

        delay(20)
        val fsResp = queryEp0(transport, 0xA1)
        val fsText = fsResp?.getOrNull(3)?.let { formatSampleRate(it.toInt() and 0xFF) }

        delay(20)
        val volResp = queryEp0(transport, 0xA2)
        val volume = volResp?.getOrNull(4)?.let { levelFromByte(it.toInt() and 0xFF) } ?: 30
        val balR = volResp?.getOrNull(5)?.toInt()?.and(0xFF) ?: 0
        val balL = volResp?.getOrNull(6)?.toInt()?.and(0xFF) ?: 0
        val balance = decodeBalance(balL, balR)

        delay(20)
        val cfg1Resp = queryEp0(transport, 0xA3)
        val filterIdx = (cfg1Resp?.getOrNull(3)?.toInt()?.and(0xFF) ?: 0).coerceIn(0, 4)
        val workMode = (cfg1Resp?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0).coerceIn(0, 1)
        val ledMode = when (cfg1Resp?.getOrNull(5)?.toInt()?.and(0xFF) ?: 0) {
            0x02 -> 2
            else -> 0
        }

        delay(20)
        val cfg2Resp = queryEp0(transport, 0xA4)
        val outMode = (cfg2Resp?.getOrNull(3)?.toInt()?.and(0xFF) ?: 0).coerceIn(0, 1)
        val dreByte = cfg2Resp?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0x01
        extras[KEY_DRE] = DacControlValue(boolValue = dreByte == 0x00)
        extras.putIfAbsent(KEY_DEPOP, DacControlValue(boolValue = false))

        return DacDeviceState(
            firmwareVersion = fwText,
            eqEnabled = false,
            bands = emptyList(),
            dacFilterIndex = filterIdx,
            outputModeIndex = outMode,
            workModeIndex = workMode,
            volume = volume,
            maxVolume = 60,
            channelBalance = balance,
            ledMode = ledMode,
            extraControlValues = extras,
            diagnosticNote = fsText?.let { "Sample Rate: $it" }
        )
    }

    override suspend fun refreshLiveTelemetry(
        transport: DacTransport,
        profile: DacProfile,
        currentState: DacDeviceState,
        includeAllControls: Boolean
    ): DacDeviceState {
        val fsResp = queryEp0(transport, 0xA1)
        val fsText = fsResp?.getOrNull(3)?.let { formatSampleRate(it.toInt() and 0xFF) }
        val volResp = queryEp0(transport, 0xA2)
        val nextVol = volResp?.getOrNull(4)?.let { levelFromByte(it.toInt() and 0xFF) } ?: currentState.volume
        val nextBal = if (volResp != null && volResp.size >= 7) {
            decodeBalance(volResp[6].toInt() and 0xFF, volResp[5].toInt() and 0xFF)
        } else {
            currentState.channelBalance
        }

        var nextFilter = currentState.dacFilterIndex
        var nextWork = currentState.workModeIndex
        var nextLed = currentState.ledMode
        var nextOut = currentState.outputModeIndex
        val nextExtras = currentState.extraControlValues.toMutableMap()

        if (includeAllControls) {
            val cfg1 = queryEp0(transport, 0xA3)
            if (cfg1 != null && cfg1.size >= 6) {
                nextFilter = (cfg1[3].toInt() and 0xFF).coerceIn(0, 4)
                nextWork = (cfg1[4].toInt() and 0xFF).coerceIn(0, 1)
                nextLed = if ((cfg1[5].toInt() and 0xFF) == 0x02) 2 else 0
            }
            val cfg2 = queryEp0(transport, 0xA4)
            if (cfg2 != null && cfg2.size >= 5) {
                nextOut = (cfg2[3].toInt() and 0xFF).coerceIn(0, 1)
                nextExtras[KEY_DRE] = DacControlValue(boolValue = (cfg2[4].toInt() and 0xFF) == 0x00)
            }
        }
        return currentState.copy(
            dacFilterIndex = nextFilter,
            workModeIndex = nextWork,
            ledMode = nextLed,
            outputModeIndex = nextOut,
            volume = nextVol,
            channelBalance = nextBal,
            extraControlValues = nextExtras,
            diagnosticNote = fsText?.let { "Sample Rate: $it" } ?: currentState.diagnosticNote
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
        sendEp0Write(transport, 0x01, filterIndex.coerceIn(0, 4))

    override suspend fun setWorkMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean =
        sendEp0Write(transport, 0x02, modeIndex.coerceIn(0, 1))

    override suspend fun setVolume(transport: DacTransport, profile: DacProfile, volume: Int): Boolean {
        val rawByte = VOLUME_BYTES[volume.coerceIn(0, VOLUME_BYTES.lastIndex)]
        return sendEp0Write(transport, 0x04, rawByte)
    }

    override suspend fun setChannelBalance(transport: DacTransport, profile: DacProfile, balance: Int): Boolean {
        val clamped = balance.coerceIn(-12, 12)
        // LUNACommand: [3] = R attenuation steps (0.5dB), [4] = L attenuation steps (0.5dB)
        // Slider > 0 boosts R (attenuates L); Slider < 0 boosts L (attenuates R).
        val leftAttenSteps = if (clamped > 0) clamped * 2 else 0
        val rightAttenSteps = if (clamped < 0) (-clamped) * 2 else 0
        return sendEp0Write(transport, 0x05, rightAttenSteps, leftAttenSteps)
    }

    override suspend fun setLedMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean =
        sendEp0Write(transport, 0x06, if (mode == 2 || mode == 0x02) 0x02 else 0x00)

    override suspend fun setOutputMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean =
        sendEp0Write(transport, 0x08, modeIndex.coerceIn(0, 1))

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean = when (key) {
        KEY_DEPOP -> sendEp0Write(transport, 0x07, if (value.boolValue) 0x01 else 0x00)
        KEY_DRE -> sendEp0Write(transport, 0x09, if (value.boolValue) 0x00 else 0x01)
        KEY_REFRESH_SR -> queryEp0(transport, 0xA1) != null
        else -> false
    }

    private suspend fun queryEp0(transport: DacTransport, cmd: Int): ByteArray? {
        val req = buildCommand(cmd)
        repeat(2) { attempt ->
            val sent = transport.controlTransfer(
                requestType = EP0_REQ_TYPE_OUT,
                request = EP0_REQ_OUT,
                value = 0,
                index = EP0_INDEX,
                buffer = req,
                timeoutMs = 500
            )
            if (sent < 0) return null
            delay(if (attempt == 0) 15 else 35)
            val recv = ByteArray(7)
            val n = transport.controlTransfer(
                requestType = EP0_REQ_TYPE_IN,
                request = EP0_REQ_IN,
                value = 0,
                index = EP0_INDEX,
                buffer = recv,
                timeoutMs = 500
            )
            if (n >= 4 && (recv[0].toInt() and 0xFF) == 0xA1 &&
                (recv[1].toInt() and 0xFF) == 0xA5 &&
                (recv[2].toInt() and 0xFF) == cmd
            ) {
                return recv
            }
        }
        return null
    }

    private suspend fun sendEp0Write(
        transport: DacTransport,
        cmd: Int,
        p0: Int = 0,
        p1: Int = 0
    ): Boolean {
        val req = buildCommand(cmd, p0, p1)
        val sent = transport.controlTransfer(
            requestType = EP0_REQ_TYPE_OUT,
            request = EP0_REQ_OUT,
            value = 0,
            index = EP0_INDEX,
            buffer = req,
            timeoutMs = 500
        )
        return sent >= 0
    }

    internal fun buildCommand(cmd: Int, p0: Int = 0, p1: Int = 0): ByteArray = byteArrayOf(
        0xC1.toByte(),
        0xA5.toByte(),
        (cmd and 0xFF).toByte(),
        (p0 and 0xFF).toByte(),
        (p1 and 0xFF).toByte(),
        0x00,
        0x00
    )

    companion object {
        const val EP0_REQ_TYPE_OUT = 0x43
        const val EP0_REQ_OUT = 0xA0
        const val EP0_REQ_TYPE_IN = 0xC3
        const val EP0_REQ_IN = 0xA1
        const val EP0_INDEX = 0x09A0

        private const val LUNA_DRE_MIN_FW = 7
        private const val SPACE_DRE_MIN_FW = 9

        const val KEY_DRE = "comtrue_dre"
        const val KEY_DEPOP = "comtrue_depop"
        const val KEY_REFRESH_SR = "comtrue_refresh_sr"
        private const val KEY_FW_NUM = "_comtrue_fw_num"

        // Exact 61-step CS43131 attenuation curve from Tanchjim assets/tail_volume.json (level 0..60)
        val VOLUME_BYTES = intArrayOf(
            0xFF, 0xDC, 0xC8, 0xBE, 0xB6, 0xAE, 0xA6, 0x9E, 0x96, 0x90,
            0x8A, 0x84, 0x7E, 0x78, 0x72, 0x6E, 0x6C, 0x6A, 0x68, 0x66,
            0x64, 0x63, 0x62, 0x61, 0x60, 0x5F, 0x5E, 0x5D, 0x5C, 0x5B,
            0x5A, 0x59, 0x58, 0x57, 0x56, 0x55, 0x54, 0x53, 0x52, 0x51,
            0x50, 0x4E, 0x4C, 0x4A, 0x48, 0x46, 0x44, 0x40, 0x3C, 0x38,
            0x34, 0x30, 0x2C, 0x28, 0x24, 0x1E, 0x18, 0x12, 0x0C, 0x06,
            0x00
        )

        fun levelFromByte(value: Int): Int {
            val u8 = value and 0xFF
            val exact = VOLUME_BYTES.indexOf(u8)
            if (exact >= 0) return exact
            return VOLUME_BYTES.indices.minByOrNull { abs(VOLUME_BYTES[it] - u8) } ?: 30
        }

        private fun decodeBalance(leftSteps: Int, rightSteps: Int): Int {
            val valDb = if (rightSteps > 0) -(rightSteps / 2.0) else (leftSteps / 2.0)
            return valDb.roundToInt().coerceIn(-12, 12)
        }

        fun formatSampleRate(code: Int): String = when (code and 0xFF) {
            0x00 -> "Idle (No Audio)"
            0x10 -> "PCM 44.1 kHz"
            0x11 -> "PCM 48 kHz"
            0x12 -> "PCM 88.2 kHz"
            0x13 -> "PCM 96 kHz"
            0x14 -> "PCM 176.4 kHz"
            0x15 -> "PCM 192 kHz"
            0x16 -> "PCM 352.8 kHz"
            0x17 -> "PCM 384 kHz"
            0x18 -> "PCM 705.6 kHz"
            0x19 -> "PCM 768 kHz"
            0x20 -> "DSD DoP 64"
            0x21 -> "DSD DoP 128"
            0x22 -> "DSD DoP 256"
            0x23 -> "DSD DoP 512"
            0x40 -> "DSD Native 64"
            0x41 -> "DSD Native 128"
            0x42 -> "DSD Native 256"
            0x43 -> "DSD Native 512"
            else -> "Unknown (0x%02X)".format(code and 0xFF)
        }
    }
}
