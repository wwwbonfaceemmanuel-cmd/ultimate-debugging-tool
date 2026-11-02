
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
 * USB bulk transport for the Android Debug Bridge protocol.
 *
 * This class handles:
 * - Opening an authorized USB device.
 * - Claiming its ADB interface.
 * - Sending ADB packets through Bulk OUT.
 * - Receiving ADB packets through Bulk IN.
 * - Handling partial USB transfers.
 *
 * ADB authentication and connection negotiation are separate stages.
 */
class AdbUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val interfaceId: Int
) : AutoCloseable {

    companion object {
        private const val USB_TIMEOUT_MS = 5000
        private const val MAX_TRANSFER_CHUNK = 16384
    }

    private var connection: UsbDeviceConnection? = null
    private var adbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    private var interfaceClaimed = false
    private var closed = false

    /**
     * Opens the device and claims its ADB USB interface.
     *
     * Call this method from a background thread.
     */
    @Synchronized
    @Throws(IOException::class)
    fun open() {
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

        try {
            if (!usbConnection.claimInterface(selectedInterface, true)) {
                throw IOException("Unable to claim the ADB USB interface.")
            }

            connection = usbConnection
            adbInterface = selectedInterface
            bulkInEndpoint = inputEndpoint
            bulkOutEndpoint = outputEndpoint
            interfaceClaimed = true
            closed = false
        } catch (error: Exception) {
            usbConnection.close()
            throw error
        }
    }

    /**
     * Finds the requested standard ADB interface.
     */
    private fun findAdbInterface(): UsbInterface {
        val selected = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { usbInterface ->
                usbInterface.id == interfaceId &&
                    usbInterface.interfaceClass == 0xFF &&
                    usbInterface.interfaceSubclass == 0x42 &&
                    usbInterface.interfaceProtocol == 0x01
            }

        return selected
            ?: throw IOException(
                "The requested standard ADB interface was not found."
            )
    }

    /**
     * Finds a bulk endpoint in the requested direction.
     */
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
     *
     * Partial USB writes are handled until the complete packet
     * has been transmitted.
     */
    @Synchronized
    @Throws(IOException::class)
    fun writePacket(packet: AdbProtocol.AdbPacket) {
        val usbConnection = requireOpenConnection()
        val endpoint = bulkOutEndpoint
            ?: throw IOException("Bulk OUT endpoint is unavailable.")

        val bytes = AdbProtocol.encode(packet)

        var offset = 0

        while (offset < bytes.size) {
            val remaining = bytes.size - offset
            val chunkSize = minOf(remaining, MAX_TRANSFER_CHUNK)

            val written = usbConnection.bulkTransfer(
                endpoint,
                bytes,
                offset,
                chunkSize,
                USB_TIMEOUT_MS
            )

            if (written <= 0) {
                throw IOException(
                    "USB bulk write failed or timed out."
                )
            }

            offset += written
        }
    }

    /**
     * Receives one complete ADB packet.
     *
     * Reads the 24-byte ADB header first, then reads the payload
     * length declared by that header.
     */
    @Synchronized
    @Throws(IOException::class)
    fun readPacket(): AdbProtocol.AdbPacket {
        val usbConnection = requireOpenConnection()
        val endpoint = bulkInEndpoint
            ?: throw IOException("Bulk IN endpoint is unavailable.")

        val header = readExact(
            usbConnection,
            endpoint,
            AdbProtocol.HEADER_SIZE
        )

        val headerBuffer = ByteBuffer
            .wrap(header)
            .order(ByteOrder.LITTLE_ENDIAN)

        val command = headerBuffer.int
        val arg0 = headerBuffer.int
        val arg1 = headerBuffer.int
        val dataLength = headerBuffer.int

        if (dataLength < 0 || dataLength > AdbProtocol.MAX_DATA_SIZE) {
            throw IOException("Invalid ADB packet payload length.")
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

    /**
     * Reads exactly the requested number of bytes.
     *
     * A USB transfer may return fewer bytes than requested.
     * This method continues reading until the requested amount
     * has arrived or a transfer fails.
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
            val remaining = expectedLength - offset
            val chunkSize = minOf(
                remaining,
                MAX_TRANSFER_CHUNK
            )

            val received = usbConnection.bulkTransfer(
                endpoint,
                result,
                offset,
                chunkSize,
                USB_TIMEOUT_MS
            )

            if (received <= 0) {
                throw IOException(
                    "USB bulk read failed, timed out, or returned no data."
                )
            }

            offset += received
        }

        return result
    }

    /**
     * Ensures the transport is open before an operation.
     */
    private fun requireOpenConnection(): UsbDeviceConnection {
        if (closed) {
            throw IOException("USB transport is closed.")
        }

        return connection
            ?: throw IOException("USB transport has not been opened.")
    }

    /**
     * Releases the claimed interface and closes the connection.
     */
    @Synchronized
    override fun close() {
        if (closed) return

        val usbConnection = connection

        if (usbConnection != null) {
            if (interfaceClaimed && adbInterface != null) {
                usbConnection.releaseInterface(adbInterface)
            }

            usbConnection.close()
        }

        connection = null
        adbInterface = null
        bulkInEndpoint = null
        bulkOutEndpoint = null
        interfaceClaimed = false
        closed = true
    }
}
