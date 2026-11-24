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
import kotlin.math.roundToInt

/**
 * Synaptics / Conexant Freeman3 (`CX2070x`) USB-C CAF (`"CTRL"`) HID protocol driver.
 * Used by legacy Moondrop USB-C cables/earphones (`0x35D8:0x1496..0x149C`, `0x0572`).
 *
 * Uses HID Class EP0 `SET_REPORT` (`0x21/0x09 wValue=0x0201 wIndex=3`) and
 * `GET_REPORT` (`0xA1/0x01 wValue=0x0101 wIndex=3`) carrying CAF 32-bit word messages,
 * computing 24-bit `CX2070x` Q2.22 coefficients directly in pure Kotlin.
 */
class MoondropFreeman3Protocol : DacProtocol {

    override val family: ProtocolFamily = ProtocolFamily.MOONDROP_FREEMAN3
    override val displayName: String = "Moondrop Freeman3 / CX2070x (CAF)"

    override fun buildCapabilities(profile: DacProfile): DacCapabilities = DacCapabilities(
        maxBands = profile.bandCount.coerceIn(5, 9),
        minGainDb = profile.minGainDb.coerceAtLeast(-12.0),
        maxGainDb = profile.maxGainDb.coerceAtMost(12.0),
        minQ = 0.25,
        maxQ = 10.0,
        supportedFilters = listOf(
            FilterType.PEAK,
            FilterType.LOW_SHELF,
            FilterType.HIGH_SHELF,
            FilterType.LOW_PASS,
            FilterType.HIGH_PASS
        ),
        sampleRate = profile.sampleRate.takeIf { it > 0 } ?: 48_000,
        hasEqSwitch = true,
        hasGlobalGain = false,
        presets = listOf(
            NamedOption(0, "Custom EQ"),
            NamedOption(1, "Preset 1"),
            NamedOption(2, "Preset 2"),
            NamedOption(3, "Preset 3")
        ),
        supportsRamRefresh = false,
        supportsFlashSave = true
    )

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        val bandCount = profile.bandCount.coerceIn(5, 9)
        val fwResp = sendCafQuery(transport, CMD_GET_VERSION, LongArray(13))
        val firmware = if (isCafSuccess(fwResp) && fwResp!!.size >= 26) {
            listOf(10, 14, 18, 22).joinToString(".") { off -> getI32Le(fwResp, off).toString() }
        } else {
            null
        }

        val eqStateBuf = byteArrayOf(0x04, 0x00, 0x01, 0x11, 0xC8.toByte(), 0x00)
        val eqStateGot = transport.controlTransfer(0xA1, 0x01, 0x0105, HID_WINDEX, eqStateBuf, timeoutMs = 200)
        val eqEnabled = if (eqStateGot >= 2) (eqStateBuf[1].toInt() and 0x02) != 0 else true

        val presetResp = sendCafQuery(transport, CMD_GET_PARAM, longArrayOf(90L))
        val presetIdx = if (isCafSuccess(presetResp) && presetResp!!.size >= 18) {
            getI32Le(presetResp, 14).coerceIn(0, 3)
        } else {
            0
        }

        val defaults = DefaultBands.flatBands(bandCount)
        val readBands = defaults.mapIndexed { idx, defaultBand ->
            val resp = sendCafQuery(transport, CMD_GET_EQ_PARAM_LIST, longArrayOf((idx + 1).toLong()))
            if (isCafSuccess(resp) && resp!!.size >= 34) {
                val freq = getI32Le(resp, 18).coerceIn(10, 24000)
                val qRaw = getI32Le(resp, 22)
                val filterCode = getI32Le(resp, 26)
                val gainRaw = getI32Le(resp, 30)
                if (freq in 20..20000 && qRaw > 0) {
                    EqBand(
                        index = idx,
                        frequencyHz = freq,
                        gainDb = (gainRaw / 256.0).coerceIn(-12.0, 12.0),
                        q = (qRaw / 256.0).coerceIn(0.25, 10.0),
                        filterType = FilterType.fromGm55Code(filterCode)
                    )
                } else {
                    defaultBand
                }
            } else {
                defaultBand
            }
        }

        return DacDeviceState(
            firmwareVersion = firmware,
            eqEnabled = eqEnabled,
            activePresetId = presetIdx,
            globalGainDb = 0.0,
            bands = readBands
        )
    }

    override suspend fun setEqEnabled(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean {
        val hidPkt = byteArrayOf(0x04, 0x40, 0x01, 0x11, 0xC8.toByte(), if (enabled) 0x03 else 0x01)
        transport.controlTransfer(0x21, 0x09, 0x0204, HID_WINDEX, hidPkt, timeoutMs = 200)
        val cmdId = if (enabled) CMD_SET_F3_EQ_ENABLE else CMD_SET_F3_EQ_BYPASS
        val data = longArrayOf(if (enabled) 1L else 0L)
        return isCafSuccess(sendCafQuery(transport, cmdId, data))
    }

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        val words = LongArray(13).apply {
            this[0] = presetId.coerceIn(0, 3).toLong()
        }
        val ok = isCafSuccess(sendCafQuery(transport, CMD_SAVE_FLASH, words))
        if (!ok) return false
        delay(25)
        val commit = LongArray(13).apply { this[0] = 255L }
        return isCafSuccess(sendCafQuery(transport, CMD_SAVE_FLASH, commit))
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val fs = if (profile.sampleRate > 0) profile.sampleRate else 48_000
        val qCoeffs = encodeFreeman3Coeffs(band, fs)
        val hwBand = band.index + 5 // FreemanCnxtUsbDevice shiftEQBandForFreeman3(band) = band + 5
        val words = LongArray(13).apply {
            this[0] = 0L // live EQ channel
            this[1] = hwBand.toLong()
            this[2] = qCoeffs.gainShiftWord.toLong()
            this[3] = qCoeffs.b0.toLong()
            this[4] = qCoeffs.b1.toLong()
            this[5] = qCoeffs.b2.toLong()
            this[6] = qCoeffs.a0.toLong()
            this[7] = qCoeffs.a1.toLong()
        }
        return isCafSuccess(sendCafQuery(transport, CMD_LIVE_EQ_BAND, words))
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        setEqEnabled(transport, profile, enabled = true)
        delay(20)
        var allOk = true
        for (band in bands) {
            if (!writeEqBand(transport, profile, band, globalGainDb, presetId)) {
                allOk = false
            }
            delay(20)
        }
        return allOk
    }

    override suspend fun saveToFlash(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        val bands = DefaultBands.flatBands(profile.bandCount.coerceIn(5, 9))
        return saveBandsToFlash(transport, profile, bands, presetId)
    }

    suspend fun saveBandsToFlash(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        presetId: Int
    ): Boolean {
        for (band in bands) {
            val paramWords = LongArray(13).apply {
                this[0] = 0L // param block
                this[1] = (band.index + 1).toLong()
                this[2] = band.frequencyHz.coerceIn(20, 20000).toLong()
                this[3] = (band.q * 256.0).roundToInt().toLong()
                this[4] = band.filterType.gm55Code.toLong()
                this[5] = (band.gainDb * 256.0).roundToInt().toLong()
            }
            if (!isCafSuccess(sendCafQuery(transport, CMD_SAVE_FLASH, paramWords))) return false
            delay(15)
        }
        for (band in bands) {
            for (srIdx in SAMPLE_RATE_INDICES) {
                val fs = SAMPLE_RATE_TABLE[srIdx] ?: 48_000
                val qCoeffs = encodeFreeman3Coeffs(band, fs)
                val coeffWords = LongArray(13).apply {
                    this[0] = srIdx.toLong()
                    this[1] = (band.index + 1).toLong()
                    this[2] = qCoeffs.gainShiftWord.toLong()
                    this[3] = qCoeffs.b0.toLong()
                    this[4] = qCoeffs.b1.toLong()
                    this[5] = qCoeffs.b2.toLong()
                    this[6] = qCoeffs.a0.toLong()
                    this[7] = qCoeffs.a1.toLong()
                }
                sendCafQuery(transport, CMD_SAVE_FLASH, coeffWords, waitReply = (band == bands.last() && srIdx == 8))
                delay(10)
            }
        }
        val commitWords = LongArray(13).apply { this[0] = 255L }
        return isCafSuccess(sendCafQuery(transport, CMD_SAVE_FLASH, commitWords))
    }

    internal data class Freeman3Coeffs(
        val gainShiftWord: Int,
        val b0: Int,
        val b1: Int,
        val b2: Int,
        val a0: Int,
        val a1: Int
    )

    internal fun encodeFreeman3Coeffs(band: EqBand, sampleRate: Int): Freeman3Coeffs {
        val c = BiquadCalculator.compute(band, sampleRate)
        val q = BiquadCalculator.quantizeCx31993(c)
        return Freeman3Coeffs(
            gainShiftWord = 3 + q.gainShift,
            b0 = q.b0,
            b1 = q.b1,
            b2 = q.b2,
            a0 = q.negA1,
            a1 = q.negA2
        )
    }

    internal fun buildCafMessage(cmdId: Int, data: LongArray, reportId: Int = HID_REPORT_ID): ByteArray {
        val wordCount = data.size.coerceAtMost(13)
        val buf = ByteArray(10 + wordCount * 4)
        buf[0] = (reportId and 0xFF).toByte()
        buf[1] = ((reportId ushr 8) and 0xFF).toByte()
        val hdrWord = (wordCount and 0xFFFF) or ((cmdId and 0x7FFF) shl 16)
        BytePack.i32Le(buf, 2, hdrWord)
        BytePack.i32Le(buf, 6, CAF_CTRL_ID)
        for (i in 0 until wordCount) {
            BytePack.i32Le(buf, 10 + i * 4, data[i].toInt())
        }
        return buf
    }

    private suspend fun sendCafQuery(
        transport: DacTransport,
        cmdId: Int,
        data: LongArray,
        waitReply: Boolean = true
    ): ByteArray? {
        val msg = buildCafMessage(cmdId, data)
        val out = transport.controlTransfer(0x21, 0x09, HID_WVALUE_OUT, HID_WINDEX, msg, timeoutMs = 450)
        if (out < 0) return null
        if (!waitReply) {
            val ack = ByteArray(msg.size)
            ack[5] = 0x80.toByte()
            return ack
        }
        val rx = ByteArray(msg.size.coerceAtLeast(62))
        repeat(10) {
            delay(15)
            val got = transport.controlTransfer(0xA1, 0x01, HID_WVALUE_IN, HID_WINDEX, rx, timeoutMs = 250)
            if (got >= 10 && ((rx[5].toInt() and 0x80) != 0)) {
                return rx.copyOf(got)
            }
        }
        return null
    }

    private fun isCafSuccess(resp: ByteArray?): Boolean =
        resp != null && resp.size >= 10 && ((resp[5].toInt() and 0x80) != 0)

    private fun getI32Le(b: ByteArray, off: Int): Int {
        if (off + 3 >= b.size) return 0
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    companion object {
        const val HID_REPORT_ID = 1
        const val HID_WVALUE_OUT = 0x0201
        const val HID_WVALUE_IN = 0x0101
        const val HID_WINDEX = 3

        // 6-bit packed CAF FourCC "CTRL" = CommonUtil.getCafId("CTRL")
        val CAF_CTRL_ID: Int = cafId('C', 'T', 'R', 'L')

        private const val CMD_SET_F3_EQ_BYPASS = 187
        private const val CMD_SET_F3_EQ_ENABLE = 188
        private const val CMD_LIVE_EQ_BAND = 190
        private const val CMD_SAVE_FLASH = 220
        private const val CMD_GET_VERSION = 259
        private const val CMD_GET_PARAM = 346
        private const val CMD_GET_EQ_PARAM_LIST = 477

        private val SAMPLE_RATE_INDICES = intArrayOf(4, 5, 6, 7, 8)
        private val SAMPLE_RATE_TABLE = mapOf(
            4 to 44100,
            5 to 48000,
            6 to 96000,
            7 to 192000,
            8 to 384000
        )

        fun cafId(c0: Char, c1: Char, c2: Char, c3: Char): Int {
            fun enc(c: Char) = (c.code - 32) and 0x3F
            return (enc(c0) shl 8) or (enc(c1) shl 14) or (enc(c2) shl 20) or (enc(c3) shl 26)
        }
    }
}
