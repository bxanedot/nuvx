
package com.bxane.nuvx.playback.audio.bitperfect.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectDriver
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectTransport
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectTransportException
import java.nio.ByteBuffer

internal object UsbDirectNative {
    init {
        System.loadLibrary("nuvx_usb")
    }

    external fun open(fd: Int, candidates: IntArray): Long
    external fun chosen(handle: Long): Int
    external fun capacity(handle: Long): Int
    external fun write(handle: Long, data: ByteBuffer, offset: Int, count: Int): Int
    external fun setPlaying(handle: Long, playing: Boolean)
    external fun flush(handle: Long)
    external fun frames(handle: Long): Long
    external fun control(handle: Long, input: Boolean, request: Int, value: Int, index: Int, data: ByteArray): ByteArray
    external fun close(handle: Long)

    const val CANDIDATE_INTS = 17

    fun encode(plans: List<UsbDescriptors.Plan>): IntArray = plans.flatMap { plan ->
        val a = plan.alternate
        val f = plan.format
        listOf(
            a.control, a.interface_, a.alternate, if (a.uac2) 1 else 0, a.clock,
            plan.data.address, plan.data.attributes, plan.data.maxPacket, plan.data.interval,
            if (plan.data.rateControl) 1 else 0,
            plan.feedback?.address ?: -1, plan.feedback?.maxPacket ?: 0,
            f.sampleRate, f.channels, f.subslot, f.bits, f.encoding.code,
        )
    }.toIntArray()
}

internal class UsbDirectTransport private constructor(
    private val connection: UsbDeviceConnection,
    private var handle: Long,
    val usbDeviceId: Int,
    override val deviceName: String,
    private val format: UsbDescriptors.OutputFormat,
    private val volumeControl: UsbVolume.Volume?,
) : BitPerfectTransport {

    override val driver = BitPerfectDriver.USB_DIRECT
    override val sampleRate = format.sampleRate
    override val channelCount = format.channels

    override val outputBits = format.subslot * 8
    override val validBits = format.bits
    override val bufferSizeFrames: Long = UsbDirectNative.capacity(handle).toLong()

    @Volatile private var failure: String? = null
    private var lastFrames = 0L

    private val control = UsbControl { input, request, value, index, data ->
        val reply = UsbDirectNative.control(handle, input, request, value, index, data)
        if (input) reply.copyInto(data)
    }

    val volume: UsbVolume.Snapshot? get() = volumeControl?.snapshot

    fun setVolume(db: Double): UsbVolume.Snapshot {
        val volume = volumeControl ?: throw IllegalStateException("USB hardware volume unavailable")
        return synchronized(volume) { volume.set(control, db) }
    }

    fun markDetached() {
        failure = "USB disconnected"
    }

    override fun write(buffer: ByteBuffer): Int {
        val count = call { UsbDirectNative.write(handle, buffer, buffer.position(), minOf(buffer.remaining(), MAX_WRITE)) }
        buffer.position(buffer.position() + count)
        return count
    }

    override fun play() = call { UsbDirectNative.setPlaying(handle, true) }

    override fun pause() = call { UsbDirectNative.setPlaying(handle, false) }

    override fun flush() {
        call { UsbDirectNative.flush(handle) }
        lastFrames = 0
    }

    override fun stop() = Unit

    override fun playedFrames(): Long {
        if (handle == 0L || failure != null) return lastFrames
        lastFrames = maxOf(lastFrames, runCatching { UsbDirectNative.frames(handle) }.getOrDefault(lastFrames))
        return lastFrames
    }

    override fun release() {
        if (handle != 0L && failure == null) {
            volumeControl?.let { volume -> runCatching { synchronized(volume) { volume.restore(control) } } }
        }
        if (handle != 0L) UsbDirectNative.close(handle)
        handle = 0
        runCatching { connection.close() }
    }

    private inline fun <T> call(block: () -> T): T {
        failure?.let { throw BitPerfectTransportException(it) }
        return try {
            block()
        } catch (error: IllegalStateException) {
            failure = error.message ?: "USB output failed"
            throw BitPerfectTransportException(failure!!, error)
        }
    }

    companion object {
        private const val MAX_WRITE = 256 * 1024

        fun open(
            manager: UsbManager,
            device: UsbDevice,
            rate: Int,
            channels: Int,
            sourceBits: Int,
            convert: Boolean,
            allowFixedVolume: Boolean,
            rememberedDb: Double?,
        ): UsbDirectTransport {
            val connection = manager.openDevice(device) ?: throw BitPerfectTransportException("cannot_open_usb")
            var handle = 0L
            try {
                val raw = connection.rawDescriptors ?: throw BitPerfectTransportException("no_descriptors")
                val plans = plans(raw, rate, channels, sourceBits, convert)
                if (plans.isEmpty()) throw BitPerfectTransportException("format")
                handle = try {
                    UsbDirectNative.open(connection.fileDescriptor, UsbDirectNative.encode(plans))
                } catch (error: IllegalStateException) {
                    throw BitPerfectTransportException("unsupported", error)
                }
                val plan = plans[UsbDirectNative.chosen(handle)]
                val opened = handle
                val control = UsbControl { input, request, value, index, data ->
                    val reply = UsbDirectNative.control(opened, input, request, value, index, data)
                    if (input) reply.copyInto(data)
                }
                val volume = UsbVolume.feature(raw, plan.alternate.control, plan.alternate.terminal, channels)
                    ?.let { feature -> runCatching { UsbVolume.Volume.open(control, feature) }.getOrNull() }
                if (volume == null && !allowFixedVolume) throw BitPerfectTransportException("volume_unavailable")
                if (volume != null && rememberedDb != null) {
                    runCatching { volume.set(control, minOf(rememberedDb, volume.snapshot.restoreLimitDb)) }
                }
                return UsbDirectTransport(
                    connection = connection,
                    handle = handle,
                    usbDeviceId = device.deviceId,
                    deviceName = device.productName?.takeIf { it.isNotBlank() } ?: "USB audio",
                    format = plan.format,
                    volumeControl = volume,
                )
            } catch (error: Exception) {
                if (handle != 0L) UsbDirectNative.close(handle)
                runCatching { connection.close() }
                throw error as? BitPerfectTransportException ?: BitPerfectTransportException("unsupported", error)
            }
        }

        fun plans(raw: ByteArray, rate: Int, channels: Int, sourceBits: Int, convert: Boolean): List<UsbDescriptors.Plan> {
            val device = try {
                UsbDescriptors.parse(raw)
            } catch (_: UsbDescriptors.MalformedDescriptorException) {
                return emptyList()
            }
            return UsbDescriptors.dacFormats(device, rate, channels, sourceBits, convert)
                .mapNotNull { (alternate, format) -> UsbDescriptors.plan(alternate, format) }
                .take(32)
        }
    }
}
