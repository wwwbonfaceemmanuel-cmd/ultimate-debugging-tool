package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single transport read loop after the ADB handshake has completed.
 *
 * Incoming stream packets are routed by the host-local stream ID in arg1.
 * Stream handlers are responsible for protocol actions such as acknowledging
 * WRTE packets and closing their own streams.
 */
class AdbPacketReader(
    private val transport: AdbTransport,
    private val onReaderError: ((IOException) -> Unit)? = null,
    private val onUnexpectedPacket: ((AdbProtocol.AdbPacket) -> Unit)? = null
) : AutoCloseable {

    fun interface StreamPacketHandler {
        fun onPacket(packet: AdbProtocol.AdbPacket)
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

        val thread = Thread({ readLoop() }, "ADB-Packet-Reader").apply {
            isDaemon = true
        }
        readerThread = thread
        thread.start()
    }

    /** Registers a handler for a host-local ADB stream ID. */
    @Throws(IOException::class)
    fun registerStream(localStreamId: Int, handler: StreamPacketHandler) {
        if (localStreamId <= 0) throw IOException("ADB stream ID must be positive.")
        if (closed.get()) throw IOException("ADB packet reader is closed.")

        if (streamHandlers.putIfAbsent(localStreamId, handler) != null) {
            throw IOException("An ADB handler is already registered for this stream.")
        }
    }

    fun unregisterStream(localStreamId: Int) {
        streamHandlers.remove(localStreamId)
    }

    /** Writes through the same transport; transports serialize concurrent writes. */
    @Throws(IOException::class)
    fun writePacket(packet: AdbProtocol.AdbPacket) {
        if (closed.get()) throw IOException("ADB packet reader is closed.")
        transport.writePacket(packet)
    }

    private fun readLoop() {
        try {
            while (running.get() && !closed.get()) {
                val packet = transport.readPacket()
                when (packet.command) {
                    AdbProtocol.CMD_OKAY,
                    AdbProtocol.CMD_WRTE,
                    AdbProtocol.CMD_CLSE -> {
                        // Device-to-host packets identify our stream in arg1.
                        val handler = streamHandlers[packet.arg1]
                        if (handler != null) {
                            try {
                                handler.onPacket(packet)
                            } catch (error: Exception) {
                                notifyReaderError(
                                    IOException("ADB stream packet handler failed.", error)
                                )
                            }
                        } else {
                            notifyUnexpectedPacket(packet)
                        }
                    }
                    else -> notifyUnexpectedPacket(packet)
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                val ioError = if (error is IOException) error
                    else IOException("ADB packet reader stopped unexpectedly.", error)
                notifyReaderError(ioError)
            }
        } finally {
            running.set(false)
        }
    }

    private fun notifyReaderError(error: IOException) {
        try {
            onReaderError?.invoke(error)
        } catch (_: Exception) {
            // Observer failures must not crash the packet reader.
        }
    }

    private fun notifyUnexpectedPacket(packet: AdbProtocol.AdbPacket) {
        try {
            onUnexpectedPacket?.invoke(packet)
        } catch (_: Exception) {
            // Observer failures must not crash the packet reader.
        }
    }

    /**
     * Stops the reader and closes the transport to unblock a pending read.
     * The owning connection must not reuse this transport afterwards.
     */
    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        streamHandlers.clear()
        try {
            transport.close()
        } catch (_: Exception) {
            // Continue shutdown.
        }
        readerThread?.interrupt()
        readerThread = null
    }
}
