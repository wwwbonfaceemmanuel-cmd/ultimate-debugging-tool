package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FastbootTest {

    private val variables = linkedMapOf(
        "version" to "0.4",
        "product" to "a12",
        "serialno" to "R58N1ABCDEF",
        "unlocked" to "no",
        "slot-count" to "2",
        "current-slot" to "a",
        "partition-size:boot" to "0x00000100",
        "partition-size:recovery" to "0x00000200"
    )

    private fun image(size: Int): ByteArray = ByteArray(size) { 0x41 }

    // ---------- Protocol ----------

    @Test
    fun readsEveryKindOfReply() {
        assertEquals(
            "0.4",
            (FastbootProtocol.parse(packet("OKAY0.4")) as FastbootProtocol.Response.Okay).payload
        )
        assertEquals(
            "no space left",
            (FastbootProtocol.parse(packet("FAILno space left")) as FastbootProtocol.Response.Fail).payload
        )
        assertEquals(
            "writing boot",
            (FastbootProtocol.parse(packet("INFOwriting boot")) as FastbootProtocol.Response.Info).payload
        )
        assertEquals(
            4096L,
            (FastbootProtocol.parse(packet("DATA00001000")) as FastbootProtocol.Response.Data).size
        )
        assertTrue(FastbootProtocol.parse(packet("WHAT")) is FastbootProtocol.Response.Unknown)
    }

    @Test
    fun paddingAfterTheAnswerIsIgnored() {
        val bytes = byteArrayOf(
            'O'.code.toByte(), 'K'.code.toByte(), 'A'.code.toByte(),
            'Y'.code.toByte(), 'n'.code.toByte(), 'o'.code.toByte(), 0, 0, 0
        )
        val answer = FastbootProtocol.parse(bytes) as FastbootProtocol.Response.Okay

        assertEquals("no", answer.payload)
    }

    @Test
    fun hexIsEightLowerCaseDigits() {
        assertEquals("00000010", FastbootProtocol.hex(16))
        assertEquals("0000ffff", FastbootProtocol.hex(65535))
        assertEquals(4096L, FastbootProtocol.parseHex("0x1000"))
        assertEquals(4096L, FastbootProtocol.parseHex("1000"))
        assertNull(FastbootProtocol.parseHex("not a number"))
    }

    @Test
    fun variableNamesMayContainAColon() {
        val pair = FastbootProtocol.parseVariable("partition-size:boot: 0x1000")!!

        assertEquals("partition-size:boot", pair.first)
        assertEquals("0x1000", pair.second)
    }

    @Test
    fun partitionNamesAreStrict() {
        assertTrue(FastbootProtocol.isPartitionName("boot"))
        assertTrue(FastbootProtocol.isPartitionName("init_boot"))
        assertFalse(FastbootProtocol.isPartitionName("boot; reboot"))
        assertFalse(FastbootProtocol.isPartitionName(""))
        assertFalse(FastbootProtocol.isPartitionName("a".repeat(40)))
    }

    // ---------- Reading the phone ----------

    @Test
    fun readsOneVariable() {
        val device = FakeFastbootDevice(variables)
        val session = FastbootSession(device)

        assertEquals("a12", session.getvar("product"))
    }

    @Test
    fun aRefusalKeepsThePhonesOwnWords() {
        val device = FakeFastbootDevice(variables, refuse = "getvar:")
        val session = FastbootSession(device)

        try {
            session.getvar("product")
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: FastbootException) {
            assertEquals("this phone says no", expected.message)
        }
    }

    @Test
    fun listsEveryVariableAndThePartitionTable() {
        val session = FastbootSession(FakeFastbootDevice(variables))
        val info = session.deviceInfo()

        assertEquals("a12", info.product)
        assertEquals("R58N1ABCDEF", info.serial)
        assertEquals(false, info.unlocked)
        assertEquals(2, info.slots)
        assertEquals("a", info.currentSlot)
        assertEquals(
            listOf("boot", "recovery"),
            info.partitions.map { it.name }
        )
        assertEquals(256L, info.partitions.first().sizeBytes)
        assertEquals("0.4", info.raw["version"])
    }

    @Test
    fun anUnknownUnlockStateIsNotGuessed() {
        val session = FastbootSession(
            FakeFastbootDevice(linkedMapOf("product" to "a12"))
        )

        assertNull(session.deviceInfo().unlocked)
        assertEquals("Not reported", session.deviceInfo().toMap()["unlockText"])
    }

    @Test
    fun aSilentPhoneIsReportedClearly() {
        val silent = object : FastbootTransport {
            override fun write(bytes: ByteArray, timeoutMs: Long) = Unit
            override fun read(maxBytes: Int, timeoutMs: Long): ByteArray =
                throw IOException("The phone stopped answering. Check the cable.")
            override fun close() = Unit
        }

        try {
            FastbootSession(silent).getvar("product")
            org.junit.Assert.fail("expected a failure")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("stopped answering"))
        }
    }

    // ---------- Downloading ----------

    @Test
    fun downloadSendsEveryByteInPlainChunks() {
        val device = FakeFastbootDevice(variables, chunkSize = 8)
        val session = FastbootSession(device)
        val data = image(20)

        session.download(data)

        assertEquals(listOf("download:00000014"), device.commands)
        assertArrayEquals(data, device.image.toByteArray())
    }

    @Test
    fun anEmptyImageIsRefusedBeforeAnythingIsSent() {
        val device = FakeFastbootDevice(variables)

        try {
            FastbootSession(device).download(ByteArray(0))
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: FastbootException) {
            assertTrue(expected.message!!.contains("empty"))
        }

        assertTrue(device.commands.isEmpty())
    }

    // ---------- Flashing ----------

    @Test
    fun flashingWritesAnAllowedPartition() {
        val device = FakeFastbootDevice(variables, chunkSize = 8)
        val session = FastbootSession(device)
        val data = image(16)

        val message = session.flash("boot", data)

        assertTrue(device.commands.contains("getvar:partition-size:boot"))
        assertTrue(device.commands.contains("download:00000010"))
        assertTrue(device.commands.contains("flash:boot"))
        assertArrayEquals(data, device.image.toByteArray())
        assertTrue(message.isNotEmpty())
    }

    @Test
    fun aPartitionThisToolDoesNotKnowIsRefusedBeforeAnyBytesMove() {
        val device = FakeFastbootDevice(variables)
        val session = FastbootSession(device)

        for (partition in listOf("system", "userdata", "modem", "boot; reboot")) {
            try {
                session.flash(partition, image(16))
                org.junit.Assert.fail("expected a refusal for $partition")
            } catch (expected: FastbootException) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }

        assertTrue(device.commands.isEmpty())
        assertEquals(0, device.image.size())
    }

    @Test
    fun anImageThatDoesNotFitThePartitionIsRefused() {
        val device = FakeFastbootDevice(variables)
        val session = FastbootSession(device)

        // The phone says boot is 0x100 bytes; send more than that.
        try {
            session.flash("boot", image(300))
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: FastbootException) {
            assertTrue(expected.message!!.contains("only"))
        }

        assertFalse(device.commands.any { it.startsWith("download:") })
        assertEquals(0, device.image.size())
    }

    @Test
    fun aPhoneThatRefusesTheWriteSaysWhy() {
        val device = FakeFastbootDevice(variables, refuse = "flash:")
        val session = FastbootSession(device)

        try {
            session.flash("boot", image(8))
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: FastbootException) {
            assertEquals("this phone says no", expected.message)
        }
    }

    @Test
    fun erasingIsLimitedToTheSamePartitions() {
        val device = FakeFastbootDevice(variables)
        val session = FastbootSession(device)

        session.erase("recovery")
        assertTrue(device.commands.contains("erase:recovery"))

        try {
            session.erase("userdata")
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: FastbootException) {
            assertTrue(expected.message!!.contains("erases only"))
        }
    }

    @Test
    fun rebootingNamesTheTarget() {
        val device = FakeFastbootDevice(variables)
        val session = FastbootSession(device)

        session.reboot("bootloader")
        session.reboot("recovery")
        session.reboot()

        assertEquals(
            listOf("reboot-bootloader", "reboot-recovery", "reboot"),
            device.commands
        )
    }

    @Test
    fun closingIsQuietEvenWhenThePhoneIsGone() {
        val device = FakeFastbootDevice(variables)
        FastbootSession(device).close()

        assertTrue(device.closed)
    }

    private fun packet(text: String): ByteArray {
        val bytes = ByteArray(FastbootProtocol.MAX_PACKET) { 0 }
        val ascii = text.toByteArray(Charsets.ISO_8859_1)
        ascii.copyInto(bytes)

        return bytes
    }
}
