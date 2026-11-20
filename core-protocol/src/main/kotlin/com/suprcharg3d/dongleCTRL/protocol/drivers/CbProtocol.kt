package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.BiquadCalculator
import com.suprcharg3d.dongleCTRL.dsp.DefaultBands
import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.FilterType
import com.suprcharg3d.dongleCTRL.protocol.BytePack
import com.suprcharg3d.dongleCTRL.protocol.DacCapabilities
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
 * CB / TTGK / WalkPlay / EPZ HID protocol driver (Schemes 1, 10, 11..16, 18, 20, 21, 25, 29, 30).
 * Report ID 0x4B, 64-byte HID packets, Q2.30 biquads.
 */
class CbProtocol(
    private val isSurround3dFamily: Boolean = false
) : DacProtocol {

    override val family: ProtocolFamily =
        if (isSurround3dFamily) ProtocolFamily.CB1300D else ProtocolFamily.CB

    override val displayName: String =
        if (isSurround3dFamily) "CB1300D Surround (0x4B)" else "CB / TTGK DSP (0x4B)"

    override fun buildCapabilities(profile: DacProfile): DacCapabilities {
        val supportsStageControls = !isSurround3dFamily &&
            (profile.supportsDacFilter || profile.schemeNo in setOf("11", "16", "18"))
        return DacCapabilities(
            maxBands = profile.bandCount.coerceIn(1, 15),
            minGainDb = profile.minGainDb,
            maxGainDb = profile.maxGainDb,
            minQ = 0.25,
            maxQ = 16.0,
            supportedFilters = listOf(
                FilterType.PEAK,
                FilterType.LOW_SHELF,
                FilterType.HIGH_SHELF,
                FilterType.LOW_PASS,
                FilterType.HIGH_PASS
            ),
            sampleRate = profile.sampleRate,
            hasEqSwitch = false,
            hasGlobalGain = true,
            globalGainRange = -12.0..12.0,
            hasDacFilter = supportsStageControls,
            dacFilterOptions = listOf(
                NamedOption(1, "FAST-LL: Steep-Drop-Low-Latency Filter"),
                NamedOption(2, "FAST-PC: Steep Drop-Phase Compensation Filter"),
                NamedOption(3, "SLOW-LL: Slow descent-Low Latency Filter"),
                NamedOption(4, "SLOW-PC: Slow Decay-Phase Compensation Filter"),
                NamedOption(5, "NON OS: Non-Oversampling Filter")
            ),
            hasOutputMode = supportsStageControls,
            outputModeOptions = listOf(
                NamedOption(0, "Class AB"),
                NamedOption(1, "Class H")
            ),
            hasWorkMode = supportsStageControls,
            workModeOptions = listOf(
                NamedOption(0, "Low Gain"),
                NamedOption(1, "High Gain")
            ),
            hasVolume = false,
            hasChannelBalance = true,
            hasMicGain = profile.supportsMic,
            micGainRange = -15..15,
            hasMicMonitor = false,
            hasMicEnc = profile.supportsMic,
            hasSurround3d = isSurround3dFamily || profile.supportsSurround3d,
            // CB DACs store only a single active EQ bank in hardware RAM/Flash (no hardware preset slots);
            // pkt[36] is just an app-side eqTag metadata byte. Keep presets empty so the UI hides fake slots.
            supportsRamRefresh = true,
            supportsFlashSave = true
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()

        val fwResp = query(transport, CMD_FW_VERSION, 0)
        val firmware = fwResp?.let { buf ->
            val len = (buf.getOrNull(3)?.toInt() ?: 0) and 0xFF
            if (len in 1..20 && 4 + len <= buf.size) {
                buf.copyOfRange(4, 4 + len)
                    .takeWhile { it != 0.toByte() }
                    .toByteArray()
                    .decodeToString()
                    .trim()
                    .ifEmpty { null }
            } else {
                null
            }
        }

        val dacOffsetResp = query(transport, CMD_DAC_OFFSET, 1)
        val dacGainDb = dacOffsetResp?.let { buf ->
            if (buf.size >= 6) (BytePack.getI16Le(buf, 4) / 256.0).coerceIn(-12.0, 12.0) else 0.0
        } ?: 0.0

        val filterResp = query(transport, CMD_DAC_FILTER, 1)
        val rawFilterIdx = filterResp?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0
        val filterIdx = if (rawFilterIdx in 1..5) rawFilterIdx else 1

        val outModeResp = query(transport, CMD_OUTPUT_MODE, 1)
        val outMode = outModeResp?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0

        val workModeResp = query(transport, CMD_WORK_MODE, 1)
        val workMode = workModeResp?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0

        val encResp = query(transport, CMD_ENC, 1)
        val encOn = (encResp?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) == 1

        val adcResp = query(transport, CMD_ADC_OFFSET, 1)
        val micGain = adcResp?.let { buf ->
            if (buf.size >= 6) (BytePack.getI16Le(buf, 4) / 256.0).roundToInt().coerceIn(-15, 15) else 0
        } ?: 0

        val leftBalResp = query(transport, CMD_CHANNEL_BALANCE, 4, extra = byteArrayOf(0, 0))
        val rightBalResp = query(transport, CMD_CHANNEL_BALANCE, 4, extra = byteArrayOf(1, 0))
        val leftBal = leftBalResp?.let { sliderFromDevice(BytePack.getU16Be(it, 6)) } ?: 0
        val rightBal = rightBalResp?.let { sliderFromDevice(BytePack.getU16Be(it, 6)) } ?: 0
        val balance = if (leftBal < 0) abs(leftBal) else if (rightBal < 0) -abs(rightBal) else 0

        var activeTag = 0
        val flat = DefaultBands.flatBands(profile.bandCount)
        val readBands = flat.mapIndexed { idx, defaultBand ->
            val resp = query(transport, CMD_EQ_DATA, 0, byteArrayOf(0, idx.toByte()))
            if (resp != null && resp.size > 36) {
                activeTag = resp[36].toInt() and 0xFF
                val freq = BytePack.getU16Le(resp, 28)
                val qRaw = BytePack.getU16Le(resp, 30)
                val gainRaw = BytePack.getI16Le(resp, 32)
                val fType = resp[34].toInt() and 0xFF
                if (freq in 10..24000 && qRaw > 0) {
                    EqBand(
                        index = idx,
                        frequencyHz = freq,
                        gainDb = (gainRaw / 256.0).coerceIn(profile.minGainDb, profile.maxGainDb),
                        q = (qRaw / 256.0).coerceIn(0.25, 16.0),
                        filterType = FilterType.fromCbCode(fType)
                    )
                } else {
                    defaultBand
                }
            } else {
                defaultBand
            }
        }

        val surroundOn = if (isSurround3dFamily || profile.supportsSurround3d) {
            (query(transport, CMD_3D_SWITCH, 1)?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) == 1
        } else {
            false
        }

        return DacDeviceState(
            firmwareVersion = firmware,
            eqEnabled = true,
            activePresetId = activeTag,
            globalGainDb = dacGainDb,
            bands = readBands,
            dacFilterIndex = filterIdx,
            outputModeIndex = outMode.coerceIn(0, 1),
            workModeIndex = workMode.coerceIn(0, 1),
            channelBalance = balance.coerceIn(-12, 12),
            micGainDb = micGain,
            micEncEnabled = encOn,
            surround3dEnabled = surroundOn
        )
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val sampleRate = if (profile.sampleRate > 0) profile.sampleRate else 96_000
        val coeffs = BiquadCalculator.computeForCbQ2_30(band, sampleRate)
        val q30 = BiquadCalculator.quantizeCbQ2_30(coeffs)

        val pkt = ByteArray(64)
        pkt[0] = REPORT_ID.toByte()
        pkt[1] = 0x01
        pkt[2] = CMD_EQ_DATA.toByte()
        pkt[3] = 24 // 4 + 20 coefficient bytes
        pkt[4] = 0x00
        pkt[5] = (band.index and 0xFF).toByte()
        pkt[6] = 0x00
        pkt[7] = 0x00
        var off = 8
        for (c in q30) {
            BytePack.i32Le(pkt, off, c)
            off += 4
        }
        val q256 = (band.q * 256.0).roundToInt().coerceIn(25, 16 * 256)
        val gain256 = (band.gainDb * 256.0).roundToInt()
        BytePack.u16Le(pkt, 28, band.frequencyHz.coerceIn(10, 24000))
        BytePack.u16Le(pkt, 30, q256)
        BytePack.u16Le(pkt, 32, gain256)
        pkt[34] = band.filterType.cbCode.toByte()
        pkt[35] = (globalGainDb.roundToInt() and 0xFF).toByte()
        pkt[36] = (presetId and 0xFF).toByte()

        return transport.send(pkt)
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        if (!setGlobalGain(transport, profile, globalGainDb)) return false
        delay(50)
        for (band in bands) {
            if (!writeEqBand(transport, profile, band, globalGainDb, presetId)) return false
            delay(50)
        }
        val refreshed = refreshEqRam(transport, profile)
        delay(50)
        return refreshed
    }

    override suspend fun refreshEqRam(transport: DacTransport, profile: DacProfile): Boolean {
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_REFRESH_EQ, 0x04, 0x00, 0x00, 0xFF, 0xFF)
        return transport.send(pkt)
    }

    override suspend fun saveToFlash(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        if (!refreshEqRam(transport, profile)) return false
        delay(120)
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_SAVE_FLASH, 0x00)
        val ok = transport.send(pkt)
        delay(80)
        return ok
    }

    override suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean {
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_RESET_EQ, 0x01, 0x04)
        return transport.send(pkt)
    }

    override suspend fun setGlobalGain(transport: DacTransport, profile: DacProfile, gainDb: Double): Boolean {
        val raw = (gainDb * 256.0).roundToInt()
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_OFFSET, 0x02)
        BytePack.u16Le(pkt, 4, raw)
        return transport.send(pkt)
    }

    override suspend fun setDacFilter(transport: DacTransport, profile: DacProfile, filterIndex: Int): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_DAC_FILTER, 0x01, filterIndex.coerceIn(1, 5)))

    override suspend fun setOutputMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_OUTPUT_MODE, 0x01, modeIndex))

    override suspend fun setWorkMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_WORK_MODE, 0x01, modeIndex))

    override suspend fun setChannelBalance(transport: DacTransport, profile: DacProfile, balance: Int): Boolean {
        val clamped = balance.coerceIn(-12, 12)
        val leftSlider = if (clamped > 0) -clamped else 0
        val rightSlider = if (clamped < 0) clamped else 0
        val leftDev = deviceValueFromSlider(leftSlider)
        val rightDev = deviceValueFromSlider(rightSlider)

        val pktL = BytePack.fixed64(REPORT_ID, 0x01, CMD_CHANNEL_BALANCE, 0x04, 0x00, 0x00)
        BytePack.u16Be(pktL, 6, leftDev)
        val pktR = BytePack.fixed64(REPORT_ID, 0x01, CMD_CHANNEL_BALANCE, 0x04, 0x01, 0x00)
        BytePack.u16Be(pktR, 6, rightDev)
        val okL = transport.send(pktL)
        delay(15)
        val okR = transport.send(pktR)
        return okL && okR
    }

    override suspend fun setMicGain(transport: DacTransport, profile: DacProfile, gainDb: Int): Boolean {
        val raw = gainDb.coerceIn(-15, 15) * 256
        val pkt = BytePack.fixed64(REPORT_ID, 0x01, CMD_ADC_OFFSET, 0x02)
        BytePack.u16Le(pkt, 4, raw)
        return transport.send(pkt)
    }

    override suspend fun setMicEnc(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_ENC, 0x01, if (enabled) 1 else 0))

    override suspend fun setSurround3d(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean =
        transport.send(BytePack.fixed64(REPORT_ID, 0x01, CMD_3D_SWITCH, 0x01, if (enabled) 1 else 0))

    private suspend fun query(
        transport: DacTransport,
        cmd: Int,
        len: Int,
        extra: ByteArray = byteArrayOf()
    ): ByteArray? {
        val req = BytePack.fixed64(REPORT_ID, 0x80, cmd, len)
        for (i in extra.indices) {
            if (4 + i < req.size) req[4 + i] = extra[i]
        }
        return transport.transceive(req, expectedSize = 64, timeoutMs = 450)
    }

    companion object {
        const val REPORT_ID = 0x4B

        private const val CMD_SAVE_FLASH = 0x01
        private const val CMD_ADC_OFFSET = 0x02
        private const val CMD_DAC_OFFSET = 0x03
        private const val CMD_RESET_EQ = 0x05
        private const val CMD_EQ_DATA = 0x09
        private const val CMD_REFRESH_EQ = 0x0A
        private const val CMD_FW_VERSION = 0x0C
        private const val CMD_DAC_FILTER = 0x11
        private const val CMD_CHANNEL_BALANCE = 0x16
        private const val CMD_OUTPUT_MODE = 0x19
        private const val CMD_ENC = 0x1B
        private const val CMD_WORK_MODE = 0x1D
        private const val CMD_3D_SWITCH = 0x22

        private val POSITIVE_BALANCE_MAP = intArrayOf(
            0, 4170, 4180, 4189, 4198, 4208, 4217, 4226, 4236, 4245, 4254, 4264, 4273
        )

        internal fun deviceValueFromSlider(sliderValue: Int): Int = when {
            sliderValue in -12..-1 -> (65536 + sliderValue * 512) and 0xFFFF
            sliderValue in 1..12 -> POSITIVE_BALANCE_MAP[sliderValue]
            else -> 0
        }

        internal fun sliderFromDevice(deviceValue: Int): Int {
            val u16 = deviceValue and 0xFFFF
            POSITIVE_BALANCE_MAP.forEachIndexed { idx, v ->
                if (idx > 0 && v == u16) return idx
            }
            for (s in -12..-1) {
                if (((65536 + s * 512) and 0xFFFF) == u16) return s
            }
            return 0
        }
    }
}
