package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Exercises the whole ADB engine (authentication, connection, streams,
 * shell and session handling) against FakeAdbDevice.
 */
class AdbEngineTest {

    companion object {
        // One key for all tests; RSA key generation is slow.
        private val key: SoftwareAdbKey by lazy {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            SoftwareAdbKey(pair.public as RSAPublicKey, pair.private as RSAPrivateKey)
        }
    }

    private val states = CopyOnWriteArrayList<AdbConnection.State>()

    private fun newSession(): AdbSessionManager {
        return AdbSessionManager(AdbAuthentication { key }) { states.add(it.state) }
    }

    private fun connect(
        session: AdbSessionManager,
        device: FakeAdbDevice,
        link: AdbSessionManager.Link = AdbSessionManager.Link.USB
    ): LinkedBlockingQueue<AdbSessionManager.ConnectResult> {
        val results = LinkedBlockingQueue<AdbSessionManager.ConnectResult>()
        session.connect(
            link = link,
            target = "test-device",
            transportFactory = { device.transport },
            callback = { results.put(it) }
        )
        return results
    }

    private fun connected(
        device: FakeAdbDevice,
        session: AdbSessionManager = newSession()
    ): AdbSessionManager {
        device.trustedKeys.add(key.publicKey.modulus)
        val result = connect(session, device).poll(10, TimeUnit.SECONDS)
        assertTrue("expected Connected but was $result", result is AdbSessionManager.ConnectResult.Connected)
        return session
    }

    private fun waitForState(session: AdbSessionManager, state: AdbConnection.State) {
        val deadline = System.currentTimeMillis() + 5000
        while (session.snapshot().state != state) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for $state, state is ${session.snapshot().state}")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun announcesOnlyFeaturesThatAreImplemented() {
        val device = FakeAdbDevice()
        val session = connected(device)

        val banner = device.hostBanner
        assertEquals("old phones need a closing NUL", 0.toByte(), banner.last())

        // Read the way a current phone does: split by ':' then ';' then '='.
        val text = String(banner, Charsets.UTF_8)
        val properties = text.split(':')[2].split(';')
        val features = properties.first { it.startsWith("features=") }
            .removePrefix("features=").split(',')

        assertEquals("host", text.split(':')[0])
        assertEquals(listOf("shell_v2"), features)

        session.shutdown()
    }

    @Test
    fun firstConnectionShowsPromptThenConnects() {
        val device = FakeAdbDevice().apply { promptDelayMs = 200 }
        val session = newSession()

        val result = connect(session, device).poll(10, TimeUnit.SECONDS)

        assertTrue(result is AdbSessionManager.ConnectResult.Connected)
        assertEquals(1, device.promptsShown)
        assertTrue(states.contains(AdbConnection.State.AUTHENTICATION_REQUIRED))
        assertTrue(session.snapshot().connected)
        assertEquals(AdbSessionManager.Link.USB, session.link)
        session.shutdown()
    }

    @Test
    fun approvedKeyConnectsWithoutPrompt() {
        // The fake device checks the signature with the JDK's RSA code.
        // If the signature layout were wrong, a prompt would be shown.
        val device = FakeAdbDevice()
        val session = connected(device)

        assertEquals(0, device.promptsShown)
        assertFalse(states.contains(AdbConnection.State.AUTHENTICATION_REQUIRED))
        session.shutdown()
    }

    @Test
    fun unansweredPromptCanBeCancelledAndRetried() {
        val silent = FakeAdbDevice().apply { prompt = FakeAdbDevice.Prompt.IGNORE }
        val session = newSession()
        val results = connect(session, silent)

        waitForState(session, AdbConnection.State.AUTHENTICATION_REQUIRED)
        assertNull("connect must still be waiting", results.poll(300, TimeUnit.MILLISECONDS))

        assertTrue(session.disconnect())
        assertTrue(results.poll(5, TimeUnit.SECONDS) is AdbSessionManager.ConnectResult.Cancelled)
        assertEquals(AdbConnection.State.DISCONNECTED, session.snapshot().state)

        // The engine must be immediately usable again.
        val second = connect(session, FakeAdbDevice(), AdbSessionManager.Link.WIRELESS)
        assertTrue(second.poll(10, TimeUnit.SECONDS) is AdbSessionManager.ConnectResult.Connected)
        assertEquals(AdbSessionManager.Link.WIRELESS, session.link)
        session.shutdown()
    }

    @Test
    fun secondConnectionIsRefusedWhileBusy() {
        val session = connected(FakeAdbDevice())
        val result = connect(session, FakeAdbDevice()).poll(5, TimeUnit.SECONDS)

        assertTrue(result is AdbSessionManager.ConnectResult.Failed)
        assertEquals("ADB_BUSY", (result as AdbSessionManager.ConnectResult.Failed).code)
        assertTrue(session.snapshot().connected)
        session.shutdown()
    }

    @Test
    fun shellV2ReturnsOutputErrorAndExitCode() {
        val device = FakeAdbDevice(shell = { command ->
            when (command) {
                "echo hi" -> FakeAdbDevice.Reply("hi\n")
                else -> FakeAdbDevice.Reply("", "sh: not found\n", 127)
            }
        })
        val session = connected(device)

        val ok = session.shell("echo hi")
        assertEquals("hi\n", ok.output)
        assertEquals(0, ok.exitCode)
        assertTrue(ok.usedShellV2)
        assertTrue(ok.succeeded)

        val bad = session.shell("nothing")
        assertEquals("sh: not found\n", bad.errorOutput)
        assertEquals(127, bad.exitCode)
        assertFalse(bad.succeeded)
        session.shutdown()
    }

    @Test
    fun legacyShellRecoversExitCode() {
        // Android 6 and older: no shell_v2, so no exit code on the wire.
        val device = FakeAdbDevice(shellV2 = false, shell = { command ->
            if (command == "id") FakeAdbDevice.Reply("uid=2000(shell)\n")
            else FakeAdbDevice.Reply("failed\n", exitCode = 3)
        })
        val session = connected(device)

        val ok = session.shell("id")
        assertEquals("uid=2000(shell)\n", ok.output)
        assertEquals(0, ok.exitCode)
        assertFalse(ok.usedShellV2)

        val bad = session.shell("false")
        assertEquals("failed\n", bad.output)
        assertEquals(3, bad.exitCode)
        session.shutdown()
    }

    @Test
    fun largeOutputIsReceivedCompletely() {
        val big = buildString { repeat(20000) { append("line ").append(it).append('\n') } }
        val device = FakeAdbDevice(shell = { FakeAdbDevice.Reply(big) })
        val session = connected(device)

        val result = session.shell("getprop")

        assertEquals(big.length, result.output.length)
        assertEquals(big, result.output)
        assertTrue("device reported: ${device.protocolErrors}", device.protocolErrors.isEmpty())
        session.shutdown()
    }

    @Test
    fun outputLimitStopsRunawayCommand() {
        val big = "x".repeat(300000)
        val session = connected(FakeAdbDevice(shell = { FakeAdbDevice.Reply(big) }))

        val result = session.shell("logcat", maxOutputBytes = 50000)

        assertTrue(result.truncated)
        assertEquals(50000, result.output.length)
        session.shutdown()
    }

    @Test
    fun timeoutLeavesConnectionUsable() {
        val device = FakeAdbDevice(shell = { command ->
            if (command == "hang") FakeAdbDevice.Reply("", neverFinish = true)
            else FakeAdbDevice.Reply("still alive\n")
        })
        val session = connected(device)

        try {
            session.shell("hang", timeoutMs = 300)
            fail("expected a timeout")
        } catch (expected: AdbTimeoutException) {
            assertTrue(expected.message!!.contains("did not finish"))
        }

        assertTrue(session.snapshot().connected)
        assertEquals("still alive\n", session.shell("echo").output)
        session.shutdown()
    }

    @Test
    fun commandsCanRunAtTheSameTime() {
        val device = FakeAdbDevice(shell = { command ->
            if (command == "slow") FakeAdbDevice.Reply("slow done\n", delayMs = 1500)
            else FakeAdbDevice.Reply("fast done\n")
        })
        val session = connected(device)

        val slowFinished = CountDownLatch(1)
        var slowOutput = ""
        Thread {
            slowOutput = session.shell("slow").output
            slowFinished.countDown()
        }.start()

        Thread.sleep(200)
        val started = System.currentTimeMillis()
        assertEquals("fast done\n", session.shell("fast").output)
        assertTrue("fast command waited for the slow one", System.currentTimeMillis() - started < 1000)

        assertTrue(slowFinished.await(5, TimeUnit.SECONDS))
        assertEquals("slow done\n", slowOutput)
        session.shutdown()
    }

    @Test
    fun idleConnectionStaysOpen() {
        // The old USB transport dropped the link after one quiet second.
        val session = connected(FakeAdbDevice(shell = { FakeAdbDevice.Reply("ok\n") }))

        Thread.sleep(1600)

        assertTrue(session.snapshot().connected)
        assertEquals("ok\n", session.shell("echo ok").output)
        session.shutdown()
    }

    @Test
    fun unpluggingFailsFastAndReportsWhy() {
        val device = FakeAdbDevice(shell = { command ->
            FakeAdbDevice.Reply("", neverFinish = command == "hang")
        })
        val session = connected(device)

        val error = LinkedBlockingQueue<Throwable>()
        Thread {
            try {
                session.shell("hang", timeoutMs = 30000)
                error.put(AssertionError("command should have failed"))
            } catch (e: Throwable) {
                error.put(e)
            }
        }.start()

        Thread.sleep(200)
        device.unplug()

        val failure = error.poll(3, TimeUnit.SECONDS)
        assertNotNull("pending command must fail promptly", failure)
        assertTrue(failure is IOException)

        waitForState(session, AdbConnection.State.FAILED)
        assertNotNull(session.snapshot().error)
        assertEquals(AdbSessionManager.Link.NONE, session.link)

        try {
            session.shell("id")
            fail("expected not-connected error")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("No phone is connected"))
        }

        // A lost session must not block the next connection.
        val again = connect(session, FakeAdbDevice().apply { trustedKeys.add(key.publicKey.modulus) })
        assertTrue(again.poll(10, TimeUnit.SECONDS) is AdbSessionManager.ConnectResult.Connected)
        session.shutdown()
    }

    @Test
    fun deviceIdentityIsReadOnConnect() {
        val device = FakeAdbDevice(shell = { command ->
            if (command.startsWith("getprop ro.product.manufacturer")) {
                FakeAdbDevice.Reply(
                    "Samsung\nsamsung\nSM-A125F\na12\n12\n31\n2023-05-01\nR58N123\nSP1A.210812.016\n"
                )
            } else {
                FakeAdbDevice.Reply("")
            }
        })
        val session = connected(device)
        val snapshot = session.snapshot()

        assertEquals("SM-A125F", snapshot.device["model"])
        assertEquals(31, session.targetSdk)
        assertEquals("Samsung SM-A125F (Android 12)", snapshot.deviceLabel)
        assertEquals("R58N123", snapshot.toMap()["device"].let { (it as Map<*, *>)["serial"] })
        session.shutdown()
    }

    @Test
    fun refusedServiceGivesClearError() {
        val session = connected(FakeAdbDevice())

        try {
            session.service("bogus:")
            fail("expected refusal")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("refused"))
        }

        assertTrue(session.snapshot().connected)
        session.shutdown()
    }

    @Test
    fun oneShotServiceReturnsDeviceText() {
        val session = connected(FakeAdbDevice())
        assertTrue(session.service("tcpip:5555").contains("5555"))
        session.shutdown()
    }
}
