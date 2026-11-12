package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

/**
 * One direction-pair of USB bulk endpoints, reduced to what ADB needs.
 *
 * Kept free of Android classes so the ADB framing rules can be tested
 * on a normal JVM.
 */
interface UsbBulkChannel {

    /** wMaxPacketSize of the bulk OUT endpoint. */
    val maxPacketSize: Int

    /**
     * Sends [length] bytes as part of the current transfer.
     * A length of zero sends a zero-length packet.
     */
    @Throws(IOException::class)
    fun write(data: ByteArray, offset: Int, length: Int)

    /**
     * Waits for incoming data and stores up to [length] bytes.
     * Returns the number of bytes received, which may be zero.
     * Must block until data arrives or the channel is closed.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * Frames ADB packets for USB.
 *
 * The ADB daemon on the phone requires that:
 *  - the 24-byte header arrives as its own USB transfer;
 *  - the payload follows as a separate transfer;
 *  - a transfer whose size is an exact multiple of the endpoint packet
 *    size is terminated with a zero-length packet.
 *
 * Sending header and payload glued together makes modern Android
 * versions drop the connection.
 */
class AdbUsbPacketCodec(
    private val channel: UsbBulkChannel
) {

    companion object {
        /** Largest single request that every Android version accepts. */
        const val MAX_TRANSFER_CHUNK = 16384

        private const val MAX_EMPTY_READS = 64
        private const val MAX_RESYNC_ATTEMPTS = 8
    }

    private var receivedValidPacket = false

    @Throws(IOException::class)
    fun write(packet: AdbProtocol.AdbPacket) {
        writeTransfer(AdbProtocol.encodeHeader(packet))

        if (packet.data.isNotEmpty()) {
            writeTransfer(packet.data)
        }
    }

    private fun writeTransfer(bytes: ByteArray) {
        var offset = 0

        while (offset < bytes.size) {
            val chunk = minOf(bytes.size - offset, MAX_TRANSFER_CHUNK)
            channel.write(bytes, offset, chunk)
            offset += chunk
        }

        val packetSize = channel.maxPacketSize

        if (packetSize > 0 && bytes.size % packetSize == 0) {
            // Tell the device that this transfer is complete.
            channel.write(bytes, 0, 0)
        }
    }

    @Throws(IOException::class)
    fun read(): AdbProtocol.AdbPacket {
        val header = readHeader()

        val payload = if (header.dataLength > 0) {
            readExact(header.dataLength)
        } else {
            ByteArray(0)
        }

        val packet = try {
            AdbProtocol.assemble(header, payload)
        } catch (error: IllegalArgumentException) {
            throw IOException("Received an invalid ADB packet.", error)
        }

        receivedValidPacket = true
        return packet
    }

    /**
     * Reads the next header.
     *
     * Right after connecting, the device may still deliver bytes left over
     * from an earlier session. Those are skipped until the first valid
     * header is seen. After that, a bad header is a real protocol error.
     */
    private fun readHeader(): AdbProtocol.Header {
        var attempts = 0

        while (true) {
            val bytes = ByteArray(AdbProtocol.HEADER_SIZE)
            var received = 0
            var emptyReads = 0

            while (received == 0) {
                received = channel.read(
                    bytes,
                    0,
                    AdbProtocol.HEADER_SIZE
                )

                if (received == 0 && ++emptyReads > MAX_EMPTY_READS) {
                    throw IOException(
                        "The USB device stopped sending ADB data."
                    )
                }
            }

            if (received == AdbProtocol.HEADER_SIZE) {
                val header = AdbProtocol.parseHeader(bytes)

                if (header.isValid) {
                    return header
                }
            }

            if (receivedValidPacket || ++attempts > MAX_RESYNC_ATTEMPTS) {
                throw IOException(
                    "Received an invalid ADB packet header over USB."
                )
            }
        }
    }

    private fun readExact(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        var emptyReads = 0

        while (offset < length) {
            val chunk = minOf(length - offset, MAX_TRANSFER_CHUNK)
            val received = channel.read(result, offset, chunk)

            if (received < 0 || received > chunk) {
                throw IOException("USB bulk read failed.")
            }

            if (received == 0) {
                if (++emptyReads > MAX_EMPTY_READS) {
                    throw IOException(
                        "The USB device stopped sending ADB data."
                    )
                }
                continue
            }

            emptyReads = 0
            offset += received
        }

        return result
    }
}
