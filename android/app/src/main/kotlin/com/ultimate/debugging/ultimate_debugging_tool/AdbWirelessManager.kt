
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

    private val lifecycleLock = Any()

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

    /**
     * Starts a wireless ADB connection attempt.
     */
    fun connect(
        host: String,
        port: Int,
        callback: (ConnectionInfo) -> Unit
    ) {

        var failure: ConnectionInfo? = null
        var previousConnection: AdbConnection? = null
        var previousTransport: AdbTcpTransport? = null
        var attemptId = -1L

        synchronized(lifecycleLock) {

            if (closed) {
                failure = ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.FAILED,
                    error = "Wireless manager is closed."
                )

            } else if (connectionTask?.isDone == false) {
                failure = ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.FAILED,
                    error = "A wireless connection attempt is already running."
                )

            } else if (host.isBlank() || port !in 1..65535) {
                failure = ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.FAILED,
                    error = "Invalid wireless ADB host or port."
                )

            } else if (
                connectionInfo?.state == AdbConnection.State.CONNECTED ||
                connectionInfo?.state == AdbConnection.State.AUTHENTICATION_REQUIRED
            ) {
                failure = ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.FAILED,
                    error = "Disconnect the existing wireless connection first."
                )

            } else {

                previousConnection = connection
                previousTransport = transport

                connection = null
                transport = null
                connectionInfo = null

                cancelled.set(false)

                attemptId = attemptCounter.incrementAndGet()
            }
        }

        val rejected = failure

        if (rejected != null) {
            notifyCallback(callback, rejected)
            return
        }

        // Close old resources outside the lifecycle lock.
        // This prevents deadlocks with connection-state callbacks.

        try {
            previousConnection?.close()
        } catch (_: Exception) {
            // Continue with transport cleanup.
        }

        try {
            previousTransport?.close()
        } catch (_: Exception) {
            // Continue with the new attempt.
        }

        synchronized(lifecycleLock) {

            if (
                closed ||
                cancelled.get() ||
                attemptCounter.get() != attemptId
            ) {
                return
            }

            try {
                connectionTask = executor.submit {
                    performConnection(
                        host = host,
                        port = port,
                        attemptId = attemptId,
                        callback = callback
                    )
                }
            } catch (error: Exception) {

                connectionTask = null

                connectionInfo = ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.FAILED,
                    error = error.message
                        ?: "Unable to schedule wireless ADB connection."
                )

                failure = connectionInfo
            }
        }

        failure?.let {
            notifyCallback(callback, it)
        }
    }

    /**
     * Performs the actual wireless connection and handshake.
     */
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
                    host,
                    port,
                    AdbConnection.State.CONNECTING
                ),
                callback
            )

            checkAttempt(attemptId)

            val newTransport = AdbTcpTransport(
                host = host,
                port = port
            )

            activeTransport = newTransport

            synchronized(lifecycleLock) {
                checkAttempt(attemptId)
                transport = newTransport
            }

            newTransport.open()

            checkAttempt(attemptId)

            val newConnection = AdbConnection(
                transport = newTransport,
                onStateChanged = { state ->

                    publish(
                        attemptId,
                        ConnectionInfo(
                            host,
                            port,
                            state
                        ),
                        callback
                    )
                }
            )

            activeConnection = newConnection

            synchronized(lifecycleLock) {
                checkAttempt(attemptId)
                connection = newConnection
            }

            val result = newConnection.connect(authentication)

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

                    ConnectionInfo(
                        host,
                        port,
                        AdbConnection.State.FAILED,
                        error = "ADB authentication could not be completed."
                    )
                }

                is AdbConnection.HandshakeResult.Rejected -> {

                    ConnectionInfo(
                        host,
                        port,
                        AdbConnection.State.FAILED,
                        error = result.reason
                    )
                }
            }

            publish(
                attemptId,
                finalInfo,
                callback
            )

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

                attemptCounter.compareAndSet(
                    attemptId,
                    attemptId + 1
                )

                synchronized(lifecycleLock) {

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
                    // Continue with transport cleanup.
                }

                try {
                    activeTransport?.close()
                } catch (_: Exception) {
                    // Transport may already be closed.
                }
            }
        }
    }

    /**
     * Verifies that an attempt is still allowed to continue.
     */
    private fun checkAttempt(attemptId: Long) {

        if (
            closed ||
            cancelled.get() ||
            attemptCounter.get() != attemptId
        ) {
            throw AdbConnectionCancelledException(
                "Wireless connection attempt was cancelled."
            )
        }
    }

    /**
     * Publishes connection information only for the active attempt.
     */
    private fun publish(
        attemptId: Long,
        info: ConnectionInfo,
        callback: (ConnectionInfo) -> Unit
    ) {

        val accepted = synchronized(lifecycleLock) {

            if (
                closed ||
                cancelled.get() ||
                attemptCounter.get() != attemptId
            ) {
                false
            } else {
                connectionInfo = info
                true
            }
        }

        if (!accepted) {
            return
        }

        try {
            onStateChanged?.invoke(info.state)
        } catch (_: Exception) {
            // Listener errors must not interrupt ADB communication.
        }

        notifyCallback(callback, info)
    }

    /**
     * Safely notifies the connection callback.
     */
    private fun notifyCallback(
        callback: (ConnectionInfo) -> Unit,
        info: ConnectionInfo
    ) {
        try {
            callback(info)
        } catch (_: Exception) {
            // Callback failures must not crash the manager.
        }
    }

    /**
     * Returns the latest wireless connection information.
     */
    fun getConnectionInfo(): ConnectionInfo? {
        return connectionInfo
    }

    /**
     * Executes a shell command over the connected ADB session.
     */
    @Throws(IOException::class)
    fun executeCommand(
        command: String,
        timeoutMs: Long = 30000L
    ): CommandResult {

        val activeConnection = synchronized(lifecycleLock) {

            if (closed || cancelled.get()) {
                throw IOException(
                    "Wireless ADB is disconnected."
                )
            }

            connection?.takeIf {
                it.isConnected()
            } ?: throw IOException(
                "No active wireless ADB connection is available."
            )
        }

        return activeConnection.createShellSession().execute(
            command,
            timeoutMs
        )
    }

    /**
     * Disconnects the active wireless connection and
     * cancels any pending connection attempt.
     */
    fun disconnect() {

        val activeConnection: AdbConnection?
        val activeTransport: AdbTcpTransport?
        val activeTask: Future<*>?

        synchronized(lifecycleLock) {

            cancelled.set(true)
            attemptCounter.incrementAndGet()

            activeTask = connectionTask
            connectionTask = null

            activeConnection = connection
            activeTransport = transport

            connection = null
            transport = null
            connectionInfo = null
        }

        // Cancel and close outside the lifecycle lock.
        try {
            activeTask?.cancel(true)
        } catch (_: Exception) {
            // Continue with cleanup.
        }

        try {
            activeConnection?.close()
        } catch (_: Exception) {
            // Continue with transport cleanup.
        }

        try {
            activeTransport?.close()
        } catch (_: Exception) {
            // Transport may already be closed.
        }

        try {
            onStateChanged?.invoke(
                AdbConnection.State.CLOSED
            )
        } catch (_: Exception) {
            // Ignore listener failures during disconnect.
        }
    }

    /**
     * Permanently shuts down the wireless ADB manager.
     */
    fun shutdown() {

        synchronized(lifecycleLock) {

            if (closed) {
                return
            }

            closed = true
        }

        disconnect()

        executor.shutdownNow()
    }
}
