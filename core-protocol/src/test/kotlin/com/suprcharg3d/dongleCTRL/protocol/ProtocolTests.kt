package com.suprcharg3d.dongleCTRL.protocol

import com.suprcharg3d.dongleCTRL.dsp.CrcUtils
import com.suprcharg3d.dongleCTRL.dsp.DefaultBands
import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.FilterType
import com.suprcharg3d.dongleCTRL.protocol.drivers.Cb1100AuProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.CbProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.ComtrueEp0Protocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.Cx31993Protocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.FiioBtProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.FiioHidProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.FiioPeqCodec
import com.suprcharg3d.dongleCTRL.protocol.drivers.FiioRestmomentProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.FiioSchemaControlsHelper
import com.suprcharg3d.dongleCTRL.protocol.drivers.FiioUsbCodecs
import com.suprcharg3d.dongleCTRL.protocol.drivers.Gm55Protocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.Kt1213Protocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.KtProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.MoondropAirohaProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.MoondropBleSourceProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.MoondropComtureProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.MoondropFreeman3Protocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.MoondropGaiaV3Protocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.MoondropJieliRcspProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.MoondropSpvProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.MoondropWmicProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.NicehckOriginProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.TanchjimBesMinoProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.TanchjimBluetrumV1Protocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.TanchjimBluetrumV2Protocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.TtkInfoProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private open class FakeTransport(
    override val descriptor: UsbDeviceDescriptor = UsbDeviceDescriptor("dev1", 0x3302, 0x43E6, "TP35 Pro"),
    override val transportType: TransportType = TransportType.USB_HID,
    private val replyFactory: (ByteArray) -> ByteArray? = { ByteArray(64) },
    private val controlReplyFactory: (Int, Int, Int, Int, ByteArray) -> Int = { _, _, _, _, buf -> buf.size }
) : DacTransport {
    data class ControlCall(val requestType: Int, val request: Int, val value: Int, val index: Int, val data: ByteArray)

    val sent = mutableListOf<ByteArray>()
    val controlCalls = mutableListOf<ControlCall>()
    override val isConnected: Boolean = true
    private var nextReply: ByteArray? = null

    override suspend fun send(data: ByteArray, timeoutMs: Int): Boolean {
        sent.add(data)
        nextReply = replyFactory(data)
        return true
    }

    override suspend fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        timeoutMs: Int
    ): Int {
        val safeBuf = buffer ?: ByteArray(0)
        controlCalls.add(ControlCall(requestType, request, value, index, safeBuf.copyOf()))
        return controlReplyFactory(requestType, request, value, index, safeBuf)
    }

    override suspend fun receive(expectedSize: Int, timeoutMs: Int): ByteArray? {
        val r = nextReply
        nextReply = null
        return r
    }
}

class ProtocolTests {

    @Test
    fun `EPZ TP35 Pro writes exact 0x4B CB packets and flash save`() = runTest {
        val profile = DacProfile(
            id = "epz_tp35_pro",
            brand = "EPZ",
            model = "TP35 Pro",
            vendorId = 0x3302,
            productId = 0x43E6,
            protocol = ProtocolFamily.CB,
            schemeNo = "16",
            bandCount = 10,
            sampleRate = 96_000
        )
        val driver = CbProtocol()
        val tx = FakeTransport()

        val band = EqBand(index = 2, frequencyHz = 1000, gainDb = 3.5, q = 1.5, filterType = FilterType.PEAK)
        assertTrue(driver.writeEqBand(tx, profile, band, globalGainDb = -1.0, presetId = 1))
        assertTrue(driver.saveToFlash(tx, profile, 1))

        val eqPacket = tx.sent[0]
        assertEquals(64, eqPacket.size)
        assertEquals(0x4B, eqPacket[0].toInt() and 0xFF)
        assertEquals(0x01, eqPacket[1].toInt() and 0xFF)
        assertEquals(0x09, eqPacket[2].toInt() and 0xFF)
        assertEquals(24, eqPacket[3].toInt() and 0xFF)
        assertEquals(2, eqPacket[5].toInt() and 0xFF) // band index
        assertEquals(1000, BytePack.getU16Le(eqPacket, 28))
        assertEquals(384, BytePack.getU16Le(eqPacket, 30)) // 1.5 * 256
        assertEquals(896, BytePack.getI16Le(eqPacket, 32)) // 3.5 * 256
        assertEquals(FilterType.PEAK.cbCode, eqPacket[34].toInt() and 0xFF)

        val refreshPkt = tx.sent[1]
        assertEquals(0x0A, refreshPkt[2].toInt() and 0xFF)
        val flashPkt = tx.sent[2]
        assertEquals(0x01, flashPkt[2].toInt() and 0xFF)

        // EPZ/WalkPlay CB & CB1300D DAC filters use 1-based wire IDs 1..5 (FAST-LL .. NON OS), and KT hides fake filter
        val caps = driver.buildCapabilities(profile)
        assertTrue(caps.presets.isEmpty())
        assertEquals(listOf(1, 2, 3, 4, 5), caps.dacFilterOptions.map { it.id })
        assertTrue(caps.dacFilterOptions[0].label.startsWith("FAST-LL"))
        assertTrue(caps.dacFilterOptions[4].label.startsWith("NON OS"))
        assertTrue(driver.setDacFilter(tx, profile, 5))
        assertEquals(0x11, tx.sent.last()[2].toInt() and 0xFF)
        assertEquals(5, tx.sent.last()[4].toInt() and 0xFF)
        assertFalse(KtProtocol().buildCapabilities(profile.copy(protocol = ProtocolFamily.KT02H20)).hasDacFilter)

        val readFilterTx = FakeTransport(replyFactory = { req ->
            ByteArray(64).also { resp ->
                resp[0] = 0x4B
                resp[1] = 0x80.toByte()
                resp[2] = req[2]
                if ((req[2].toInt() and 0xFF) == 0x11) resp[4] = 5
            }
        })
        assertEquals(5, driver.readDeviceState(readFilterTx, profile).dacFilterIndex)
    }

    @Test
    fun `CB1100AU builds valid EQ1# chunked frames preserves full bands on single-band update and supports preamp and reset`() = runTest {
        val driver = Cb1100AuProtocol()
        val bands = DefaultBands.flatBands(10)
        val packets = driver.buildEqPackets(bands, globalGainDb = 0.0)
        assertTrue(packets.size in 7..9)
        assertEquals(0xCB, packets[0][0].toInt() and 0xFF)
        assertEquals(0x00, packets[0][1].toInt() and 0xFF)
        assertEquals('E'.code.toByte(), packets[0][2])
        assertEquals('Q'.code.toByte(), packets[0][3])
        assertEquals('1'.code.toByte(), packets[0][4])
        assertEquals('#'.code.toByte(), packets[0][5])

        val profile = DacProfile("cb1100", "WalkPlay", "USB DSP", 0x3302, 0x8901, protocol = ProtocolFamily.CB1100AU, bandCount = 10)
        val tx = FakeTransport(descriptor = UsbDeviceDescriptor("cb", 0x3302, 0x8901, "USB DSP"))
        assertTrue(driver.writeEqBand(tx, profile, EqBand(3, 1000, 4.0, 1.5, FilterType.PEAK), globalGainDb = -2.0, presetId = 1))
        val firstFrame = tx.sent.first()
        val totalLen = BytePack.getU16Le(firstFrame, 6)
        assertEquals(220, totalLen) // 6 + 8 + 4 + 10*20 + 2 (must retain all 10 bands)
        assertEquals(10, firstFrame[9].toInt() and 0xFF) // bandCount descriptor

        tx.sent.clear()
        assertTrue(driver.setGlobalGain(tx, profile, -3.5))
        assertTrue(tx.sent.size >= 8)

        tx.sent.clear()
        assertTrue(driver.resetEq(tx, profile))
        assertTrue(tx.sent.size >= 8)
    }

    @Test
    fun `KT02H20 writes 11-byte 0x4B register packets mic gain 0x3A and reads state cleanly`() = runTest {
        val driver = KtProtocol(isSixBand = false)
        val profile = DacProfile("kt5", "WalkPlay", "KT5", 0x3302, 0x1001, protocol = ProtocolFamily.KT02H20, schemeNo = "17", bandCount = 5)
        val tx = FakeTransport(descriptor = UsbDeviceDescriptor("kt", 0x3302, 0x1001, "KT5"))
        val band = EqBand(0, 1000, 4.0, 1.5, FilterType.PEAK)

        assertTrue(driver.writeEqBand(tx, profile, band, 0.0, 1))
        assertEquals(2, tx.sent.size)
        val p0 = tx.sent[0]
        assertEquals(11, p0.size)
        assertEquals(0x4B, p0[0].toInt() and 0xFF)
        assertEquals(38, p0[1].toInt() and 0xFF)
        assertEquals('W'.code.toByte(), p0[5])
        assertEquals(40, BytePack.getI16Le(p0, 7)) // 4.0 * 10
        assertEquals(500, BytePack.getU16Le(p0, 9)) // 1000 / 2

        assertTrue(driver.buildCapabilities(profile).hasMicGain)
        assertFalse(KtProtocol(isSixBand = true).buildCapabilities(profile.copy(protocol = ProtocolFamily.KT0231H, schemeNo = "19")).hasMicGain)

        tx.sent.clear()
        assertTrue(driver.setMicGain(tx, profile, 20))
        val micPkt = tx.sent.single()
        assertEquals(58, micPkt[1].toInt() and 0xFF) // reg 0x3A
        assertEquals(4, BytePack.getU16Le(micPkt, 7)) // 20 dB = index 4

        val readTx = FakeTransport(replyFactory = { req ->
            ByteArray(11).also { resp ->
                resp[0] = 0x4B
                when (req[1].toInt() and 0xFF) {
                    58 -> resp[7] = 5 // index 5 -> 26 dB
                    59 -> resp[7] = 11 // CHIP_DAC_DB[11] -> -3.0 dB
                    else -> resp[7] = 0
                }
            }
        })
        val st = driver.readDeviceState(readTx, profile)
        assertEquals(26, st.micGainDb)
        assertEquals(-3.0, st.globalGainDb, 0.01)
    }

    @Test
    fun `KT1213 builds valid 0x57 CRC16-IBM frame CX31993 and GM55 build valid frames and reset cleanly`() = runTest {
        val kt1213 = Kt1213Protocol()
        val ktProfile = DacProfile("kt15", "EPZ", "G10 Pro", 0x5AC1, 0x2101, protocol = ProtocolFamily.KT1213, schemeNo = "26", bandCount = 15)
        assertFalse(kt1213.buildCapabilities(ktProfile).hasGlobalGain)
        val f15 = kt1213.buildExtendedWriteFrame(moduleId = 72, offset = 0, payload = ByteArray(10))
        assertEquals(0x57, f15[0].toInt() and 0xFF)
        assertEquals(0x3F, f15[1].toInt() and 0xFF)
        assertEquals(0x5A, f15[2].toInt() and 0xFF)
        assertEquals(0xA5, f15[3].toInt() and 0xFF)
        val ktTx = FakeTransport()
        assertTrue(kt1213.resetEq(ktTx, ktProfile))
        assertEquals(16, ktTx.sent.size) // 15 bands + 1 refreshEqRam

        val cx = Cx31993Protocol()
        val cxProfile = DacProfile("cx9", "WalkPlay", "CX31993", 0x3302, 0x0001, protocol = ProtocolFamily.CX31993, bandCount = 9)
        val fCx = cx.buildBandPacket(EqBand(0, 1000, 2.0, 1.4, FilterType.PEAK))
        assertEquals(62, fCx.size)
        assertEquals(0x01, fCx[0].toInt() and 0xFF)
        val cxTx = FakeTransport()
        assertTrue(cx.resetEq(cxTx, cxProfile))
        assertEquals(9, cxTx.sent.size)

        val gm = Gm55Protocol()
        val gmProfile = DacProfile("gm55", "EPZ", "GM55", 0x1024, 0x8087, protocol = ProtocolFamily.GM55, bandCount = 10)
        val fGm = gm.buildBandPacket(EqBand(1, 250, -3.0, 1.0, FilterType.LOW_SHELF))
        assertEquals(30, fGm.size)
        assertEquals(0x02, fGm[0].toInt() and 0xFF)
        assertEquals(11, fGm[1].toInt() and 0xFF)
        val gmTx = FakeTransport()
        assertTrue(gm.resetEq(gmTx, gmProfile))
        assertEquals(10, gmTx.sent.size)

        // Verify CB capability gating hides stage/mic flags when profile omits them on non-stage scheme
        val bareCbCaps = CbProtocol().buildCapabilities(
            DacProfile("cb_bare", "Generic", "Cable", 0x3302, 0x1111, protocol = ProtocolFamily.CB, schemeNo = "13", supportsDacFilter = false, supportsMic = false)
        )
        assertFalse(bareCbCaps.hasDacFilter)
        assertFalse(bareCbCaps.hasOutputMode)
        assertFalse(bareCbCaps.hasWorkMode)
        assertFalse(bareCbCaps.hasMicGain)
        assertFalse(bareCbCaps.hasMicEnc)
    }

    @Test
    fun `FiiO HID builds valid 0xAA frame with CRC8-Maxim and 0xEE tail`() {
        val fiio = FiioHidProtocol()
        val frame = fiio.buildSetFrame(
            cmd = FiioHidProtocol.CMD_PEQ_PARAMS,
            data = byteArrayOf(0, 0, 20, 0x03, 0xE8.toByte(), 0, 100, 0)
        )
        assertEquals(0xAA, frame[0].toInt() and 0xFF)
        assertEquals(0x0A, frame[1].toInt() and 0xFF)
        assertEquals(0x15, frame[4].toInt() and 0xFF)
        assertEquals(8, frame[5].toInt() and 0xFF)
        assertEquals(CrcUtils.crc8Maxim(frame, 0, frame.size - 2), frame[frame.size - 2].toInt() and 0xFF)
        assertEquals(0xEE, frame.last().toInt() and 0xFF)

        val rm = FiioRestmomentProtocol().buildPacket(FiioRestmomentProtocol.CMD_WRITE, 5, 0, byteArrayOf(1, 2))
        assertEquals(0xFC, rm[0].toInt() and 0xFF)
        assertEquals(0xFA, rm[1].toInt() and 0xFF)
    }

    @Test
    fun `offline dac_catalog json loads all 498 entries and matches TP35 Pro plus FiiO models`() {
        val jsonFile = listOf(
            File("../app/src/main/assets/dac_catalog.json"),
            File("app/src/main/assets/dac_catalog.json")
        ).first { it.exists() }

        val catalog = DacCatalog.fromJson(jsonFile.readText())
        assertTrue("Expected >= 490 catalog devices, got ${catalog.profiles.size}", catalog.profiles.size >= 490)

        val tp35 = catalog.match(UsbDeviceDescriptor("u1", 0x3302, 0x43E6, "TP35 Pro"))
        assertNotNull(tp35)
        assertEquals("EPZ", tp35?.brand)
        assertEquals(ProtocolFamily.CB, tp35?.protocol)
        assertEquals(10, tp35?.bandCount)

        val ka17 = catalog.match(UsbDeviceDescriptor("u2", 0x2972, 0x0047, "FIIO KA17"))
        assertNotNull(ka17)
        assertEquals(ProtocolFamily.FIIO_HID, ka17?.protocol)
        assertEquals(1, ka17?.reportId)

        val ja11 = catalog.match(UsbDeviceDescriptor("u3", 0x2972, 0x0048, "JadeAudio JA11"))
        assertNotNull(ja11)
        assertEquals(5, ja11?.bandCount)
        assertEquals(2, ja11?.reportId)
    }

    @Test
    fun `fiio_schemas json provides exact universal capabilities for BTR17 USB, JA11 USB, and BT models`() = runTest {
        val schemaFile = listOf(
            File("../app/src/main/assets/fiio_schemas.json"),
            File("app/src/main/assets/fiio_schemas.json")
        ).first { it.exists() }
        val catalogFile = listOf(
            File("../app/src/main/assets/dac_catalog.json"),
            File("app/src/main/assets/dac_catalog.json")
        ).first { it.exists() }

        val schemas = FiioSchemaCatalog.fromJson(schemaFile.readText())
        assertTrue("Expected >= 70 FiiO schemas, got ${schemas.schemas.size}", schemas.schemas.size >= 70)

        val dacCatalog = DacCatalog.fromJson(catalogFile.readText(), schemas)

        // BTR17 over USB: PEQ only, 10 USER slots (160..169) + Bypass (240), no BT-only controls
        val btr17Usb = dacCatalog.match(UsbDeviceDescriptor("usb_btr17", 0x2972, 0x0052, "FIIO BTR17"))!!
        val hidDriver = FiioHidProtocol(ProtocolFamily.FIIO_HID, schemas)
        val btrUsbCaps = hidDriver.buildCapabilities(btr17Usb)
        assertEquals(10, btrUsbCaps.maxBands)
        assertTrue(btrUsbCaps.hasEqSwitch)
        assertEquals(240, btrUsbCaps.bypassPresetId)
        assertEquals((160..169).toSet(), btrUsbCaps.writablePresetIds)
        assertEquals("R&B", btrUsbCaps.presets.first { it.id == 4 }.label)
        assertEquals("User 10", btrUsbCaps.presets.first { it.id == 169 }.label)
        assertEquals("Bypass", btrUsbCaps.presets.first { it.id == 240 }.label)
        assertFalse(btrUsbCaps.hasVolume)
        assertFalse(btrUsbCaps.hasDacFilter)
        assertFalse(btrUsbCaps.hasChannelBalance)
        assertFalse(btrUsbCaps.hasLedControl)

        // JA11 over USB: 5 bands, presets 0..4, explicit flash save (cmd 25), UAC + Mic + Volume, no DAC filter
        val ja11Usb = dacCatalog.match(UsbDeviceDescriptor("usb_ja11", 0x2972, 0x0048, "JadeAudio JA11"))!!
        val ja11Caps = hidDriver.buildCapabilities(ja11Usb)
        assertEquals(5, ja11Caps.maxBands)
        assertEquals(setOf(3), ja11Caps.writablePresetIds)
        assertEquals(4, ja11Caps.bypassPresetId)
        assertTrue(ja11Caps.supportsFlashSave)
        assertTrue(ja11Caps.hasVolume)
        assertTrue(ja11Caps.hasUacSwitch)
        assertTrue(ja11Caps.hasMicSwitch)
        assertFalse(ja11Caps.hasDacFilter)
        assertFalse(ja11Caps.hasChannelBalance)

        // BTR17 over Bluetooth: GAIA V3, Volume, Balance, 8 DAC Filters, Gain Level, Battery
        val btr17Bt = dacCatalog.matchBluetoothDevice("Devin’s FiiO BTR17", "40:ED:98:1D:2C:0F")
        assertNotNull(btr17Bt)
        assertEquals(ProtocolFamily.FIIO_BT_GAIA, btr17Bt?.protocol)
        val btDriver = FiioBtProtocol(ProtocolFamily.FIIO_BT_GAIA, schemas)
        val btrBtCaps = btDriver.buildCapabilities(btr17Bt!!)
        assertTrue(btrBtCaps.hasVolume)
        assertEquals(60, btrBtCaps.maxVolume)
        assertTrue(btrBtCaps.hasChannelBalance)
        assertTrue(btrBtCaps.hasDacFilter)
        assertEquals(8, btrBtCaps.dacFilterOptions.size)
        assertEquals("Minimum Phase", btrBtCaps.dacFilterOptions.first { it.id == 0 }.label)
        assertEquals("Linear Phase Fast Roll-off", btrBtCaps.dacFilterOptions.first { it.id == 2 }.label)
        assertTrue(btrBtCaps.hasWorkMode)
        assertTrue(btrBtCaps.hasBattery)
        assertTrue(btrBtCaps.hasEqSwitch)
        assertFalse(btrBtCaps.eqToggleUsesBypass)
        assertEquals(12, btrBtCaps.channelBalanceMax)
        val extraKeys = btrBtCaps.extraControls.map { it.key }.toSet()
        assertTrue("power_save_mode" in extraKeys)
        assertTrue("charge_protection" in extraKeys)
        assertTrue("brightness" in extraKeys)
        assertTrue("bt_encode_choose_title" in extraKeys)
        assertTrue("distortion_compensation" in extraKeys)
        assertTrue("vol_control" in extraKeys)
        assertTrue("vol_limit" in extraKeys)
        assertTrue(btrBtCaps.canRenameDevice)
        assertTrue(btrBtCaps.clearPairingCmd > 0)
        assertTrue(btrBtCaps.resetDefaultCmd > 0)
        assertTrue(btrBtCaps.closeDeviceCmd > 0)

        val gaiaPkt = btDriver.buildPacket(cbt = 5, isBle = false, cmd = 196865) // 0x030101 eqSwitch get
        assertEquals(10, gaiaPkt.size)
        assertEquals(0xFF, gaiaPkt[0].toInt() and 0xFF)
        assertEquals(0x03, gaiaPkt[1].toInt() and 0xFF)
        assertEquals(2, gaiaPkt[3].toInt() and 0xFF) // payloadLen = 2 (remaining 2 command bytes + payload)
        assertEquals(0x00, gaiaPkt[4].toInt() and 0xFF)
        assertEquals(0x1D, gaiaPkt[5].toInt() and 0xFF)
        assertEquals(0xCC, gaiaPkt[6].toInt() and 0xFF)
        assertEquals(0x03, gaiaPkt[7].toInt() and 0xFF)
        assertEquals(0x01, gaiaPkt[8].toInt() and 0xFF)
        assertEquals(0x01, gaiaPkt[9].toInt() and 0xFF)

        val batRx = byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x03, 0x00, 0x1D, 0xCD.toByte(), 0x00, 0x03, 0x01, 0x64)
        val batPayload = btDriver.extractResponsePayload(batRx, isBle = false, expectedCmd16 = 0x0301, fullCmd = 769, cbt = 5)
        assertEquals(listOf(0x64), batPayload?.map { it.toInt() and 0xFF })

        // BT11 / Air Link (cbt = 5 with <= 0xFF commands) does not use 00 1D CC 24-bit SC shell
        val bt11Profile = dacCatalog.matchBluetoothDevice("FIIO BT11", "AA:BB:CC:DD:EE:01")!!
        val bt11Pkt = btDriver.buildPacket(cbt = 5, isBle = false, cmd = 0x1A, useShell24 = false)
        assertEquals(0x04, bt11Pkt[4].toInt() and 0xFF)
        assertEquals("FIIO BT11", bt11Profile.model)

        // BTR13 / K13 R2R / BR13 Bluetooth matching & packet framing
        val k13Bt = dacCatalog.matchBluetoothDevice("FIIO K13 R2R", "11:22:33:44:55:66")
        assertEquals(ProtocolFamily.FIIO_BT_UNIVERSAL, k13Bt?.protocol)
        val uniPkt = FiioBtProtocol(ProtocolFamily.FIIO_BT_UNIVERSAL, schemas).buildPacket(6, false, 196865)
        assertEquals(0xF1, uniPkt[0].toInt() and 0xFF)
        assertEquals(0x10, uniPkt[1].toInt() and 0xFF)
        assertEquals(0xFF, uniPkt.last().toInt() and 0xFF)
    }

    @Test
    fun `HidReportDescriptorParser detects wired remote vs vendor output report size`() {
        // Consumer Control (0x0C), Input 2 bytes -> wired remote only
        val remoteDesc = byteArrayOf(
            0x05, 0x0C,       // Usage Page (Consumer)
            0x09, 0x01,       // Usage (Consumer Control)
            0x75, 0x08,       // Report Size (8)
            0x95.toByte(), 0x02, // Report Count (2)
            0x81.toByte(), 0x02  // Input
        )
        val parsedRemote = HidReportDescriptorParser.parse(remoteDesc)
        assertTrue(parsedRemote.isWiredRemoteOnly)

        // Vendor 0xFF00 with Report ID 2, Output 63 bytes (64 on wire), Input 63 bytes (64 on wire)
        val vendorDesc = byteArrayOf(
            0x06, 0x00, 0xFF.toByte(), // Usage Page (0xFF00)
            0x85.toByte(), 0x02,       // Report ID (2)
            0x75, 0x08,                // Report Size (8)
            0x95.toByte(), 0x3F,       // Report Count (63)
            0x81.toByte(), 0x02,       // Input
            0x95.toByte(), 0x3F,       // Report Count (63)
            0x91.toByte(), 0x02        // Output
        )
        val parsedVendor = HidReportDescriptorParser.parse(vendorDesc)
        assertFalse(parsedVendor.isWiredRemoteOnly)
        assertTrue(parsedVendor.hasVendorUsagePage)
        assertEquals(64, parsedVendor.wireOutputSizes()[2])
    }

    @Test
    fun `FiioSchemaControlsHelper encodes and decodes bitmasks, dual sliders, and segmented UTF-8 device names`() {
        val distortionDef = FiioControlDef(
            section = "audio",
            key = "distortion_compensation",
            label = "Distortion Compensation",
            uiType = 11,
            getCmd = 262913,
            setCmd = 1311489,
            min = null,
            max = null,
            step = null
        )
        val encodedDistortion = FiioSchemaControlsHelper.encodePayload(
            distortionDef,
            DacControlValue(boolValue = true, intValue = 3, secondaryValue = 5)
        )
        assertEquals(listOf(1, 3, 5), encodedDistortion.map { it.toInt() and 0xFF })
        val decodedDistortion = FiioSchemaControlsHelper.decodeResponse(distortionDef, encodedDistortion)
        assertTrue(decodedDistortion.boolValue)
        assertEquals(3, decodedDistortion.intValue)
        assertEquals(5, decodedDistortion.secondaryValue)

        val codecDef = FiioControlDef(
            section = "state",
            key = "bt_encode_choose_title",
            label = "Bluetooth Codec Selection",
            uiType = 8,
            getCmd = 525057,
            setCmd = 1573633,
            min = null,
            max = null,
            step = null,
            bitMaskOptions = listOf(
                NamedOption(1, "SBC"),
                NamedOption(2, "AAC"),
                NamedOption(8, "aptX HD"),
                NamedOption(32, "LDAC")
            )
        )
        val encodedMask = FiioSchemaControlsHelper.encodePayload(codecDef, DacControlValue(intValue = 0x2B, boolValue = true))
        assertEquals(listOf(0x2B), encodedMask.map { it.toInt() and 0xFF })
        val decodedMask = FiioSchemaControlsHelper.decodeResponse(codecDef, encodedMask)
        assertEquals(0x2B, decodedMask.intValue)
        assertTrue(decodedMask.boolValue)

        // Multi-segment UTF-8 name encoding & decoding (ruler = 1, 10-byte chunked [0xFF, len, ...utf8, 0xFF])
        val originalName = "Devin’s FiiO BTR17"
        val chunks = FiioSchemaControlsHelper.encodeDeviceNameChunks(originalName, maxBytes = 24, ruler = 1)
        assertEquals(3, chunks.size)
        assertEquals(0xFF, chunks[0][0].toInt() and 0xFF)
        assertEquals(originalName.toByteArray(Charsets.UTF_8).size, chunks[0][1].toInt() and 0xFF)
        assertEquals(0xFF, chunks.last().last().toInt() and 0xFF)
        assertEquals(originalName, FiioSchemaControlsHelper.decodeDeviceName(chunks, ruler = 1))

        // parseRulerType == 1 stepped slider mapping (brightness 1..5 <-> 0..4, auto_screen_off 5..30 <-> 0..5)
        val brightnessDef = FiioControlDef(
            section = "state",
            key = "brightness",
            label = "Screen Brightness",
            uiType = 3,
            getCmd = 328961,
            setCmd = 1377537,
            min = 1,
            max = 5,
            step = 1,
            parseRulerType = 1
        )
        assertEquals(2, FiioSchemaControlsHelper.decodeResponse(brightnessDef, byteArrayOf(1)).intValue)
        assertEquals(listOf(0), FiioSchemaControlsHelper.encodePayload(brightnessDef, DacControlValue(intValue = 1)).map { it.toInt() and 0xFF })
        assertEquals(listOf(4), FiioSchemaControlsHelper.encodePayload(brightnessDef, DacControlValue(intValue = 5)).map { it.toInt() and 0xFF })

        val screenOffDef = FiioControlDef(
            section = "state",
            key = "auto_screen_off_time",
            label = "Auto Screen Off Time",
            uiType = 3,
            getCmd = 329217,
            setCmd = 1377793,
            min = 5,
            max = 30,
            step = 5,
            unit = "s",
            parseRulerType = 1
        )
        assertEquals(30, FiioSchemaControlsHelper.decodeResponse(screenOffDef, byteArrayOf(5)).intValue)
        assertEquals(listOf(2), FiioSchemaControlsHelper.encodePayload(screenOffDef, DacControlValue(intValue = 15)).map { it.toInt() and 0xFF })

        // Channel balance codecs (BT [0=L/1=R, mag] vs USB [right, left], plus rulers 1..6)
        assertEquals(listOf(0, 5), FiioSchemaControlsHelper.encodeChannelBalance(-5, 12, parseRulerType = 0, isUsb = false).map { it.toInt() and 0xFF })
        assertEquals(listOf(1, 5), FiioSchemaControlsHelper.encodeChannelBalance(+5, 12, parseRulerType = 0, isUsb = false).map { it.toInt() and 0xFF })
        assertEquals(listOf(1, 0), FiioSchemaControlsHelper.encodeChannelBalance(0, 12, parseRulerType = 0, isUsb = false).map { it.toInt() and 0xFF })
        assertEquals(-5, FiioSchemaControlsHelper.decodeChannelBalance(byteArrayOf(0, 5), parseRulerType = 0, isUsb = false))
        assertEquals(+5, FiioSchemaControlsHelper.decodeChannelBalance(byteArrayOf(1, 5), parseRulerType = 0, isUsb = false))
        assertEquals(+12, FiioSchemaControlsHelper.decodeChannelBalance(byteArrayOf(1, 12), parseRulerType = 0, isUsb = false))
        assertEquals(0, FiioSchemaControlsHelper.decodeChannelBalance(byteArrayOf(0, 0), parseRulerType = 0, isUsb = false))
        assertEquals(0, FiioSchemaControlsHelper.decodeChannelBalance(byteArrayOf(1, 0), parseRulerType = 0, isUsb = false))

        assertEquals(listOf(0, 5), FiioSchemaControlsHelper.encodeChannelBalance(-5, 12, parseRulerType = 0, isUsb = true).map { it.toInt() and 0xFF })
        assertEquals(listOf(5, 0), FiioSchemaControlsHelper.encodeChannelBalance(+5, 12, parseRulerType = 0, isUsb = true).map { it.toInt() and 0xFF })
        assertEquals(-5, FiioSchemaControlsHelper.decodeChannelBalance(byteArrayOf(0, 5), parseRulerType = 0, isUsb = true))
        assertEquals(+5, FiioSchemaControlsHelper.decodeChannelBalance(byteArrayOf(5, 0), parseRulerType = 0, isUsb = true))
        // KA17 USB ruler 5 negates [right, left] -> [left, right]
        assertEquals(listOf(5, 0), FiioSchemaControlsHelper.encodeChannelBalance(-5, 6, parseRulerType = 5, isUsb = true).map { it.toInt() and 0xFF })
        assertEquals(listOf(0, 5), FiioSchemaControlsHelper.encodeChannelBalance(+5, 6, parseRulerType = 5, isUsb = true).map { it.toInt() and 0xFF })

        // Nibble-packed balance (ruler 1), BTR5 (ruler 4), and BR13 signed attenuate (ruler 3)
        assertEquals(-4, FiioSchemaControlsHelper.decodeChannelBalance(FiioSchemaControlsHelper.encodeChannelBalance(-4, 12, parseRulerType = 1), parseRulerType = 1))
        assertEquals(+6, FiioSchemaControlsHelper.decodeChannelBalance(FiioSchemaControlsHelper.encodeChannelBalance(+6, 12, parseRulerType = 4), parseRulerType = 4))
        assertEquals(-3, FiioSchemaControlsHelper.decodeChannelBalance(FiioSchemaControlsHelper.encodeChannelBalance(-3, 12, parseRulerType = 3), parseRulerType = 3))
        assertEquals(+3, FiioSchemaControlsHelper.decodeChannelBalance(FiioSchemaControlsHelper.encodeChannelBalance(+3, 12, parseRulerType = 3), parseRulerType = 3))
    }

    @Test
    fun `FiioPeqCodec covers K19, BR13, SlotParametric, Graphic, QX11 UAC1, eqSwitch, eqReset, and editName parity`() {
        val file = listOf("app/src/main/assets/fiio_schemas.json", "../app/src/main/assets/fiio_schemas.json")
            .map { File(it) }.first { it.exists() }
        val schemas = FiioSchemaCatalog.fromJson(file.readText())
        val baseSchema = schemas.findByDeviceType(29)!! // FIIO K19 (parseRulerType=3)

        // K19 (ruler 3) 362-freq / 101-Q LUT & 24dB offset gain
        val k19Band = EqBand(index = 2, frequencyHz = 1000, gainDb = -4.6, q = 2.0, filterType = FilterType.PEAK)
        val k19Encoded = FiioPeqCodec.encodeSingleBand(baseSchema, k19Band, presetId = 7, batchSet = false)
        assertEquals(7, k19Encoded.size)
        val k19Decoded = FiioPeqCodec.decodeSingleBand(k19Encoded, baseSchema, expectedIndex = 2)
        assertNotNull(k19Decoded)
        assertEquals(1000, k19Decoded!!.frequencyHz)
        assertEquals(-4.6, k19Decoded.gainDb, 0.05)
        assertEquals(2.0, k19Decoded.q, 0.05)
        assertEquals(listOf(0), FiioPeqCodec.buildSavePayload(baseSchema, presetId = 7)!!.map { it.toInt() and 0xFF })

        // BR13 (ruler 11) word-swapped Q12/Q20/Q24 & i32BE masterGain
        val br13Schema = schemas.findByDeviceType(25)!!
        val br13Band = EqBand(index = 1, frequencyHz = 2500, gainDb = 3.5, q = 1.41, filterType = FilterType.PEAK)
        val br13Write = FiioPeqCodec.encodeSingleBand(br13Schema, br13Band, presetId = 160)
        assertEquals(13, br13Write.size)
        // Simulate 16-byte word-swapped RX packet [dummy4 | freq_le | gain_le | q_le]
        val fFixed = 2500 * 4096
        val gFixed = (3.5 * 1048576.0).toInt()
        val qFixed = (1.41 * 16777216.0).toInt()
        fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte())
        val br13Rx = ByteArray(4) + le32(fFixed) + le32(gFixed) + le32(qFixed)
        val br13Dec = FiioPeqCodec.decodeSingleBand(br13Rx, br13Schema, expectedIndex = 1)
        assertNotNull(br13Dec)
        assertEquals(2500, br13Dec!!.frequencyHz)
        assertEquals(3.5, br13Dec.gainDb, 0.1)
        assertEquals(1.41, br13Dec.q, 0.02)
        val br13Mg = FiioPeqCodec.encodeMasterGain(br13Schema, -2.5, 160)
        assertEquals(-2.5, FiioPeqCodec.decodeMasterGain(br13Mg, 11)!!, 0.1)

        // Standard parametric batch decoding (N*9 and 1+N*8)
        val stdSchema = schemas.findByDeviceType(35)!!.copy(bandCount = 2)
        val b0 = EqBand(0, 120, 2.0, 1.0, FilterType.LOW_SHELF)
        val b1 = EqBand(1, 8000, -3.5, 2.5, FilterType.HIGH_SHELF)
        val n9 = FiioPeqCodec.encodeSingleBand(stdSchema, b0, 160, batchSet = true) +
            FiioPeqCodec.encodeSingleBand(stdSchema, b1, 160, batchSet = true)
        val decN9 = FiioPeqCodec.decodeBatchBands(n9, stdSchema)
        assertEquals(2, decN9?.size)
        assertEquals(120, decN9!![0].frequencyHz)
        assertEquals(-3.5, decN9[1].gainDb, 0.05)

        val hdr8 = byteArrayOf(2) +
            FiioPeqCodec.encodeSingleBand(stdSchema, b0, 160, batchSet = false) +
            FiioPeqCodec.encodeSingleBand(stdSchema, b1, 160, batchSet = false)
        val decHdr8 = FiioPeqCodec.decodeBatchBands(hdr8, stdSchema)
        assertEquals(2, decHdr8?.size)
        assertEquals(8000, decHdr8!![1].frequencyHz)

        // Graphic 10-band (/60.0) & KA17 eqSwitchRuler=1
        val graphicSchema = schemas.findByDeviceType(2)!!
        val gEnc = FiioPeqCodec.encodeSingleBand(graphicSchema, EqBand(0, 31, -3.0, 0.71, FilterType.PEAK), 0)
        val gDec = FiioPeqCodec.decodeSingleBand(gEnc, graphicSchema, 0)
        assertEquals(-3.0, gDec!!.gainDb, 0.1)
        assertFalse(FiioPeqCodec.decodeEqSwitch(byteArrayOf(0x10), eqSwitchRuler = 1))
        assertTrue(FiioPeqCodec.decodeEqSwitch(byteArrayOf(0x00), eqSwitchRuler = 1))
        assertEquals(listOf(0x10), FiioPeqCodec.encodeEqSwitch(false, 1).map { it.toInt() and 0xFF })

        // editEqName Single (18B <=0xFF / 20B >0xFF / ruler 9 KA15), Batch (20B [slot, slot, ...8B]), & QX11 UAC1 preset swap
        val slot0Name = FiioPeqCodec.buildEditEqNamePayload(baseSchema, 7, "Warm")
        assertEquals(18, slot0Name?.size)
        assertEquals(0, slot0Name!![0].toInt() and 0xFF)
        assertEquals("Warm", slot0Name.copyOfRange(1, 5).decodeToString())
        val qx11 = schemas.findByDeviceType(121)!!
        val highSlotName = FiioPeqCodec.buildEditEqNamePayload(qx11, 257, "FPS")
        assertEquals(20, highSlotName?.size)
        assertEquals(0x01, highSlotName!![0].toInt() and 0xFF)
        assertEquals(0x01, highSlotName[1].toInt() and 0xFF)
        assertEquals("FPS", highSlotName.copyOfRange(2, 5).decodeToString())
        val ka15Schema = schemas.findByDeviceType(110)!!
        val ka15Name = FiioPeqCodec.buildEditEqNamePayload(ka15Schema, 8, "Harman")
        assertEquals(18, ka15Name?.size)
        assertEquals(1, ka15Name!![0].toInt() and 0xFF) // 8 - 7 = wire slot 1

        val btr17Schema = schemas.findByDeviceType(35)!!
        val btr17BatchSet = FiioPeqCodec.buildEditEqNamePayload(btr17Schema, 160, "MyEQ")
        assertEquals(20, btr17BatchSet?.size)
        assertEquals(160, btr17BatchSet!![0].toInt() and 0xFF)
        assertEquals(160, btr17BatchSet[1].toInt() and 0xFF)
        assertEquals("MyEQ", btr17BatchSet.copyOfRange(2, 6).decodeToString())

        val btr17Ranges = FiioPeqCodec.encodeQueryBatchEqNamesPayloads(btr17Schema, (160..169).toList())
        assertEquals(1, btr17Ranges.size)
        assertEquals(listOf(160, 169), btr17Ranges[0].map { it.toInt() and 0xFF })
        val batchNamesResp = ByteArray(2 + 10 * 8).also { buf ->
            buf[0] = 160.toByte()
            buf[1] = 169.toByte()
            "Warm".encodeToByteArray().copyInto(buf, 2)
            "Vocal".encodeToByteArray().copyInto(buf, 2 + 8)
        }
        val decodedBatch = FiioPeqCodec.decodeBatchEqNames(batchNamesResp, (160..169).toList(), btr17Schema.parseRulerType)
        assertEquals(mapOf(160 to "Warm", 161 to "Vocal"), decodedBatch)
        val uac1Presets = qx11.presetsForUac(isUac1 = true)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 12, 257, 258, 259, 260, 160, 161, 162, 163, 164, 165, 166, 240), uac1Presets.map { it.id })
    }

    @Test
    fun `FiioUsbCodecs and FiioBtProtocol frame C7A5, KA1, KA13, Jiedian, BatchQuery, BR13, Q5, and Universal CRC cleanly`() = runTest {
        // C7A5 KA5/KA3/KA2 framing, volume delta LUT, & brightness LUT
        val c7Frame = FiioUsbCodecs.buildC7A5Frame(0xA0)
        assertEquals(listOf(0xC7, 0xA5, 0xA0, 0, 0, 0, 0), c7Frame.map { it.toInt() and 0xFF })
        val b5Tx = FiioUsbCodecs.encodeC7A5Write(61449, 0x0B, byteArrayOf(5))
        assertEquals(0x3C, b5Tx[3].toInt() and 0xFF)
        val b5Rx = FiioUsbCodecs.extractC7A5Read(61449, 0x0B, byteArrayOf(0xA7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x3C, 0x00))
        assertEquals(5, b5Rx!![0].toInt() and 0xFF)
        val ka5Reg = FiioUsbCodecs.ka5VolLevelToReg(30, volCtrl = 1)
        assertEquals(30, FiioUsbCodecs.ka5VolRegToLevel(ka5Reg, volCtrl = 1))
        assertEquals(90, FiioUsbCodecs.ka3VolRegToLevel(FiioUsbCodecs.ka3VolLevelToReg(90)))
        assertEquals(85, FiioUsbCodecs.ka2VolRegToLevel(FiioUsbCodecs.ka2VolLevelToReg(85)))

        // KA1 REG64 bitfield extract & modify
        assertEquals(listOf(1), FiioUsbCodecs.ka1Extract(6, 0x04).map { it.toInt() and 0xFF })
        assertEquals(0x14, FiioUsbCodecs.ka1ApplyReg234Field(5, 0x04, 1))

        // KA13 XMOS report builder & decode
        val ka13Req = FiioUsbCodecs.ka13BuildReadReport(0x04, 1)!!
        assertEquals(0x0C, ka13Req[0].toInt() and 0xFF)
        assertEquals(0x10, ka13Req[4].toInt() and 0xFF)
        val ka13Vol = FiioUsbCodecs.ka13DecodeRead(0x04, byteArrayOf(42, 0, 0, 0))!!
        assertEquals(42, ka13Vol[0].toInt() and 0xFF)

        // Jiedian USB HID wrapper & Batch Multi-Query codec
        val wrapped = FiioUsbCodecs.buildJiedianUsbReport(byteArrayOf(0xF1.toByte(), 0x10, 0x00, 0x06, 0xFF.toByte()))
        assertEquals(0x07, wrapped[0].toInt() and 0xFF)
        assertEquals(0xF1, wrapped[1].toInt() and 0xFF)
        val batchReqs = FiioUsbCodecs.buildBatchQueryRequest(listOf(0x030101, 0x030201), maxPayloadBytes = 200)
        assertEquals(1, batchReqs.size)
        val fakeResp = byteArrayOf(0x02, 0x03, 0x01, 0x01, 0x00, 0x01, 0x01, 0x03, 0x02, 0x01, 0x00, 0x01, 0xA0.toByte())
        val parsedItems = FiioUsbCodecs.parseBatchQueryResponse(fakeResp)
        assertEquals(2, parsedItems.size)
        assertEquals(0xA0, parsedItems[1].payload[0].toInt() and 0xFF)

        // BT BR13 framing & RX hi-byte normalization + live applyIncomingPacket
        val file = listOf("app/src/main/assets/fiio_schemas.json", "../app/src/main/assets/fiio_schemas.json")
            .map { File(it) }.first { it.exists() }
        val schemas = FiioSchemaCatalog.fromJson(file.readText())
        val br13Proto = FiioBtProtocol(ProtocolFamily.FIIO_BT_BR13, schemas)
        val br13Tx = br13Proto.buildPacket(cbt = 61453, isBle = true, cmd = 0x0A05)
        assertEquals(listOf(0x00, 0x1D, 0x0A, 0x05), br13Tx.map { it.toInt() and 0xFF })
        val br13Ack = byteArrayOf(0x00, 0x1D, 0x0B, 0x05, 0x01, 0x02)
        assertEquals(listOf(0x01, 0x02), br13Proto.extractResponsePayload(br13Ack, isBle = true, expectedCmd16 = 0x0A05, fullCmd = 0x0A05, cbt = 61453)?.map { it.toInt() and 0xFF })

        // Universal CRC-8 verification rejects corrupted frame and accepts valid frame
        val uniProto = FiioBtProtocol(ProtocolFamily.FIIO_BT_UNIVERSAL, schemas)
        val validUni = byteArrayOf(0xF1.toByte(), 0x10, 0x00, 0x0A, 0x03, 0x01, 0x01, 0x01, 0x00, 0xFF.toByte()).also {
            it[8] = CrcUtils.crc8Maxim(it, 0, 8).toByte()
        }
        assertEquals(listOf(1), uniProto.extractResponsePayload(validUni, isBle = false, expectedCmd16 = 0x0301, fullCmd = 0x030101, cbt = 6)?.map { it.toInt() and 0xFF })
        val badUni = validUni.copyOf().also { it[8] = (it[8].toInt() xor 0xFF).toByte() }
        assertEquals(null, uniProto.extractResponsePayload(badUni, isBle = false, expectedCmd16 = 0x0301, fullCmd = 0x030101, cbt = 6))

        // Q5 frame builder + live packet decode
        val q5Profile = DacCatalog(emptyList(), schemas).matchBluetoothDevice("FIIO Q5", "AA:BB:CC:DD:EE:01")!!
        val gaiaProto = FiioBtProtocol(ProtocolFamily.FIIO_BT_GAIA, schemas)
        val q5BalRx = byteArrayOf(0x81.toByte(), 0x04, 0x01, 0x04, 0xFE.toByte())
        val updatedQ5 = gaiaProto.applyIncomingPacket(q5BalRx, q5Profile, DacDeviceState(bands = DefaultBands.flatBands(5)))
        assertEquals(-4, updatedQ5?.channelBalance)

        // BR13 aptX-LL codec list reordering via encodePayload (codec 5 <-> index 0)
        val llCtrl = FiioControlDef(section = "bt", key = "low_latency_mode", label = "Low Latency", uiType = 0, getCmd = 0x0A01, setCmd = 0x0A02, min = null, max = null, step = null, parseRulerType = 3)
        val switchReorder = FiioSchemaControlsHelper.encodePayload(llCtrl, DacControlValue(boolValue = true, rawHex = "0001020500"))
        assertEquals(5, switchReorder[0].toInt() and 0xFF)
    }

    @Test
    fun `Dual-mode usbOnlyPeq over USB HID uses standard 0x15-0x30 PEQ commands instead of BT cmd IDs`() = runTest {
        val file = listOf("app/src/main/assets/fiio_schemas.json", "../app/src/main/assets/fiio_schemas.json")
            .map { File(it) }.first { it.exists() }
        val schemas = FiioSchemaCatalog.fromJson(file.readText())
        val hidProto = FiioHidProtocol(ProtocolFamily.FIIO_HID, schemas)
        val btr17UsbProfile = DacProfile(
            id = "fiio_btr17_usb",
            brand = "FiiO",
            model = "FIIO BTR17",
            vendorId = 0x2972,
            productId = 0x0047,
            protocol = ProtocolFamily.FIIO_HID,
            reportId = 7,
            bandCount = 10,
            fiioDeviceType = 35 // BTR17 schema: eqBandGetCmd=0x30d01 (BT), usbOnlyPeq=true
        )

        val caps = hidProto.buildCapabilities(btr17UsbProfile)
        assertTrue(caps.hasEqSwitch)
        assertTrue(caps.canRenamePresets)
        assertFalse(caps.hasVolume) // BTR17 USB mode hides non-PEQ controls
        assertTrue(caps.extraControls.isEmpty())

        val tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("btr17", 0x2972, 0x0047, "FIIO BTR17")
        )
        assertTrue(hidProto.selectPreset(tx, btr17UsbProfile, 160))
        assertTrue(hidProto.setEqEnabled(tx, btr17UsbProfile, true))
        assertTrue(
            hidProto.writeEqBand(
                tx, btr17UsbProfile,
                EqBand(index = 1, frequencyHz = 1000, gainDb = 2.5, q = 1.41, filterType = FilterType.PEAK),
                globalGainDb = 0.0, presetId = 160
            )
        )
        assertTrue(hidProto.setPresetName(tx, btr17UsbProfile, 160, "Harman"))
        assertTrue(hidProto.saveToFlash(tx, btr17UsbProfile, 160))
        assertTrue(hidProto.resetEq(tx, btr17UsbProfile))

        // Verify cmd bytes in report[1..] are AA 0A seqHi seqLo CMD_BYTE ...
        assertEquals(0x16, tx.sent[0][5].toInt() and 0xFF) // selectPreset -> CMD_PEQ_PRE (0x16)
        assertEquals(0x1A, tx.sent[1][5].toInt() and 0xFF) // setEqEnabled -> CMD_PEQ_SWITCH (0x1A)
        assertEquals(0x15, tx.sent[2][5].toInt() and 0xFF) // writeEqBand -> CMD_PEQ_PARAMS (0x15)
        assertEquals(8, tx.sent[2][6].toInt() and 0xFF)    // standard 8-byte USB payload, not BT
        assertEquals(0x30, tx.sent[3][5].toInt() and 0xFF) // setPresetName -> CMD_PEQ_NAME (0x30)
        assertEquals(0x19, tx.sent[4][5].toInt() and 0xFF) // saveToFlash -> CMD_PEQ_SAVE (0x19)
        assertEquals(0x1B, tx.sent[5][5].toInt() and 0xFF) // resetEq -> CMD_RESET_PRE (0x1B)

        // Ensure non-PEQ dongles like KA13 (cbt=61448) do NOT advertise PEQ preset renaming
        val ka13Profile = DacProfile(
            id = "fiio_ka13",
            brand = "FiiO",
            model = "FiiO KA13",
            vendorId = 0x2972,
            productId = 0x0041,
            protocol = ProtocolFamily.FIIO_HID,
            fiioDeviceType = 106
        )
        assertFalse(hidProto.buildCapabilities(ka13Profile).canRenamePresets)
    }

    @Test
    fun `FiioRestmomentProtocol encodes FC FA frames checksums, decodes 31-band PEQ, and swaps PEAK vs Shelf Q`() = runTest {
        val rest = FiioRestmomentProtocol()
        val k17Profile = DacProfile(
            id = "fiio_k17",
            brand = "FiiO",
            model = "FIIO K17",
            vendorId = 0x2972,
            productId = 0x0099,
            protocol = ProtocolFamily.FIIO_RESTMOMENT,
            transport = TransportType.USB_SERIAL,
            bandCount = 31
        )

        // Handshake [17, 0] checksum matches ((0xFC + 0xA7 + 0 + 48 + 17 + 0) & 0xFF) xor 0xFA
        val handshake = rest.buildPacket(FiioRestmomentProtocol.CMD_WRITE, 0, 48, byteArrayOf(17, 0))
        val expectedChk = ((0xFC + 0xA7 + 0 + 48 + 17) and 0xFF) xor 0xFA
        assertEquals(0xFC, handshake[0].toInt() and 0xFF)
        assertEquals(0xFA, handshake[1].toInt() and 0xFF)
        assertEquals(6, BytePack.getU16Le(handshake, 2)) // payload(2) + 4
        assertEquals(expectedChk, handshake.last().toInt() and 0xFF)

        // Single band write: Low Shelf places Q index into sIdx (offset 10) and leaves qIdx = 16 (1.0)
        val shelfBand = EqBand(index = 3, frequencyHz = 105, gainDb = -4.0, q = 0.71, filterType = FilterType.LOW_SHELF)
        val writePkt = rest.buildWriteBandPacket(shelfBand)
        assertEquals(16, BytePack.getU16Le(writePkt, 15)) // qIdx at payload off 8 -> frame off 7+8=15
        assertEquals(10, BytePack.getU16Le(writePkt, 17)) // K19_Q_TABLE[10] == 0.71 at sIdx -> frame off 17

        // Build synthetic 31-band read response (310 band bytes + 4 prefix bytes + 4 hdr + 4 tail/body = 322 bytes, len = 318)
        val bandBody = ByteArray(11 + 31 * 10 + 1)
        bandBody[0] = 0xFC.toByte()
        bandBody[1] = 0xFA.toByte()
        BytePack.u16Le(bandBody, 2, 318)
        bandBody[4] = FiioRestmomentProtocol.CMD_READ.toByte()
        bandBody[5] = FiioRestmomentProtocol.EQ_INDEX.toByte()
        bandBody[6] = FiioRestmomentProtocol.ADDR_UPLOAD.toByte()
        // Band 0: PEAK, idx=204 (1000 Hz), gIdx=130 (+2.0 dB), qIdx=28 (2.0), sIdx=16 (1.0)
        BytePack.u16Le(bandBody, 11, 0)
        BytePack.u16Le(bandBody, 13, 204)
        BytePack.u16Le(bandBody, 15, 130)
        BytePack.u16Le(bandBody, 17, 28)
        BytePack.u16Le(bandBody, 19, 16)
        // Band 1: LOW_SHELF, idx=87 (105 Hz), gIdx=100 (-4.0 dB), qIdx=16, sIdx=10 (0.71)
        BytePack.u16Le(bandBody, 21, 1)
        BytePack.u16Le(bandBody, 23, 87)
        BytePack.u16Le(bandBody, 25, 100)
        BytePack.u16Le(bandBody, 27, 16)
        BytePack.u16Le(bandBody, 29, 10)

        val parsedBands = rest.parseEqBandsResponse(bandBody, maxBands = 31)
        assertNotNull(parsedBands)
        assertEquals(31, parsedBands!!.size)
        assertEquals(1000, parsedBands[0].frequencyHz)
        assertEquals(2.0, parsedBands[0].gainDb, 0.01)
        assertEquals(2.0, parsedBands[0].q, 0.01)
        assertEquals(FilterType.PEAK, parsedBands[0].filterType)
        assertEquals(105, parsedBands[1].frequencyHz)
        assertEquals(-4.0, parsedBands[1].gainDb, 0.01)
        assertEquals(0.71, parsedBands[1].q, 0.01)
        assertEquals(FilterType.LOW_SHELF, parsedBands[1].filterType)

        // End-to-end FakeTransport state read + preset + reset + save
        val tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("k17", 0x2972, 0x0099, "FIIO K17", hasCdcInterface = true),
            transportType = TransportType.USB_SERIAL,
            replyFactory = { req ->
                val modIdx = req[5].toInt() and 0xFF
                val addr = req[6].toInt() and 0xFF
                val cmdType = req[4].toInt() and 0xFF
                when {
                    cmdType == FiioRestmomentProtocol.CMD_WRITE -> byteArrayOf(0xBB.toByte())
                    modIdx == 0 && addr == FiioRestmomentProtocol.ADDR_MODE_LOAD ->
                        rest.buildPacket(FiioRestmomentProtocol.CMD_READ, 0, FiioRestmomentProtocol.ADDR_MODE_LOAD, byteArrayOf(162.toByte(), 0))
                    modIdx == 0 && addr == FiioRestmomentProtocol.ADDR_UPLOAD -> bandBody
                    else -> byteArrayOf(0xBB.toByte())
                }
            }
        )
        val state = rest.readDeviceState(tx, k17Profile)
        assertTrue(state.eqEnabled)
        assertEquals(162, state.activePresetId)
        assertEquals(31, state.bands.size)
        assertTrue(rest.resetEq(tx, k17Profile))
        assertTrue(rest.saveToFlash(tx, k17Profile, 162))
        assertEquals(FiioRestmomentProtocol.ADDR_DOWNLOAD, tx.sent.last { it[4].toInt() and 0xFF == 0xA7 && it[6].toInt() and 0xFF == 15 }[6].toInt() and 0xFF)
    }

    @Test
    fun `GigaWit An framing, dynamic USB interface index routing, and QX11 activePresetsForUac work accurately`() = runTest {
        val file = listOf("app/src/main/assets/fiio_schemas.json", "../app/src/main/assets/fiio_schemas.json")
            .map { File(it) }.first { it.exists() }
        val schemas = FiioSchemaCatalog.fromJson(file.readText())

        // 1. GigaWit An(featureId=24, cmd=83, [2]) -> [FF 03 00 01 00 1D 30 53 02] wrapped in 0x07 report
        val gigaPkt = FiioUsbCodecs.gigaWitBuildPacket(featureId = 24, cmd = 0x53, payload = byteArrayOf(2))
        assertEquals(listOf(0xFF, 0x03, 0x00, 0x01, 0x00, 0x1D, 0x30, 0x53, 0x02), gigaPkt.map { it.toInt() and 0xFF })
        val extracted = FiioUsbCodecs.gigaWitExtractPayload(
            FiioUsbCodecs.buildJiedianUsbReport(FiioUsbCodecs.gigaWitBuildPacket(24, 0x00, "BT11-Lab".encodeToByteArray())),
            expectedFeatureId = 24,
            expectedCmd = 0x00
        )
        assertEquals("BT11-Lab", extracted?.decodeToString())

        val gigaProto = FiioHidProtocol(ProtocolFamily.FIIO_GIGAWIT, schemas)
        val bt11Profile = DacProfile(id = "bt11", brand = "FiiO", model = "FIIO BT11", vendorId = 0x2972, productId = 0x0050, protocol = ProtocolFamily.FIIO_GIGAWIT, fiioDeviceType = 30)
        val gigaTx = FakeTransport(replyFactory = { req ->
            val cmdByte = req[8].toInt() and 0xFF
            when (cmdByte) {
                0x05 -> FiioUsbCodecs.buildJiedianUsbReport(FiioUsbCodecs.gigaWitBuildPacket(0, 0x05, "1.0.9".encodeToByteArray()))
                0x00 -> FiioUsbCodecs.buildJiedianUsbReport(FiioUsbCodecs.gigaWitBuildPacket(24, 0x00, "MyBT11".encodeToByteArray()))
                0x52 -> FiioUsbCodecs.buildJiedianUsbReport(FiioUsbCodecs.gigaWitBuildPacket(24, 0x52, byteArrayOf(2)))
                else -> FiioUsbCodecs.buildJiedianUsbReport(FiioUsbCodecs.gigaWitBuildPacket(24, cmdByte, byteArrayOf()))
            }
        })
        val gigaState = gigaProto.readDeviceState(gigaTx, bt11Profile)
        assertEquals("1.0.9", gigaState.firmwareVersion)
        assertEquals("MyBT11", gigaState.deviceName)
        assertEquals(2, gigaState.ledMode)
        assertTrue(gigaProto.setDeviceName(gigaTx, bt11Profile, "Studio"))

        // 2. Dynamic acInterfaceIndex / hidInterfaceIndex routing + KA13 readUacLeft best-effort
        val hidProto = FiioHidProtocol(ProtocolFamily.FIIO_HID, schemas)
        val ka5Tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("ka5", 0x2972, 0x0033, "FiiO KA5", acInterfaceIndex = 2, hidInterfaceIndex = 3),
            controlReplyFactory = { rt, _, _, _, buf ->
                if (rt == 0xC3) {
                    buf[0] = 0xA7.toByte()
                    7
                } else buf.size
            }
        )
        val ka5Profile = DacProfile(id = "ka5", brand = "FiiO", model = "FiiO KA5", vendorId = 0x2972, productId = 0x0033, protocol = ProtocolFamily.FIIO_HID, fiioDeviceType = 105)
        assertTrue(hidProto.setLedMode(ka5Tx, ka5Profile, 1))
        assertEquals(2, ka5Tx.controlCalls.first().index)

        val ka13Tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("ka13", 0x2972, 0x0041, "FiiO KA13", acInterfaceIndex = 1, hidInterfaceIndex = 4)
        )
        val ka13Profile = DacProfile(id = "ka13", brand = "FiiO", model = "FiiO KA13", vendorId = 0x2972, productId = 0x0041, protocol = ProtocolFamily.FIIO_HID, fiioDeviceType = 106)
        assertTrue(hidProto.setVolume(ka13Tx, ka13Profile, 45))
        assertEquals(4, ka13Tx.controlCalls.first().index)

        // 3. QX11 activePresetsForUac switches preset list between UAC 1.0 (0) and UAC 2.0 (1) + categories
        val qx11Profile = DacProfile(id = "qx11", brand = "FiiO", model = "FIIO QX11", vendorId = 0x2972, productId = 0x0077, protocol = ProtocolFamily.FIIO_HID, fiioDeviceType = 121)
        val qx11Caps = hidProto.buildCapabilities(qx11Profile)
        assertTrue(qx11Caps.canRenamePresets)
        assertEquals("game", qx11Caps.presetCategories[257])
        assertEquals("movie", qx11Caps.presetCategories[12])
        assertTrue(qx11Caps.activePresetsForUac(0).any { it.id == 257 })
        assertFalse(qx11Caps.activePresetsForUac(1).any { it.id == 257 })

        // 4. BTR17 BT refreshPresetEqState queries ONLY EQ switch/gain/bands and GAIA V3 error NACK fails fast without retry loop
        val btr17BtProfile = DacCatalog(emptyList(), schemas).matchBluetoothDevice("FIIO BTR17", "11:22:33:44:55:66")!!
        val btProto = FiioBtProtocol(ProtocolFamily.FIIO_BT_GAIA, schemas)
        val btr17Tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("btr17_bt", 0, 0, "FIIO BTR17"),
            transportType = TransportType.BLUETOOTH_SPP,
            replyFactory = { req ->
                val b2 = req[7].toInt() and 0xFF
                val b1 = req[8].toInt() and 0xFF
                val b0 = req[9].toInt() and 0xFF
                val cmd24 = (b2 shl 16) or (b1 shl 8) or b0
                when (cmd24) {
                    0x030C01 -> byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x01, 0x00, 0x1D, 0xCD.toByte(), 0x83.toByte(), 0x0C) // immediate error NACK
                    0x030B01 -> byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x03, 0x00, 0x1D, 0xCD.toByte(), 0x03, 0x0B, 0x01, 0x01)
                    0x030F01 -> byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x04, 0x00, 0x1D, 0xCD.toByte(), 0x03, 0x0F, 0x01, 0x00, 0x00)
                    0x030D01 -> {
                        val bandIdx = req.getOrElse(10) { 0 }
                        byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x0A, 0x00, 0x1D, 0xCD.toByte(), 0x03, 0x0D, 0x01, bandIdx, 0x00, 0x1E, 0x03, 0xE8.toByte(), 0x00, 0x8D.toByte(), 0x00)
                    }
                    else -> byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x03, 0x00, 0x1D, 0xCD.toByte(), b2.toByte(), b1.toByte(), b0.toByte(), 0x00)
                }
            }
        )
        val fastEq = btProto.refreshPresetEqState(btr17Tx, btr17BtProfile, DacDeviceState(bands = DefaultBands.flatBands(10)), 161)!!
        assertEquals(161, fastEq.activePresetId)
        assertEquals(3.0, fastEq.bands[0].gainDb, 0.05)
        // Only EQ queries (eqSwitch + masterGain + bands <= 13 TX, no extra controls or preset-name loops)
        assertTrue(btr17Tx.sent.size <= 13)

        // Ensure full readDeviceState queries batch 030C01 once with [160, 169] payload & still fails fast on CD 83 0C NACK
        btr17Tx.sent.clear()
        btProto.readDeviceState(btr17Tx, btr17BtProfile)
        val eqNameReqs = btr17Tx.sent.filter { req ->
            req.size >= 10 &&
                (req[7].toInt() and 0xFF) == 0x03 &&
                (req[8].toInt() and 0xFF) == 0x0C &&
                (req[9].toInt() and 0xFF) == 0x01
        }
        assertEquals(1, eqNameReqs.size)
        assertEquals(listOf(160, 169), eqNameReqs[0].copyOfRange(10, 12).map { it.toInt() and 0xFF })

        // Verify valid BTR17 streamed 10-frame batch EQ name read ([A0,A0],...,[A9,A9]) + 20-byte batch rename + BT balance
        val streamingTx = object : FakeTransport(
            descriptor = UsbDeviceDescriptor("btr17_bt", 0, 0, "FIIO BTR17"),
            transportType = TransportType.BLUETOOTH_SPP,
            replyFactory = { req ->
                val b2 = req[7].toInt() and 0xFF
                val b1 = req[8].toInt() and 0xFF
                val b0 = req[9].toInt() and 0xFF
                when ((b2 shl 16) or (b1 shl 8) or b0) {
                    0x030C01 -> null
                    0x020601 -> byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x04, 0x00, 0x1D, 0xCD.toByte(), 0x02, 0x06, 0x01, 0x01, 12)
                    else -> byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x03, 0x00, 0x1D, 0xCD.toByte(), b2.toByte(), b1.toByte(), b0.toByte(), 0x00)
                }
            }
        ) {
            val extraRx = ArrayDeque<ByteArray>()
            override suspend fun send(data: ByteArray, timeoutMs: Int): Boolean {
                val ok = super.send(data, timeoutMs)
                if (data.size >= 12 && (data[7].toInt() and 0xFF) == 0x03 && (data[8].toInt() and 0xFF) == 0x0C && (data[9].toInt() and 0xFF) == 0x01) {
                    // Replace single dummy response with 10 real BTR17-style streamed frames
                    extraRx.clear()
                    val s = data[10].toInt() and 0xFF
                    val e = data[11].toInt() and 0xFF
                    for (slot in s..e) {
                        val f = ByteArray(20)
                        f[0] = 0xFF.toByte(); f[1] = 0x03; f[3] = 12; f[4] = 0x00; f[5] = 0x1D
                        f[6] = 0xCD.toByte(); f[7] = 0x03; f[8] = 0x0C; f[9] = 0x01
                        f[10] = slot.toByte(); f[11] = slot.toByte()
                        val label = when (slot) {
                            160 -> "custom"
                            161 -> "FT1Pro"
                            162 -> "ATHM50"
                            else -> "User ${slot - 159}"
                        }
                        label.encodeToByteArray().copyInto(f, 12)
                        extraRx += f
                    }
                }
                return ok
            }
            override suspend fun receive(expectedSize: Int, timeoutMs: Int): ByteArray? {
                if (extraRx.isNotEmpty()) return extraRx.removeFirst()
                return super.receive(expectedSize, timeoutMs)
            }
        }
        val okState = btProto.readDeviceState(streamingTx, btr17BtProfile)
        assertEquals("custom", okState.customPresetNames[160])
        assertEquals("FT1Pro", okState.customPresetNames[161])
        assertEquals("ATHM50", okState.customPresetNames[162])
        assertEquals("User 10", okState.customPresetNames[169])
        assertEquals(12, okState.channelBalance)
        assertTrue(btProto.setPresetName(streamingTx, btr17BtProfile, 161, "Vocal"))
        val renamePkt = streamingTx.sent.last()
        assertEquals(30, renamePkt.size)
        assertEquals(listOf(0x13, 0x0C, 0x01, 161, 161), renamePkt.copyOfRange(7, 12).map { it.toInt() and 0xFF })
        assertEquals("Vocal", renamePkt.copyOfRange(12, 17).decodeToString())
        assertTrue(btProto.setChannelBalance(streamingTx, btr17BtProfile, 12))
        val balPkt = streamingTx.sent.last()
        assertEquals(listOf(0x12, 0x06, 0x01, 1, 12), balPkt.copyOfRange(7, 12).map { it.toInt() and 0xFF })

        // Verify BTR17 120-step mode keeps dynamicMax = 120 across GET/CE frames and ignores SET ACKs
        val step120Tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("btr17_bt", 0, 0, "FIIO BTR17"),
            transportType = TransportType.BLUETOOTH_SPP,
            replyFactory = { req ->
                val b2 = req[7].toInt() and 0xFF
                val b1 = req[8].toInt() and 0xFF
                val b0 = req[9].toInt() and 0xFF
                val v = when ((b2 shl 16) or (b1 shl 8) or b0) {
                    0x020B01 -> 1   // vol_control = 120 Steps
                    0x020401 -> 120 // vol_limit = 120
                    0x020201 -> 95  // vol_call = 95
                    0x020301 -> 88  // vol_tip = 88
                    0x020101 -> 74  // device_volume = 74
                    else -> 0
                }
                byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x03, 0x00, 0x1D, 0xCD.toByte(), b2.toByte(), b1.toByte(), b0.toByte(), v.toByte())
            }
        )
        val state120 = btProto.readDeviceState(step120Tx, btr17BtProfile)
        assertEquals(120, state120.maxVolume)
        assertEquals(120, state120.extraControlValues["vol_limit"]?.intValue)
        assertEquals(120, state120.extraControlValues["vol_limit"]?.dynamicMax)
        assertEquals(95, state120.extraControlValues["vol_call"]?.intValue)
        assertEquals(120, state120.extraControlValues["vol_call"]?.dynamicMax)
        assertEquals(88, state120.extraControlValues["vol_tip"]?.intValue)
        assertEquals(120, state120.extraControlValues["vol_tip"]?.dynamicMax)

        // Incoming GET/notify packet must NOT wipe dynamicMax to null (which caused 60<->120 oscillation)
        val volLimitGetFrame = byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x03, 0x00, 0x1D, 0xCD.toByte(), 0x02, 0x04, 0x01, 120.toByte())
        val afterGetIncoming = btProto.applyIncomingPacket(volLimitGetFrame, btr17BtProfile, state120)!!
        assertEquals(120, afterGetIncoming.extraControlValues["vol_limit"]?.dynamicMax)
        assertEquals(120, afterGetIncoming.extraControlValues["vol_call"]?.dynamicMax)
        assertEquals(120, afterGetIncoming.extraControlValues["vol_tip"]?.dynamicMax)

        // Incoming GAIA V3 SET status ACK ([0x00] on 0x120B01 / 0x120401) must be ignored
        val setStepAck = byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x03, 0x00, 0x1D, 0xCD.toByte(), 0x12, 0x0B, 0x01, 0x00)
        assertEquals(null, btProto.applyIncomingPacket(setStepAck, btr17BtProfile, state120))
        val setLimitAck = byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x03, 0x00, 0x1D, 0xCD.toByte(), 0x12, 0x04, 0x01, 0x00)
        assertEquals(null, btProto.applyIncomingPacket(setLimitAck, btr17BtProfile, state120))

        // Writing vol_limit / vol_call above 60 in 120-step mode sends exact 110 (not clamped to 60)
        step120Tx.sent.clear()
        assertTrue(btProto.setExtraControl(step120Tx, btr17BtProfile, "vol_limit", DacControlValue(intValue = 110, dynamicMax = 120)))
        assertEquals(110, step120Tx.sent.last().last().toInt() and 0xFF)

        // Check inverted KA5 vol_control option labels ("120 Steps" = 0, "60 Steps" = 1)
        val ka5Schema = schemas.findByDeviceType(105)!!
        assertEquals(120, FiioSchemaControlsHelper.resolveStepMax(ka5Schema, 0, 60))
        assertEquals(60, FiioSchemaControlsHelper.resolveStepMax(ka5Schema, 1, 60))
    }

    @Test
    fun `CbProtocol maps 0x19 to Gain Level and 0x1D to Amp Work Mode`() = runTest {
        val cbProto = CbProtocol(isSurround3dFamily = false)
        val profile = DacProfile(
            id = "epz_3302_43e6_tp35_pro",
            brand = "EPZ",
            model = "TP35 Pro",
            vendorId = 0x3302,
            productId = 0x43E6,
            protocol = ProtocolFamily.CB,
            schemeNo = "16",
            bandCount = 10,
            supportsMic = true,
            supportsDacFilter = true
        )

        val caps = cbProto.buildCapabilities(profile)
        assertEquals(listOf("Class AB", "Class H"), caps.outputModeOptions.map { it.label })
        assertEquals(listOf("Low Gain", "High Gain"), caps.workModeOptions.map { it.label })

        val tx = object : FakeTransport() {
            override suspend fun transceive(request: ByteArray, expectedSize: Int, timeoutMs: Int): ByteArray {
                sent.add(request.copyOf())
                val cmd = request[2].toInt() and 0xFF
                val resp = ByteArray(64)
                resp[0] = 0x4B
                resp[1] = 0x80.toByte()
                resp[2] = cmd.toByte()
                when (cmd) {
                    0x19 -> resp[4] = 1 // Gain = High Gain -> workModeIndex = 1
                    0x1D -> resp[4] = 0 // Amp Work Mode = Class AB -> outputModeIndex = 0
                }
                return resp
            }
        }

        val state = cbProto.readDeviceState(tx, profile)
        assertEquals(1, state.workModeIndex)
        assertEquals(0, state.outputModeIndex)

        tx.sent.clear()
        assertTrue(cbProto.setWorkMode(tx, profile, 1))   // Gain Level = High Gain -> 0x19
        assertTrue(cbProto.setOutputMode(tx, profile, 1)) // Amp Work Mode = Class H -> 0x1D

        assertEquals(0x19, tx.sent[0][2].toInt() and 0xFF)
        assertEquals(0x01, tx.sent[0][4].toInt() and 0xFF)
        assertEquals(0x1D, tx.sent[1][2].toInt() and 0xFF)
        assertEquals(0x01, tx.sent[1][4].toInt() and 0xFF)
    }

    @Test
    fun `CB safe probe dynamically unlocks hardware volume DRE LED swap and Q8_8 LE balance`() = runTest {
        val cbProto = CbProtocol()
        val profile = DacProfile(
            id = "tp35_probe",
            brand = "EPZ",
            model = "TP35 Pro",
            vendorId = 0x3302,
            productId = 0x43E6,
            protocol = ProtocolFamily.CB,
            schemeNo = "16",
            bandCount = 10,
            supportsMic = true,
            supportsDacFilter = true,
            balanceEncoding = BalanceEncoding.Q8_8_LE_AT_5
        )
        val tx = object : FakeTransport() {
            override suspend fun transceive(request: ByteArray, expectedSize: Int, timeoutMs: Int): ByteArray {
                sent.add(request.copyOf())
                val cmd = request[2].toInt() and 0xFF
                val resp = ByteArray(64)
                resp[0] = 0x4B
                resp[1] = 0x80.toByte()
                resp[2] = cmd.toByte()
                when (cmd) {
                    0x0E -> { resp[3] = 4; resp[4] = 0x49; resp[5] = 0x00; resp[6] = 0x40; resp[7] = 0x80.toByte() }
                    0x85 -> { resp[3] = 1; resp[4] = 42 }
                    0x32 -> { resp[3] = 1; resp[4] = 0 } // DRE ON (inverted)
                    0x18 -> { resp[3] = 1; resp[4] = 1 } // LED On
                    0x30 -> { resp[3] = 1; resp[4] = 1 } // Swap On
                    0x23 -> { resp[3] = 2; BytePack.u16Le(resp, 4, -3 * 256) }
                    0x16 -> {
                        resp[3] = 4
                        val isRight = (request[4].toInt() and 0xFF) == 1
                        BytePack.u16Le(resp, 5, if (isRight) -4 * 256 else 0)
                    }
                }
                return resp
            }
        }

        val state = cbProto.readDeviceState(tx, profile)
        val caps = cbProto.resolveCapabilities(profile, state)
        assertTrue(caps.hasVolume)
        assertEquals(42, state.volume)
        assertTrue(caps.hasLedControl)
        assertEquals(1, state.ledMode)
        assertEquals(-4, state.channelBalance)
        assertEquals("Chip 49004080", state.diagnosticNote)
        assertEquals(true, state.extraControlValues["cb_dre"]?.boolValue)
        assertEquals(true, state.extraControlValues["cb_channel_swap"]?.boolValue)
        assertEquals(-3, state.extraControlValues["cb_pre_gain"]?.intValue)
        assertTrue(caps.extraControls.any { it.key == "cb_dre" })
        assertTrue(caps.extraControls.any { it.key == "cb_channel_swap" })
        assertTrue(caps.extraControls.any { it.key == "cb_pre_gain" })

        tx.sent.clear()
        assertTrue(cbProto.setVolume(tx, profile, 55))
        assertEquals(0x85, tx.sent.last()[2].toInt() and 0xFF)
        assertEquals(55, tx.sent.last()[4].toInt() and 0xFF)

        assertTrue(cbProto.setExtraControl(tx, profile, "cb_dre", DacControlValue(boolValue = false)))
        assertEquals(0x32, tx.sent.last()[2].toInt() and 0xFF)
        assertEquals(0x01, tx.sent.last()[4].toInt() and 0xFF) // OFF = 0x01

        assertTrue(cbProto.setChannelBalance(tx, profile, 5))
        val pktL = tx.sent[tx.sent.size - 2]
        assertEquals(0x16, pktL[2].toInt() and 0xFF)
        assertEquals(-5 * 256, BytePack.getI16Le(pktL, 5))

        // Headset guard disables extended stage probes
        val headsetCaps = cbProto.resolveCapabilities(
            profile.copy(isHeadset = true),
            cbProto.readDeviceState(tx, profile.copy(isHeadset = true))
        )
        assertFalse(headsetCaps.hasVolume)
        assertFalse(headsetCaps.extraControls.any { it.key == "cb_dre" })
    }

    @Test
    fun `KT02H20 supports 1x frequency mode nonBlockingWrite and catalog parses BT profiles`() = runTest {
        val kt = KtProtocol(isSixBand = false)
        val profile1x = DacProfile(
            id = "tanchjim_bunny_dsp",
            brand = "TANCHJIM",
            model = "TANCHJIM BUNNY DSP",
            vendorId = 0x3302,
            productId = 0x1001,
            protocol = ProtocolFamily.KT02H20,
            bandCount = 5,
            compensate2X = false,
            nonBlockingWrite = true
        )
        val tx = object : FakeTransport() {
            override suspend fun transceive(request: ByteArray, expectedSize: Int, timeoutMs: Int): ByteArray? {
                throw AssertionError("nonBlockingWrite must use send() instead of blocking transceive()")
            }
        }
        assertTrue(kt.writeEqBand(tx, profile1x, EqBand(0, 1000, 2.0, 1.0, FilterType.PEAK), 0.0, 0))
        assertEquals(1000, BytePack.getU16Le(tx.sent[0], 9)) // 1x Hz, NOT 500

        val btJson = """
            {
              "devices": [
                {
                  "id": "nicehck_origin_bt",
                  "brand": "NICEHCK",
                  "model": "NICEHCK OriG in",
                  "productNameMatch": "OriG in",
                  "vendorId": 0,
                  "protocol": "NICEHCK_ORIGIN_BT",
                  "transport": "BLUETOOTH_GATT",
                  "bandCount": 10,
                  "isHeadset": true
                }
              ]
            }
        """.trimIndent()
        val cat = DacCatalog.fromJson(btJson)
        val matched = cat.matchBluetoothDevice("NICEHCK OriG in", "AA:BB:CC:DD:EE:FF")
        assertNotNull(matched)
        assertEquals(ProtocolFamily.NICEHCK_ORIGIN_BT, matched?.protocol)
        assertEquals(TransportType.BLUETOOTH_GATT, matched?.transport)
        assertTrue(matched?.isHeadset == true)
    }

    @Test
    fun `ComtrueEp0Protocol reads sample rate fw-gated DRE and writes tail_volume steps`() = runTest {
        val driver = ComtrueEp0Protocol()
        val profileLuna = DacProfile(
            id = "tanchjim_luna",
            brand = "TANCHJIM",
            model = "TANCHJIM-LUNA",
            vendorId = 0x0666,
            protocol = ProtocolFamily.COMTRUE_EP0,
            bandCount = 0,
            sampleRate = 384_000,
            reportId = 0
        )

        var fwNum = 8 // LUNA >= 7 enables DRE
        var sampleRateCode = 0x15 // PCM 192 kHz
        var lastCmd = 0
        val tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("luna", 0x0666, 0x0001, "TANCHJIM-LUNA"),
            controlReplyFactory = { reqType, _, _, index, buf ->
                assertEquals(ComtrueEp0Protocol.EP0_INDEX, index)
                if (reqType == ComtrueEp0Protocol.EP0_REQ_TYPE_OUT) {
                    lastCmd = buf[2].toInt() and 0xFF
                    buf.size
                } else {
                    buf[0] = 0xA1.toByte()
                    buf[1] = 0xA5.toByte()
                    buf[2] = lastCmd.toByte()
                    when (lastCmd) {
                        0xA0 -> buf[3] = fwNum.toByte()
                        0xA1 -> buf[3] = sampleRateCode.toByte()
                        0xA2 -> {
                            buf[4] = ComtrueEp0Protocol.VOLUME_BYTES[30].toByte()
                            buf[5] = 0x00
                            buf[6] = 0x04 // Left atten 4 steps -> +2 dB slider
                        }
                        0xA3 -> {
                            buf[3] = 0x02 // Filter 2 (SLOW-LL)
                            buf[4] = 0x01 // High gain
                            buf[5] = 0x02 // LED Red On
                        }
                        0xA4 -> {
                            buf[3] = 0x01 // Class H
                            buf[4] = 0x00 // DRE On (0x00)
                        }
                    }
                    7
                }
            }
        )

        val stateV8 = driver.readDeviceState(tx, profileLuna)
        val capsV8 = driver.resolveCapabilities(profileLuna, stateV8)
        assertEquals(0, capsV8.maxBands)
        assertEquals("V8", stateV8.firmwareVersion)
        assertEquals("Sample Rate: PCM 192 kHz", stateV8.diagnosticNote)
        assertEquals(30, stateV8.volume)
        assertEquals(2, stateV8.channelBalance)
        assertEquals(2, stateV8.dacFilterIndex)
        assertEquals(1, stateV8.workModeIndex)
        assertEquals(2, stateV8.ledMode)
        assertEquals(1, stateV8.outputModeIndex)
        assertEquals(true, stateV8.extraControlValues[ComtrueEp0Protocol.KEY_DRE]?.boolValue)
        assertTrue(capsV8.extraControls.any { it.key == ComtrueEp0Protocol.KEY_DRE })

        // Old FW (<7 on LUNA) hides DRE
        fwNum = 6
        sampleRateCode = 0x42 // DSD Native 256
        val stateV6 = driver.readDeviceState(tx, profileLuna)
        val capsV6 = driver.resolveCapabilities(profileLuna, stateV6)
        assertEquals("Sample Rate: DSD Native 256", stateV6.diagnosticNote)
        assertFalse(capsV6.extraControls.any { it.key == ComtrueEp0Protocol.KEY_DRE })

        // Write commands verify exact EP0 bytes
        tx.controlCalls.clear()
        assertTrue(driver.setVolume(tx, profileLuna, 45))
        assertTrue(driver.setChannelBalance(tx, profileLuna, 3))
        assertTrue(driver.setLedMode(tx, profileLuna, 2))
        assertTrue(driver.setExtraControl(tx, profileLuna, ComtrueEp0Protocol.KEY_DEPOP, DacControlValue(boolValue = true)))

        val volCall = tx.controlCalls.first { it.requestType == ComtrueEp0Protocol.EP0_REQ_TYPE_OUT && (it.data[2].toInt() and 0xFF) == 0x04 }
        assertEquals(ComtrueEp0Protocol.VOLUME_BYTES[45], volCall.data[3].toInt() and 0xFF)
        val balCall = tx.controlCalls.first { it.requestType == ComtrueEp0Protocol.EP0_REQ_TYPE_OUT && (it.data[2].toInt() and 0xFF) == 0x05 }
        assertEquals(0, balCall.data[3].toInt() and 0xFF) // right atten
        assertEquals(6, balCall.data[4].toInt() and 0xFF) // left atten = 3dB * 2
        val ledCall = tx.controlCalls.first { it.requestType == ComtrueEp0Protocol.EP0_REQ_TYPE_OUT && (it.data[2].toInt() and 0xFF) == 0x06 }
        assertEquals(0x02, ledCall.data[3].toInt() and 0xFF)
        val depopCall = tx.controlCalls.first { it.requestType == ComtrueEp0Protocol.EP0_REQ_TYPE_OUT && (it.data[2].toInt() and 0xFF) == 0x07 }
        assertEquals(0x01, depopCall.data[3].toInt() and 0xFF)
    }

    @Test
    fun `Tanchjim Space2 and StarGate2 CB schemes encode int8 gains volume commit and Q8_8 balance`() = runTest {
        val cb = CbProtocol()
        val space2 = DacProfile(
            id = "tanchjim_space_2",
            brand = "TANCHJIM",
            model = "TANCHJIM-SPACE II",
            vendorId = 0x3302,
            productId = 0x4381,
            protocol = ProtocolFamily.CB,
            schemeNo = CbProtocol.SCHEME_TANCHJIM_SPACE2,
            bandCount = 10,
            supportsDacFilter = true,
            supportsMic = true,
            balanceEncoding = BalanceEncoding.Q8_8_LE_AT_5
        )
        val stargate2 = space2.copy(
            id = "tanchjim_stargate_2",
            model = "TANCHJIM-STARGATE II",
            productId = 0x43D4,
            schemeNo = CbProtocol.SCHEME_TANCHJIM_STARGATE2,
            bandCount = 8,
            supportsDacFilter = false
        )

        val tx = FakeTransport(
            replyFactory = { req ->
                val cmd = req[2].toInt() and 0xFF
                val r = ByteArray(64)
                r[0] = 0x4B.toByte()
                r[1] = 0x80.toByte()
                r[2] = cmd.toByte()
                when (cmd) {
                    0x03 -> { // int8 -4 dB at [5]
                        r[3] = 0x02
                        r[5] = (-4).toByte()
                    }
                    0x02 -> { // int8 +6 dB mic at [5]
                        r[3] = 0x02
                        r[5] = 6.toByte()
                    }
                    0x16 -> { // Test Space2 read at [6..7] (left channel -2 dB -> L atten = -2 -> slider +2)
                        r[3] = 0x02
                        r[4] = req[4]
                        if ((req[4].toInt() and 0xFF) == 0) {
                            val raw = -2 * 256
                            BytePack.u16Le(r, 6, raw)
                        }
                    }
                    0x85 -> {
                        r[3] = 0x01
                        r[4] = 42
                    }
                }
                r
            }
        )

        val space2State = cb.readDeviceState(tx, space2)
        val space2Caps = cb.resolveCapabilities(space2, space2State)
        assertEquals(-4.0, space2State.globalGainDb, 0.01)
        assertEquals(6, space2State.micGainDb)
        assertEquals(2, space2State.channelBalance)
        assertEquals(42, space2State.volume)
        assertEquals(60, space2Caps.maxVolume)
        assertTrue(space2Caps.hasVolume)
        assertTrue(space2Caps.hasDacFilter)
        assertFalse(space2Caps.hasMicEnc)
        assertTrue(space2Caps.extraControls.any { it.key == "cb_dre" })

        val sg2Caps = cb.buildCapabilities(stargate2)
        assertFalse(sg2Caps.hasDacFilter)
        assertFalse(sg2Caps.hasOutputMode)
        assertFalse(sg2Caps.hasMicEnc)
        assertTrue(sg2Caps.hasMicGain)

        tx.sent.clear()
        assertTrue(cb.setGlobalGain(tx, space2, -5.0))
        assertTrue(cb.setMicGain(tx, space2, -3))
        assertTrue(cb.setVolume(tx, space2, 48))

        val dacWrite = tx.sent[0]
        assertEquals(0x03, dacWrite[2].toInt() and 0xFF)
        assertEquals(-5, dacWrite[5].toInt())
        val adcWrite = tx.sent[1]
        assertEquals(0x02, adcWrite[2].toInt() and 0xFF)
        assertEquals(-3, adcWrite[5].toInt())
        assertEquals(0x85, tx.sent[2][2].toInt() and 0xFF)
        assertEquals(48, tx.sent[2][4].toInt() and 0xFF)
        assertEquals(0x04, tx.sent[3][2].toInt() and 0xFF) // Space2 post-commit 0x04
    }

    @Test
    fun `KtProtocol supports Tanchjim reg 0x65 mic gain and tanchjim_dsp_nomic gate`() = runTest {
        val kt = KtProtocol(isSixBand = false)
        val micProfile = DacProfile(
            id = "tanchjim_bunny_dsp",
            brand = "TANCHJIM",
            model = "TANCHJIM BUNNY DSP",
            vendorId = 0x3302,
            protocol = ProtocolFamily.KT02H20,
            schemeNo = KtProtocol.SCHEME_TANCHJIM_DSP,
            bandCount = 5,
            supportsMic = true,
            compensate2X = false
        )
        val noMicProfile = micProfile.copy(
            id = "tanchjim_fission_dsp",
            model = "TANCHJIM-FISSION DSP",
            schemeNo = KtProtocol.SCHEME_TANCHJIM_NOMIC,
            supportsMic = false
        )

        val micCaps = kt.buildCapabilities(micProfile)
        val noMicCaps = kt.buildCapabilities(noMicProfile)
        assertTrue(micCaps.hasMicGain)
        assertEquals(-15..15, micCaps.micGainRange)
        assertFalse(noMicCaps.hasMicGain)

        val tx = FakeTransport(
            replyFactory = { req ->
                val reg = req[1].toInt() and 0xFF
                val r = ByteArray(16)
                r[0] = 0x4B
                r[1] = reg.toByte()
                r[5] = 'R'.code.toByte()
                if (reg == KtProtocol.REG_TANCHJIM_ADC_PGA) {
                    // -8 dB = -16 * 0.5 -> 256 - 16 = 240 (0xF0)
                    r[7] = 240.toByte()
                }
                r
            }
        )
        val state = kt.readDeviceState(tx, micProfile)
        assertEquals(-8, state.micGainDb)

        tx.sent.clear()
        assertTrue(kt.setMicGain(tx, micProfile, -5))
        val pkt = tx.sent[0]
        assertEquals(KtProtocol.REG_TANCHJIM_ADC_PGA, pkt[1].toInt() and 0xFF)
        assertEquals(246, pkt[7].toInt() and 0xFF) // 256 + (-5 * 2) = 246
    }

    @Test
    fun `TanchjimBesMinoProtocol decodes state and pads 5-band MINO EQ to 10 hardware bands`() = runTest {
        val driver = TanchjimBesMinoProtocol()
        val profile = DacProfile(
            id = "tanchjim_mino",
            brand = "TANCHJIM",
            model = "TANCHJIM-MINO",
            vendorId = 0,
            protocol = ProtocolFamily.BES_MINO,
            bandCount = 5,
            transport = TransportType.BLUETOOTH_SPP,
            isHeadset = true
        )
        val tx = FakeTransport(
            transportType = TransportType.BLUETOOTH_SPP,
            replyFactory = { req ->
                val cmd = req[3].toInt() and 0xFF
                when (cmd) {
                    0x4E -> ByteArray(18).apply {
                        this[0] = 0x01; this[1] = 0x01; this[3] = 0x4E; this[5] = 0x0A
                        this[10] = 1; this[11] = 2; this[12] = 3
                        this[13] = 85; this[17] = 75
                    }
                    0x97 -> byteArrayOf(0x01, 0x01, 0x00, 0x97.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x01)
                    0x93 -> byteArrayOf(0x01, 0x01, 0x00, 0x93.toByte(), 0x00, 0x02, 0x00, 0x00, 0x00, 0x02, 0x03)
                    0x95 -> byteArrayOf(0x01, 0x01, 0x00, 0x95.toByte(), 0x00, 0x02, 0x00, 0x00, 0x00, 0x0B, 0x01)
                    0x99 -> byteArrayOf(0x01, 0x01, 0x00, 0x99.toByte(), 0x00, 0x03, 0x00, 0x00, 0x00, 0x01)
                    0x90 -> byteArrayOf(0x01, 0x01, 0x00, 0x90.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x03)
                    0x87 -> byteArrayOf(
                        0x01, 0x01, 0x00, 0x87.toByte(), 0x00, 0x06, 0x00, 0x00, 0x00,
                        0x21, 0x11, 0x31, 0x22, 0x12, 0x33
                    )
                    0x6A -> byteArrayOf(0x01, 0x01, 0x00, 0x6A, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02)
                    0x54 -> ByteArray(9 + 10 * 7).apply {
                        this[0] = 0x01; this[1] = 0x01; this[3] = 0x54; this[5] = 0x46
                        this[9] = 0x02 // Low shelf
                        // +3.0 dB => (3 + 12)*100 = 1500 = 0x05DC
                        this[10] = 0x05; this[11] = 0xDC.toByte()
                        // 250 Hz = 0x00FA
                        this[12] = 0x00; this[13] = 0xFA.toByte()
                        // Q 1.25 = 125 = 0x007D
                        this[14] = 0x00; this[15] = 0x7D
                    }
                    else -> null
                }
            }
        )

        val state = driver.readDeviceState(tx, profile)
        assertEquals("V1.2.3", state.firmwareVersion)
        assertEquals(75, state.batteryPercent)
        assertEquals(1, state.extraControlValues["mino_anc_mode"]?.intValue)
        assertEquals(2, state.extraControlValues["mino_anc_cycle_left"]?.intValue)
        assertEquals(3, state.extraControlValues["mino_anc_cycle_right"]?.intValue)
        assertEquals(11, state.volume)
        assertEquals(true, state.extraControlValues["mino_vol_memory"]?.boolValue)
        assertEquals(true, state.extraControlValues["mino_in_ear"]?.boolValue)
        assertEquals(3, state.extraControlValues["mino_color"]?.intValue)
        assertEquals(2, state.activePresetId)
        assertEquals(FilterType.LOW_SHELF, state.bands[0].filterType)
        assertEquals(3.0, state.bands[0].gainDb, 0.05)
        assertEquals(250, state.bands[0].frequencyHz)
        assertEquals(1.3, state.bands[0].q, 0.05)

        tx.sent.clear()
        val custom5 = DefaultBands.flatBands(5).mapIndexed { idx, b ->
            if (idx == 0) b.copy(frequencyHz = 120, gainDb = -4.5, q = 1.5, filterType = FilterType.HIGH_SHELF) else b
        }
        assertTrue(driver.writeAllEqBands(tx, profile, custom5, 0.0, 2))
        val eqPkt = tx.sent[0]
        assertEquals(9 + 70, eqPkt.size)
        assertEquals(0x53, eqPkt[3].toInt() and 0xFF)
        assertEquals(0x46, eqPkt[5].toInt() and 0xFF)
        assertEquals(3, eqPkt[9].toInt() and 0xFF) // High shelf
        assertEquals(750, ((eqPkt[10].toInt() and 0xFF) shl 8) or (eqPkt[11].toInt() and 0xFF)) // (-4.5 + 12) * 100
        assertEquals(120, ((eqPkt[12].toInt() and 0xFF) shl 8) or (eqPkt[13].toInt() and 0xFF))
        // Dummy band 5 starts at offset 9 + 5*7 = 44 with 40 Hz
        assertEquals(40, ((eqPkt[44 + 3].toInt() and 0xFF) shl 8) or (eqPkt[44 + 4].toInt() and 0xFF))

        // Preserve volMemory flag when updating volume
        tx.sent.clear()
        assertTrue(driver.setVolume(tx, profile, 14))
        val volSet = tx.sent[0]
        assertEquals(0x96, volSet[3].toInt() and 0xFF)
        assertEquals(14, volSet[9].toInt() and 0xFF)
        assertEquals(1, volSet[10].toInt() and 0xFF)
    }

    @Test
    fun `TanchjimBluetrumV1Protocol ECHO parses PEQ buttons and writes custom preset + gain`() = runTest {
        val driver = TanchjimBluetrumV1Protocol()
        val profile = DacProfile(
            id = "tanchjim_echo",
            brand = "TANCHJIM",
            model = "TANCHJIM-ECHO",
            vendorId = 0,
            protocol = ProtocolFamily.BLUETRUM_V1,
            bandCount = 5,
            transport = TransportType.BLUETOOTH_SPP,
            isHeadset = true
        )
        val tx = FakeTransport(
            transportType = TransportType.BLUETOOTH_SPP,
            replyFactory = { req ->
                val grp = req[6].toInt() and 0xFF
                val cmd = req[7].toInt() and 0xFF
                when {
                    grp == 0x02 && cmd == 0x10 -> byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x04, 0x00, 0x1D, 0x03, 0x10) + "V1.0".encodeToByteArray()
                    grp == 0x02 && cmd == 0x16 -> byteArrayOf(
                        0xFF.toByte(), 0x03, 0x00, 0x05, 0x00, 0x1D, 0x03, 0x16,
                        0x01, 0x00, 90, 0x00, 80
                    )
                    grp == 0x0A && cmd == 0x02 -> byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x01, 0x00, 0x1D, 0x0B, 0x02, 0x3F)
                    grp == 0x0A && cmd == 0x05 -> ByteArray(10 + 5 * 7).apply {
                        this[0] = 0xFF.toByte(); this[1] = 0x03; this[5] = 0x1D; this[6] = 0x0B; this[7] = 0x05
                        // Band 0: freq=1000 (0x03E8), q=3 (0x0003), gain=-5 (0xFFFB)
                        this[10] = 0x03; this[11] = 0xE8.toByte()
                        this[12] = 0x00; this[13] = 0x03
                        this[15] = 0xFF.toByte(); this[16] = 0xFB.toByte()
                    }
                    else -> null
                }
            }
        )

        val state = driver.readDeviceState(tx, profile)
        assertEquals("V1.0", state.firmwareVersion)
        assertEquals(80, state.batteryPercent)
        assertEquals(5, state.activePresetId) // 0x3F -> Custom (index 5)
        assertEquals(1000, state.bands[0].frequencyHz)
        assertEquals(3.0, state.bands[0].q, 0.01)
        assertEquals(-5.0, state.bands[0].gainDb, 0.01)

        tx.sent.clear()
        assertTrue(driver.setExtraControl(tx, profile, "echo_btn_repair", DacControlValue(boolValue = true)))
        val btnWrite = tx.sent[0]
        assertEquals(42, btnWrite.size) // 8 header + 34 payload (0x22)
        assertEquals(0x22, btnWrite[3].toInt() and 0xFF)
        assertEquals(0x14, btnWrite[7].toInt() and 0xFF)
        assertEquals(0x01, btnWrite[8 + 14].toInt() and 0xFF) // repairFlag = 0x01
    }

    @Test
    fun `TanchjimBluetrumV2Protocol distinguishes REVO SOVA RITA and encodes signed BE EQ + Preamp`() = runTest {
        val driver = TanchjimBluetrumV2Protocol()
        val revo = DacProfile(
            id = "tanchjim_revo", brand = "TANCHJIM", model = "TANCHJIM-REVO",
            vendorId = 0, protocol = ProtocolFamily.BLUETRUM_V2,
            schemeNo = TanchjimBluetrumV2Protocol.SCHEME_REVO, bandCount = 8,
            transport = TransportType.BLUETOOTH_SPP, isHeadset = true
        )
        val sova = revo.copy(id = "tanchjim_sova", model = "TANCHJIM-SOVA", schemeNo = TanchjimBluetrumV2Protocol.SCHEME_SOVA, bandCount = 9)
        val rita = revo.copy(id = "tanchjim_rita", model = "TANCHJIM-RITA", schemeNo = TanchjimBluetrumV2Protocol.SCHEME_RITA, bandCount = 12)

        val revoCaps = driver.buildCapabilities(revo)
        val sovaCaps = driver.buildCapabilities(sova)
        val ritaCaps = driver.buildCapabilities(rita)
        assertEquals(8, revoCaps.maxBands)
        assertEquals(9, sovaCaps.maxBands)
        assertEquals(12, ritaCaps.maxBands)
        assertTrue(revoCaps.extraControls.any { it.key == "v2_game_mode" })
        assertFalse(revoCaps.extraControls.any { it.key == "v2_anc_mode" })
        assertTrue(sovaCaps.extraControls.any { it.key == "v2_anc_mode" })
        assertFalse(sovaCaps.extraControls.any { it.key == "v2_dual_device" })
        assertTrue(ritaCaps.extraControls.any { it.key == "v2_dual_device" })
        assertTrue(ritaCaps.extraControls.any { it.key == "v2_prompt_vol" })

        val tx = FakeTransport(
            transportType = TransportType.BLUETOOTH_SPP,
            replyFactory = { req ->
                val cmd = req[3].toInt() and 0xFF
                when (cmd) {
                    0x01 -> byteArrayOf(0xFF.toByte(), 0xA2.toByte(), 0x04, 0x01, 0x02, 0x01, 0x00, 0xAA.toByte())
                    0x02 -> byteArrayOf(0xFF.toByte(), 0xA2.toByte(), 0x03, 0x02, 0x00, 88, 0xAA.toByte())
                    0x0F -> byteArrayOf(0xFF.toByte(), 0xA2.toByte(), 0x04, 0x0F, 0x02, 0x06, 0x05, 0xAA.toByte()) // -6.5 dB
                    0x0B -> ByteArray(6 + 12 * 7).apply {
                        this[0] = 0xFF.toByte(); this[1] = 0xA2.toByte(); this[2] = 0x56; this[3] = 0x0B; this[4] = 0x0C
                        this[lastIndex] = 0xAA.toByte()
                        // Band 0: PEAK(1), gain=-3.5dB (-350 = 65186 = 0xFEA2), freq=205, q=1.0 (100 = 0x0064)
                        this[5] = 1
                        this[6] = 0xFE.toByte(); this[7] = 0xA2.toByte()
                        this[8] = 0x00; this[9] = 205.toByte()
                        this[10] = 0x00; this[11] = 100
                    }
                    else -> null
                }
            }
        )

        val state = driver.readDeviceState(tx, rita)
        assertEquals("V2.1.0", state.firmwareVersion)
        assertEquals(88, state.batteryPercent)
        assertEquals(-6.5, state.globalGainDb, 0.05)
        assertEquals(-3.5, state.bands[0].gainDb, 0.05)
        assertEquals(205, state.bands[0].frequencyHz)

        tx.sent.clear()
        assertTrue(driver.writeAllEqBands(tx, rita, state.bands, 0.0, 0))
        assertTrue(driver.setGlobalGain(tx, rita, -4.2))
        assertTrue(driver.setExtraControl(tx, rita, "v2_dual_device", DacControlValue(boolValue = false)))

        val eqPkt = tx.sent[0]
        assertEquals(0x56, eqPkt[2].toInt() and 0xFF) // 1 + 12*7 + 1? Wait cmd+bandCount+84 = 86 = 0x56
        assertEquals(0x2B, eqPkt[3].toInt() and 0xFF)
        assertEquals(12, eqPkt[4].toInt() and 0xFF)
        assertEquals(0xFE, eqPkt[6].toInt() and 0xFF)
        assertEquals(0xA2, eqPkt[7].toInt() and 0xFF)
        assertEquals(0xAA, eqPkt.last().toInt() and 0xFF)

        val dacPkt = tx.sent[1]
        assertEquals(0x2F, dacPkt[3].toInt() and 0xFF)
        assertEquals(0x02, dacPkt[4].toInt() and 0xFF) // negative
        assertEquals(4, dacPkt[5].toInt() and 0xFF)
        assertEquals(2, dacPkt[6].toInt() and 0xFF)

        val dualOffPkt = tx.sent[2]
        assertEquals(0x30, dualOffPkt[3].toInt() and 0xFF)
        assertEquals(0xFF, dualOffPkt[4].toInt() and 0xFF) // 0xFF disables Dual-Device
    }

    @Test
    fun `Bundled dac_catalog resolves all Tanchjim USB and BT devices without collisions`() {
        val catalogFile = listOf(
            File("../app/src/main/assets/dac_catalog.json"),
            File("app/src/main/assets/dac_catalog.json")
        ).firstOrNull { it.exists() } ?: error("dac_catalog.json not found")
        val cat = DacCatalog.fromJson(catalogFile.readText())

        // ComTrue EP0 specificity: LUNA vs LUNA II vs LUNA AT; SPACE vs SPACE II vs SPACE PRO
        val space = cat.match(UsbDeviceDescriptor("u1", 0x0666, 0x0001, "TANCHJIM-SPACE"))
        assertEquals(ProtocolFamily.COMTRUE_EP0, space?.protocol)

        val space2 = cat.match(UsbDeviceDescriptor("u2", 0x3302, 0x4381, "TANCHJIM-SPACE II"))
        assertEquals(ProtocolFamily.CB, space2?.protocol)
        assertEquals(CbProtocol.SCHEME_TANCHJIM_SPACE2, space2?.schemeNo)
        assertEquals(10, space2?.bandCount)

        val luna = cat.match(UsbDeviceDescriptor("u3", 0x0666, 0x0002, "TANCHJIM-LUNA"))
        assertEquals(ProtocolFamily.COMTRUE_EP0, luna?.protocol)

        val lunaAt = cat.match(UsbDeviceDescriptor("u4", 0x0666, 0x0003, "TANCHJIM-LUNA AT"))
        assertEquals(ProtocolFamily.COMTRUE_EP0, lunaAt?.protocol)
        assertEquals("tanchjim_luna_at", lunaAt?.id)

        // RITA USB vs RITA BT must not collide!
        val ritaUsb = cat.match(UsbDeviceDescriptor("u5", 0x3302, 0x1002, "TANCHJIM-RITA"))
        assertEquals(TransportType.USB_HID, ritaUsb?.transport)
        assertEquals(ProtocolFamily.CB, ritaUsb?.protocol)
        assertEquals("tanchjim_rita_dsp", ritaUsb?.id)

        val ritaBt = cat.match(UsbDeviceDescriptor("b1", 0, 0, "TANCHJIM-RITA", bluetoothAddress = "11:22:33:44:55:66"))
        assertEquals(TransportType.BLUETOOTH_SPP, ritaBt?.transport)
        assertEquals(ProtocolFamily.BLUETRUM_V2, ritaBt?.protocol)
        assertEquals(12, ritaBt?.bandCount)

        val ritaMikuBt = cat.match(UsbDeviceDescriptor("b2", 0, 0, "TANCHJIM-RITA HATSUNE MIKU", bluetoothAddress = "11:22:33:44:55:67"))
        assertTrue(ritaMikuBt?.id?.startsWith("tanchjim_rita_miku_") == true)

        val minoAt = cat.match(UsbDeviceDescriptor("b3", 0, 0, "TANCHJIM-MINO AT", bluetoothAddress = "11:22:33:44:55:68"))
        assertEquals(ProtocolFamily.BES_MINO, minoAt?.protocol)
        assertEquals(10, minoAt?.bandCount)

        val echoBt = cat.match(UsbDeviceDescriptor("b4", 0, 0, "TANCHJIM-ECHO", bluetoothAddress = "11:22:33:44:55:69"))
        assertEquals(ProtocolFamily.BLUETRUM_V1, echoBt?.protocol)

        // KT 2x vs 1x + mic gate
        val oneDsp = cat.match(UsbDeviceDescriptor("u6", 0x3302, 0x2001, "TANCHJIM-ONE DSP"))
        assertEquals(ProtocolFamily.KT02H20, oneDsp?.protocol)
        assertEquals(true, oneDsp?.compensate2X)
        assertEquals(true, oneDsp?.nonBlockingWrite)

        val bunnyDsp = cat.match(UsbDeviceDescriptor("u7", 0x3302, 0x2002, "TANCHJIM BUNNY DSP"))
        assertEquals(false, bunnyDsp?.compensate2X)
        assertEquals(KtProtocol.SCHEME_TANCHJIM_DSP, bunnyDsp?.schemeNo)

        val fission = cat.match(UsbDeviceDescriptor("u8", 0x3302, 0x2003, "TANCHJIM-FISSION  DSP"))
        assertEquals(KtProtocol.SCHEME_TANCHJIM_NOMIC, fission?.schemeNo)
        val fissionRational = cat.match(UsbDeviceDescriptor("u9", 0x3302, 0x2004, "FISSION-Rational HiFi Edition"))
        assertEquals("tanchjim_fission_rational", fissionRational?.id)
    }

    @Test
    fun `CbProtocol handles NICEHCK PureAural 3-step gain, inverted Class H-AB, static volume, and 0x04 commits`() = runTest {
        val pureAural = DacProfile(
            id = "nicehck_pureaural",
            brand = "NICEHCK",
            model = "NICEHCK PureAural",
            vendorId = 0x3302,
            productId = 0x4322,
            protocol = ProtocolFamily.CB,
            schemeNo = CbProtocol.SCHEME_NICEHCK_PUREAURAL,
            bandCount = 10,
            sampleRate = 96_000,
            balanceEncoding = BalanceEncoding.Q8_8_LE_AT_5,
            supportsMic = true,
            supportsDacFilter = true,
            source = "nicehck"
        )
        val proto = CbProtocol()
        val queriedCmds = mutableListOf<Int>()
        val tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("nh1", 0x3302, 0x4322, "NICEHCK PureAural"),
            replyFactory = { req ->
                val cmd = req[2].toInt() and 0xFF
                queriedCmds += cmd
                val resp = ByteArray(64)
                resp[0] = 0x4B
                resp[1] = 0x80.toByte()
                resp[2] = cmd.toByte()
                when (cmd) {
                    0x0C -> {
                        resp[3] = 5
                        "V1.02".encodeToByteArray().copyInto(resp, 4)
                    }
                    0x03 -> {
                        resp[3] = 2
                        BytePack.u16Le(resp, 4, (-3 * 256))
                    }
                    0x19 -> {
                        resp[3] = 1
                        resp[4] = 2 // High Gain
                    }
                    0x1D -> {
                        resp[3] = 1
                        resp[4] = 0 // Class H (inverted)
                    }
                    0x85 -> {
                        resp[3] = 1
                        resp[4] = 72
                    }
                    0x02 -> {
                        resp[3] = 2
                        BytePack.u16Le(resp, 4, (-6 * 256))
                    }
                    0x16 -> {
                        resp[3] = 4
                        resp[4] = req[4]
                        if ((req[4].toInt() and 0xFF) == 0) {
                            BytePack.u16Le(resp, 5, -35 * 256) // L attenuated -35 => slider +35
                        }
                    }
                }
                resp
            }
        )

        val state = proto.readDeviceState(tx, pureAural)
        val caps = proto.resolveCapabilities(pureAural, state)
        assertEquals(10, caps.maxBands)
        assertEquals(listOf(FilterType.PEAK), caps.supportedFilters)
        assertEquals(8, caps.presets.size)
        assertEquals("Game", caps.presetCategories[5])
        assertEquals(-12.0..8.0, caps.globalGainRange)
        assertEquals(-12..12, caps.micGainRange)
        assertEquals(50, caps.channelBalanceMax)
        assertEquals(listOf(NamedOption(0, "Class H"), NamedOption(1, "Class AB")), caps.outputModeOptions)
        assertEquals(3, caps.workModeOptions.size)
        assertEquals("Medium Gain", caps.workModeOptions[1].label)
        assertTrue(caps.hasVolume)
        assertEquals(100, caps.maxVolume)
        // Extended non-NICEHCK probes (0x32 DRE, 0x18 LED, 0x30 SWAP, 0x23 PRE_GAIN) must not be queried
        assertFalse(queriedCmds.contains(0x32))
        assertFalse(queriedCmds.contains(0x18))

        assertEquals(2, state.workModeIndex)
        assertEquals(0, state.outputModeIndex)
        assertEquals(72, state.volume)
        assertEquals(-6, state.micGainDb)
        assertEquals(-3.0, state.globalGainDb, 0.01)
        assertEquals(35, state.channelBalance)

        tx.sent.clear()
        assertTrue(proto.setChannelBalance(tx, pureAural, -50))
        assertEquals(2, tx.sent.size)
        assertEquals(-50 * 256, BytePack.getI16Le(tx.sent[1], 5))

        tx.sent.clear()
        assertTrue(proto.setGlobalGain(tx, pureAural, 11.0)) // clamps to +8.0 + sends 0x04 commit
        assertEquals(2, tx.sent.size)
        assertEquals(0x03, tx.sent[0][2].toInt() and 0xFF)
        assertEquals(8 * 256, BytePack.getI16Le(tx.sent[0], 4))
        assertEquals(0x04, tx.sent[1][2].toInt() and 0xFF)

        tx.sent.clear()
        assertTrue(proto.setMicGain(tx, pureAural, -15)) // clamps to -12 + sends 0x04 commit
        assertEquals(2, tx.sent.size)
        assertEquals(0x02, tx.sent[0][2].toInt() and 0xFF)
        assertEquals(-12 * 256, BytePack.getI16Le(tx.sent[0], 4))
        assertEquals(0x04, tx.sent[1][2].toInt() and 0xFF)

        tx.sent.clear()
        assertTrue(proto.saveToFlash(tx, pureAural, 1))
        assertEquals(listOf(0x0A, 0x01, 0x04), tx.sent.map { it[2].toInt() and 0xFF })
    }

    @Test
    fun `CbProtocol gates NICEHCK StringSnow and Octave capabilities and applies factory presets`() = runTest {
        val stringSnow = DacProfile(
            id = "nicehck_stringsnow",
            brand = "YUANDAO",
            model = "StringSnow",
            vendorId = 0x3302,
            productId = 0x1291,
            protocol = ProtocolFamily.CB,
            schemeNo = CbProtocol.SCHEME_NICEHCK_STRINGSNOW,
            bandCount = 8,
            sampleRate = 96_000,
            isHeadset = true,
            source = "nicehck"
        )
        val octave = DacProfile(
            id = "nicehck_octave",
            brand = "NICEHCK",
            model = "NICEHCK Octave",
            vendorId = 0x3302,
            productId = 0x39C3,
            protocol = ProtocolFamily.CB,
            schemeNo = CbProtocol.SCHEME_NICEHCK_OCTAVE,
            bandCount = 10,
            sampleRate = 96_000,
            balanceEncoding = BalanceEncoding.Q8_8_LE_AT_5,
            supportsMic = true,
            supportsDacFilter = true,
            source = "nicehck"
        )
        val proto = CbProtocol()
        val snowCaps = proto.buildCapabilities(stringSnow)
        assertFalse(snowCaps.hasDacFilter)
        assertFalse(snowCaps.hasOutputMode)
        assertFalse(snowCaps.hasWorkMode)
        assertFalse(snowCaps.hasChannelBalance)
        assertFalse(snowCaps.hasVolume)
        assertFalse(snowCaps.hasMicGain)
        assertEquals(6, snowCaps.presets.size)
        assertEquals("Mode", snowCaps.presetCategories[5])

        val octaveCaps = proto.buildCapabilities(octave)
        assertTrue(octaveCaps.hasDacFilter)
        assertTrue(octaveCaps.hasMicGain)
        assertTrue(octaveCaps.hasChannelBalance)
        assertFalse(octaveCaps.hasOutputMode)
        assertFalse(octaveCaps.hasWorkMode)
        assertFalse(octaveCaps.hasVolume)
        assertEquals(4, octaveCaps.presets.size)

        val tx = FakeTransport(UsbDeviceDescriptor("nh2", 0x3302, 0x1291, "StringSnow"))
        assertTrue(proto.selectPreset(tx, stringSnow, 5)) // Game Mode (8 bands)
        // Global gain (0x03 + 0x04) + 8 x 0x09 + 0x0A refresh
        assertEquals(11, tx.sent.size)
        assertEquals(0x0A, tx.sent.last()[2].toInt() and 0xFF)
        assertEquals(5, tx.sent[2][36].toInt() and 0xFF)
    }

    @Test
    fun `TtkInfoProtocol exposes read-only UAC 2_0 metadata for NK1 series`() = runTest {
        val nk1 = DacProfile(
            id = "nicehck_nk1",
            brand = "YUANDAO",
            model = "NK1",
            vendorId = 0x3302,
            productId = 0x1595,
            protocol = ProtocolFamily.TTGK_INFO,
            schemeNo = "ttgk_info_v1",
            bandCount = 0,
            sampleRate = 384_000,
            reportId = 0
        )
        val proto = TtkInfoProtocol()
        val caps = proto.buildCapabilities(nk1)
        assertEquals(0, caps.maxBands)
        assertEquals(384_000, caps.sampleRate)
        assertFalse(caps.supportsRamRefresh)
        assertFalse(caps.supportsFlashSave)

        val tx = FakeTransport(UsbDeviceDescriptor("nk1", 0x3302, 0x1595, "NK1"))
        val state = proto.readDeviceState(tx, nk1)
        assertEquals("UAC 2.0", state.firmwareVersion)
        assertFalse(state.eqEnabled)
        assertTrue(state.diagnosticNote?.contains("CX31993 + MAX97220") == true)
        assertFalse(proto.writeEqBand(tx, nk1, EqBand(0, 1000, 1.0, 1.0, FilterType.PEAK), 0.0, 0))
        assertTrue(tx.sent.isEmpty())
    }

    @Test
    fun `NicehckOriginProtocol origin_v1 handles CRC16 framing, PEQ encode-decode, FW gating, and controls`() = runTest {
        val origin = DacProfile(
            id = "nicehck_orig_in",
            brand = "YUANDAO",
            model = "YUANDAO OriG in",
            vendorId = 0,
            productNameMatch = "OriG in",
            protocol = ProtocolFamily.NICEHCK_ORIGIN_BT,
            schemeNo = "origin_v1",
            bandCount = 10,
            sampleRate = 48_000,
            reportId = 0,
            transport = TransportType.BLUETOOTH_GATT,
            btSppUuid = NicehckOriginProtocol.SPP_UUID,
            isHeadset = true
        )
        val proto = NicehckOriginProtocol()

        // Verify optional CRC16-MODBUS packet roundtrip
        val crcPkt = proto.buildOriginPacket(0x0207, byteArrayOf(0x21, 0x00, 0x01), includeCrc = true)
        val parsedCrc = proto.parseOriginPacket(crcPkt)
        assertNotNull(parsedCrc)
        assertEquals(0x0207, parsedCrc!!.first)
        assertEquals(listOf(0x21, 0x00, 0x01), parsedCrc.second.map { it.toInt() and 0xFF })

        var fwString = "V4.06"
        val tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("bt1", 0, 0, "YUANDAO OriG in", bluetoothAddress = "AA:BB:CC:DD:EE:FF"),
            transportType = TransportType.BLUETOOTH_GATT,
            replyFactory = { req ->
                val (cmdId, _) = proto.parseOriginPacket(req) ?: return@FakeTransport null
                val body = when (cmdId) {
                    0x0003 -> fwString.encodeToByteArray()
                    0x0005 -> byteArrayOf(85, 90, 100) // L=85, R=90, Case=100
                    0x0101 -> byteArrayOf(3) // Deep ANC
                    0x01E1 -> byteArrayOf(1) // Wind noise on
                    0x010A -> byteArrayOf(0, 1, 1, 0, 2, 1) // Off + ANC cycle
                    0x010B -> byteArrayOf(1) // Bass boost on
                    0x010C -> byteArrayOf(1, 0) // Spatial audio on
                    0x010E -> byteArrayOf(1) // Vol adaptive on
                    0x0108 -> byteArrayOf(1) // Game mode on
                    0x0106 -> byteArrayOf(0) // Low latency off
                    0x0104 -> byteArrayOf(0) // AAC
                    0x0105 -> byteArrayOf(1) // Dual conn on
                    0x0109 -> byteArrayOf(1) // In-ear on
                    0x0102 -> byteArrayOf(12) // Tone vol = 12
                    0x0103 -> byteArrayOf(1, 0, 1, 1, 2, 0, 5, 4) // L tap=Play/Pause, R long=ANC Cycle
                    0x0107 -> byteArrayOf(
                        0x21, 0, 1, (100 and 0xFF).toByte(), (100 ushr 8).toByte(),
                        (0xFE00 and 0xFF).toByte(), ((0xFE00 ushr 8) and 0xFF).toByte(),
                        (0x0180 and 0xFF).toByte(), ((0x0180 ushr 8) and 0xFF).toByte(),
                        0x21, 1, 2, (12000 and 0xFF).toByte(), ((12000 ushr 8) and 0xFF).toByte(),
                        (0x0300 and 0xFF).toByte(), ((0x0300 ushr 8) and 0xFF).toByte(),
                        (0x00B3 and 0xFF).toByte(), ((0x00B3 ushr 8) and 0xFF).toByte()
                    )
                    else -> byteArrayOf()
                }
                proto.buildOriginPacket(cmdId, body)
            }
        )

        val state406 = proto.readDeviceState(tx, origin)
        val caps406 = proto.resolveCapabilities(origin, state406)
        assertEquals(6, caps406.presets.size) // 5 presets + Custom PEQ (FW < 407)
        assertEquals(2, caps406.extraControls.first { it.key == "origin_codec" }.options.size) // LHDC, AAC
        assertEquals(85, state406.batteryPercent)
        assertEquals("AAC", state406.bluetoothCodec)
        assertEquals(3, state406.extraControlValues["origin_anc_mode"]?.intValue)
        assertEquals(5, state406.extraControlValues["origin_anc_cycle"]?.intValue) // BIT_ANC_OFF(1) | BIT_ANC_NR(4)
        assertEquals(12, state406.extraControlValues["origin_tone_vol"]?.intValue)
        assertEquals(1, state406.extraControlValues["origin_btn_l_1"]?.intValue)
        assertEquals(4, state406.extraControlValues["origin_btn_r_5"]?.intValue)
        assertEquals(FilterType.HIGH_PASS, state406.bands[0].filterType)
        assertEquals(-2.0, state406.bands[0].gainDb, 0.05)
        assertEquals(FilterType.LOW_PASS, state406.bands[1].filterType)
        assertEquals(12000, state406.bands[1].frequencyHz)

        fwString = "V4.08"
        val state408 = proto.readDeviceState(tx, origin)
        val caps408 = proto.resolveCapabilities(origin, state408)
        assertEquals(8, caps408.presets.size) // +Fine Tune, +Velvet Voice
        assertEquals(3, caps408.extraControls.first { it.key == "origin_codec" }.options.size) // +SBC

        tx.sent.clear()
        assertTrue(proto.setExtraControl(tx, origin, "origin_anc_cycle", DacControlValue(intValue = 3)))
        assertTrue(proto.setExtraControl(tx, origin, "origin_btn_r_2", DacControlValue(intValue = 7)))
        assertTrue(proto.writeEqBand(tx, origin, EqBand(2, 2500, 4.5, 1.5, FilterType.PEAK), 0.0, 0x21))
        val cycleReq = proto.parseOriginPacket(tx.sent[0])!!
        assertEquals(0x020A, cycleReq.first)
        assertEquals(listOf(0, 1, 1, 1, 2, 0), cycleReq.second.map { it.toInt() and 0xFF })
        val touchReq = proto.parseOriginPacket(tx.sent[1])!!
        assertEquals(0x0203, touchReq.first)
        assertEquals(listOf(2, 0, 2, 7), touchReq.second.map { it.toInt() and 0xFF })
        val peqReq = proto.parseOriginPacket(tx.sent[2])!!
        assertEquals(0x0207, peqReq.first)
        assertEquals(9, peqReq.second.size)
        assertEquals(0x21, peqReq.second[0].toInt() and 0xFF)
        assertEquals(2500, BytePack.getU16Le(peqReq.second, 3))
    }

    @Test
    fun `Bundled dac_catalog resolves all NICEHCK USB and BT devices`() {
        val catalogFile = listOf(
            File("../app/src/main/assets/dac_catalog.json"),
            File("app/src/main/assets/dac_catalog.json")
        ).firstOrNull { it.exists() } ?: error("dac_catalog.json not found")
        val cat = DacCatalog.fromJson(catalogFile.readText())

        val pureAural = cat.match(UsbDeviceDescriptor("n1", 0x3302, 0x4322, "NICEHCK PureAural"))
        assertEquals("nicehck_pureaural", pureAural?.id)
        assertEquals(CbProtocol.SCHEME_NICEHCK_PUREAURAL, pureAural?.schemeNo)
        assertEquals(10, pureAural?.bandCount)

        val tenth = cat.match(UsbDeviceDescriptor("n2", 0x3302, 0xC200, "10th Anniversary Edition"))
        assertEquals("nicehck_10th", tenth?.id)
        assertEquals(CbProtocol.SCHEME_NICEHCK_10TH, tenth?.schemeNo)

        val snow = cat.match(UsbDeviceDescriptor("n3", 0x3302, 0x1291, "StringSnow"))
        assertEquals(CbProtocol.SCHEME_NICEHCK_STRINGSNOW, snow?.schemeNo)

        val tears = cat.match(UsbDeviceDescriptor("n4", 0x3302, 0x129E, "Tears"))
        assertEquals(CbProtocol.SCHEME_NICEHCK_TEARS, tears?.schemeNo)

        val octave = cat.match(UsbDeviceDescriptor("n5", 0x3302, 0x39C3, "NICEHCK Octave"))
        assertEquals(CbProtocol.SCHEME_NICEHCK_OCTAVE, octave?.schemeNo)
        assertEquals(10, octave?.bandCount)

        val nk1 = cat.match(UsbDeviceDescriptor("n6", 0x3302, 0x1595, "NK1"))
        assertEquals(ProtocolFamily.TTGK_INFO, nk1?.protocol)
        val nk1u = cat.match(UsbDeviceDescriptor("n7", 0x3302, 0x332D, "NK1 Ultra"))
        assertEquals(ProtocolFamily.TTGK_INFO, nk1u?.protocol)
        val nk1m = cat.match(UsbDeviceDescriptor("n8", 0x3302, 0x3347, "NK1 Max"))
        assertEquals(ProtocolFamily.TTGK_INFO, nk1m?.protocol)

        val originBt = cat.match(UsbDeviceDescriptor("nb1", 0, 0, "YUANDAO OriG in", bluetoothAddress = "11:22:33:44:55:70"))
        assertEquals(ProtocolFamily.NICEHCK_ORIGIN_BT, originBt?.protocol)
        assertEquals(NicehckOriginProtocol.SPP_UUID, originBt?.btSppUuid)
    }

    @Test
    fun `MoondropSpvProtocol handles HID superset opcodes and MD-QT-072 Vendor EP0 polling`() = runTest {
        val qt072 = DacProfile(
            id = "moondrop_md_qt_072",
            brand = "MOONDROP",
            model = "MD-QT-072",
            vendorId = 0x35D8,
            productId = 0x012F,
            protocol = ProtocolFamily.MOONDROP_SPV,
            schemeNo = MoondropSpvProtocol.SCHEME_SPV_VENDOR_EP0,
            bandCount = 10,
            sampleRate = 96_000,
            isHeadset = true,
            supportsMic = true
        )
        val proto = MoondropSpvProtocol()
        var pendingCmd = -1
        val tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("m1", 0x35D8, 0x012F, "MD-QT-072"),
            controlReplyFactory = { reqType, req, _, _, data ->
                if (reqType == 0x40 && req == 0x51) {
                    pendingCmd = data[2].toInt() and 0xFF
                    data.size
                } else if (reqType == 0xC0 && req == 0x52) {
                    data.fill(0)
                    data[0] = 0x4B
                    data[1] = 0x80.toByte()
                    data[2] = pendingCmd.toByte()
                    when (pendingCmd) {
                        0x0C -> {
                            data[3] = 5
                            "V2.10".encodeToByteArray().copyInto(data, 4)
                        }
                        0x0D -> {
                            data[3] = 4
                            "D1.4".encodeToByteArray().copyInto(data, 4)
                        }
                        0x23 -> {
                            data[3] = 2
                            BytePack.u16Le(data, 4, -4 * 256)
                        }
                        0x26 -> { data[3] = 1; data[4] = 2 }
                        0x38 -> { data[3] = 1; data[4] = 1 }
                        0x37 -> { data[3] = 1; data[4] = 85 } // 8.5 dB
                        0x33 -> { data[3] = 1; data[4] = 3 } // Adaptive ANC
                        0x32 -> { data[3] = 1; data[4] = 1 }
                        0x34 -> { data[3] = 1; data[4] = 1 }
                        0x35 -> {
                            data[3] = 5; data[4] = 1; data[5] = 1; data[6] = 0; data[7] = 1; data[8] = 2
                        }
                        0x27 -> {
                            data[3] = 6; data[4] = 1; data[5] = 75; data[6] = 1; data[7] = 80; data[8] = 1; data[9] = 95
                        }
                        0x2A -> { data[3] = 2; data[4] = 1; data[5] = 2 }
                        0x2B -> { data[3] = 3; data[4] = 1; data[5] = 60; data[6] = 0 }
                        0x2E -> { data[3] = 4; data[4] = 1; data[5] = 15; data[6] = 1; data[7] = 30 }
                        0x24 -> {
                            data[3] = 6; data[4] = 1
                            data[5] = 0x12.toByte() // L=1 Play/Pause, R=2 Prev
                        }
                    }
                    64
                } else 0
            }
        )

        val state = proto.readDeviceState(tx, qt072)
        val caps = proto.resolveCapabilities(qt072, state)
        assertEquals("V2.10", state.firmwareVersion)
        assertEquals(75, state.batteryPercent)
        assertTrue(state.diagnosticNote?.contains("Dongle FW D1.4") == true)
        assertEquals(-4, state.extraControlValues["spv_pre_gain"]?.intValue)
        assertEquals(2, state.extraControlValues["spv_preset_gain"]?.intValue)
        assertEquals(85, state.extraControlValues["spv_post_gain"]?.intValue)
        assertEquals(3, state.extraControlValues["spv_anc_v3"]?.intValue)
        assertEquals(5, state.extraControlValues["spv_anc_cycle"]?.intValue) // ANC + TP
        assertEquals(1, state.extraControlValues["spv_btn_l_0"]?.intValue)
        assertEquals(2, state.extraControlValues["spv_btn_r_0"]?.intValue)
        assertTrue(caps.extraControls.any { it.key == "spv_head_track" })

        tx.controlCalls.clear()
        assertTrue(proto.setExtraControl(tx, qt072, "spv_pre_gain", DacControlValue(intValue = -5)))
        assertEquals(2, tx.controlCalls.size) // 0x23 + 0x04 commit via 0x40/0x51
        assertEquals(0x40, tx.controlCalls[0].requestType)
        assertEquals(0x23, tx.controlCalls[0].data[2].toInt() and 0xFF)
        assertEquals(0x04, tx.controlCalls[1].data[2].toInt() and 0xFF)

        tx.controlCalls.clear()
        assertTrue(proto.writeEqBand(tx, qt072, EqBand(0, 1000, 3.0, 1.4, FilterType.PEAK), 0.0, 0))
        assertEquals(0x09, tx.controlCalls[0].data[2].toInt() and 0xFF)
        assertEquals(0x07, tx.controlCalls[0].data[36].toInt() and 0xFF)
    }

    @Test
    fun `MoondropComtureProtocol reads status-volume and writes inverted EP0 volume`() = runTest {
        val click = DacProfile(
            id = "moondrop_click",
            brand = "MOONDROP",
            model = "Click",
            vendorId = 0x35D8,
            productNameMatch = "MOONDROP Click",
            protocol = ProtocolFamily.MOONDROP_COMTURE,
            bandCount = 0,
            sampleRate = 384_000,
            reportId = 0
        )
        val proto = MoondropComtureProtocol()
        var lastCmd = 0
        val tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("mc1", 0x35D8, 0x0001, "MOONDROP Click"),
            controlReplyFactory = { reqType, req, _, idx, data ->
                assertEquals(0x09A0, idx)
                if (reqType == 0x43 && req == 0xA0) {
                    lastCmd = data[2].toInt() and 0xFF
                    7
                } else if (reqType == 0xC3 && req == 0xA1) {
                    when (lastCmd) {
                        0xA3 -> { data[3] = 3; data[4] = 1; data[5] = 0 }
                        0xA2 -> { data[4] = 25 } // 100 - 25 = 75
                    }
                    7
                } else 0
            }
        )
        val state = proto.readDeviceState(tx, click)
        assertEquals(3, state.dacFilterIndex)
        assertEquals(1, state.workModeIndex)
        assertEquals(0, state.ledMode)
        assertEquals(75, state.volume)

        tx.controlCalls.clear()
        assertTrue(proto.setVolume(tx, click, 0))
        assertTrue(proto.setVolume(tx, click, 80))
        assertEquals(0xFF, tx.controlCalls[0].data[3].toInt() and 0xFF)
        assertEquals(20, tx.controlCalls[1].data[3].toInt() and 0xFF)
    }

    @Test
    fun `MoondropFreeman3Protocol builds CAF CTRL commands and Q2_22 biquads`() = runTest {
        val f3 = DacProfile(
            id = "moondrop_freeman3_1496",
            brand = "MOONDROP",
            model = "MOONDROP Freeman3",
            vendorId = 0x35D8,
            productId = 0x1496,
            protocol = ProtocolFamily.MOONDROP_FREEMAN3,
            bandCount = 5,
            sampleRate = 48_000,
            reportId = 1,
            isHeadset = true
        )
        val proto = MoondropFreeman3Protocol()
        val flatCoeffs = proto.encodeFreeman3Coeffs(EqBand(0, 1000, 0.0, 1.0, FilterType.PEAK), 48_000)
        assertEquals(3, flatCoeffs.gainShiftWord)
        assertEquals(4194304, flatCoeffs.b0)

        var lastCafCmd = 0
        val tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("mf1", 0x35D8, 0x1496, "Freeman3"),
            controlReplyFactory = { reqType, req, wVal, wIdx, data ->
                if (reqType == 0x21 && req == 0x09 && wVal == 0x0201) {
                    assertEquals(3, wIdx)
                    lastCafCmd = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0x7F) shl 8)
                    data.size
                } else if (reqType == 0xA1 && req == 0x01 && wVal == 0x0101) {
                    data[5] = 0x80.toByte() // reply bit
                    when (lastCafCmd) {
                        259 -> {
                            BytePack.i32Le(data, 10, 7)
                            BytePack.i32Le(data, 14, 49)
                            BytePack.i32Le(data, 18, 0)
                            BytePack.i32Le(data, 22, 1)
                        }
                        346 -> BytePack.i32Le(data, 14, 2)
                        477 -> {
                            BytePack.i32Le(data, 18, 1200)
                            BytePack.i32Le(data, 22, 256)
                            BytePack.i32Le(data, 26, 0)
                            BytePack.i32Le(data, 30, -3 * 256)
                        }
                    }
                    data.size
                } else if (reqType == 0xA1 && req == 0x01 && wVal == 0x0105) {
                    data[1] = 0x02
                    data.size
                } else data.size
            }
        )
        val state = proto.readDeviceState(tx, f3)
        assertEquals("7.49.0.1", state.firmwareVersion)
        assertTrue(state.eqEnabled)
        assertEquals(2, state.activePresetId)
        assertEquals(1200, state.bands[0].frequencyHz)
        assertEquals(-3.0, state.bands[0].gainDb, 0.05)

        tx.controlCalls.clear()
        assertTrue(proto.writeEqBand(tx, f3, EqBand(1, 2000, 4.0, 1.2, FilterType.PEAK), 0.0, 0))
        val setCall = tx.controlCalls.first { it.requestType == 0x21 }
        assertEquals(190, (setCall.data[4].toInt() and 0xFF) or ((setCall.data[5].toInt() and 0x7F) shl 8))
        assertEquals(6, BytePack.getI16Le(setCall.data, 14)) // shiftEQBandForFreeman3(1) = 1 + 5 = 6
    }

    @Test
    fun `MoondropBleSourceProtocol handles 0xA5 frames, PEQ 0_01dB math, revision commit, and notify`() = runTest {
        val pill = DacProfile(
            id = "moondrop_pill",
            brand = "MOONDROP",
            model = "Pill",
            vendorId = 0,
            productNameMatch = "MOONDROP Pill",
            protocol = ProtocolFamily.MOONDROP_BLE_SOURCE,
            bandCount = 10,
            sampleRate = 48_000,
            transport = TransportType.BLUETOOTH_GATT,
            isHeadset = true
        )
        val proto = MoondropBleSourceProtocol()
        val tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("ble1", 0, 0, "MOONDROP Pill", bluetoothAddress = "11:22:33:44:55:99"),
            transportType = TransportType.BLUETOOTH_GATT,
            replyFactory = { req ->
                val (cmdId, pld) = proto.parseFrame(req) ?: return@FakeTransport null
                val body = when (cmdId) {
                    0 -> byteArrayOf(0, 1, 4, 2) // 1.4.2
                    1 -> byteArrayOf(0, 1) // USB Audio
                    5 -> byteArrayOf(0, 64) // vol 64
                    7 -> byteArrayOf(0, 7) // preset 7
                    9 -> byteArrayOf(0, 7, 10, 0x2A, 0x00, 0xCE.toByte(), 0xFF.toByte(), 1) // rev=42, -0.50dB
                    11 -> byteArrayOf(
                        0, pld[0],
                        0xE8.toByte(), 0x03, // 1000 Hz
                        0xC8.toByte(), 0x00, // +2.00 dB
                        0x78, 0x05, // Q = 1.400
                        0 // PEAKING
                    )
                    13 -> byteArrayOf(0, 0x2B, 0x00) // new rev = 43
                    14 -> byteArrayOf(0, (-30 and 0xFF).toByte(), (-30 ushr 8).toByte()) // -3.0 dB
                    else -> byteArrayOf(0)
                }
                proto.buildFrame(cmdId, body)
            }
        )
        val state = proto.readDeviceState(tx, pill)
        assertEquals("1.4.2", state.firmwareVersion)
        assertEquals(64, state.volume)
        assertEquals(7, state.activePresetId)
        assertEquals(-0.5, state.globalGainDb, 0.01)
        assertEquals(-3, state.micGainDb)
        assertEquals(1000, state.bands[0].frequencyHz)
        assertEquals(2.0, state.bands[0].gainDb, 0.01)
        assertEquals(1.4, state.bands[0].q, 0.005)

        tx.sent.clear()
        assertTrue(proto.setGlobalGain(tx, pill, -2.5))
        assertTrue(proto.writeEqBand(tx, pill, EqBand(2, 4000, 3.25, 1.5, FilterType.HIGH_SHELF), -2.5, 7))
        assertTrue(proto.saveToFlash(tx, pill, 7))
        val preReq = proto.parseFrame(tx.sent[0])!!
        assertEquals(10, preReq.first)
        assertEquals(7, preReq.second[0].toInt() and 0xFF)
        assertEquals(-250, BytePack.getI16Le(preReq.second, 1))
        val ptReq = proto.parseFrame(tx.sent[1])!!
        assertEquals(12, ptReq.first)
        assertEquals(325, BytePack.getI16Le(ptReq.second, 3))
        assertEquals(1500, BytePack.getU16Le(ptReq.second, 5))
        val commitReq = proto.parseFrame(tx.sent[2])!!
        assertEquals(13, commitReq.first)
        assertEquals(42, BytePack.getU16Le(commitReq.second, 1))

        val pushed = proto.applyIncomingPacket(proto.buildFrame(134, byteArrayOf(0, 3, 7, 1)), pill, state)
        assertEquals(3, pushed?.activePresetId)
    }

    @Test
    fun `MoondropGaiaV3, Airoha, JieliRcsp, and Wmic protocols encode and decode frames`() = runTest {
        // 1. Qualcomm GAIA V3 (with feature discovery, 10-band chunked PEQ, battery, post-gain 0.1dB)
        val robin = DacProfile("moondrop_robin", "MOONDROP", "Robin", 0, protocol = ProtocolFamily.QUALCOMM_GAIA_V3, bandCount = 10, transport = TransportType.BLUETOOTH_GATT)
        val gaia = MoondropGaiaV3Protocol()
        val gTx = FakeTransport(
            descriptor = UsbDeviceDescriptor("g1", 0, 0, "MOONDROP Robin", bluetoothAddress = "11:22:33:44:55:01"),
            transportType = TransportType.BLUETOOTH_GATT,
            replyFactory = { req ->
                val (cmd16, payload) = gaia.parseGaiaV3Packet(req) ?: return@FakeTransport null
                val feat = (cmd16 ushr 9) and 0x7F
                val pdu = cmd16 and 0x7F
                val respCmd = (feat shl 9) or (2 shl 7) or pdu
                val body = when {
                    feat == 0x00 && pdu == 0x01 -> byteArrayOf(
                        0x00, // hasMore = false
                        0x00, 1, 0x05, 1, 0x0D, 1, 0x21, 1, 0x24, 1
                    )
                    feat == 0x05 && pdu == 0x00 -> byteArrayOf(1)
                    feat == 0x05 && pdu == 0x02 -> byteArrayOf(63)
                    feat == 0x05 && pdu == 0x04 -> byteArrayOf(10)
                    feat == 0x05 && pdu == 0x05 -> {
                        val s = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
                        val e = payload.getOrNull(1)?.toInt()?.and(0xFF) ?: 6
                        val n = (e - s + 1).coerceAtLeast(1)
                        ByteArray(4 + n * 7).also { out ->
                            out[0] = s.toByte()
                            out[1] = e.toByte()
                            // First band in first chunk: 1000 Hz, Q=1.0, +2.0 dB (120/60)
                            if (s == 0) {
                                BytePack.u16Be(out, 4, 1000)
                                BytePack.u16Be(out, 6, 4096)
                                out[8] = 0
                                BytePack.u16Be(out, 9, 120)
                            } else {
                                for (i in 0 until n) {
                                    val off = 4 + i * 7
                                    BytePack.u16Be(out, off, 4000)
                                    BytePack.u16Be(out, off + 2, 4096)
                                    out[off + 4] = 0
                                }
                            }
                        }
                    }
                    feat == 0x0D && pdu == 0x00 -> byteArrayOf(1, 2, 3)
                    feat == 0x0D && pdu == 0x01 -> byteArrayOf(1, 92, 2, 88, 3, 70)
                    feat == 0x21 && pdu == 0x03 -> byteArrayOf(1) // ANC On
                    feat == 0x24 && pdu == 0x01 -> byteArrayOf(60) // 6.0 dB
                    feat == 0x24 && pdu == 0x03 -> byteArrayOf(1)
                    else -> byteArrayOf()
                }
                val out = ByteArray(4 + body.size)
                BytePack.u16Be(out, 0, MoondropGaiaV3Protocol.VENDOR_QUALCOMM_V3)
                BytePack.u16Be(out, 2, respCmd)
                body.copyInto(out, 4)
                out
            }
        )
        val gState = gaia.readDeviceState(gTx, robin)
        val gCaps = gaia.resolveCapabilities(robin, gState)
        assertEquals(88, gState.batteryPercent)
        assertEquals(60, gState.extraControlValues["gaia_post_gain"]?.intValue)
        assertEquals(10, gState.bands.size)
        assertEquals(10, gCaps.maxBands)
        assertEquals(1000, gState.bands[0].frequencyHz)
        assertEquals(2.0, gState.bands[0].gainDb, 0.05)

        // Verify 10-band write splits into 7 + 3 chunks on PDU 0x06 (bp4.java)
        gTx.sent.clear()
        assertTrue(gaia.writeAllEqBands(gTx, robin, gState.bands, 0.0, 63))
        assertEquals(2, gTx.sent.size)
        val c0 = gaia.parseGaiaV3Packet(gTx.sent[0])!!
        val c1 = gaia.parseGaiaV3Packet(gTx.sent[1])!!
        assertEquals(0x06, c0.first and 0x7F)
        assertEquals(0, c0.second[0].toInt() and 0xFF)
        assertEquals(6, c0.second[1].toInt() and 0xFF)
        assertEquals(0x06, c1.first and 0x7F)
        assertEquals(7, c1.second[0].toInt() and 0xFF)
        assertEquals(9, c1.second[1].toInt() and 0xFF)

        // 1b. Gen-1 Space Travel (bandCount=0, ANC_V2 + Battery only -> no PEQ exposed)
        val st1 = DacProfile("moondrop_space_travel", "MOONDROP", "Space Travel", 0, protocol = ProtocolFamily.QUALCOMM_GAIA_V3, bandCount = 0, transport = TransportType.BLUETOOTH_SPP, isHeadset = true)
        val stTx = FakeTransport(
            descriptor = UsbDeviceDescriptor("st1", 0, 0, "MOONDROP Space Travel", bluetoothAddress = "11:22:33:44:55:09"),
            transportType = TransportType.BLUETOOTH_SPP,
            replyFactory = { req ->
                val (cmd16, _) = gaia.parseGaiaV3Packet(req) ?: return@FakeTransport null
                val feat = (cmd16 ushr 9) and 0x7F
                val pdu = cmd16 and 0x7F
                val body = when {
                    feat == 0x00 && pdu == 0x01 -> byteArrayOf(0x00, 0x00, 1, 0x0D, 1, 0x20, 1)
                    feat == 0x0D && pdu == 0x00 -> byteArrayOf(1, 2)
                    feat == 0x0D && pdu == 0x01 -> byteArrayOf(1, 95, 2, 95)
                    feat == 0x20 && pdu == 0x03 -> byteArrayOf(2) // Transparency
                    else -> byteArrayOf()
                }
                val respCmd = (feat shl 9) or (2 shl 7) or pdu
                ByteArray(8 + body.size).also { out ->
                    out[0] = 0xFF.toByte(); out[1] = 0x03; out[2] = 0; out[3] = body.size.toByte()
                    BytePack.u16Be(out, 4, MoondropGaiaV3Protocol.VENDOR_QUALCOMM_V3)
                    BytePack.u16Be(out, 6, respCmd)
                    body.copyInto(out, 8)
                }
            }
        )
        val stState = gaia.readDeviceState(stTx, st1)
        val stCaps = gaia.resolveCapabilities(st1, stState)
        assertEquals(0, stCaps.maxBands)
        assertTrue(stState.bands.isEmpty())
        assertEquals(listOf("gaia_anc_v2"), stCaps.extraControls.map { it.key })
        assertEquals(2, stState.extraControlValues["gaia_anc_v2"]?.intValue)

        // 1c. bandCount=0 with factory presets (FEAT_MUSIC_PROCESSING), FEAT_AUDIO_CURATION (mode + cycle order), FEAT_DAC_GAIN, FEAT_TOUCH_V3 (7-byte)
        val stPresetTx = FakeTransport(
            descriptor = UsbDeviceDescriptor("st1p", 0, 0, "MOONDROP Space Travel", bluetoothAddress = "11:22:33:44:55:10"),
            transportType = TransportType.BLUETOOTH_SPP,
            replyFactory = { req ->
                val (cmd16, _) = gaia.parseGaiaV3Packet(req) ?: return@FakeTransport null
                val feat = (cmd16 ushr 9) and 0x7F
                val pdu = cmd16 and 0x7F
                val body = when {
                    feat == 0x00 && pdu == 0x01 -> byteArrayOf(0x00, 0x00, 1, 0x05, 1, 0x08, 1, 0x0F, 1, 0x1A, 1)
                    feat == 0x05 && pdu == 0x00 -> byteArrayOf(1)
                    feat == 0x05 && pdu == 0x01 -> byteArrayOf(3, 0, 1, 2)
                    feat == 0x05 && pdu == 0x02 -> byteArrayOf(1)
                    feat == 0x08 && pdu == 0x03 -> byteArrayOf(1) // ANC On
                    feat == 0x08 && pdu == 41 -> byteArrayOf(1, 1, 1, 1, 3) // ON_TP_OFF
                    feat == 0x0F && pdu == 0x01 -> byteArrayOf(2)
                    feat == 0x1A && pdu == 0x02 -> byteArrayOf(0x11, 0x22, 0x33, 0x77, 0x66, 0x44, 0x55)
                    else -> byteArrayOf()
                }
                val respCmd = (feat shl 9) or (2 shl 7) or pdu
                ByteArray(8 + body.size).also { out ->
                    out[0] = 0xFF.toByte(); out[1] = 0x03; out[2] = 0; out[3] = body.size.toByte()
                    BytePack.u16Be(out, 4, MoondropGaiaV3Protocol.VENDOR_QUALCOMM_V3)
                    BytePack.u16Be(out, 6, respCmd)
                    body.copyInto(out, 8)
                }
            }
        )
        val stPresetState = gaia.readDeviceState(stPresetTx, st1)
        val stPresetCaps = gaia.resolveCapabilities(st1, stPresetState)
        assertEquals(0, stPresetCaps.maxBands)
        assertFalse(stPresetCaps.hasEqSwitch)
        assertEquals(listOf(0, 1, 2), stPresetCaps.presets.map { it.id })
        assertEquals(1, stPresetState.activePresetId)
        assertEquals(1, stPresetState.extraControlValues["gaia_audio_curation"]?.intValue)
        assertEquals(3, stPresetState.extraControlValues["gaia_ac_order"]?.intValue)
        assertEquals(2, stPresetState.extraControlValues["gaia_dac_gain"]?.intValue)
        assertEquals(5, stPresetState.extraControlValues["gaia_touch_r_6"]?.intValue)
        stPresetTx.sent.clear()
        assertTrue(gaia.selectPreset(stPresetTx, st1, 2))
        assertTrue(gaia.setExtraControl(stPresetTx, st1, "gaia_dac_gain", DacControlValue(intValue = 1)))
        assertTrue(gaia.setExtraControl(stPresetTx, st1, "gaia_ac_order", DacControlValue(intValue = 4)))
        assertTrue(gaia.setExtraControl(stPresetTx, st1, "gaia_touch_r_6", DacControlValue(intValue = 7)))
        assertEquals(0x03, gaia.parseGaiaV3Packet(stPresetTx.sent[0])!!.first and 0x7F)
        assertEquals(2, gaia.parseGaiaV3Packet(stPresetTx.sent[0])!!.second[0].toInt() and 0xFF)
        assertEquals(0x0F, (gaia.parseGaiaV3Packet(stPresetTx.sent[1])!!.first ushr 9) and 0x7F)
        assertEquals(42, gaia.parseGaiaV3Packet(stPresetTx.sent[2])!!.first and 0x7F)
        assertEquals(4, gaia.parseGaiaV3Packet(stPresetTx.sent[2])!!.second[4].toInt() and 0xFF)
        assertEquals(7, gaia.parseGaiaV3Packet(stPresetTx.sent[3])!!.second.size)

        // 1d. Early GAIA FEAT_ANC (0x02) fallback
        val stAncV1Tx = FakeTransport(
            descriptor = UsbDeviceDescriptor("st1a", 0, 0, "MOONDROP Sparks", bluetoothAddress = "11:22:33:44:55:11"),
            transportType = TransportType.BLUETOOTH_SPP,
            replyFactory = { req ->
                val (cmd16, _) = gaia.parseGaiaV3Packet(req) ?: return@FakeTransport null
                val feat = (cmd16 ushr 9) and 0x7F
                val pdu = cmd16 and 0x7F
                val body = when {
                    feat == 0x00 && pdu == 0x01 -> byteArrayOf(0x00, 0x00, 1, 0x02, 1)
                    feat == 0x02 && pdu == 0x01 -> byteArrayOf(1)
                    feat == 0x02 && pdu == 0x03 -> byteArrayOf(2)
                    else -> byteArrayOf()
                }
                val respCmd = (feat shl 9) or (2 shl 7) or pdu
                ByteArray(8 + body.size).also { out ->
                    out[0] = 0xFF.toByte(); out[1] = 0x03; out[2] = 0; out[3] = body.size.toByte()
                    BytePack.u16Be(out, 4, MoondropGaiaV3Protocol.VENDOR_QUALCOMM_V3)
                    BytePack.u16Be(out, 6, respCmd)
                    body.copyInto(out, 8)
                }
            }
        )
        val ancV1State = gaia.readDeviceState(stAncV1Tx, st1)
        val ancV1Caps = gaia.resolveCapabilities(st1, ancV1State)
        assertEquals(listOf("gaia_anc_v1_state", "gaia_anc_v1_mode"), ancV1Caps.extraControls.map { it.key })
        assertEquals(true, ancV1State.extraControlValues["gaia_anc_v1_state"]?.boolValue)
        assertEquals(2, ancV1State.extraControlValues["gaia_anc_v1_mode"]?.intValue)

        // 2. Airoha Race
        val airoha = MoondropAirohaProtocol()
        val aProf = DacProfile("moondrop_edge", "MOONDROP", "Edge", 0, protocol = ProtocolFamily.MOONDROP_AIROHA, bandCount = 5, transport = TransportType.BLUETOOTH_SPP)
        val aTx = FakeTransport(UsbDeviceDescriptor("a1", 0, 0, "MOONDROP Edge", bluetoothAddress = "11:22:33:44:55:02"), TransportType.BLUETOOTH_SPP)
        assertTrue(airoha.writeEqBand(aTx, aProf, EqBand(0, 500, -3.5, 1.41, FilterType.PEAK), 0.0, 0x65))
        val parsedRace = airoha.parseRaceFrame(aTx.sent.first())!!
        assertEquals(0x0A05, parsedRace.first)

        // 3. Jieli RCSP
        val jieli = MoondropJieliRcspProtocol()
        val jProf = DacProfile("moondrop_space_travel", "MOONDROP", "Space Travel", 0, protocol = ProtocolFamily.MOONDROP_JIELI_RCSP, bandCount = 10, transport = TransportType.BLUETOOTH_SPP)
        val jTx = FakeTransport(UsbDeviceDescriptor("j1", 0, 0, "Space Travel", bluetoothAddress = "11:22:33:44:55:03"), TransportType.BLUETOOTH_SPP)
        assertTrue(jieli.selectPreset(jTx, jProf, 2))
        val parsedRcsp = jieli.parseRcspFrame(jTx.sent.first())!!
        assertEquals(0x08, parsedRcsp.first)

        // 4. Wireless Mic MW fragmentation
        val wmic = MoondropWmicProtocol()
        val single = wmic.fragmentPdu(byteArrayOf(1, 2, 3), mtu = 247)
        assertEquals(1, single.size)
        val multi = wmic.fragmentPdu(ByteArray(60) { it.toByte() }, mtu = 23)
        assertEquals(4, multi.size)
        assertEquals(0xF0.toByte(), multi[0][0])
    }

    @Test
    fun `Bundled dac_catalog resolves all Moondrop USB and BT devices`() {
        val catalogFile = listOf(
            File("../app/src/main/assets/dac_catalog.json"),
            File("app/src/main/assets/dac_catalog.json")
        ).firstOrNull { it.exists() } ?: error("dac_catalog.json not found")
        val cat = DacCatalog.fromJson(catalogFile.readText())

        val qt072 = cat.match(UsbDeviceDescriptor("m1", 0x35D8, 0x012F, "MD-QT-072"))
        assertEquals("moondrop_md_qt_072", qt072?.id)
        assertEquals(ProtocolFamily.MOONDROP_SPV, qt072?.protocol)
        assertEquals(MoondropSpvProtocol.SCHEME_SPV_VENDOR_EP0, qt072?.schemeNo)

        val f3 = cat.match(UsbDeviceDescriptor("m2", 0x35D8, 0x1496, "MOONDROP"))
        assertEquals(ProtocolFamily.MOONDROP_FREEMAN3, f3?.protocol)
        val cnxtFallback = cat.match(UsbDeviceDescriptor("m3", 0x0572, 0x9999, "Legacy Cnx"))
        assertEquals(ProtocolFamily.MOONDROP_FREEMAN3, cnxtFallback?.protocol)

        val click = cat.match(UsbDeviceDescriptor("m4", 0x35D8, 0x0001, "MOONDROP Click"))
        assertEquals(ProtocolFamily.MOONDROP_FREEMAN3, click?.protocol)
        assertEquals(5, click?.bandCount)
        val starlight = cat.match(UsbDeviceDescriptor("m4s", 0x35D8, 0x1496, "Starlight"))
        assertEquals(ProtocolFamily.MOONDROP_FREEMAN3, starlight?.protocol)
        assertEquals(9, starlight?.bandCount)
        val dawnPro = cat.match(UsbDeviceDescriptor("m5", 0x35D8, 0x0001, "Dawn Pro"))
        assertEquals(ProtocolFamily.MOONDROP_COMTURE, dawnPro?.protocol)
        val dawnPro2 = cat.match(UsbDeviceDescriptor("m5b", 0x35D8, 0x0001, "Dawn Pro 2"))
        assertEquals(ProtocolFamily.MOONDROP_SPV, dawnPro2?.protocol)
        assertEquals(8, dawnPro2?.bandCount)
        val echoBp = cat.match(UsbDeviceDescriptor("m5c", 0x35D8, 0x0001, "ECHO-BP"))
        assertEquals(ProtocolFamily.MOONDROP_SPV, echoBp?.protocol)
        assertEquals(32, echoBp?.bandCount)
        assertEquals(32, MoondropSpvProtocol().buildCapabilities(echoBp!!).maxBands)
        val jiu = cat.match(UsbDeviceDescriptor("m6", 0x3302, 0x9999, "MOONDROP JIU"))
        assertEquals(ProtocolFamily.KT02H20, jiu?.protocol)
        val chu2 = cat.match(UsbDeviceDescriptor("m7", 0x3302, 0x9999, "CHU2 DSP"))
        assertEquals(ProtocolFamily.KT02H20, chu2?.protocol)

        assertEquals(ProtocolFamily.QUALCOMM_GAIA_V3, cat.match(UsbDeviceDescriptor("mb1", 0, 0, "MOONDROP Pill", bluetoothAddress = "00:11:22:33:44:01"))?.protocol)
        assertEquals(
            ProtocolFamily.MOONDROP_BLE_SOURCE,
            cat.match(UsbDeviceDescriptor("mb1b", 0, 0, "MOONDROP Pill", bluetoothAddress = "00:11:22:33:44:11", btServiceUuids = listOf("9ECA0000-7F3A-4F32-9A38-A91B2C6E0100")))?.protocol
        )
        assertEquals(ProtocolFamily.QUALCOMM_GAIA_V3, cat.match(UsbDeviceDescriptor("mb2", 0, 0, "Robin's Earphones", bluetoothAddress = "00:11:22:33:44:02"))?.protocol)
        assertEquals(ProtocolFamily.QUALCOMM_GAIA_V3, cat.match(UsbDeviceDescriptor("mb3", 0, 0, "MOONDROP Golden Ages", bluetoothAddress = "00:11:22:33:44:03"))?.protocol)
        val st1 = cat.match(UsbDeviceDescriptor("mb4", 0, 0, "MOONDROP Space Travel", bluetoothAddress = "00:11:22:33:44:04"))
        assertEquals(ProtocolFamily.QUALCOMM_GAIA_V3, st1?.protocol)
        assertEquals(0, st1?.bandCount)
        val st2 = cat.match(UsbDeviceDescriptor("mb4b", 0, 0, "SPACE TRAVEL 2", bluetoothAddress = "00:11:22:33:44:14"))
        assertEquals(ProtocolFamily.QUALCOMM_GAIA_V3, st2?.protocol)
        assertEquals(5, st2?.bandCount)
        assertEquals(ProtocolFamily.MOONDROP_AIROHA, cat.match(UsbDeviceDescriptor("mb6", 0, 0, "MOONDROP The Garden", bluetoothAddress = "00:11:22:33:44:06"))?.protocol)
        assertEquals(ProtocolFamily.QUALCOMM_GAIA_V3, cat.match(UsbDeviceDescriptor("mb7", 0, 0, "MOONDROP Dots", bluetoothAddress = "00:11:22:33:44:07"))?.protocol)
        assertEquals(ProtocolFamily.MOONDROP_WMIC, cat.match(UsbDeviceDescriptor("mb5", 0, 0, "MOONDROP Wireless Mic", bluetoothAddress = "00:11:22:33:44:05"))?.protocol)
    }
}
