package com.ultimate.debugging.ultimate_debugging_tool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android Debug Bridge (ADB) wire format.
 *
 * Every ADB message is a 24-byte header followed by an optional payload.
 * TCP carries them as one byte stream. USB carries the header and the
 * payload as two separate bulk transfers (see AdbUsbPacketCodec).
 */
object AdbProtocol {

    const val HEADER_SIZE = 24
    const val MAX_DATA_SIZE = 1024 * 1024

    // Protocol version announced by this host. Staying below 0x01000001
    // keeps payload checksums enabled on both sides.
    const val VERSION = 0x01000000

    const val CMD_CNXN = 0x4E584E43
    const val CMD_AUTH = 0x48545541
    const val CMD_OPEN = 0x4E45504F
    const val CMD_OKAY = 0x59414B4F
    const val CMD_CLSE = 0x45534C43
    const val CMD_WRTE = 0x45545257

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_PUBLIC_KEY = 3

    class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray = ByteArray(0)
    )

    /** Parsed fields of a 24-byte header. */
    class Header(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataChecksum: Int,
        val magic: Int
    ) {
        val isValid: Boolean
            get() = magic == (command xor -1) &&
                dataLength in 0..MAX_DATA_SIZE &&
                isKnownCommand(command)
    }

    fun isKnownCommand(command: Int): Boolean {
        return command == CMD_CNXN ||
            command == CMD_AUTH ||
            command == CMD_OPEN ||
            command == CMD_OKAY ||
            command == CMD_CLSE ||
            command == CMD_WRTE
    }

    /** Encodes only the 24-byte header of a packet. */
    fun encodeHeader(packet: AdbPacket): ByteArray {
        require(packet.data.size <= MAX_DATA_SIZE) {
            "ADB packet data exceeds maximum allowed size."
        }

        val buffer = ByteBuffer
            .allocate(HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(packet.command)
        buffer.putInt(packet.arg0)
        buffer.putInt(packet.arg1)
        buffer.putInt(packet.data.size)
        buffer.putInt(checksum(packet.data))
        buffer.putInt(packet.command xor -1)

        return buffer.array()
    }

    /** Encodes header and payload as one contiguous byte array (TCP). */
    fun encode(packet: AdbPacket): ByteArray {
        val header = encodeHeader(packet)
        val result = ByteArray(HEADER_SIZE + packet.data.size)

        System.arraycopy(header, 0, result, 0, HEADER_SIZE)
        System.arraycopy(
            packet.data,
            0,
            result,
            HEADER_SIZE,
            packet.data.size
        )

        return result
    }

    /** Reads the fields of a header without validating them. */
    fun parseHeader(bytes: ByteArray): Header {
        require(bytes.size >= HEADER_SIZE) {
            "Incomplete ADB packet header."
        }

        val buffer = ByteBuffer
            .wrap(bytes, 0, HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        return Header(
            command = buffer.int,
            arg0 = buffer.int,
            arg1 = buffer.int,
            dataLength = buffer.int,
            dataChecksum = buffer.int,
            magic = buffer.int
        )
    }

    /**
     * Builds a packet from a validated header and its payload.
     *
     * A checksum of zero is accepted because newer ADB daemons may skip
     * checksums; the transport already protects data integrity.
     */
    fun assemble(header: Header, payload: ByteArray): AdbPacket {
        require(header.magic == (header.command xor -1)) {
            "Invalid ADB packet magic value."
        }

        require(header.dataLength == payload.size) {
            "ADB packet size does not match its header."
        }

        require(
            header.dataChecksum == 0 ||
                header.dataChecksum == checksum(payload)
        ) {
            "ADB packet checksum verification failed."
        }

        return AdbPacket(
            command = header.command,
            arg0 = header.arg0,
            arg1 = header.arg1,
            data = payload
        )
    }

    /** Decodes a complete packet (header immediately followed by payload). */
    fun decode(bytes: ByteArray): AdbPacket {
        val header = parseHeader(bytes)

        require(header.dataLength in 0..MAX_DATA_SIZE) {
            "Invalid ADB packet data length."
        }

        require(bytes.size == HEADER_SIZE + header.dataLength) {
            "ADB packet size does not match its header."
        }

        return assemble(
            header,
            bytes.copyOfRange(HEADER_SIZE, bytes.size)
        )
    }

    fun checksum(data: ByteArray): Int {
        var result = 0

        for (byte in data) {
            result += byte.toInt() and 0xFF
        }

        return result
    }

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
