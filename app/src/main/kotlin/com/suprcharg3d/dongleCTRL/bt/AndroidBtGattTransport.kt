package com.suprcharg3d.dongleCTRL.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.suprcharg3d.dongleCTRL.protocol.DacTransport
import com.suprcharg3d.dongleCTRL.protocol.TransportType
import com.suprcharg3d.dongleCTRL.protocol.UsbDeviceDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android BLE GATT transport supporting FiiO GAIA, Qualcomm GAIA, and FiiO custom GATT profiles (`z9/b.java`).
 */
@SuppressLint("MissingPermission")
class AndroidBtGattTransport private constructor(
    override val descriptor: UsbDeviceDescriptor,
    private val gatt: BluetoothGatt,
    private val writeChar: BluetoothGattCharacteristic,
    private val logSink: (String) -> Unit
) : DacTransport {

    override val transportType: TransportType = TransportType.BLUETOOTH_GATT
    private val writeMutex = Mutex()
    private val rxQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val activeReceivers = AtomicInteger(0)
    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingFrames: Flow<ByteArray> = _incomingFrames.asSharedFlow()

    @Volatile
    private var open = true

    @Volatile
    private var pendingWriteAck: CompletableDeferred<Boolean>? = null

    override val isConnected: Boolean
        get() = open

    private fun onNotify(bytes: ByteArray) {
        if (!open) return
        val copy = bytes.copyOf()
        logSink("BLE RX[${copy.size}]: ${copy.take(16).joinToString(" ") { "%02X".format(it) }}")
        if (activeReceivers.get() > 0) {
            rxQueue.trySend(copy)
        } else {
            _incomingFrames.tryEmit(copy)
        }
    }

    private fun onWriteDone(status: Int) {
        pendingWriteAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
    }

    private fun onDisconnected() {
        open = false
        pendingWriteAck?.complete(false)
        rxQueue.close()
    }

    override suspend fun send(data: ByteArray, timeoutMs: Int): Boolean = writeMutex.withLock {
        if (!open) return false
        val ack = CompletableDeferred<Boolean>()
        pendingWriteAck = ack
        val writeType = if ((writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
            (writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(writeChar, data, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                writeChar.writeType = writeType
                writeChar.value = data
                gatt.writeCharacteristic(writeChar)
            }
        }
        if (!started) {
            pendingWriteAck = null
            logSink("BLE TX failed to enqueue (${data.size}B)")
            return false
        }
        val ok = withTimeoutOrNull(timeoutMs.toLong()) { ack.await() } ?: false
        pendingWriteAck = null
        logSink("BLE TX[${data.size}] ${if (ok) "OK" else "ERR"}: ${data.take(16).joinToString(" ") { "%02X".format(it) }}")
        return ok
    }

    override suspend fun receive(expectedSize: Int, timeoutMs: Int): ByteArray? {
        activeReceivers.incrementAndGet()
        return try {
            withTimeoutOrNull(timeoutMs.toLong()) {
                rxQueue.receiveCatching().getOrNull()
            }
        } finally {
            activeReceivers.decrementAndGet()
        }
    }

    override suspend fun drainInput(maxPackets: Int) {
        repeat(maxPackets) {
            rxQueue.tryReceive().getOrNull() ?: return
        }
    }

    override fun close() {
        if (!open) return
        open = false
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        rxQueue.close()
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Known (Service, Write, Notify) UUID profiles:
        // - FiiO Control z9/b.java (GAIA, Universal)
        // - NICEHCK OriG in (y80.java)
        // - Moondrop bleSource (j30.java), Airoha Race (xk5/pc2), Jieli RCSP (i40/u00), Wireless Mic (xw5)
        private val KNOWN_PROFILES = listOf(
            Triple(
                UUID.fromString("00001100-04a5-1000-1000-40ed981a04a5"),
                UUID.fromString("00001101-04a5-1000-1000-40ed981a04a5"),
                UUID.fromString("00001102-04a5-1000-1000-40ed981a04a5")
            ),
            Triple(
                UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5"),
                UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5"),
                UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")
            ),
            Triple(
                UUID.fromString("0000ff06-0000-1000-8000-00805f9b34fb"),
                UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb"),
                UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")
            ),
            Triple(
                UUID.fromString("00001C00-D102-11E1-9B23-000EFB0000A9"),
                UUID.fromString("00001C01-D102-11E1-9B23-000EFB0000A9"),
                UUID.fromString("00001C0F-D102-11E1-9B23-000EFB0000A9")
            ),
            // NICEHCK OriG in ("NHCKCTRL")
            Triple(
                UUID.fromString("0000a100-1000-8000-4e48-434b4354524c"),
                UUID.fromString("0000a100-1001-8000-4e48-434b4354524c"),
                UUID.fromString("0000a100-1002-8000-4e48-434b4354524c")
            ),
            // Moondrop bleSource
            Triple(
                UUID.fromString("9ECA0000-7F3A-4F32-9A38-A91B2C6E0100"),
                UUID.fromString("9ECA0001-7F3A-4F32-9A38-A91B2C6E0100"),
                UUID.fromString("9ECA0003-7F3A-4F32-9A38-A91B2C6E0100")
            ),
            // Airoha Race
            Triple(
                UUID.fromString("5052494D-2DAB-0341-6972-6F6861424C45"),
                UUID.fromString("43484152-2DAB-3241-6972-6F6861424C45"),
                UUID.fromString("43484152-2DAB-3141-6972-6F6861424C45")
            ),
            // Jieli RCSP
            Triple(
                UUID.fromString("0000ae00-0000-1000-8000-00805F9B34FB"),
                UUID.fromString("0000ae01-0000-1000-8000-00805F9B34FB"),
                UUID.fromString("0000ae02-0000-1000-8000-00805F9B34FB")
            ),
            // Moondrop Wireless Mic
            Triple(
                UUID.fromString("8af0b4d0-8d24-4b6b-8c14-47c81a400000"),
                UUID.fromString("8af0b4d0-8d24-4b6b-8c14-47c81a400001"),
                UUID.fromString("8af0b4d0-8d24-4b6b-8c14-47c81a400002")
            )
        )

        suspend fun open(
            context: Context,
            device: BluetoothDevice,
            descriptor: UsbDeviceDescriptor,
            logSink: (String) -> Unit
        ): AndroidBtGattTransport? {
            val ready = CompletableDeferred<AndroidBtGattTransport?>()
            var holder: AndroidBtGattTransport? = null
            var notifyAck: CompletableDeferred<Boolean>? = null

            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS && holder == null) {
                        logSink("BLE connect failed status=$status")
                        runCatching { gatt.close() }
                        ready.complete(null)
                        return
                    }
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            logSink("BLE GATT connected to ${device.name ?: device.address}, discovering services...")
                            gatt.requestMtu(512)
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            holder?.onDisconnected()
                            if (!ready.isCompleted) ready.complete(null)
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        ready.complete(null)
                        return
                    }
                    val pair = findWriteAndNotifyChars(gatt)
                    if (pair == null) {
                        logSink("BLE: no matching FiiO/GAIA GATT characteristic found")
                        runCatching { gatt.disconnect(); gatt.close() }
                        ready.complete(null)
                        return
                    }
                    val (wChar, nChar) = pair
                    val tx = AndroidBtGattTransport(descriptor, gatt, wChar, logSink)
                    holder = tx
                    gatt.setCharacteristicNotification(nChar, true)
                    val cccd = nChar.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        notifyAck = CompletableDeferred()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            run {
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(cccd)
                            }
                        }
                    } else {
                        ready.complete(tx)
                    }
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    notifyAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
                    val tx = holder
                    if (!ready.isCompleted) {
                        ready.complete(tx)
                    }
                }

                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: return
                    holder?.onNotify(bytes)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    holder?.onNotify(value)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    holder?.onWriteDone(status)
                }
            }

            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            val result = withTimeoutOrNull(9_000L) { ready.await() }
            if (result == null) {
                holder?.close()
                logSink("BLE GATT connection timed out for ${device.address}")
            }
            return result
        }

        private fun findWriteAndNotifyChars(
            gatt: BluetoothGatt
        ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            val notifyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            for ((svcUuid, wUuid, nUuid) in KNOWN_PROFILES) {
                val svc = gatt.getService(svcUuid) ?: continue
                val wRaw = svc.getCharacteristic(wUuid)
                val nRaw = svc.getCharacteristic(nUuid)
                val w = wRaw?.takeIf { (it.properties and writeMask) != 0 }
                    ?: svc.characteristics.firstOrNull { (it.properties and writeMask) != 0 }
                    ?: continue
                val n = nRaw?.takeIf { (it.properties and notifyMask) != 0 }
                    ?: svc.characteristics.firstOrNull { (it.properties and notifyMask) != 0 }
                    ?: w
                return w to n
            }
            // Generic fallback: any non-standard service offering both writable and notifiable characteristics
            for (svc in gatt.services) {
                val uuidStr = svc.uuid.toString().lowercase()
                if (uuidStr.startsWith("00001800") || uuidStr.startsWith("00001801") || uuidStr.startsWith("0000180a")) continue
                val w = svc.characteristics.firstOrNull {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                } ?: continue
                val n = svc.characteristics.firstOrNull {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                } ?: w
                return w to n
            }
            return null
        }
    }
}
