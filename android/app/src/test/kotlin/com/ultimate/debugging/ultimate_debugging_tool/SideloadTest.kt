package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class SideloadTest {

    private fun packageFile(bytes: ByteArray): File {
        val file = File.createTempFile("ota", ".zip")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    /** A plausible package: a zip header, then filler. */
    private fun zipBytes(size: Int): ByteArray {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        bytes[0] = 'P'.code.toByte()
        bytes[1] = 'K'.code.toByte()
        bytes[2] = 3.toByte()
        bytes[3] = 4.toByte()
        return bytes
    }

    @Test
    fun onlyAZipLooksLikeAnUpdatePackage() {
        assertTrue(AdbSideload.looksLikePackage(zipBytes(16)))
        assertFalse(AdbSideload.looksLikePackage("hello".toByteArray()))
        assertFalse(AdbSideload.looksLikePackage(byteArrayOf()))
        assertFalse(AdbSideload.looksLikePackage(byteArrayOf('P'.code.toByte())))
    }

    @Test
    fun theServiceNamesThePackageSizeAndBlockSize() {
        var service = ""
        val file = packageFile(zipBytes(100))
        val device = FakeSideloadDevice(listOf(0L))

        AdbSideload { name ->
            service = name
            device
        }.send(file)

        assertEquals(
            "sideload-host:100:${AdbSideload.BLOCK_SIZE}",
            service
        )
    }

    @Test
    fun everyBlockThePhoneAsksForIsServedInFull() {
        val data = zipBytes(3 * AdbSideload.BLOCK_SIZE)
        val file = packageFile(data)
        val device = FakeSideloadDevice(listOf(0L, 1L, 2L))

        val result = AdbSideload { _ -> device }.send(file)

        assertArrayEquals(data, device.received.toByteArray())
        assertEquals(3L, result.blocksServed)
        assertEquals(data.size.toLong(), result.bytesSent)
        assertTrue(result.message.contains("accepted"))
        assertTrue(device.closed)
    }

    @Test
    fun blocksMayBeAskedForInAnyOrder() {
        val data = zipBytes(3 * AdbSideload.BLOCK_SIZE)
        val file = packageFile(data)
        val device = FakeSideloadDevice(listOf(2L, 0L))

        AdbSideload { _ -> device }.send(file)

        val expected = data.copyOfRange(2 * AdbSideload.BLOCK_SIZE, 3 * AdbSideload.BLOCK_SIZE) +
            data.copyOfRange(0, AdbSideload.BLOCK_SIZE)

        assertArrayEquals(expected, device.received.toByteArray())
    }

    @Test
    fun theLastBlockIsShortAndNotPadded() {
        val data = zipBytes(2 * AdbSideload.BLOCK_SIZE + 1000)
        val file = packageFile(data)
        val device = FakeSideloadDevice(listOf(2L))

        val result = AdbSideload { _ -> device }.send(file)

        assertEquals(1000, device.received.size())
        assertEquals(1000L, result.bytesSent)
    }

    @Test
    fun aCommandSplitOverSeveralReadsIsStillUnderstood() {
        val data = zipBytes(AdbSideload.BLOCK_SIZE)
        val file = packageFile(data)
        val device = FakeSideloadDevice(listOf(0L), splitReads = true)

        AdbSideload { _ -> device }.send(file)

        assertArrayEquals(data, device.received.toByteArray())
    }

    @Test
    fun thePhonesDONEEndsTheSession() {
        val file = packageFile(zipBytes(64))
        val device = FakeSideloadDevice(listOf(0L), ending = AdbSideload.DONE)

        val result = AdbSideload { _ -> device }.send(file)

        assertTrue(result.message.contains("installing"))
    }

    @Test
    fun thePhonesFAILIsReportedWithItsOwnWords() {
        val file = packageFile(zipBytes(64))
        val device = FakeSideloadDevice(listOf(0L), ending = AdbSideload.FAIL)

        try {
            AdbSideload { _ -> device }.send(file)
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("rejected"))
        }
    }

    @Test
    fun aFileThatIsNotAZipIsRefusedBeforeThePhoneIsTouched() {
        var opened = false
        val file = packageFile("not a package".toByteArray())

        try {
            AdbSideload { _ ->
                opened = true
                FakeSideloadDevice(emptyList())
            }.send(file)
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("not an update package"))
        }

        assertFalse("the phone must not be contacted", opened)
    }

    @Test
    fun aMissingOrEmptyPackageIsRefused() {
        val empty = packageFile(ByteArray(0))

        for (file in listOf(empty, File("/nonexistent/ota.zip"))) {
            try {
                AdbSideload { _ -> FakeSideloadDevice(emptyList()) }.send(file)
                org.junit.Assert.fail("expected a refusal")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun aPhoneThatWillNotOpenTheServiceExplainsWhatToDo() {
        val file = packageFile(zipBytes(64))

        try {
            AdbSideload { _ -> throw IOException("closed") }.send(file)
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("Apply update from ADB"))
        }
    }

    @Test
    fun aBlockPastTheEndIsRefused() {
        val file = packageFile(zipBytes(100))

        try {
            AdbSideload { _ -> FakeSideloadDevice(listOf(40L)) }.send(file)
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("past the end"))
        }
    }

    @Test
    fun progressStartsAtZeroAndEndsAtHundred() {
        val file = packageFile(zipBytes(AdbSideload.BLOCK_SIZE))
        val percents = ArrayList<Int>()

        AdbSideload { _ -> FakeSideloadDevice(listOf(0L)) }
            .send(file) { percents.add(it) }

        assertEquals(0, percents.first())
        assertEquals(100, percents.last())
        assertTrue(percents.all { it in 0..100 })
    }
}
