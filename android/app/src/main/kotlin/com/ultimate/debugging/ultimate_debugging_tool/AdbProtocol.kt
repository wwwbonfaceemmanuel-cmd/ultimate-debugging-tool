
package com.ultimate.debugging.ultimate_debugging_tool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Foundation for the Android Debug Bridge (ADB) protocol.
 *
 * Implements ADB packet headers and command constants.
 * USB transport and authentication will be implemented separately.
 */
object AdbProtocol {

    const val HEADER_SIZE = 24
    const val MAX_DATA_SIZE = 1024 * 1024

    // ADB protocol version.
    const val VERSION = 0x01000000

    // ADB commands.
    const val CMD_CNXN = 0x4E584E43
    const val CMD_AUTH = 0x48545541
    const val CMD_OPEN = 0x4E45504F
    const val CMD_OKAY = 0x59414B4F
    const val CMD_CLSE = 0x45534C43
    const val CMD_WRTE = 0x45545257

    // Authentication types.
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_PUBLIC_KEY = 3

    data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray = ByteArray(0)
    )

    /**
     * Encodes an ADB packet into its USB-transfer byte representation.
     */
    fun encode(packet: AdbPacket): ByteArray {
        require(packet.data.size <= MAX_DATA_SIZE) {
            "ADB packet data exceeds maximum allowed size."
        }

        val dataChecksum = checksum(packet.data)
        val magic = packet.command xor -1

        val buffer = ByteBuffer
            .allocate(HEADER_SIZE + packet.data.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(packet.command)
        buffer.putInt(packet.arg0)
        buffer.putInt(packet.arg1)
        buffer.putInt(packet.data.size)
        buffer.putInt(dataChecksum)
        buffer.putInt(magic)
        buffer.put(packet.data)

        return buffer.array()
    }

    /**
     * Decodes an ADB packet from received bytes.
     */
    fun decode(bytes: ByteArray): AdbPacket {
        require(bytes.size >= HEADER_SIZE) {
            "Incomplete ADB packet header."
        }

        val buffer = ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val expectedChecksum = buffer.int
        val magic = buffer.int

        require(dataLength in 0..MAX_DATA_SIZE) {
            "Invalid ADB packet data length."
        }

        require(bytes.size == HEADER_SIZE + dataLength) {
            "ADB packet size does not match its header."
        }

        require(magic == (command xor -1)) {
            "Invalid ADB packet magic value."
        }

        val data = ByteArray(dataLength)
        buffer.get(data)

        require(checksum(data) == expectedChecksum) {
            "ADB packet checksum verification failed."
        }

        return AdbPacket(
            command = command,
            arg0 = arg0,
            arg1 = arg1,
            data = data
        )
    }

    /**
     * Calculates the ADB packet data checksum.
     */
    private fun checksum(data: ByteArray): Int {
        var result = 0

        for (byte in data) {
            result += byte.toInt() and 0xFF
        }

        return result
    }

    /**
     * Converts an ADB command integer into readable text.
     */
    fun commandName(command: Int): String {
        return when (command) {
            CMD_CNXN -> "CNXN"
            CMD_AUTH -> "AUTH"
            CMD_OPEN -> "OPEN"
            CMD_OKAY -> "OKAY"
            CMD_CLSE -> "CLSE"
            CMD_WRTE -> "WRTE"
            else -> "UNKNOWN"
        }
    }
}
