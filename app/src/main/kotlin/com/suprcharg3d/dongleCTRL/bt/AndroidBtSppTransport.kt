package com.suprcharg3d.dongleCTRL.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.suprcharg3d.dongleCTRL.protocol.DacTransport
import com.suprcharg3d.dongleCTRL.protocol.TransportType
import com.suprcharg3d.dongleCTRL.protocol.UsbDeviceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class SppFramingMode {
    FIIO_DEFAULT,
    BLUETRUM_V2,
    ORIGIN_V1,
    RAW_CHUNK
}

/**
 * Android Classic Bluetooth SPP (RFCOMM) transport supporting FiiO GAIA/Universal/RCSP,
 * Bluetrum v1/v2, BES, and raw RFCOMM packet streams.
 */
@SuppressLint("MissingPermission")
class AndroidBtSppTransport private constructor(
    override val descriptor: UsbDeviceDescriptor,
    private val socket: BluetoothSocket,
    private val input: InputStream,
    private val output: OutputStream,
    private val framingMode: SppFramingMode,
    private val logSink: (String) -> Unit
) : DacTransport {

    override val transportType: TransportType = TransportType.BLUETOOTH_SPP
    private val writeMutex = Mutex()
    private val rxFrames = Channel<ByteArray>(Channel.UNLIMITED)
    private val activeReceivers = AtomicInteger(0)
    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingFrames: Flow<ByteArray> = _incomingFrames.asSharedFlow()

    @Volatile
    private var open = true

    private val readerThread = Thread({
        val chunk = ByteArray(1024)
        val acc = ByteArrayOutputStream()
        try {
            while (open) {
                val n = input.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                acc.write(chunk, 0, n)
                val extracted = extractCompleteFrames(acc)
                for (frame in extracted) {
                    logSink("BT-SPP RX[${frame.size}]: ${frame.take(16).joinToString(" ") { "%02X".format(it) }}")
                    _incomingFrames.tryEmit(frame)
                    if (activeReceivers.get() > 0) {
                        rxFrames.trySend(frame)
                    }
                }
            }
        } catch (e: Exception) {
            if (open) logSink("BT-SPP reader stopped: ${e.message}")
        } finally {
            open = false
            rxFrames.close()
        }
    }, "donglectrl-bt-spp-rx").apply {
        isDaemon = true
        start()
    }

    override val isConnected: Boolean
        get() = open && socket.isConnected

    override suspend fun send(data: ByteArray, timeoutMs: Int): Boolean = writeMutex.withLock {
        if (!open) return false
        return withContext(Dispatchers.IO) {
            try {
                output.write(data)
                output.flush()
                logSink("BT-SPP TX[${data.size}]: ${data.take(16).joinToString(" ") { "%02X".format(it) }}")
                true
            } catch (e: Exception) {
                logSink("BT-SPP TX error: ${e.message}")
                false
            }
        }
    }

    override suspend fun receive(expectedSize: Int, timeoutMs: Int): ByteArray? {
        activeReceivers.incrementAndGet()
        return try {
            withTimeoutOrNull(timeoutMs.toLong()) {
                rxFrames.receiveCatching().getOrNull()
            }
        } finally {
            activeReceivers.decrementAndGet()
        }
    }

    override suspend fun transceive(
        request: ByteArray,
        expectedSize: Int,
        timeoutMs: Int
    ): ByteArray? {
        activeReceivers.incrementAndGet()
        return try {
            if (!send(request, timeoutMs)) null
            else withTimeoutOrNull(timeoutMs.toLong()) {
                rxFrames.receiveCatching().getOrNull()
            }
        } finally {
            activeReceivers.decrementAndGet()
        }
    }

    override suspend fun drainInput(maxPackets: Int) {
        repeat(maxPackets) {
            rxFrames.tryReceive().getOrNull() ?: return
        }
    }

    override fun close() {
        if (!open) return
        open = false
        runCatching { readerThread.interrupt() }
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
        rxFrames.close()
    }

    private fun extractCompleteFrames(acc: ByteArrayOutputStream): List<ByteArray> = when (framingMode) {
        SppFramingMode.RAW_CHUNK -> {
            val out = if (acc.size() > 0) listOf(acc.toByteArray()) else emptyList()
            acc.reset()
            out
        }
        SppFramingMode.ORIGIN_V1 -> extractOriginV1Frames(acc)
        SppFramingMode.BLUETRUM_V2 -> extractBluetrumV2Frames(acc)
        SppFramingMode.FIIO_DEFAULT -> extractFiioFrames(acc)
    }

    private fun extractOriginV1Frames(acc: ByteArrayOutputStream): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val buf = acc.toByteArray()
        var pos = 0
        while (pos < buf.size) {
            val b0 = buf[pos].toInt() and 0xFF
            if (b0 == 0x4E) {
                if (pos + 6 > buf.size) break
                val len = (buf[pos + 1].toInt() and 0xFF) or ((buf[pos + 2].toInt() and 0xFF) shl 8)
                val hasCrc = buf[pos + 3] != 0.toByte()
                val frameLen = 3 + len + if (hasCrc) 2 else 0
                if (len < 3 || frameLen > 512) {
                    pos++
                    continue
                }
                if (pos + frameLen > buf.size) break
                out += buf.copyOfRange(pos, pos + frameLen)
                pos += frameLen
                continue
            }
            val next = (pos + 1 until buf.size).firstOrNull { (buf[it].toInt() and 0xFF) == 0x4E }
            if (next != null) {
                pos = next
            } else {
                pos = buf.size
                break
            }
        }
        acc.reset()
        if (pos < buf.size) {
            acc.write(buf, pos, buf.size - pos)
        }
        return out
    }

    private fun extractBluetrumV2Frames(acc: ByteArrayOutputStream): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val buf = acc.toByteArray()
        var pos = 0
        while (pos < buf.size) {
            val b0 = buf[pos].toInt() and 0xFF
            val b1 = if (pos + 1 < buf.size) (buf[pos + 1].toInt() and 0xFF) else -1
            if (b0 == 0xFF && (b1 == 0xA1 || b1 == 0xA2)) {
                if (pos + 4 > buf.size) break
                val pLen = buf[pos + 2].toInt() and 0xFF
                val frameLen = 4 + pLen
                if (frameLen > 260) {
                    pos++
                    continue
                }
                if (pos + frameLen > buf.size) break
                if ((buf[pos + frameLen - 1].toInt() and 0xFF) == 0xAA) {
                    out += buf.copyOfRange(pos, pos + frameLen)
                    pos += frameLen
                    continue
                }
            }
            val next = (pos + 1 until buf.size - 1).firstOrNull { i ->
                val nb0 = buf[i].toInt() and 0xFF
                val nb1 = buf[i + 1].toInt() and 0xFF
                nb0 == 0xFF && (nb1 == 0xA1 || nb1 == 0xA2)
            }
            if (next != null) {
                pos = next
            } else {
                break
            }
        }
        acc.reset()
        if (pos < buf.size) {
            acc.write(buf, pos, buf.size - pos)
        }
        return out
    }

    private fun extractFiioFrames(acc: ByteArrayOutputStream): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var buf = acc.toByteArray()
        var pos = 0

        while (pos < buf.size) {
            val b0 = buf[pos].toInt() and 0xFF
            // 1. GAIA V1 / V3 frame: [0xFF, ver(1|3), flags, pLen, ... 8+pLen+(flags&1)]
            if (b0 == 0xFF && pos + 1 < buf.size) {
                val ver = buf[pos + 1].toInt() and 0xFF
                if (ver == 0x01 || ver == 0x03) {
                    if (pos + 4 > buf.size) break
                    val flags = buf[pos + 2].toInt() and 0xFF
                    val pLen = buf[pos + 3].toInt() and 0xFF
                    val frameLen = 8 + pLen + if ((flags and 1) != 0) 1 else 0
                    if (frameLen > 272) {
                        pos++
                        continue
                    }
                    if (pos + frameLen > buf.size) break
                    out += buf.copyOfRange(pos, pos + frameLen)
                    pos += frameLen
                    continue
                }
            }
            // 2. Universal frame: [0xF1, 0x10, dir, totalLen, ..., 0xFF]
            if (b0 == 0xF1 && pos + 1 < buf.size && (buf[pos + 1].toInt() and 0xFF) == 0x10) {
                if (pos + 4 > buf.size) break
                val frameLen = buf[pos + 3].toInt() and 0xFF
                if (frameLen !in 8..264) {
                    pos++
                    continue
                }
                if (pos + frameLen > buf.size) break
                if ((buf[pos + frameLen - 1].toInt() and 0xFF) == 0xFF) {
                    out += buf.copyOfRange(pos, pos + frameLen)
                    pos += frameLen
                    continue
                }
            }
            // 3. RCSP frame: [0xFE, 0xDC, 0xBA, flags, op, lenHi, lenLo, ..., 0xEF]
            if (b0 == 0xFE && pos + 2 < buf.size &&
                (buf[pos + 1].toInt() and 0xFF) == 0xDC &&
                (buf[pos + 2].toInt() and 0xFF) == 0xBA
            ) {
                if (pos + 7 > buf.size) break
                val pLen = ((buf[pos + 5].toInt() and 0xFF) shl 8) or (buf[pos + 6].toInt() and 0xFF)
                val frameLen = 8 + pLen
                if (frameLen > 512) {
                    pos++
                    continue
                }
                if (pos + frameLen > buf.size) break
                out += buf.copyOfRange(pos, pos + frameLen)
                pos += frameLen
                continue
            }

            // Resync to next known frame start or emit unframed chunk if none appears
            val nextSync = findNextFrameStart(buf, pos + 1)
            if (nextSync > pos) {
                pos = nextSync
            } else {
                break
            }
        }

        acc.reset()
        if (pos < buf.size) {
            acc.write(buf, pos, buf.size - pos)
        }
        return out
    }

    private fun findNextFrameStart(buf: ByteArray, from: Int): Int {
        for (i in from until buf.size - 1) {
            val b0 = buf[i].toInt() and 0xFF
            val b1 = buf[i + 1].toInt() and 0xFF
            if ((b0 == 0xFF && (b1 == 0x01 || b1 == 0x03)) ||
                (b0 == 0xF1 && b1 == 0x10) ||
                (b0 == 0xFE && b1 == 0xDC)
            ) {
                return i
            }
        }
        return -1
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        suspend fun open(
            adapter: BluetoothAdapter,
            device: BluetoothDevice,
            descriptor: UsbDeviceDescriptor,
            logSink: (String) -> Unit,
            sppUuid: UUID = SPP_UUID,
            framingMode: SppFramingMode = SppFramingMode.FIIO_DEFAULT
        ): AndroidBtSppTransport? = withContext(Dispatchers.IO) {
            runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }

            val attempts: List<() -> BluetoothSocket?> = listOf(
                { runCatching { device.createInsecureRfcommSocketToServiceRecord(sppUuid) }.getOrNull() },
                { runCatching { device.createRfcommSocketToServiceRecord(sppUuid) }.getOrNull() },
                {
                    runCatching {
                        device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            .invoke(device, 1) as? BluetoothSocket
                    }.getOrNull()
                }
            )

            for ((idx, creator) in attempts.withIndex()) {
                val sock = creator() ?: continue
                try {
                    sock.connect()
                    logSink("BT-SPP connected to ${device.name ?: device.address} (attempt ${idx + 1})")
                    return@withContext AndroidBtSppTransport(
                        descriptor = descriptor,
                        socket = sock,
                        input = sock.inputStream,
                        output = sock.outputStream,
                        framingMode = framingMode,
                        logSink = logSink
                    )
                } catch (e: Exception) {
                    logSink("BT-SPP attempt ${idx + 1} failed: ${e.message}")
                    runCatching { sock.close() }
                }
            }
            null
        }
    }
}
