package com.ultimate.debugging.ultimate_debugging_tool

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown when the device does not answer within the allowed time. */
class AdbTimeoutException(message: String) : IOException(message)

/**
 * One logical ADB stream (for example a shell command).
 *
 * Several streams can be open on the same connection. Closing or timing
 * out one stream never closes the connection itself.
 */
class AdbStream internal constructor(
    private val reader: AdbPacketReader,
    val localId: Int,
    val service: String,
    private val maxPayload: Int = 4096
) : AdbPacketReader.StreamPacketHandler, Closeable {

    private sealed class Event {
        class Data(val bytes: ByteArray) : Event()
        object RemoteClosed : Event()
        class Failed(val error: IOException) : Event()
    }

    private val events = LinkedBlockingQueue<Event>()
    private val opened = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val writeAcks = Semaphore(0)

    @Volatile
    private var remoteId = 0

    @Volatile
    private var remoteClosed = false

    @Volatile
    private var failure: IOException? = null

    @Volatile
    private var finished = false

    override fun onPacket(packet: AdbProtocol.AdbPacket) {
        when (packet.command) {
            AdbProtocol.CMD_OKAY -> {
                if (remoteId == 0 && packet.arg0 != 0) {
                    remoteId = packet.arg0
                    opened.countDown()
                } else if (remoteId != 0) {
                    // The device has taken the last block we sent.
                    writeAcks.release()
                }
            }

            AdbProtocol.CMD_WRTE -> {
                if (remoteId == 0 || packet.arg0 != remoteId) return

                events.put(Event.Data(packet.data))

                // The device waits for this before sending more data.
                try {
                    reader.writePacket(
                        AdbProtocol.AdbPacket(
                            command = AdbProtocol.CMD_OKAY,
                            arg0 = localId,
                            arg1 = remoteId
                        )
                    )
                } catch (_: IOException) {
                    // The read loop reports transport failures.
                }
            }

            AdbProtocol.CMD_CLSE -> {
                remoteClosed = true
                reader.unregisterStream(localId)

                if (!closed.get() && packet.arg0 != 0) {
                    try {
                        reader.writePacket(
                            AdbProtocol.AdbPacket(
                                command = AdbProtocol.CMD_CLSE,
                                arg0 = localId,
                                arg1 = packet.arg0
                            )
                        )
                    } catch (_: IOException) {
                        // The read loop reports transport failures.
                    }
                }

                events.put(Event.RemoteClosed)
                opened.countDown()
                writeAcks.release()
            }
        }
    }

    override fun onTransportFailed(error: IOException) {
        failure = error
        events.put(Event.Failed(error))
        opened.countDown()
        writeAcks.release()
    }

    /** Waits until the device accepts the service. */
    @Throws(IOException::class)
    internal fun awaitOpen(timeoutMs: Long) {
        val accepted = try {
            opened.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            close()
            throw IOException("ADB operation was interrupted.", error)
        }

        if (!accepted) {
            close()
            throw AdbTimeoutException(
                "The device did not answer the ADB request in time."
            )
        }

        failure?.let { throw it }

        if (remoteId == 0) {
            close()
            throw IOException(
                "The device refused the ADB service: ${describeService()}"
            )
        }
    }

    /**
     * Returns the next block of data, or null once the device has closed
     * the stream. Throws AdbTimeoutException if nothing arrives in time.
     */
    @Throws(IOException::class)
    fun read(timeoutMs: Long): ByteArray? {
        if (finished) return null

        val event = try {
            events.poll(maxOf(timeoutMs, 1L), TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("ADB operation was interrupted.", error)
        } ?: throw AdbTimeoutException(
            "The device did not send data in time."
        )

        return when (event) {
            is Event.Data -> event.bytes

            is Event.RemoteClosed -> {
                finished = true
                null
            }

            is Event.Failed -> {
                finished = true
                throw event.error
            }
        }
    }

    /**
     * Sends data to the device. ADB allows one unacknowledged block per
     * stream, so each block waits for the device's OKAY.
     */
    @Throws(IOException::class)
    fun write(data: ByteArray, timeoutMs: Long = 30000L) {
        var offset = 0

        while (offset < data.size) {
            if (closed.get()) throw IOException("The ADB stream is closed.")
            failure?.let { throw it }

            if (remoteClosed) {
                throw IOException("The device closed the ADB stream.")
            }

            val size = minOf(maxPayload, data.size - offset)

            reader.writePacket(
                AdbProtocol.AdbPacket(
                    command = AdbProtocol.CMD_WRTE,
                    arg0 = localId,
                    arg1 = remoteId,
                    data = data.copyOfRange(offset, offset + size)
                )
            )

            val acknowledged = try {
                writeAcks.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("ADB operation was interrupted.", error)
            }

            if (!acknowledged) {
                throw AdbTimeoutException(
                    "The device did not accept the data in time."
                )
            }

            failure?.let { throw it }

            if (remoteClosed) {
                throw IOException("The device closed the ADB stream.")
            }

            offset += size
        }
    }

    private fun describeService(): String {
        return service.substringBefore(':').ifBlank { service }
    }

    /** Closes this stream only. Safe to call more than once. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        reader.unregisterStream(localId)

        val remote = remoteId

        if (!remoteClosed && remote != 0 && failure == null) {
            try {
                reader.writePacket(
                    AdbProtocol.AdbPacket(
                        command = AdbProtocol.CMD_CLSE,
                        arg0 = localId,
                        arg1 = remote
                    )
                )
            } catch (_: IOException) {
                // The connection may already be unavailable.
            }
        }
    }
}
