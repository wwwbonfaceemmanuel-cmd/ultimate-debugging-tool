package com.ultimate.debugging.ultimate_debugging_tool

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP transport for Android Debug Bridge.
 *
 * Supports standard ADB communication over a TCP connection,
 * including wireless ADB.
 *
 * Socket lifecycle, packet reads and writes are synchronized
 * independently to support safe cancellation and cleanup.
 */
class AdbTcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 15000
) : AdbTransport {

    private val lifecycleLock = Any()
    private val readLock = Any()
    private val writeLock = Any()

    private val closed = AtomicBoolean(true)

    @Volatile
    private var socket: Socket? = null

    override fun open() {
        if (host.isBlank()) {
            throw IOException("ADB host cannot be empty.")
        }

        if (port !in 1..65535) {
            throw IOException("Invalid ADB port: $port")
        }

        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw IOException("Socket timeouts must be greater than zero.")
        }

        val newSocket = Socket()

        synchronized(lifecycleLock) {
            if (!closed.get() || socket != null) {
                throw IOException("ADB TCP transport is already open.")
            }

            socket = newSocket
            closed.set(false)
        }

        try {
            newSocket.tcpNoDelay = true
            newSocket.keepAlive = true

            newSocket.connect(
                InetSocketAddress(host, port),
                connectTimeoutMs
            )

            synchronized(lifecycleLock) {
                if (closed.get() || socket !== newSocket) {
                    throw IOException(
                        "ADB TCP connection was cancelled."
                    )
                }

                newSocket.soTimeout = readTimeoutMs
            }

        } catch (error: Exception) {
            closeSocketAfterFailure(newSocket)

            if (error is IOException) {
                throw error
            }

            throw IOException(
                "Failed to open ADB TCP connection.",
                error
            )
        }
    }

    override fun writePacket(packet: AdbProtocol.AdbPacket) {
        synchronized(writeLock) {
            val activeSocket = requireOpenSocket()

            try {
                val output = activeSocket.getOutputStream()
                val encodedPacket = AdbProtocol.encode(packet)

                output.write(encodedPacket)
                output.flush()

            } catch (error: Exception) {
                if (error is IOException) {
                    throw transportError(
                        "Failed to write ADB packet.",
                        error
                    )
                }

                throw transportError(
                    "Unexpected ADB packet write failure.",
                    error
                )
            }
        }
    }

    override fun readPacket(): AdbProtocol.AdbPacket {
        synchronized(readLock) {
            val activeSocket = requireOpenSocket()

            try {
                val input = activeSocket.getInputStream()

                val header = ByteArray(AdbProtocol.HEADER_SIZE)

                readFully(input, header)

                val headerBuffer = ByteBuffer.wrap(header)
                    .order(ByteOrder.LITTLE_ENDIAN)

                headerBuffer.int
                headerBuffer.int
                headerBuffer.int

                val payloadLength = headerBuffer.int

                if (payloadLength < 0 ||
                    payloadLength > AdbProtocol.MAX_DATA_SIZE
                ) {
                    throw IOException(
                        "Invalid ADB payload length: $payloadLength"
                    )
                }

                val completePacket = ByteArray(
                    AdbProtocol.HEADER_SIZE + payloadLength
                )

                System.arraycopy(
                    header,
                    0,
                    completePacket,
                    0,
                    header.size
                )

                if (payloadLength > 0) {
                    val payload = ByteArray(payloadLength)

                    readFully(input, payload)

                    System.arraycopy(
                        payload,
                        0,
                        completePacket,
                        AdbProtocol.HEADER_SIZE,
                        payloadLength
                    )
                }

                return AdbProtocol.decode(completePacket)

            } catch (error: Exception) {
                if (error is IOException) {
                    throw transportError(
                        "Failed to read ADB packet.",
                        error
                    )
                }

                throw transportError(
                    "Unexpected ADB packet read failure.",
                    error
                )
            }
        }
    }

    /**
     * Reads exactly the requested number of bytes.
     *
     * Temporary socket read timeouts are retried while the
     * transport remains active. Closing the transport stops
     * the retry loop.
     */
    private fun readFully(
        input: InputStream,
        buffer: ByteArray
    ) {
        var offset = 0

        while (offset < buffer.size) {
            if (closed.get()) {
                throw IOException(
                    "ADB TCP transport was closed during reading."
                )
            }

            try {
                val count = input.read(
                    buffer,
                    offset,
                    buffer.size - offset
                )

                if (count < 0) {
                    throw EOFException(
                        "ADB TCP connection ended unexpectedly."
                    )
                }

                if (count == 0) {
                    continue
                }

                offset += count

            } catch (error: SocketTimeoutException) {
                if (closed.get()) {
                    throw IOException(
                        "ADB TCP connection was cancelled.",
                        error
                    )
                }
            }
        }
    }

    /**
     * Returns the active socket or throws if disconnected.
     */
    private fun requireOpenSocket(): Socket {
        synchronized(lifecycleLock) {
            if (closed.get()) {
                throw IOException(
                    "ADB TCP transport is not connected."
                )
            }

            val activeSocket = socket
                ?: throw IOException(
                    "ADB TCP socket is unavailable."
                )

            if (activeSocket.isClosed ||
                !activeSocket.isConnected
            ) {
                throw IOException(
                    "ADB TCP socket is no longer connected."
                )
            }

            return activeSocket
        }
    }

    /**
     * Closes a socket after a failed connection attempt.
     */
    private fun closeSocketAfterFailure(
        failedSocket: Socket
    ) {
        synchronized(lifecycleLock) {
            if (socket === failedSocket) {
                socket = null
                closed.set(true)
            }
        }

        try {
            failedSocket.close()
        } catch (_: IOException) {
            // Cleanup must not hide the original failure.
        }
    }

    /**
     * Adds transport context to an I/O failure.
     */
    private fun transportError(
        message: String,
        cause: Throwable
    ): IOException {
        return IOException(message, cause)
    }

    override fun close() {
        val socketToClose: Socket?

        synchronized(lifecycleLock) {
            closed.set(true)

            socketToClose = socket
            socket = null
        }

        try {
            socketToClose?.close()
        } catch (_: IOException) {
            // Socket cleanup is best-effort.
        }
    }
}
