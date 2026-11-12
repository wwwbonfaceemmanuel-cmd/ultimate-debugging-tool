package com.ultimate.debugging.ultimate_debugging_tool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * USB bulk transport for Android Debug Bridge.
 *
 * Reading uses an asynchronous USB request that waits for as long as
 * necessary. A timed bulk read would report "no data yet" as an error
 * and could discard bytes that arrive just as the timer expires, which
 * is what made idle USB sessions drop.
 *
 * The ADB packet framing itself lives in AdbUsbPacketCodec.
 */
class AdbUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val interfaceId: Int
) : AdbTransport {

    companion object {
        private const val WRITE_TIMEOUT_MS = 10000
        private const val CLOSE_WAIT_MS = 500L

        const val ADB_CLASS = 0xFF
        const val ADB_SUBCLASS = 0x42
        const val ADB_PROTOCOL = 0x01
    }

    @Volatile
    private var connection: UsbDeviceConnection? = null

    private var adbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    private var readRequest: UsbRequest? = null
    private var codec: AdbUsbPacketCodec? = null

    @Volatile
    private var closed = true

    private val readLock = ReentrantLock()
    private val writeLock = Any()

    // Guards the hand-over between "queue a read" and "cancel on close".
    private val requestLock = Any()
    private var readInFlight = false

    /** Opens the device and claims its ADB interface. */
    @Synchronized
    @Throws(IOException::class)
    override fun open() {
        if (connection != null) {
            throw IOException("USB transport is already open.")
        }

        if (!usbManager.hasPermission(device)) {
            throw IOException(
                "USB permission has not been granted for this device."
            )
        }

        val selectedInterface = findAdbInterface()

        val inputEndpoint = findBulkEndpoint(
            selectedInterface,
            UsbConstants.USB_DIR_IN
        )

        val outputEndpoint = findBulkEndpoint(
            selectedInterface,
            UsbConstants.USB_DIR_OUT
        )

        val usbConnection = usbManager.openDevice(device)
            ?: throw IOException("Unable to open the USB device.")

        var claimed = false

        try {
            if (!usbConnection.claimInterface(selectedInterface, true)) {
                throw IOException(
                    "Unable to claim the ADB USB interface. Another app " +
                        "may be using it."
                )
            }

            claimed = true

            val request = UsbRequest()

            if (!request.initialize(usbConnection, inputEndpoint)) {
                throw IOException("Unable to prepare USB reading.")
            }

            adbInterface = selectedInterface
            bulkInEndpoint = inputEndpoint
            bulkOutEndpoint = outputEndpoint
            readRequest = request
            codec = AdbUsbPacketCodec(BulkChannel(usbConnection))

            synchronized(requestLock) {
                readInFlight = false
                closed = false
            }

            connection = usbConnection

        } catch (error: Exception) {
            if (claimed) {
                try {
                    usbConnection.releaseInterface(selectedInterface)
                } catch (_: Exception) {
                    // Continue with connection cleanup.
                }
            }

            usbConnection.close()

            if (error is IOException) throw error

            throw IOException(
                error.message ?: "Unable to open the USB device.",
                error
            )
        }
    }

    private fun findAdbInterface(): UsbInterface {
        val selected = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { usbInterface ->
                usbInterface.id == interfaceId &&
                    usbInterface.interfaceClass == ADB_CLASS &&
                    usbInterface.interfaceSubclass == ADB_SUBCLASS &&
                    usbInterface.interfaceProtocol == ADB_PROTOCOL
            }

        return selected ?: throw IOException(
            "The requested standard ADB interface was not found."
        )
    }

    private fun findBulkEndpoint(
        usbInterface: UsbInterface,
        direction: Int
    ): UsbEndpoint {
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)

            if (
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                endpoint.direction == direction
            ) {
                return endpoint
            }
        }

        throw IOException("Required USB bulk endpoint was not found.")
    }

    /** Raw USB access used by the packet codec. */
    private inner class BulkChannel(
        private val usbConnection: UsbDeviceConnection
    ) : UsbBulkChannel {

        override val maxPacketSize: Int
            get() = bulkOutEndpoint?.maxPacketSize ?: 0

        override fun write(data: ByteArray, offset: Int, length: Int) {
            val endpoint = bulkOutEndpoint
                ?: throw IOException("USB transport is closed.")

            var position = offset
            var remaining = length

            do {
                if (closed) throw IOException("USB transport is closed.")

                val written = usbConnection.bulkTransfer(
                    endpoint,
                    data,
                    position,
                    remaining,
                    WRITE_TIMEOUT_MS
                )

                if (written < 0 || (written == 0 && remaining > 0)) {
                    throw IOException(
                        "USB write failed. The device may have been " +
                            "disconnected."
                    )
                }

                position += written
                remaining -= written
            } while (remaining > 0)
        }

        @Suppress("DEPRECATION")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val request = readRequest
                ?: throw IOException("USB transport is closed.")

            val target = ByteBuffer.allocate(length)

            synchronized(requestLock) {
                if (closed) throw IOException("USB transport is closed.")

                val queued = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ) {
                    request.queue(target)
                } else {
                    request.queue(target, length)
                }

                if (!queued) {
                    throw IOException(
                        "USB read failed. The device may have been " +
                            "disconnected."
                    )
                }

                readInFlight = true
            }

            try {
                while (true) {
                    val finished = usbConnection.requestWait()

                    if (finished == null) {
                        throw IOException(
                            if (closed) {
                                "USB transport is closed."
                            } else {
                                "USB read failed. The device may have " +
                                    "been disconnected."
                            }
                        )
                    }

                    if (finished === request) break
                }
            } finally {
                synchronized(requestLock) {
                    readInFlight = false
                }
            }

            if (closed) throw IOException("USB transport is closed.")

            // Before Android 8 the platform does not report how many
            // bytes arrived. ADB always sends exactly what was asked for.
            val received = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {
                target.position()
            } else {
                length
            }

            if (received > 0) {
                System.arraycopy(
                    target.array(),
                    target.arrayOffset(),
                    buffer,
                    offset,
                    received
                )
            }

            return received
        }
    }

    /** Sends one complete ADB packet. */
    @Throws(IOException::class)
    override fun writePacket(packet: AdbProtocol.AdbPacket) {
        synchronized(writeLock) {
            val activeCodec = codec

            if (closed || activeCodec == null) {
                throw IOException("USB transport is closed.")
            }

            activeCodec.write(packet)
        }
    }

    /** Waits for and returns one complete ADB packet. */
    @Throws(IOException::class)
    override fun readPacket(): AdbProtocol.AdbPacket {
        readLock.lock()

        try {
            val activeCodec = codec

            if (closed || activeCodec == null) {
                throw IOException("USB transport is closed.")
            }

            return activeCodec.read()
        } finally {
            readLock.unlock()
        }
    }

    /** Releases the interface and closes the connection. */
    @Synchronized
    override fun close() {
        val request: UsbRequest?

        synchronized(requestLock) {
            if (closed) return
            closed = true

            request = readRequest

            if (readInFlight) {
                try {
                    request?.cancel()
                } catch (_: Exception) {
                    // Releasing the interface also stops the read.
                }
            }
        }

        val usbConnection = connection
        val selectedInterface = adbInterface

        connection = null

        if (usbConnection != null && selectedInterface != null) {
            try {
                usbConnection.releaseInterface(selectedInterface)
            } catch (_: Exception) {
                // Continue closing the USB connection.
            }
        }

        // Give a blocked reader a moment to leave before the device
        // handle is closed underneath it.
        var readerLeft = false

        try {
            readerLeft = readLock.tryLock(
                CLOSE_WAIT_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try {
            usbConnection?.close()
        } catch (_: Exception) {
            // Resources are being released.
        } finally {
            if (readerLeft) readLock.unlock()
        }

        adbInterface = null
        bulkInEndpoint = null
        bulkOutEndpoint = null
        codec = null
        // The read request is released by the platform once the reader
        // thread no longer references it.
        readRequest = null
    }
}
