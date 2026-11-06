
package com.ultimate.debugging.ultimate_debugging_tool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB bulk transport for Android Debug Bridge.
 *
 * Implements the shared AdbTransport interface so the
 * same ADB engine can communicate over USB and TCP.
 */
class AdbUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val interfaceId: Int
) : AdbTransport {

    companion object {
        private const val WRITE_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 1000
        private const val MAX_TRANSFER_CHUNK = 16384
    }

    @Volatile
    private var connection: UsbDeviceConnection? = null

    private var adbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null

    @Volatile
    private var interfaceClaimed = false

    @Volatile
    private var closed = true

    private val readLock = Any()
    private val writeLock = Any()

    /**
     * Opens the device and claims its ADB interface.
     */
    @Synchronized
    @Throws(IOException::class)
    override fun open() {
        if (connection != null) {
            throw IOException("USB transport is already open.")
        }

        if (!usbManager.hasPermission(device)) {
            throw SecurityException(
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
            ?: throw IOException("Unable to open USB device.")

        var claimed = false

        try {
            if (!usbConnection.claimInterface(selectedInterface, true)) {
                throw IOException(
                    "Unable to claim the ADB USB interface."
                )
            }

            claimed = true

            adbInterface = selectedInterface
            bulkInEndpoint = inputEndpoint
            bulkOutEndpoint = outputEndpoint
            interfaceClaimed = true
            closed = false
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
            throw error
        }
    }

    private fun findAdbInterface(): UsbInterface {
        val selected = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { usbInterface ->
                usbInterface.id == interfaceId &&
                    usbInterface.interfaceClass == 0xFF &&
                    usbInterface.interfaceSubclass == 0x42 &&
                    usbInterface.interfaceProtocol == 0x01
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

        throw IOException(
            "Required USB bulk endpoint was not found."
        )
    }

    /**
     * Sends one complete ADB packet.
     */
    @Throws(IOException::class)
    override fun writePacket(packet: AdbProtocol.AdbPacket) {
        synchronized(writeLock) {
            val usbConnection = requireOpenConnection()

            val endpoint = bulkOutEndpoint
                ?: throw IOException(
                    "Bulk OUT endpoint is unavailable."
                )

            val bytes = AdbProtocol.encode(packet)
            var offset = 0

            while (offset < bytes.size) {
                ensureOpen()

                val chunkSize = minOf(
                    bytes.size - offset,
                    MAX_TRANSFER_CHUNK
                )

                val written = usbConnection.bulkTransfer(
                    endpoint,
                    bytes,
                    offset,
                    chunkSize,
                    WRITE_TIMEOUT_MS
                )

                if (written <= 0) {
                    throw IOException(
                        "USB bulk write failed, timed out, or the device disconnected."
                    )
                }

                offset += written
            }
        }
    }

    /**
     * Receives one complete ADB packet.
     */
    @Throws(IOException::class)
    override fun readPacket(): AdbProtocol.AdbPacket {
        synchronized(readLock) {
            val usbConnection = requireOpenConnection()

            val endpoint = bulkInEndpoint
                ?: throw IOException(
                    "Bulk IN endpoint is unavailable."
                )

            val header = readExact(
                usbConnection,
                endpoint,
                AdbProtocol.HEADER_SIZE
            )

            val headerBuffer = ByteBuffer
                .wrap(header)
                .order(ByteOrder.LITTLE_ENDIAN)

            headerBuffer.int
            headerBuffer.int
            headerBuffer.int

            val dataLength = headerBuffer.int

            if (
                dataLength < 0 ||
                dataLength > AdbProtocol.MAX_DATA_SIZE
            ) {
                throw IOException(
                    "Invalid ADB packet payload length."
                )
            }

            val completePacket = ByteArray(
                AdbProtocol.HEADER_SIZE + dataLength
            )

            System.arraycopy(
                header,
                0,
                completePacket,
                0,
                header.size
            )

            if (dataLength > 0) {
                val payload = readExact(
                    usbConnection,
                    endpoint,
                    dataLength
                )

                System.arraycopy(
                    payload,
                    0,
                    completePacket,
                    AdbProtocol.HEADER_SIZE,
                    dataLength
                )
            }

            return try {
                AdbProtocol.decode(completePacket)
            } catch (error: IllegalArgumentException) {
                throw IOException(
                    "Received an invalid ADB packet.",
                    error
                )
            }
        }
    }

    /**
     * Reads exactly the requested number of bytes.
     *
     * A finite timeout allows the reader to periodically
     * check whether the transport has been closed.
     */
    @Throws(IOException::class)
    private fun readExact(
        usbConnection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        expectedLength: Int
    ): ByteArray {
        val result = ByteArray(expectedLength)
        var offset = 0

        while (offset < expectedLength) {
            ensureOpen()

            val chunkSize = minOf(
                expectedLength - offset,
                MAX_TRANSFER_CHUNK
            )

            val received = usbConnection.bulkTransfer(
                endpoint,
                result,
                offset,
                chunkSize,
                READ_TIMEOUT_MS
            )

            if (received < 0) {
                throw IOException(
                    "USB bulk read failed or the device disconnected."
                )
            }

            if (received == 0) {
                ensureOpen()

                continue
            }

            offset += received
        }

        return result
    }

    /**
     * Checks whether the USB transport is still available.
     */
    private fun ensureOpen() {
        if (closed || connection == null) {
            throw IOException(
                "USB transport is closed."
            )
        }
    }

    private fun requireOpenConnection(): UsbDeviceConnection {
        ensureOpen()

        return connection
            ?: throw IOException(
                "USB transport has not been opened."
            )
    }

    /**
     * Releases the interface and closes the connection.
     */
    @Synchronized
    override fun close() {
        if (closed) return

        closed = true

        val usbConnection = connection
        val selectedInterface = adbInterface

        connection = null
        adbInterface = null
        bulkInEndpoint = null
        bulkOutEndpoint = null

        if (usbConnection != null) {
            if (interfaceClaimed && selectedInterface != null) {
                try {
                    usbConnection.releaseInterface(
                        selectedInterface
                    )
                } catch (_: Exception) {
                    // Continue closing the USB connection.
                }
            }

            try {
                usbConnection.close()
            } catch (_: Exception) {
                // Resources are being released.
            }
        }

        interfaceClaimed = false
    }
}
