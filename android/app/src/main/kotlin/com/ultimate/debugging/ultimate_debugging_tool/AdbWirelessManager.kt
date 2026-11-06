
package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages standard wireless ADB TCP connections.
 *
 * Modern Android Wireless Debugging pairing is handled
 * separately and is not implemented by this manager.
 */
class AdbWirelessManager(
    private val authentication: AdbAuthentication,
    private val onStateChanged: ((AdbConnection.State) -> Unit)? = null
) {

    data class ConnectionInfo(
        val host: String,
        val port: Int,
        val state: AdbConnection.State,
        val deviceBanner: String? = null,
        val features: List<String> = emptyList(),
        val error: String? = null
    )

    private val executor = Executors.newSingleThreadExecutor()

    private val cancelled = AtomicBoolean(false)
    private val attemptCounter = AtomicLong(0L)

    @Volatile
    private var transport: AdbTcpTransport? = null

    @Volatile
    private var connection: AdbConnection? = null

    @Volatile
    private var connectionInfo: ConnectionInfo? = null

    @Volatile
    private var connectionTask: Future<*>? = null

    @Volatile
    private var closed = false

    @Synchronized
    fun connect(
        host: String,
        port: Int,
        callback: (ConnectionInfo) -> Unit
    ) {
        if (closed) {
            notifyCallback(
                callback,
                ConnectionInfo(
                    host = host,
                    port = port,
                    state = AdbConnection.State.FAILED,
                    error = "Wireless manager is closed."
                )
            )
            return
        }

        if (connectionTask?.isDone == false) {
            notifyCallback(
                callback,
                ConnectionInfo(
                    host = host,
                    port = port,
                    state = AdbConnection.State.FAILED,
                    error = "A wireless connection attempt is already running."
                )
            )
            return
        }

        if (host.isBlank() || port !in 1..65535) {
            notifyCallback(
                callback,
                ConnectionInfo(
                    host = host,
                    port = port,
                    state = AdbConnection.State.FAILED,
                    error = "Invalid wireless ADB host or port."
                )
            )
            return
        }

        val currentState = connectionInfo?.state

        if (
            currentState == AdbConnection.State.CONNECTED ||
            currentState == AdbConnection.State.AUTHENTICATION_REQUIRED
        ) {
            notifyCallback(
                callback,
                ConnectionInfo(
                    host = host,
                    port = port,
                    state = AdbConnection.State.FAILED,
                    error = "Disconnect the existing wireless connection first."
                )
            )
            return
        }

        val previousConnection = connection
        val previousTransport = transport

        connection = null
        transport = null
        connectionInfo = null

        try {
            previousConnection?.close()
        } catch (_: Exception) {
            try {
                previousTransport?.close()
            } catch (_: Exception) {
                // Continue with the new connection attempt.
            }
        }

        cancelled.set(false)

        val attemptId = attemptCounter.incrementAndGet()

        connectionTask = executor.submit {
            performConnection(
                host = host,
                port = port,
                attemptId = attemptId,
                callback = callback
            )
        }
    }

    private fun performConnection(
        host: String,
        port: Int,
        attemptId: Long,
        callback: (ConnectionInfo) -> Unit
    ) {
        var activeTransport: AdbTcpTransport? = null
        var activeConnection: AdbConnection? = null
        var retainConnection = false

        try {
            publish(
                attemptId,
                ConnectionInfo(
                    host = host,
                    port = port,
                    state = AdbConnection.State.CONNECTING
                ),
                callback
            )

            checkAttempt(attemptId)

            activeTransport = AdbTcpTransport(
                host = host,
                port = port
            )

            synchronized(this) {
                checkAttempt(attemptId)
                transport = activeTransport
            }

            activeTransport.open()

            checkAttempt(attemptId)

            activeConnection = AdbConnection(
                transport = activeTransport,
                onStateChanged = { state ->
                    publish(
                        attemptId,
                        ConnectionInfo(
                            host = host,
                            port = port,
                            state = state
                        ),
                        callback
                    )
                }
            )

            synchronized(this) {
                checkAttempt(attemptId)
                connection = activeConnection
            }

            val result = activeConnection.connect(authentication)

            checkAttempt(attemptId)

            val finalInfo = when (result) {

                is AdbConnection.HandshakeResult.Connected -> {
    retainConnection = true

    ConnectionInfo(
        host = host,
        port = port,
        state = AdbConnection.State.CONNECTED,
        deviceBanner = result.deviceBanner,
        features = result.features
    )
}

                is AdbConnection.HandshakeResult.AuthenticationRequired -> {
                    retainConnection = true

                    ConnectionInfo(
                        host = host,
                        port = port,
                        state = AdbConnection.State.AUTHENTICATION_REQUIRED,
                        error = "ADB authentication is required."
                    )
                }

                is AdbConnection.HandshakeResult.Rejected -> {
                    ConnectionInfo(
                        host = host,
                        port = port,
                        state = AdbConnection.State.FAILED,
                        error = "The device rejected the ADB connection."
                    )
                }
            }

            publish(attemptId, finalInfo, callback)

        } catch (error: Exception) {

            retainConnection = false

            val wasCancelled =
                cancelled.get() ||
                closed ||
                attemptCounter.get() != attemptId

            publish(
                attemptId,
                ConnectionInfo(
                    host = host,
                    port = port,
                    state = if (wasCancelled) {
                        AdbConnection.State.CLOSED
                    } else {
                        AdbConnection.State.FAILED
                    },
                    error = error.message
                        ?: "Wireless ADB connection failed."
                ),
                callback
            )

        } finally {

            if (!retainConnection) {
                attemptCounter.compareAndSet(attemptId, attemptId + 1)

                synchronized(this) {
                    if (connection === activeConnection) {
                        connection = null
                    }

                    if (transport === activeTransport) {
                        transport = null
                    }
                }

                try {
                    activeConnection?.close()
                } catch (_: Exception) {
                    try {
                        activeTransport?.close()
                    } catch (_: Exception) {
                        // Cleanup continues.
                    }
                }

                if (activeConnection == null) {
                    try {
                        activeTransport?.close()
                    } catch (_: Exception) {
                        // Transport cleanup completed or already closed.
                    }
                }
            }
        }
    }

    private fun checkAttempt(attemptId: Long) {
        if (
            closed ||
            cancelled.get() ||
            attemptCounter.get() != attemptId
        ) {
            throw IOException("Wireless connection attempt was cancelled.")
        }
    }

    private fun publish(
        attemptId: Long,
        info: ConnectionInfo,
        callback: (ConnectionInfo) -> Unit
    ) {
        synchronized(this) {
            if (
                closed ||
                cancelled.get() ||
                attemptCounter.get() != attemptId
            ) {
                return
            }

            connectionInfo = info
        }

        try {
            onStateChanged?.invoke(info.state)
        } catch (_: Exception) {
            // A state listener must not break the ADB connection.
        }

        notifyCallback(callback, info)
    }

    private fun notifyCallback(
        callback: (ConnectionInfo) -> Unit,
        info: ConnectionInfo
    ) {
        try {
            callback(info)
        } catch (_: Exception) {
            // A callback failure must not crash the manager.
        }
    }

    fun getConnectionInfo(): ConnectionInfo? {
        return connectionInfo
    }

    @Throws(IOException::class)
    fun executeCommand(
        command: String,
        timeoutMs: Long = 30000L
    ): CommandResult {
        val activeConnection = connection
            ?: throw IOException("No wireless ADB connection is available.")

        if (!activeConnection.isConnected()) {
            throw IOException("Wireless ADB is not connected.")
        }

        return activeConnection.createShellSession().execute(
            command,
            timeoutMs
        )
    }

    @Synchronized
    fun disconnect() {
        cancelled.set(true)
        attemptCounter.incrementAndGet()

        connectionTask?.cancel(true)
        connectionTask = null

        val activeConnection = connection
        val activeTransport = transport

        connection = null
        transport = null
        connectionInfo = null

        try {
            activeConnection?.close()
        } catch (_: Exception) {
            // Ensure the transport is also closed.
        }

        try {
            activeTransport?.close()
        } catch (_: Exception) {
            // Transport may already be closed.
        }

        try {
            onStateChanged?.invoke(AdbConnection.State.CLOSED)
        } catch (_: Exception) {
            // Ignore listener failures during disconnect.
        }
    }

    @Synchronized
    fun shutdown() {
        if (closed) return

        closed = true

        disconnect()

        executor.shutdownNow()
    }
}
