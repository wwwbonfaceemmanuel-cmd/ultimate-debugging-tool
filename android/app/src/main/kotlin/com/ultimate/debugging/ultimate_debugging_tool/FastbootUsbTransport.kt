package com.ultimate.debugging.ultimate_debugging_tool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * The USB side of fastboot: this phone acting as the host for another
 * phone that sits in its bootloader.
 *
 * A bootloader answers in small packets, so plain bulk transfers are
 * enough here; the reading side is retried a few times because a
 * bootloader may take a moment to answer.
 */
class FastbootUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val interfaceId: Int
) : FastbootTransport {

    companion object {
        const val FASTBOOT_CLASS = 0xFF
        const val FASTBOOT_SUBCLASS = 0x42
        const val FASTBOOT_PROTOCOL = 0x03

        private const val READ_RETRIES = 2
        private const val MAX_WRITE_CHUNK = 4096
    }

    private var connection: UsbDeviceConnection? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null
    private var claimed: UsbInterface? = null

    private val writeLock = Any()

    val isOpen: Boolean
        get() = connection != null

    /** Claims the fastboot interface of the device. */
    @Synchronized
    @Throws(IOException::class)
    fun open() {
        if (connection != null) {
            throw IOException("The bootloader link is already open.")
        }

        if (!usbManager.hasPermission(device)) {
            throw IOException("USB permission has not been granted for this device.")
        }

        val usbInterface = findInterface()
            ?: throw IOException("This device is not offering fastboot.")

        val inEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_IN)
            ?: throw IOException("The bootloader interface has no input endpoint.")

        val outEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)
            ?: throw IOException("The bootloader interface has no output endpoint.")

        val usbConnection = usbManager.openDevice(device)
            ?: throw IOException("Unable to open the USB device.")

        if (!usbConnection.claimInterface(usbInterface, true)) {
            usbConnection.close()
            throw IOException(
                "Unable to claim the bootloader USB interface. Another app may be using it."
            )
        }

        connection = usbConnection
        input = inEndpoint
        output = outEndpoint
        claimed = usbInterface
    }

    override fun write(bytes: ByteArray, timeoutMs: Long) {
        val connection = connection
            ?: throw IOException("The bootloader link is closed.")
        val endpoint = output
            ?: throw IOException("The bootloader link is closed.")

        synchronized(writeLock) {
            var offset = 0

            while (offset < bytes.size) {
                val count = minOf(bytes.size - offset, MAX_WRITE_CHUNK)
                val sent = connection.bulkTransfer(
                    endpoint,
                    bytes,
                    offset,
                    count,
                    timeoutMs.toInt()
                )

                if (sent < 0) {
                    throw IOException("The phone did not accept the data. Check the cable.")
                }

                offset += sent
            }
        }
    }

    override fun read(maxBytes: Int, timeoutMs: Long): ByteArray {
        val connection = connection
            ?: throw IOException("The bootloader link is closed.")
        val endpoint = input
            ?: throw IOException("The bootloader link is closed.")

        val size = maxBytes.coerceIn(1, FastbootProtocol.MAX_PACKET)
        val buffer = ByteArray(size)

        var attempt = 0

        while (attempt <= READ_RETRIES) {
            val read = connection.bulkTransfer(endpoint, buffer, size, timeoutMs.toInt())

            if (read > 0) {
                return buffer.copyOf(read)
            }

            attempt++
        }

        return ByteArray(0)
    }

    override fun close() {
        val connection = connection ?: return

        try {
            claimed?.let { connection.releaseInterface(it) }
        } catch (ignored: Exception) {
            // The device may already be gone.
        }

        try {
            connection.close()
        } catch (ignored: Exception) {
            // Nothing useful can be done here.
        }

        this.connection = null
        input = null
        output = null
        claimed = null
    }

    private fun findInterface(): UsbInterface? {
        return (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.id == interfaceId &&
                    it.interfaceClass == FASTBOOT_CLASS &&
                    it.interfaceSubclass == FASTBOOT_SUBCLASS &&
                    it.interfaceProtocol == FASTBOOT_PROTOCOL
            }
    }

    private fun findEndpoint(
        usbInterface: UsbInterface,
        direction: Int
    ): UsbEndpoint? {
        return (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .firstOrNull {
                it.direction == direction &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }
    }
}
