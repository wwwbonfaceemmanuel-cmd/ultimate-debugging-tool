
package com.ultimate.debugging.ultimate_debugging_tool

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP transport for Android Debug Bridge.
 *
 * Allows the shared ADB engine to communicate with
 * devices over a wireless network.
 *
 * Modern Wireless Debugging pairing is a separate protocol
 * and is implemented independently.
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

    private val lifecycleLock = Any()
    private val readLock = Any()
    private val writeLock = Any()

    @Volatile
    private var socket: Socket? = null

    init {
        require(connectTimeoutMs > 0) {
            "TCP connection timeout must be positive."
        }

        require(readTimeoutMs > 0) {
            "TCP read timeout must be positive."
        }
    }

    /**
     * Opens the TCP connection.
     *
     * The socket is registered before connecting so that
     * close() can cancel an in-progress connection attempt.
     */
    @Throws(IOException::class)
    override fun open() {

        if (host.isBlank()) {
            throw IOException(
                "A valid device IP address is required."
            )
        }

        if (port !in 1..65535) {
            throw IOException("Invalid TCP port.")
        }

        val newSocket = Socket()

        synchronized(lifecycleLock) {

            if (!closed.get()) {
                throw IOException(
                    "TCP transport is already open or connecting."
                )
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

            if (
                closed.get() ||
                socket !== newSocket
            ) {
                throw IOException(
                    "TCP connection was cancelled."
                )
            }

            newSocket.soTimeout = readTimeoutMs

            if (
                closed.get() ||
                socket !== newSocket
            ) {
                throw IOException(
                    "TCP connection was cancelled."
                )
            }

        } catch (error: Exception) {

            synchronized(lifecycleLock) {
                if (socket === newSocket) {
                    socket = null
                    closed.set(true)
                }
            }

            try {
                newSocket.close()
            } catch (_: Exception) {
                // Continue with error handling.
            }

            throw if (error is IOException) {
                error
            } else {
                IOException(
                    "Unable to establish wireless ADB connection.",
                    error
                )
            }
        }
    }

    /**
     * Sends one complete ADB packet.
     */
    @Throws(IOException::class)
    override fun writePacket(
        packet: AdbProtocol.AdbPacket
    ) {
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
     * Reads and validates the ADB header before
     * allocating and reading the payload.
     */
    @Throws(IOException::class)
    override fun readPacket(): AdbProtocol.AdbPacket {

        synchronized(readLock) {

            val activeSocket = requireOpenSocket()

            try {
                val input = activeSocket.getInputStream()

                val header = ByteArray(
                    AdbProtocol.HEADER_SIZE
                )

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
                    throw IOException(
                        "Invalid ADB packet payload length."
                    )
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
     *
     * Temporary socket timeouts do not invalidate an
     * otherwise open connection.
     */
    @Throws(IOException::class)
    private fun readFully(
        input: InputStream,
        buffer: ByteArray
    ) {
        var offset = 0

        while (offset < buffer.size) {

            if (closed.get()) {
                throw IOException(
                    "TCP transport is closed."
                )
            }

            try {
                val received = input.read(
                    buffer,
                    offset,
                    minOf(
                        buffer.size - offset,
                        MAX_TRANSFER_CHUNK
                    )
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

            } catch (_: SocketTimeoutException) {

                if (closed.get()) {
                    throw IOException(
                        "TCP transport was closed during reading."
                    )
                }

                // Retry after temporary inactivity.
            }
        }
    }

    /**
     * Returns the active socket only when it is usable.
     */
    private fun requireOpenSocket(): Socket {

        if (closed.get()) {
            throw IOException(
                "TCP transport is closed."
            )
        }

        val activeSocket = socket
            ?: throw IOException(
                "TCP socket has not been opened."
            )

        if (
            activeSocket.isClosed ||
            !activeSocket.isConnected
        ) {
            throw IOException(
                "Wireless ADB connection is unavailable."
            )
        }

        return activeSocket
    }

    /**
     * Converts transport failures into meaningful IOExceptions.
     */
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
     * Closes the socket and releases blocked operations.
     *
     * This method is deliberately not synchronized with
     * open(), allowing cancellation during connection setup.
     */
    override fun close() {

        val activeSocket = synchronized(lifecycleLock) {

            if (closed.get() && socket == null) {
                return
            }

            closed.set(true)

            val currentSocket = socket
            socket = null

            currentSocket
        }

        try {
            activeSocket?.close()
        } catch (_: Exception) {
            // Continue with cleanup.
        }
    }
}
