package com.ultimate.debugging.ultimate_debugging_tool

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executes commands through an already authenticated ADB connection.
 *
 * Supports:
 * - Modern shell_v2 protocol.
 * - Legacy shell protocol.
 * - Standard output.
 * - Standard error.
 * - Exit codes when supported.
 *
 * Only one command is executed at a time by each session.
 */
class AdbShellSession(
    private val transport: AdbTransport,
    private val maxDataSize: Int,
    private val supportsShellV2: Boolean
) : Closeable {

    data class CommandResult(
        val output: String,
        val errorOutput: String,
        val exitCode: Int?,
        val usedShellV2: Boolean
    )

    companion object {
        private const val CHANNEL_STDIN = 0
        private const val CHANNEL_STDOUT = 1
        private const val CHANNEL_STDERR = 2
        private const val CHANNEL_EXIT = 3

        private const val DEFAULT_TIMEOUT_MS = 30000L
        private const val MIN_LOCAL_ID = 1
    }

    private val closed = AtomicBoolean(false)
    private val nextLocalId = AtomicInteger(MIN_LOCAL_ID)

    private val incomingPackets =
        LinkedBlockingQueue<AdbProtocol.AdbPacket>()

    @Volatile
    private var readerFailure: IOException? = null

    private val writeLock = Any()
    private val commandLock = Any()

    private val readerThread = Thread(
        {
            readIncomingPackets()
        },
        "UDT-ADB-Shell-Reader"
    ).apply {
        isDaemon = true
        start()
    }

    /**
     * Continuously reads packets from the transport.
     *
     * This is the only thread allowed to read ADB packets
     * after the shell session has been created.
     */
    private fun readIncomingPackets() {
    try {
        while (!closed.get()) {
            val packet = transport.readPacket()

            if (closed.get()) {
                break
            }

            incomingPackets.put(packet)
        }
    } catch (error: Exception) {
        if (!closed.get()) {
            readerFailure = IOException(
                "ADB shell reader failed: ${error.message}",
                error
            )
        }
    }
}

    /**
     * Executes one command on the connected Android device.
     */
    @Throws(IOException::class)
    fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult {

        synchronized(commandLock) {

            checkSessionOpen()

            if (command.isBlank()) {
                throw IOException("Command cannot be empty.")
            }

            if (timeoutMs <= 0) {
                throw IOException("Command timeout must be positive.")
            }

            val localId = nextLocalId.getAndIncrement()

            val service = if (supportsShellV2) {
                "shell,v2:$command\u0000"
            } else {
                "shell:$command\u0000"
            }

            val serviceBytes = service.toByteArray(
                StandardCharsets.UTF_8
            )

            if (serviceBytes.size > maxDataSize) {
                throw IOException(
                    "The command exceeds the negotiated ADB packet size."
                )
            }

            sendPacket(
                AdbProtocol.AdbPacket(
                    command = AdbProtocol.CMD_OPEN,
                    arg0 = localId,
                    arg1 = 0,
                    data = serviceBytes
                )
            )

            return receiveCommandResult(
                localId = localId,
                timeoutMs = timeoutMs
            )
        }
    }

    /**
     * Receives and processes packets for the active command.
     */
    private fun receiveCommandResult(
        localId: Int,
        timeoutMs: Long
    ): CommandResult {

        var remoteId: Int? = null
        var streamOpened = false
        var streamClosed = false

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val shellV2Buffer = ByteArrayOutputStream()

        var exitCode: Int? = null

        val deadline =
            System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(timeoutMs)

        try {

            while (!streamClosed) {

                checkSessionOpen()

                val remainingNanos =
                    deadline - System.nanoTime()

                if (remainingNanos <= 0) {
                    throw IOException(
                        "ADB command timed out after ${timeoutMs} ms."
                    )
                }

                readerFailure?.let { failure ->
    throw failure
}

val waitNanos = minOf(
    remainingNanos,
    TimeUnit.MILLISECONDS.toNanos(200)
)

val packet = incomingPackets.poll(
    waitNanos,
    TimeUnit.NANOSECONDS
)

if (packet == null) {
    readerFailure?.let { failure ->
        throw failure
    }

    continue
}

                when (packet.command) {

                    AdbProtocol.CMD_OKAY -> {

                        if (packet.arg1 != localId) {
                            continue
                        }

                        if (!streamOpened) {

                            remoteId = packet.arg0
                            streamOpened = true

                        } else if (remoteId != packet.arg0) {

                            throw IOException(
                                "Received an ADB acknowledgement for an unexpected stream."
                            )
                        }
                    }

                    AdbProtocol.CMD_WRTE -> {

                        if (!streamOpened ||
                            packet.arg0 != remoteId ||
                            packet.arg1 != localId
                        ) {
                            continue
                        }

                        if (supportsShellV2) {

                            processShellV2Data(
                                packet.data,
                                shellV2Buffer,
                                stdout,
                                stderr,
                                { code ->
                                    exitCode = code
                                }
                            )

                        } else {

                            stdout.write(packet.data)
                        }

                        sendPacket(
                            AdbProtocol.AdbPacket(
                                command = AdbProtocol.CMD_OKAY,
                                arg0 = localId,
                                arg1 = remoteId!!
                            )
                        )
                    }

                    AdbProtocol.CMD_CLSE -> {

                        if (packet.arg1 == localId &&
                            (remoteId == null ||
                                packet.arg0 == remoteId)
                        ) {

                            if (remoteId != null) {
                                sendPacket(
                                    AdbProtocol.AdbPacket(
                                        command = AdbProtocol.CMD_CLSE,
                                        arg0 = localId,
                                        arg1 = remoteId!!
                                    )
                                )
                            }

                            streamClosed = true
                        }
                    }

                    AdbProtocol.CMD_CNXN -> {
                        throw IOException(
                            "The device restarted the ADB connection."
                        )
                    }

                    AdbProtocol.CMD_AUTH -> {
                        throw IOException(
                            "The device requested ADB authentication again."
                        )
                    }
                }
            }

            if (!streamOpened) {
                throw IOException(
                    "The device closed the shell before opening the stream."
                )
            }

              return CommandResult(
                output = stdout.toString(StandardCharsets.UTF_8.name()),
                errorOutput = stderr.toString(StandardCharsets.UTF_8.name()),
                exitCode = exitCode,
                usedShellV2 = supportsShellV2
            )

        } catch (error: InterruptedException) {

            Thread.currentThread().interrupt()

            closeFailedStream(localId, remoteId, streamClosed)

            throw IOException(
                "ADB command execution was interrupted.",
                error
            )

        } catch (error: Exception) {

            closeFailedStream(localId, remoteId, streamClosed)

            throw if (error is IOException) {
                error
            } else {
                IOException(
                    "ADB command execution failed: ${error.message}",
                    error
                )
            }
        }
    }

    /**
 * Closes an unfinished shell stream after command failure.
 */
private fun closeFailedStream(
    localId: Int,
    remoteId: Int?,
    streamClosed: Boolean
) {
    if (remoteId == null || streamClosed) {
        return
    }

    try {
        sendPacket(
            AdbProtocol.AdbPacket(
                command = AdbProtocol.CMD_CLSE,
                arg0 = localId,
                arg1 = remoteId
            )
        )
    } catch (_: Exception) {
        // The connection may already be unavailable.
    }
}

    /**
     * Decodes shell_v2 channel frames.
     *
     * Each frame contains:
     * - One channel byte.
     * - Four-byte little-endian payload length.
     * - Payload bytes.
     *
     * Frames may be split across multiple ADB packets.
     */
    private fun processShellV2Data(
        incoming: ByteArray,
        buffer: ByteArrayOutputStream,
        stdout: ByteArrayOutputStream,
        stderr: ByteArrayOutputStream,
        onExitCode: (Int) -> Unit
    ) {

        buffer.write(incoming)

        var data = buffer.toByteArray()
        var offset = 0

        while (data.size - offset >= 5) {

            val channel = data[offset].toInt() and 0xFF

            val length = ByteBuffer
                .wrap(data, offset + 1, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

            if (length < 0 || length > AdbProtocol.MAX_DATA_SIZE) {
                throw IOException(
                    "Invalid ADB shell_v2 frame length."
                )
            }

            val frameSize = 5 + length

            if (data.size - offset < frameSize) {
                break
            }

            val payload = data.copyOfRange(
                offset + 5,
                offset + frameSize
            )

            when (channel) {

                CHANNEL_STDIN -> {
                    // No stdin data is expected for these commands.
                }

                CHANNEL_STDOUT -> {
                    stdout.write(payload)
                }

                CHANNEL_STDERR -> {
                    stderr.write(payload)
                }

                CHANNEL_EXIT -> {

                    if (payload.isNotEmpty()) {
                        onExitCode(
                            payload[0].toInt() and 0xFF
                        )
                    }
                }

                else -> {
                    throw IOException(
                        "Unknown ADB shell_v2 channel: $channel"
                    )
                }
            }

            offset += frameSize
        }

        buffer.reset()

        if (offset < data.size) {
            buffer.write(
                data,
                offset,
                data.size - offset
            )
        }
    }

    /**
     * Writes an ADB packet safely.
     */
    private fun sendPacket(
        packet: AdbProtocol.AdbPacket
    ) {

        checkSessionOpen()

        synchronized(writeLock) {

            checkSessionOpen()

            transport.writePacket(packet)
        }
    }

    /**
     * Prevents operations on a closed session.
     */
    private fun checkSessionOpen() {
    if (closed.get()) {
        throw IOException(
            "ADB shell session is closed."
        )
    }

    readerFailure?.let { failure ->
        throw failure
    }
}

    /**
     * Stops the shell reader.
     *
     * The owner of the ADB connection remains responsible
     * for closing the underlying transport.
     */
    override fun close() {

        if (!closed.compareAndSet(false, true)) {
            return
        }

        readerThread.interrupt()

        incomingPackets.clear()
    }
}
