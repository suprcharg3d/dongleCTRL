package com.suprcharg3d.dongleCTRL.protocol

import com.suprcharg3d.dongleCTRL.dsp.CrcUtils
import com.suprcharg3d.dongleCTRL.dsp.DefaultBands
import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.FilterType
import com.suprcharg3d.dongleCTRL.protocol.drivers.Cb1100AuProtocol
import com.suprcharg3d.dongleCTRL.protocol.drivers.CbProtocol
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
}
