
package com.ultimate.debugging.ultimate_debugging_tool

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP transport for Android Debug Bridge.
 *
 * Allows the shared ADB engine to communicate with
 * devices over a wireless network.
 *
 * This transport handles the standard ADB connection.
 * Modern Wireless Debugging pairing is a separate protocol
 * and will be implemented independently.
 */
class AdbTcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 15000
) : AdbTransport {

    companion object {
        private const val MAX_TRANSFER_CHUNK = 16384
    }

    private val closed = AtomicBoolean(true)

    private val readLock = Any()
    private val writeLock = Any()

    @Volatile
    private var socket: Socket? = null

    /**
     * Opens the TCP connection.
     */
    @Synchronized
    @Throws(IOException::class)
    override fun open() {
        if (socket != null && socket?.isConnected == true && !socket!!.isClosed) {
            throw IOException("TCP transport is already open.")
        }

        if (host.isBlank()) {
            throw IOException("A valid device IP address is required.")
        }

        if (port !in 1..65535) {
            throw IOException("Invalid TCP port.")
        }

        val newSocket = Socket()

        try {
            newSocket.tcpNoDelay = true
            newSocket.keepAlive = true

            newSocket.connect(
                InetSocketAddress(host, port),
                connectTimeoutMs
            )

            newSocket.soTimeout = readTimeoutMs

            socket = newSocket
            closed.set(false)

        } catch (error: Exception) {
            try {
                newSocket.close()
            } catch (_: Exception) {
                // Continue with error handling.
            }

            throw if (error is IOException) {
                error
            } else {
                IOException("Unable to establish wireless ADB connection.", error)
            }
        }
    }

    /**
     * Sends one complete ADB packet.
     */
    @Throws(IOException::class)
    override fun writePacket(packet: AdbProtocol.AdbPacket) {
        synchronized(writeLock) {
            val activeSocket = requireOpenSocket()

            try {
                val bytes = AdbProtocol.encode(packet)
                val output = activeSocket.getOutputStream()

                output.write(bytes)
                output.flush()

            } catch (error: Exception) {
                throw transportError(
                    "Failed to send ADB packet.",
                    error
                )
            }
        }
    }

    /**
     * Receives one complete ADB packet.
     *
     * Reads the ADB header first, validates its payload length,
     * then reads the complete payload.
     */
    @Throws(IOException::class)
    override fun readPacket(): AdbProtocol.AdbPacket {
        synchronized(readLock) {
            val activeSocket = requireOpenSocket()

            try {
                val input = activeSocket.getInputStream()

                val header = ByteArray(AdbProtocol.HEADER_SIZE)
                readFully(input, header)

                val headerBuffer = java.nio.ByteBuffer
                    .wrap(header)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)

                headerBuffer.int
                headerBuffer.int
                headerBuffer.int

                val dataLength = headerBuffer.int

                if (
                    dataLength < 0 ||
                    dataLength > AdbProtocol.MAX_DATA_SIZE
                ) {
                    throw IOException("Invalid ADB packet payload length.")
                }

                val completePacket = ByteArray(
                    AdbProtocol.HEADER_SIZE + dataLength
                )

                System.arraycopy(
                    header,
                    0,
                    completePacket,
                    0,
                    header.size
                )

                if (dataLength > 0) {
                    val payload = ByteArray(dataLength)
                    readFully(input, payload)

                    System.arraycopy(
                        payload,
                        0,
                        completePacket,
                        AdbProtocol.HEADER_SIZE,
                        dataLength
                    )
                }

                return try {
                    AdbProtocol.decode(completePacket)
                } catch (error: IllegalArgumentException) {
                    throw IOException(
                        "Received an invalid ADB packet.",
                        error
                    )
                }

            } catch (error: Exception) {
                throw transportError(
                    "Failed to receive ADB packet.",
                    error
                )
            }
        }
    }

    /**
     * Reads until the requested number of bytes is received.
     */
    @Throws(IOException::class)
    private fun readFully(
        input: java.io.InputStream,
        buffer: ByteArray
    ) {
        var offset = 0

        while (offset < buffer.size) {
            if (closed.get()) {
                throw IOException("TCP transport is closed.")
            }

            val received = input.read(
                buffer,
                offset,
                minOf(buffer.size - offset, MAX_TRANSFER_CHUNK)
            )

            if (received < 0) {
                throw EOFException(
                    "Wireless ADB device closed the connection."
                )
            }

            if (received == 0) {
                continue
            }

            offset += received
        }
    }

    private fun requireOpenSocket(): Socket {
        if (closed.get()) {
            throw IOException("TCP transport is closed.")
        }

        val activeSocket = socket
            ?: throw IOException("TCP socket has not been opened.")

        if (activeSocket.isClosed || !activeSocket.isConnected) {
            throw IOException("Wireless ADB connection is unavailable.")
        }

        return activeSocket
    }

    private fun transportError(
        message: String,
        error: Exception
    ): IOException {
        if (error is IOException) {
            return error
        }

        return IOException(message, error)
    }

    /**
     * Closes the socket.
     *
     * Closing the socket also interrupts a blocked read,
     * allowing connection cancellation.
     */
    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        val activeSocket = socket
        socket = null

        try {
            activeSocket?.shutdownInput()
        } catch (_: Exception) {
            // Socket may already be closed.
        }

        try {
            activeSocket?.shutdownOutput()
        } catch (_: Exception) {
            // Socket may already be closed.
        }

        try {
            activeSocket?.close()
        } catch (_: Exception) {
            // Continue with cleanup.
        }
    }
}
