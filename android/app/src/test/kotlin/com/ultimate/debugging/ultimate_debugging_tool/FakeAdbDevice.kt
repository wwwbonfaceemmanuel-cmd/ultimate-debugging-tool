package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher

/**
 * A small stand-in for the ADB daemon of a phone, used by the tests.
 *
 * It follows the real protocol rules: it checks RSA signatures with the
 * JDK's own RSA code (not the app's), only accepts keys the "user" has
 * approved, and waits for an OKAY after every block of data it sends.
 */
class FakeAdbDevice(
    private val shellV2: Boolean = true,
    private val shell: (String) -> Reply = { Reply("") }
) {

    class Reply(
        val stdout: String,
        val stderr: String = "",
        val exitCode: Int = 0,
        val delayMs: Long = 0,
        val neverFinish: Boolean = false
    )

    enum class Prompt { APPROVE, IGNORE }

    @Volatile var prompt = Prompt.APPROVE
    @Volatile var promptDelayMs = 0L
    @Volatile var promptsShown = 0
    @Volatile var chunkSize = 4096

    /** The identity text the workstation sent when it connected. */
    @Volatile var hostBanner = ByteArray(0)

    val trustedKeys = ConcurrentHashMap.newKeySet<BigInteger>()
    val openedServices = LinkedBlockingQueue<String>()

    /** In-memory files served by the "sync:" file transfer service. */
    val files = ConcurrentHashMap<String, ByteArray>()
    private val inputs = ConcurrentHashMap<Int, LinkedBlockingQueue<ByteArray>>()
    val protocolErrors = LinkedBlockingQueue<String>()

    private val toDevice = LinkedBlockingQueue<ByteArray>()
    private val toHost = LinkedBlockingQueue<ByteArray>()
    private val poison = ByteArray(0)

    private val nextStreamId = AtomicInteger(100)
    private val windows = ConcurrentHashMap<Int, Semaphore>()
    private val publicKeys = ConcurrentHashMap<BigInteger, PublicKey>()

    @Volatile private var token = ByteArray(0)
    @Volatile private var running = true

    private val thread = Thread({ serve() }, "FakeAdbDevice").apply {
        isDaemon = true
        start()
    }

    /** The host side of the cable. */
    val transport = object : AdbTransport {
        @Volatile private var closed = false

        override fun open() {}

        override fun writePacket(packet: AdbProtocol.AdbPacket) {
            if (closed) throw IOException("closed")
            toDevice.put(AdbProtocol.encode(packet))
        }

        override fun readPacket(): AdbProtocol.AdbPacket {
            if (closed) throw IOException("closed")
            val bytes = toHost.take()
            if (bytes === poison || closed) {
                toHost.put(poison)
                throw IOException("The device was unplugged.")
            }
            return AdbProtocol.decode(bytes)
        }

        override fun close() {
            closed = true
            toHost.put(poison)
        }
    }

    /** Simulates pulling the cable out. */
    fun unplug() {
        running = false
        toHost.put(poison)
        toDevice.put(poison)
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        toHost.put(AdbProtocol.encode(AdbProtocol.AdbPacket(command, arg0, arg1, data)))
    }

    private fun sendConnected() {
        val features = if (shellV2) "shell_v2,cmd" else "cmd"
        send(
            AdbProtocol.CMD_CNXN,
            AdbProtocol.VERSION,
            256 * 1024,
            "device::ro.product.name=test;ro.product.model=Test Phone;features=$features"
                .toByteArray()
        )
    }

    private fun challenge() {
        token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        send(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
    }

    private fun serve() {
        while (running) {
            val bytes = toDevice.take()
            if (bytes === poison) return

            val packet = AdbProtocol.decode(bytes)

            when (packet.command) {
                AdbProtocol.CMD_CNXN -> {
                    hostBanner = packet.data
                    challenge()
                }

                AdbProtocol.CMD_AUTH -> when (packet.arg0) {
                    AdbProtocol.AUTH_SIGNATURE -> {
                        if (trustedKeys.any { verify(it, token, packet.data) }) {
                            sendConnected()
                        } else {
                            challenge()
                        }
                    }

                    AdbProtocol.AUTH_PUBLIC_KEY -> {
                        val modulus = parsePublicKey(packet.data)
                        promptsShown++

                        if (prompt == Prompt.APPROVE) {
                            Thread {
                                Thread.sleep(promptDelayMs)
                                trustedKeys.add(modulus)
                                sendConnected()
                            }.start()
                        }
                    }
                }

                AdbProtocol.CMD_OPEN -> open(packet)

                AdbProtocol.CMD_OKAY -> windows[packet.arg1]?.release()

                AdbProtocol.CMD_WRTE -> {
                    inputs[packet.arg1]?.put(packet.data)
                    send(AdbProtocol.CMD_OKAY, packet.arg1, packet.arg0)
                }

                AdbProtocol.CMD_CLSE -> windows.remove(packet.arg1)?.release(1000)
            }
        }
    }

    private fun open(packet: AdbProtocol.AdbPacket) {
        val hostId = packet.arg0
        val service = String(packet.data).trimEnd('\u0000')
        openedServices.put(service)

        val v2 = service.startsWith("shell,v2,raw:")
        val legacy = service.startsWith("shell:")

        val sync = service == "sync:"

        if (!(v2 && shellV2) && !legacy && !sync && !service.startsWith("tcpip:")) {
            // Refused service: CLSE with no stream id.
            send(AdbProtocol.CMD_CLSE, 0, hostId)
            return
        }

        val deviceId = nextStreamId.getAndIncrement()
        val window = Semaphore(0)
        windows[deviceId] = window

        if (sync) inputs[deviceId] = LinkedBlockingQueue()

        Thread {
            send(AdbProtocol.CMD_OKAY, deviceId, hostId)

            if (sync) {
                serveSync(deviceId, hostId, window)
                send(AdbProtocol.CMD_CLSE, deviceId, hostId)
                return@Thread
            }

            if (service.startsWith("tcpip:")) {
                write(deviceId, hostId, window, "restarting in TCP mode port: 5555\n".toByteArray())
                send(AdbProtocol.CMD_CLSE, deviceId, hostId)
                return@Thread
            }

            val command = service.substringAfter(':')

            if (v2) {
                val reply = shell(command)
                Thread.sleep(reply.delayMs)
                if (reply.neverFinish) return@Thread

                val out = reply.stdout.toByteArray()
                var offset = 0
                while (offset < out.size) {
                    val size = minOf(chunkSize, out.size - offset)
                    write(deviceId, hostId, window, frame(1, out.copyOfRange(offset, offset + size)))
                    offset += size
                }
                if (reply.stderr.isNotEmpty()) {
                    write(deviceId, hostId, window, frame(2, reply.stderr.toByteArray()))
                }
                write(deviceId, hostId, window, frame(3, byteArrayOf(reply.exitCode.toByte())))
            } else {
                // Legacy shell: the app appends its own exit-code marker.
                val userCommand = command.substringBefore("\necho \"")
                val reply = shell(userCommand)
                Thread.sleep(reply.delayMs)
                if (reply.neverFinish) return@Thread

                val marker = command.substringAfter("\necho \"").substringBefore("\$?")
                val text = (reply.stdout + reply.stderr).replace("\n", "\r\n") +
                    marker + reply.exitCode + "\r\n"
                write(deviceId, hostId, window, text.toByteArray())
            }

            send(AdbProtocol.CMD_CLSE, deviceId, hostId)
        }.start()
    }

    private fun write(deviceId: Int, hostId: Int, window: Semaphore, data: ByteArray) {
        send(AdbProtocol.CMD_WRTE, deviceId, hostId, data)

        // A real device sends nothing more until the host acknowledges.
        if (!window.tryAcquire(5, TimeUnit.SECONDS)) {
            protocolErrors.put("Host did not acknowledge data on stream $hostId")
        }
    }

    private fun serveSync(deviceId: Int, hostId: Int, window: Semaphore) {
        val input = inputs[deviceId]!!
        var buffer = ByteArray(0)

        fun read(count: Int): ByteArray? {
            while (buffer.size < count) {
                val more = input.poll(5, TimeUnit.SECONDS) ?: return null
                buffer += more
            }
            val result = buffer.copyOfRange(0, count)
            buffer = buffer.copyOfRange(count, buffer.size)
            return result
        }

        fun int(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int

        fun reply(id: String, vararg values: Int, extra: ByteArray = ByteArray(0)) {
            val out = ByteBuffer.allocate(4 + 4 * values.size + extra.size).order(ByteOrder.LITTLE_ENDIAN)
            out.put(id.toByteArray())
            values.forEach { out.putInt(it) }
            out.put(extra)
            write(deviceId, hostId, window, out.array())
        }

        fun isDirectory(path: String) = files.keys.any { it.startsWith(path.trimEnd('/') + "/") }

        while (true) {
            val head = read(8) ?: return
            val id = String(head, 0, 4)
            val length = int(head.copyOfRange(4, 8))
            if (id == "QUIT") return
            val path = String(read(length) ?: return)

            when (id) {
                "STAT" -> {
                    val file = files[path]
                    when {
                        file != null -> reply("STAT", 0x81A4, file.size, 1700000000)
                        isDirectory(path) -> reply("STAT", 0x41ED, 4096, 1700000000)
                        else -> reply("STAT", 0, 0, 0)
                    }
                }

                "LIST" -> {
                    val prefix = path.trimEnd('/') + "/"
                    val names = files.keys.filter { it.startsWith(prefix) }
                        .map { it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
                    reply("DENT", 0x41ED, 4096, 1700000000, 1, extra = ".".toByteArray())
                    for (name in names) {
                        val file = files[prefix + name]
                        val bytes = name.toByteArray()
                        if (file != null) {
                            reply("DENT", 0x81A4, file.size, 1700000000, bytes.size, extra = bytes)
                        } else {
                            reply("DENT", 0x41ED, 4096, 1700000000, bytes.size, extra = bytes)
                        }
                    }
                    reply("DONE", 0, 0, 0, 0)
                }

                "RECV" -> {
                    val file = files[path]
                    if (file == null) {
                        val message = "No such file or directory".toByteArray()
                        reply("FAIL", message.size, extra = message)
                    } else {
                        var offset = 0
                        while (offset < file.size) {
                            val size = minOf(64 * 1024, file.size - offset)
                            reply("DATA", size, extra = file.copyOfRange(offset, offset + size))
                            offset += size
                        }
                        reply("DONE", 0)
                    }
                }

                "SEND" -> {
                    val target = path.substringBeforeLast(',')
                    var content = ByteArray(0)
                    while (true) {
                        val chunk = read(8) ?: return
                        val size = int(chunk.copyOfRange(4, 8))
                        if (String(chunk, 0, 4) == "DONE") break
                        content += read(size) ?: return
                    }
                    if (target.startsWith("/system/")) {
                        val message = "Read-only file system".toByteArray()
                        reply("FAIL", message.size, extra = message)
                    } else {
                        files[target] = content
                        reply("OKAY", 0)
                    }
                }
            }
        }
    }

    private fun frame(channel: Int, data: ByteArray): ByteArray {
        return ByteBuffer.allocate(5 + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(channel.toByte())
            .putInt(data.size)
            .put(data)
            .array()
    }

    /** Decodes "base64 comment\0" into the RSA modulus. */
    private fun parsePublicKey(payload: ByteArray): BigInteger {
        val text = String(payload)
        require(text.endsWith("\u0000")) { "public key must end with NUL" }

        val blob = Base64.getDecoder().decode(text.substringBefore(' '))
        require(blob.size == 524) { "public key blob must be 524 bytes" }

        val words = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        require(words.int == 64) { "modulus length must be 64 words" }

        val modulus = BigInteger(1, blob.copyOfRange(8, 264).reversedArray())
        val exponent = BigInteger.valueOf(
            ByteBuffer.wrap(blob, 520, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
        )

        publicKeys[modulus] = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))

        return modulus
    }

    /** Same check as the phone: RSA_verify(NID_sha1, token, signature). */
    private fun verify(modulus: BigInteger, token: ByteArray, signature: ByteArray): Boolean {
        val key = publicKeys[modulus] ?: KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)))

        return try {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            val recovered = cipher.doFinal(signature)

            val sha1DigestInfo = byteArrayOf(
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
                0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
            )

            recovered.contentEquals(sha1DigestInfo + token)
        } catch (_: Exception) {
            false
        }
    }
}
