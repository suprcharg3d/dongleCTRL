package com.suprcharg3d.dongleCTRL.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.suprcharg3d.dongleCTRL.protocol.DacTransport
import com.suprcharg3d.dongleCTRL.protocol.HidReportDescriptorParser
import com.suprcharg3d.dongleCTRL.protocol.ParsedHidDescriptor
import com.suprcharg3d.dongleCTRL.protocol.TransportType
import com.suprcharg3d.dongleCTRL.protocol.UsbDeviceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android USB Host HID transport matching WalkPlay/EPZ APK endpoint discovery and FiiO multi-HID fallback.
 */
class AndroidUsbHidTransport private constructor(
    override val descriptor: UsbDeviceDescriptor,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface?,
    private val inEndpoint: UsbEndpoint?,
    private val outEndpoint: UsbEndpoint?,
    private val logSink: (String) -> Unit
) : DacTransport {

    override val transportType: TransportType = TransportType.USB_HID
    private val ioMutex = Mutex()

    @Volatile
    private var open = true

    @Volatile
    private var lastSentReportId: Int = 0

    override val isConnected: Boolean
        get() = open

    override suspend fun send(data: ByteArray, timeoutMs: Int): Boolean = ioMutex.withLock {
        if (!open) return false
        return withContext(Dispatchers.IO) {
            val out = outEndpoint
            val rid = resolveWireReportId(data)
            lastSentReportId = rid
            val descriptorSize = descriptor.hidOutputReportSizes[rid]
                ?: if (descriptor.hidOutputReportSizes.size == 1) descriptor.hidOutputReportSizes.values.firstOrNull() else null
            val targetSize = when {
                descriptorSize != null && descriptorSize > 0 -> maxOf(descriptorSize, data.size)
                out != null -> out.maxPacketSize.coerceAtLeast(data.size)
                else -> data.size
            }
            val framed = if (data.size == targetSize) data else data.copyOf(targetSize)
            val sentBytes = if (out != null) {
                connection.bulkTransfer(out, framed, framed.size, timeoutMs)
            } else {
                // Fallback for HID interfaces without an explicit Interrupt OUT endpoint (uses EP0 SET_REPORT)
                connection.controlTransfer(
                    0x21,
                    0x09,
                    (0x02 shl 8) or rid,
                    usbInterface?.id ?: 0,
                    framed,
                    framed.size,
                    timeoutMs
                )
            }
            val ok = sentBytes >= 0
            logSink("TX[${framed.size}] ${if (ok) "OK($sentBytes)" else "ERR($sentBytes)"}: ${framed.take(12).joinToString(" ") { "%02X".format(it) }}")
            ok
        }
    }

    private fun resolveWireReportId(data: ByteArray): Int {
        val first = data.firstOrNull()?.toInt()?.and(0xFF) ?: return 0
        return if (descriptor.hidOutputReportSizes.containsKey(first)) first
        else if (descriptor.hidOutputReportSizes.containsKey(0)) 0
        else first
    }

    override suspend fun receive(expectedSize: Int, timeoutMs: Int): ByteArray? = ioMutex.withLock {
        if (!open) return null
        val ep = inEndpoint
        val intf = usbInterface
        return withContext(Dispatchers.IO) {
            val bufSize = expectedSize.coerceAtLeast(ep?.maxPacketSize ?: descriptor.hidReportSize).coerceAtLeast(64)
            val buf = ByteArray(bufSize)
            val count = if (ep != null) {
                connection.bulkTransfer(ep, buf, buf.size, timeoutMs)
            } else if (intf != null) {
                // HID Control fallback when Interrupt IN endpoint is absent (EP0 GET_REPORT)
                val rid = lastSentReportId.coerceIn(0, 0xFF)
                connection.controlTransfer(
                    0xA1,
                    0x01,
                    (0x01 shl 8) or rid,
                    intf.id,
                    buf,
                    buf.size,
                    timeoutMs
                )
            } else {
                -1
            }
            if (count > 0) {
                val sliced = buf.copyOf(count)
                logSink("RX[$count]: ${sliced.take(12).joinToString(" ") { "%02X".format(it) }}")
                sliced
            } else {
                null
            }
        }
    }

    override suspend fun transceive(
        request: ByteArray,
        expectedSize: Int,
        timeoutMs: Int
    ): ByteArray? {
        if (!send(request, timeoutMs)) return null
        return receive(expectedSize, timeoutMs)
    }

    override suspend fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        timeoutMs: Int
    ): Int = ioMutex.withLock {
        if (!open) return -1
        withContext(Dispatchers.IO) {
            connection.controlTransfer(requestType, request, value, index, buffer, buffer?.size ?: 0, timeoutMs)
        }
    }

    override fun close() {
        if (!open) return
        open = false
        usbInterface?.let { intf -> runCatching { connection.releaseInterface(intf) } }
        runCatching { connection.close() }
    }

    companion object {
        fun open(
            usbManager: UsbManager,
            device: UsbDevice,
            descriptor: UsbDeviceDescriptor,
            allowControlOnly: Boolean = false,
            preferControlOnly: Boolean = false,
            logSink: (String) -> Unit
        ): AndroidUsbHidTransport? {
            val hidInterfaces = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }

            if (preferControlOnly || hidInterfaces.isEmpty()) {
                if (!preferControlOnly && !allowControlOnly) {
                    logSink("No USB HID interface found on ${device.productName ?: device.deviceName}")
                    return null
                }
                val conn = usbManager.openDevice(device) ?: run {
                    logSink("UsbManager.openDevice failed for ${device.deviceName}")
                    return null
                }
                logSink("Opened EP0 control-only USB transport for ${device.productName ?: device.deviceName}")
                return AndroidUsbHidTransport(
                    descriptor = descriptor,
                    connection = conn,
                    usbInterface = null,
                    inEndpoint = null,
                    outEndpoint = null,
                    logSink = logSink
                )
            }

            // Score HID interfaces: Interrupt IN+OUT > Interrupt IN only > Interrupt OUT only
            val ranked = hidInterfaces.sortedByDescending { intf ->
                var hasIn = false
                var hasOut = false
                for (i in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(i)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                        if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
                    }
                }
                (if (hasIn && hasOut) 4 else 0) + (if (hasIn) 2 else 0) + (if (hasOut) 1 else 0)
            }

            val conn = usbManager.openDevice(device) ?: run {
                logSink("UsbManager.openDevice failed for ${device.deviceName}")
                return null
            }

            data class Candidate(
                val intf: UsbInterface,
                val parsed: ParsedHidDescriptor?,
                val score: Int
            )

            val candidates = ArrayList<Candidate>(ranked.size)
            for (intf in ranked) {
                if (!conn.claimInterface(intf, true)) continue
                val rawDesc = readHidReportDescriptor(conn, intf.id)
                val parsed = rawDesc?.let { HidReportDescriptorParser.parse(it) }
                runCatching { conn.releaseInterface(intf) }

                var hasIn = false
                var hasOut = false
                for (i in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(i)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                        if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
                    }
                }
                val baseScore = (if (hasIn && hasOut) 4 else 0) + (if (hasIn) 2 else 0) + (if (hasOut) 1 else 0)
                val descBonus = when {
                    parsed == null -> 0
                    parsed.isWiredRemoteOnly -> -20 // SKIP_WIRED_REMOTE
                    parsed.hasVendorUsagePage -> 20
                    else -> 5
                }
                candidates += Candidate(intf, parsed, baseScore + descBonus)
            }

            val ordered = candidates.sortedByDescending { it.score }
            for (cand in ordered) {
                val intf = cand.intf
                if (cand.parsed?.isWiredRemoteOnly == true && ordered.any { it.parsed?.isWiredRemoteOnly != true }) {
                    logSink("Skipped wired-remote HID intf#${intf.id}")
                    continue
                }
                if (!conn.claimInterface(intf, true)) continue
                runCatching { conn.setInterface(intf) }
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (i in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(i)
                    if (ep.direction == UsbConstants.USB_DIR_IN &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    ) {
                        inEp = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    ) {
                        outEp = ep
                    }
                }
                val outSizes = cand.parsed?.wireOutputSizes().orEmpty()
                val inSizes = cand.parsed?.wireInputSizes().orEmpty()
                val reportSize = maxOf(
                    outSizes.values.maxOrNull() ?: 0,
                    inSizes.values.maxOrNull() ?: 0,
                    outEp?.maxPacketSize ?: 0,
                    inEp?.maxPacketSize ?: 0,
                    64
                )
                logSink(
                    "Claimed HID intf#${intf.id} in=${inEp != null} out=${outEp != null} outSizes=$outSizes maxPkt=$reportSize"
                )
                return AndroidUsbHidTransport(
                    descriptor = descriptor.copy(
                        hidReportSize = reportSize,
                        hidOutputReportSizes = outSizes,
                        hidInputReportSizes = inSizes,
                        hidInterfaceIndex = intf.id
                    ),
                    connection = conn,
                    usbInterface = intf,
                    inEndpoint = inEp,
                    outEndpoint = outEp,
                    logSink = logSink
                )
            }

            conn.close()
            logSink("Failed to claim any HID interface on ${device.deviceName}")
            return null
        }

        private fun readHidReportDescriptor(conn: UsbDeviceConnection, intfId: Int): ByteArray? {
            val buf = ByteArray(512)
            val directLen = conn.controlTransfer(0x81, 0x06, 0x2200, intfId, buf, buf.size, 800)
            if (directLen > 0) return buf.copyOf(directLen)
            // Fallback: read HID descriptor (0x2100) to get exact report descriptor length, then retry 0x2200
            val hidBuf = ByteArray(16)
            val hidLen = conn.controlTransfer(0x81, 0x06, 0x2100, intfId, hidBuf, hidBuf.size, 800)
            if (hidLen >= 9 && (hidBuf[6].toInt() and 0xFF) == 0x22) {
                val reportLen = ((hidBuf[8].toInt() and 0xFF) shl 8) or (hidBuf[7].toInt() and 0xFF)
                if (reportLen in 1..2048) {
                    val exact = ByteArray(reportLen)
                    val got = conn.controlTransfer(0x81, 0x06, 0x2200, intfId, exact, exact.size, 800)
                    if (got > 0) return exact.copyOf(got)
                }
            }
            return null
        }
    }
}
