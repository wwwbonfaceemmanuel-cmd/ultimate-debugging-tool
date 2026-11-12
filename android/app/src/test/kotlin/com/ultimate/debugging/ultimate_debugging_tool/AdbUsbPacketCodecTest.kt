package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

/**
 * Checks the USB framing rules that the ADB daemon of modern Android
 * versions enforces.
 */
class AdbUsbPacketCodecTest {

    /** Records every bulk write and plays back scripted bulk reads. */
    private class RecordingChannel(
        override val maxPacketSize: Int = 512
    ) : UsbBulkChannel {
        val writes = ArrayList<ByteArray>()
        val incoming = ArrayDeque<ByteArray>()

        override fun write(data: ByteArray, offset: Int, length: Int) {
            writes.add(data.copyOfRange(offset, offset + length))
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val transfer = incoming.poll() ?: throw IOException("no more data")
            // A real controller reports overflow as a failed read.
            if (transfer.size > length) return 0
            System.arraycopy(transfer, 0, buffer, offset, transfer.size)
            return transfer.size
        }

        /** Groups writes into USB transfers (ended by a short packet). */
        fun transfers(): List<Int> {
            val sizes = ArrayList<Int>()
            var current = 0
            var open = false
            for (write in writes) {
                current += write.size
                open = true
                if (write.size % maxPacketSize != 0 || write.isEmpty()) {
                    sizes.add(current)
                    current = 0
                    open = false
                }
            }
            if (open) sizes.add(-current) // negative: never terminated
            return sizes
        }
    }

    private fun packet(payloadSize: Int): AdbProtocol.AdbPacket {
        return AdbProtocol.AdbPacket(
            AdbProtocol.CMD_WRTE, 1, 2, ByteArray(payloadSize) { (it % 251).toByte() }
        )
    }

    @Test
    fun headerIsSentAsItsOwnTransfer() {
        val channel = RecordingChannel()
        AdbUsbPacketCodec(channel).write(packet(100))

        // Modern adbd rejects a first transfer that is not 24 bytes.
        assertEquals(listOf(24, 100), channel.transfers())
        assertEquals(24, channel.writes[0].size)
    }

    @Test
    fun emptyPacketSendsOnlyTheHeader() {
        val channel = RecordingChannel()
        AdbUsbPacketCodec(channel).write(
            AdbProtocol.AdbPacket(AdbProtocol.CMD_OKAY, 1, 2)
        )

        assertEquals(1, channel.writes.size)
        assertEquals(24, channel.writes[0].size)
    }

    @Test
    fun payloadOnPacketBoundaryIsTerminated() {
        // Without a zero-length packet the phone keeps waiting for more.
        val channel = RecordingChannel()
        AdbUsbPacketCodec(channel).write(packet(1024))

        assertEquals(listOf(24, 1024), channel.transfers())
        assertEquals(0, channel.writes.last().size)
    }

    @Test
    fun largePayloadIsSplitIntoSafeChunks() {
        val channel = RecordingChannel()
        AdbUsbPacketCodec(channel).write(packet(40000))

        assertEquals(listOf(24, 16384, 16384, 7232), channel.writes.map { it.size })
        assertEquals(listOf(24, 40000), channel.transfers())

        val joined = channel.writes.drop(1).fold(ByteArray(0)) { all, part -> all + part }
        assertArrayEquals(packet(40000).data, joined)
    }

    @Test
    fun largePayloadOnBoundaryIsTerminated() {
        val channel = RecordingChannel()
        AdbUsbPacketCodec(channel).write(packet(32768))

        assertEquals(listOf(24, 16384, 16384, 0), channel.writes.map { it.size })
    }

    @Test
    fun readsHeaderThenPayloadInChunks() {
        val channel = RecordingChannel()
        val original = packet(20000)
        channel.incoming.add(AdbProtocol.encodeHeader(original))
        channel.incoming.add(original.data.copyOfRange(0, 16384))
        channel.incoming.add(original.data.copyOfRange(16384, 20000))

        val received = AdbUsbPacketCodec(channel).read()

        assertEquals(AdbProtocol.CMD_WRTE, received.command)
        assertArrayEquals(original.data, received.data)
    }

    @Test
    fun toleratesEmptyReadsWhileWaiting() {
        val channel = RecordingChannel()
        val original = packet(10)
        repeat(5) { channel.incoming.add(ByteArray(0)) }
        channel.incoming.add(AdbProtocol.encodeHeader(original))
        channel.incoming.add(ByteArray(0))
        channel.incoming.add(original.data)

        assertArrayEquals(original.data, AdbUsbPacketCodec(channel).read().data)
    }

    @Test
    fun skipsLeftoverBytesBeforeFirstPacket() {
        val channel = RecordingChannel()
        val original = packet(8)
        channel.incoming.add(ByteArray(7) { 0x55 })
        channel.incoming.add(ByteArray(24) { 0x11 })
        channel.incoming.add(AdbProtocol.encodeHeader(original))
        channel.incoming.add(original.data)

        assertArrayEquals(original.data, AdbUsbPacketCodec(channel).read().data)
    }

    @Test
    fun corruptHeaderAfterValidPacketIsAnError() {
        val channel = RecordingChannel()
        val original = packet(8)
        channel.incoming.add(AdbProtocol.encodeHeader(original))
        channel.incoming.add(original.data)
        channel.incoming.add(ByteArray(24) { 0x11 })

        val codec = AdbUsbPacketCodec(channel)
        codec.read()

        try {
            codec.read()
            fail("expected an invalid header error")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("invalid"))
        }
    }

    @Test
    fun corruptPayloadIsDetected() {
        val channel = RecordingChannel()
        val original = packet(8)
        channel.incoming.add(AdbProtocol.encodeHeader(original))
        channel.incoming.add(ByteArray(8) { 9 })

        try {
            AdbUsbPacketCodec(channel).read()
            fail("expected a checksum error")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("invalid"))
        }
    }
}
