package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single transport read loop after the ADB handshake.
 *
 * Incoming stream packets are routed by the host-local stream ID in arg1.
 * When the transport fails, every open stream is told so that waiting
 * callers return immediately instead of hanging.
 */
class AdbPacketReader(
    private val transport: AdbTransport,
    private val onReaderError: ((IOException) -> Unit)? = null
) : AutoCloseable {

    interface StreamPacketHandler {
        fun onPacket(packet: AdbProtocol.AdbPacket)
        fun onTransportFailed(error: IOException)
    }

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val streamHandlers =
        ConcurrentHashMap<Int, StreamPacketHandler>()

    @Volatile
    private var readerThread: Thread? = null

    /** Call only after AdbConnection completes the CNXN handshake. */
    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (closed.get()) throw IOException("ADB packet reader is closed.")

        if (!running.compareAndSet(false, true)) {
            throw IOException("ADB packet reader is already running.")
        }

        val thread = Thread({ readLoop() }, "UDT-ADB-Reader").apply {
            isDaemon = true
        }

        readerThread = thread
        thread.start()
    }

    /** Registers a handler for a host-local ADB stream ID. */
    @Throws(IOException::class)
    fun registerStream(localStreamId: Int, handler: StreamPacketHandler) {
        if (localStreamId <= 0) {
            throw IOException("ADB stream ID must be positive.")
        }

        if (closed.get()) throw IOException("ADB connection is closed.")

        if (streamHandlers.putIfAbsent(localStreamId, handler) != null) {
            throw IOException(
                "An ADB handler is already registered for this stream."
            )
        }

        // The reader may have stopped between the check and the insert.
        if (closed.get() || !running.get()) {
            streamHandlers.remove(localStreamId)
            throw IOException("ADB connection is closed.")
        }
    }

    fun unregisterStream(localStreamId: Int) {
        streamHandlers.remove(localStreamId)
    }

    /** Transports serialize concurrent writes themselves. */
    @Throws(IOException::class)
    fun writePacket(packet: AdbProtocol.AdbPacket) {
        if (closed.get()) throw IOException("ADB connection is closed.")
        transport.writePacket(packet)
    }

    private fun readLoop() {
        var failure: IOException? = null

        try {
            while (running.get() && !closed.get()) {
                dispatch(transport.readPacket())
            }
        } catch (error: Exception) {
            failure = if (error is IOException) {
                error
            } else {
                IOException(
                    "ADB packet reader stopped unexpectedly.",
                    error
                )
            }
        } finally {
            running.set(false)
        }

        val wasClosed = closed.get()

        failAllStreams(
            failure ?: IOException("ADB connection is closed.")
        )

        if (!wasClosed) {
            try {
                onReaderError?.invoke(
                    failure ?: IOException("ADB connection ended.")
                )
            } catch (_: Exception) {
                // Observer failures must not crash the reader thread.
            }
        }
    }

    private fun dispatch(packet: AdbProtocol.AdbPacket) {
        when (packet.command) {
            AdbProtocol.CMD_OKAY,
            AdbProtocol.CMD_WRTE,
            AdbProtocol.CMD_CLSE -> {
                val handler = streamHandlers[packet.arg1]

                if (handler != null) {
                    try {
                        handler.onPacket(packet)
                    } catch (_: Exception) {
                        // One faulty stream must not stop the others.
                    }
                } else if (
                    packet.command != AdbProtocol.CMD_CLSE &&
                    packet.arg0 != 0
                ) {
                    // The stream was already closed on this side.
                    // Tell the device so it stops sending.
                    try {
                        transport.writePacket(
                            AdbProtocol.AdbPacket(
                                command = AdbProtocol.CMD_CLSE,
                                arg0 = packet.arg1,
                                arg1 = packet.arg0
                            )
                        )
                    } catch (_: Exception) {
                        // The read loop reports transport failures.
                    }
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

            else -> {
                // OPEN from the device and unknown commands are ignored.
            }
        }
    }

    private fun failAllStreams(error: IOException) {
        val handlers = streamHandlers.values.toList()
        streamHandlers.clear()

        for (handler in handlers) {
            try {
                handler.onTransportFailed(error)
            } catch (_: Exception) {
                // Continue notifying the remaining streams.
            }
        }
    }

    /**
     * Stops the reader and closes the transport to unblock a pending read.
     * The owning connection must not reuse this transport afterwards.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        running.set(false)

        try {
            transport.close()
        } catch (_: Exception) {
            // Continue shutdown.
        }

        readerThread?.interrupt()
        readerThread = null

        failAllStreams(IOException("ADB connection is closed."))
    }
}
