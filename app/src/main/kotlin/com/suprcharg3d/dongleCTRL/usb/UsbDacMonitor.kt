package com.suprcharg3d.dongleCTRL.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.suprcharg3d.dongleCTRL.data.OfflinePrefsStore
import com.suprcharg3d.dongleCTRL.protocol.DacCatalog
import com.suprcharg3d.dongleCTRL.protocol.DacProfile
import com.suprcharg3d.dongleCTRL.protocol.DacTransport
import com.suprcharg3d.dongleCTRL.protocol.ProtocolFamily
import com.suprcharg3d.dongleCTRL.protocol.TransportType
import com.suprcharg3d.dongleCTRL.protocol.UsbDeviceDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DetectedUsbDac(
    val device: UsbDevice,
    val descriptor: UsbDeviceDescriptor,
    val matchedProfile: DacProfile?,
    val effectiveProfile: DacProfile,
    val hasPermission: Boolean,
    val isSupportedCandidate: Boolean
)

/**
 * Monitors USB host devices, resolves profiles offline against [DacCatalog],
 * and automatically prompts/grants permission when a DAC is plugged in.
 */
class UsbDacMonitor(
    private val context: Context,
    private val catalog: DacCatalog,
    private val prefsStore: OfflinePrefsStore
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _detectedDevices = MutableStateFlow<List<DetectedUsbDac>>(emptyList())
    val detectedDevices: StateFlow<List<DetectedUsbDac>> = _detectedDevices.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val requestedDevices = HashSet<String>()
    private var receiverRegistered = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = extractUsbDevice(intent)
                    log("USB attached: ${dev?.productName ?: dev?.deviceName ?: "unknown"}")
                    refreshDevices(autoRequestPermission = true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = extractUsbDevice(intent)
                    log("USB detached: ${dev?.productName ?: dev?.deviceName ?: "unknown"}")
                    dev?.deviceName?.let { requestedDevices.remove(it) }
                    refreshDevices(autoRequestPermission = false)
                }
                ACTION_USB_PERMISSION -> {
                    val dev = extractUsbDevice(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log("USB permission ${if (granted) "GRANTED" else "DENIED"} for ${dev?.productName ?: dev?.deviceName}")
                    refreshDevices(autoRequestPermission = false)
                }
            }
        }
    }

    fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(usbReceiver, filter)
            }
            receiverRegistered = true
        }
        refreshDevices(autoRequestPermission = true)
    }

    fun stop() {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
    }

    fun refreshDevices(autoRequestPermission: Boolean = false) {
        val manager = usbManager ?: return
        val list = manager.deviceList.values
            .map { buildEntry(manager, it) }
            .filter { it.isSupportedCandidate }
            .sortedByDescending { it.matchedProfile != null }

        _detectedDevices.value = list

        if (autoRequestPermission) {
            list.firstOrNull { !it.hasPermission && it.matchedProfile != null && it.device.deviceName !in requestedDevices }
                ?.let { requestPermission(it.device) }
        }
    }

    fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        requestedDevices.add(device.deviceName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        manager.requestPermission(device, pi)
        log("Requested USB permission for ${device.productName ?: device.deviceName}")
    }

    fun openTransport(entry: DetectedUsbDac): DacTransport? {
        val manager = usbManager ?: return null
        if (!manager.hasPermission(entry.device)) {
            requestPermission(entry.device)
            return null
        }
        val allowEp0 = entry.effectiveProfile.protocol.usesEp0Control ||
            entry.effectiveProfile.protocol == ProtocolFamily.TTGK_INFO
        val preferEp0Only = entry.effectiveProfile.protocol == ProtocolFamily.MOONDROP_COMTURE ||
            (entry.effectiveProfile.protocol == ProtocolFamily.MOONDROP_SPV && entry.effectiveProfile.productId == 0x012F)
        return if (entry.effectiveProfile.transport == TransportType.USB_SERIAL && entry.descriptor.hasCdcInterface) {
            AndroidUsbSerialTransport.open(manager, entry.device, entry.descriptor, logSink = ::log)
                ?: AndroidUsbHidTransport.open(manager, entry.device, entry.descriptor, allowControlOnly = allowEp0, preferControlOnly = preferEp0Only, logSink = ::log)
        } else {
            AndroidUsbHidTransport.open(manager, entry.device, entry.descriptor, allowControlOnly = allowEp0, preferControlOnly = preferEp0Only, logSink = ::log)
                ?: if (entry.descriptor.hasCdcInterface) {
                    AndroidUsbSerialTransport.open(manager, entry.device, entry.descriptor, logSink = ::log)
                } else null
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun log(message: String) {
        android.util.Log.d("dongleCTRL", message)
        val line = "${timeFormat.format(Date())}  $message"
        _logs.value = (_logs.value + line).takeLast(120)
    }

    private fun buildEntry(manager: UsbManager, device: UsbDevice): DetectedUsbDac {
        var hasHid = false
        var hasCdc = false
        var hasAudio = false
        var acIntfIdx = 0
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_HID -> hasHid = true
                UsbConstants.USB_CLASS_COMM, UsbConstants.USB_CLASS_CDC_DATA -> hasCdc = true
                UsbConstants.USB_CLASS_AUDIO -> {
                    hasAudio = true
                    if (intf.interfaceSubclass == 1) acIntfIdx = intf.id
                }
            }
        }
        val desc = UsbDeviceDescriptor(
            deviceKey = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            productName = device.productName,
            manufacturerName = device.manufacturerName,
            hasHidInterface = hasHid,
            hasCdcInterface = hasCdc,
            acInterfaceIndex = acIntfIdx
        )

        val matched = catalog.match(desc)
        val overrideProto = prefsStore.getProtocolOverride(device.vendorId, device.productId)
        val effective = when {
            overrideProto != null -> catalog.genericProfileFor(desc, overrideProto)
            matched != null -> matched
            else -> catalog.genericProfileFor(desc, ProtocolFamily.CB)
        }

        // Only surface devices that matched the DAC catalog OR are USB Audio + HID/CDC devices
        val isCandidate = matched != null || (hasAudio && (hasHid || hasCdc))
        return DetectedUsbDac(
            device = device,
            descriptor = desc,
            matchedProfile = matched,
            effectiveProfile = effective,
            hasPermission = manager.hasPermission(device),
            isSupportedCandidate = isCandidate
        )
    }

    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.suprcharg3d.dongleCTRL.USB_PERMISSION"
    }
}
