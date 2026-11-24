package com.suprcharg3d.dongleCTRL.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class TransportType {
    USB_HID,
    USB_SERIAL,
    BLUETOOTH_SPP,
    BLUETOOTH_GATT
}

data class UsbDeviceDescriptor(
    val deviceKey: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String? = null,
    val manufacturerName: String? = null,
    val hasHidInterface: Boolean = true,
    val hasCdcInterface: Boolean = false,
    val hidReportSize: Int = 64,
    val hidOutputReportSizes: Map<Int, Int> = emptyMap(),
    val hidInputReportSizes: Map<Int, Int> = emptyMap(),
    val acInterfaceIndex: Int = 0,
    val hidInterfaceIndex: Int = 0,
    val bluetoothAddress: String? = null,
    val btConnectMode: Int? = null,
    val btServiceUuids: List<String> = emptyList()
)

/**
 * Platform-neutral hardware transport interface.
 * Android USB-HID / USB-Serial or future WebHID / WebSerial / BLE transports implement this contract.
 */
interface DacTransport {
    val descriptor: UsbDeviceDescriptor
    val transportType: TransportType
    val isConnected: Boolean

    suspend fun send(data: ByteArray, timeoutMs: Int = 1000): Boolean
    suspend fun receive(expectedSize: Int = 64, timeoutMs: Int = 1000): ByteArray?

    suspend fun transceive(
        request: ByteArray,
        expectedSize: Int = 64,
        timeoutMs: Int = 1000
    ): ByteArray? {
        if (!send(request, timeoutMs)) return null
        return receive(expectedSize, timeoutMs)
    }

    suspend fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray? = null,
        timeoutMs: Int = 1000
    ): Int = -1

    suspend fun drainInput(maxPackets: Int = 8) {
        repeat(maxPackets) {
            val pkt = receive(expectedSize = descriptor.hidReportSize.coerceAtLeast(64), timeoutMs = 40)
            if (pkt == null || pkt.isEmpty()) return
        }
    }

    /** Unsolicited frames pushed by the DAC while idle (e.g. physical volume knob turns). */
    val incomingFrames: Flow<ByteArray>
        get() = emptyFlow()

    fun close() {}
}
