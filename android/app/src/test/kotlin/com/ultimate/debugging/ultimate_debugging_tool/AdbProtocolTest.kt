package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbProtocolTest {

    private val packet = AdbProtocol.AdbPacket(
        AdbProtocol.CMD_OPEN, 7, 0, "shell:id\u0000".toByteArray()
    )

    @Test
    fun encodeThenDecodeReturnsSamePacket() {
        val decoded = AdbProtocol.decode(AdbProtocol.encode(packet))

        assertEquals(packet.command, decoded.command)
        assertEquals(7, decoded.arg0)
        assertEquals(0, decoded.arg1)
        assertArrayEquals(packet.data, decoded.data)
    }

    @Test
    fun headerIsLittleEndianWithMagic() {
        val header = AdbProtocol.encodeHeader(packet)

        assertEquals(24, header.size)
        // "OPEN" in ASCII, as it appears on the wire.
        assertEquals("OPEN", String(header, 0, 4))
        assertEquals(9, header[12].toInt())
        assertTrue(AdbProtocol.parseHeader(header).isValid)
    }

    @Test
    fun connectCommandSpellsCnxn() {
        val header = AdbProtocol.encodeHeader(
            AdbProtocol.AdbPacket(AdbProtocol.CMD_CNXN, AdbProtocol.VERSION, 4096)
        )
        assertEquals("CNXN", String(header, 0, 4))
    }

    @Test
    fun badMagicIsRejected() {
        val bytes = AdbProtocol.encode(packet)
        bytes[20] = (bytes[20] + 1).toByte()

        assertFalse(AdbProtocol.parseHeader(bytes).isValid)
        try {
            AdbProtocol.decode(bytes)
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("magic"))
        }
    }

    @Test
    fun badChecksumIsRejected() {
        val bytes = AdbProtocol.encode(packet)
        bytes[bytes.size - 2] = 'X'.code.toByte()

        try {
            AdbProtocol.decode(bytes)
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("checksum"))
        }
    }

    @Test
    fun zeroChecksumFromNewerDevicesIsAccepted() {
        val bytes = AdbProtocol.encode(packet)
        for (index in 16..19) bytes[index] = 0

        assertArrayEquals(packet.data, AdbProtocol.decode(bytes).data)
    }

    @Test
    fun unknownCommandHeaderIsNotValid() {
        val header = AdbProtocol.encodeHeader(AdbProtocol.AdbPacket(0x12345678, 0, 0))
        assertFalse(AdbProtocol.parseHeader(header).isValid)
    }
}
