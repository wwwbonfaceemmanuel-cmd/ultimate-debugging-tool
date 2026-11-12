package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AdbFileSyncTest {

    companion object {
        private val key: SoftwareAdbKey by lazy {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            SoftwareAdbKey(pair.public as RSAPublicKey, pair.private as RSAPrivateKey)
        }
    }

    private val device = FakeAdbDevice().apply {
        files["/sdcard/DCIM/photo.jpg"] = ByteArray(200000) { (it % 253).toByte() }
        files["/sdcard/notes.txt"] = "hello".toByteArray()
        files["/sdcard/Download/app.apk"] = ByteArray(10)
    }

    private fun session(): AdbSessionManager {
        device.trustedKeys.add(key.publicKey.modulus)
        val session = AdbSessionManager(AdbAuthentication { key })
        val results = LinkedBlockingQueue<AdbSessionManager.ConnectResult>()
        session.connect(AdbSessionManager.Link.USB, "test", transportFactory = { device.transport }) {
            results.put(it)
        }
        assertTrue(results.poll(10, TimeUnit.SECONDS) is AdbSessionManager.ConnectResult.Connected)
        return session
    }

    @Test
    fun listsFolderWithTypesAndSizes() {
        val session = session()
        val entries = AdbFileSync.open(session).use { it.list("/sdcard") }

        assertEquals(listOf("DCIM", "Download", "notes.txt"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertTrue(entries[2].isFile)
        assertEquals(5L, entries[2].size)
        assertEquals("rw-r--r--", entries[2].permissions)
        assertEquals("directory", entries[0].toMap()["type"])
        session.shutdown()
    }

    @Test
    fun statReportsMissingFileAsNull() {
        val session = session()
        AdbFileSync.open(session).use { sync ->
            assertEquals(200000L, sync.stat("/sdcard/DCIM/photo.jpg")!!.size)
            assertTrue(sync.stat("/sdcard/DCIM")!!.isDirectory)
            assertNull(sync.stat("/sdcard/missing"))
        }
        session.shutdown()
    }

    @Test
    fun pullsBinaryFileExactly() {
        val session = session()
        val output = ByteArrayOutputStream()
        var lastProgress = 0L

        val count = AdbFileSync.open(session).use {
            it.pull("/sdcard/DCIM/photo.jpg", output) { done -> lastProgress = done }
        }

        assertEquals(200000L, count)
        assertEquals(200000L, lastProgress)
        assertArrayEquals(device.files["/sdcard/DCIM/photo.jpg"]!!, output.toByteArray())
        session.shutdown()
    }

    @Test
    fun pullOfMissingFileExplainsWhy() {
        val session = session()
        try {
            AdbFileSync.open(session).use { it.pull("/sdcard/nope", ByteArrayOutputStream()) }
            fail("expected failure")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("No such file"))
        }
        assertTrue(session.snapshot().connected)
        session.shutdown()
    }

    @Test
    fun pullRespectsSizeLimit() {
        val session = session()
        try {
            AdbFileSync.open(session).use {
                it.pull("/sdcard/DCIM/photo.jpg", ByteArrayOutputStream(), maxBytes = 1000)
            }
            fail("expected failure")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("larger"))
        }
        session.shutdown()
    }

    @Test
    fun pushesFileAndReadsItBack() {
        val session = session()
        val content = ByteArray(150000) { (it * 7 % 251).toByte() }

        AdbFileSync.open(session).use { sync ->
            assertEquals(150000L, sync.push(ByteArrayInputStream(content), "/sdcard/Download/new.bin"))
            assertEquals(150000L, sync.stat("/sdcard/Download/new.bin")!!.size)
        }

        assertArrayEquals(content, device.files["/sdcard/Download/new.bin"]!!)
        assertTrue("device reported: ${device.protocolErrors}", device.protocolErrors.isEmpty())
        session.shutdown()
    }

    @Test
    fun pushToProtectedFolderReportsRefusal() {
        val session = session()
        try {
            AdbFileSync.open(session).use {
                it.push(ByteArrayInputStream(ByteArray(10)), "/system/x.bin")
            }
            fail("expected failure")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("Read-only"))
        }
        session.shutdown()
    }
}
