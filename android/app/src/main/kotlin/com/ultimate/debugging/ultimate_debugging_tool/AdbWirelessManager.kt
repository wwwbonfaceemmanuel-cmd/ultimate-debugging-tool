
package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

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
     * Starts a wireless TCP ADB connection.
     *
     * The target must already have ADB TCP enabled
     * and must accept the connection.
     */
    @Synchronized
    fun connect(
        host: String,
        port: Int,
        callback: (ConnectionInfo) -> Unit
    ) {
        if (closed) {
            callback(
                ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.FAILED,
                    error = "Wireless manager is closed."
                )
            )
            return
        }

        if (connectionTask?.isDone == false) {
            callback(
                ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.FAILED,
                    error = "A wireless connection attempt is already running."
                )
            )
            return
        }

        if (host.isBlank() || port !in 1..65535) {
            callback(
                ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.FAILED,
                    error = "Invalid device address or TCP port."
                )
            )
            return
        }

        // Do not replace an existing live connection.
        if (
            connectionInfo?.state == AdbConnection.State.CONNECTED ||
            connectionInfo?.state == AdbConnection.State.AUTHENTICATION_REQUIRED
        ) {
            callback(
                ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.FAILED,
                    error = "Disconnect the existing wireless session first."
                )
            )
            return
        }

        cancelled.set(false)

        connectionTask = executor.submit {
            performConnection(host, port, callback)
        }
    }

    private fun performConnection(
        host: String,
        port: Int,
        callback: (ConnectionInfo) -> Unit
    ) {
        var activeTransport: AdbTcpTransport? = null
        var activeConnection: AdbConnection? = null
        var retainConnection = false

        try {
            publish(
                ConnectionInfo(
                    host,
                    port,
                    AdbConnection.State.CONNECTING
                ),
                callback
            )

            activeTransport = AdbTcpTransport(
                host = host,
                port = port
            )

            synchronized(this) {
                if (closed || cancelled.get()) {
                    throw IOException("Wireless connection cancelled.")
                }

                transport = activeTransport
            }

            activeTransport.open()

            if (cancelled.get()) {
                throw IOException("Wireless connection cancelled.")
            }

            activeConnection = AdbConnection(
                transport = activeTransport,
                onStateChanged = { state ->
                    publish(
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
                if (closed || cancelled.get()) {
                    throw IOException("Wireless connection cancelled.")
                }

                connection = activeConnection
            }

            val result = activeConnection.connect(authentication)

            if (cancelled.get() || closed) {
                throw IOException("Wireless connection cancelled.")
            }

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
                        error = "Device authorization is required."
                    )
                }

                is AdbConnection.HandshakeResult.Rejected -> {
                    ConnectionInfo(
                        host = host,
                        port = port,
                        state = AdbConnection.State.FAILED,
                        error = result.reason
                    )
                }
            }

            publish(finalInfo, callback)

        } catch (error: Exception) {

            retainConnection = false

            val failedInfo = ConnectionInfo(
                host = host,
                port = port,
                state = if (cancelled.get() || closed) {
                    AdbConnection.State.CLOSED
                } else {
                    AdbConnection.State.FAILED
                },
                error = error.message ?: "Wireless ADB connection failed."
            )

            publish(failedInfo, callback)

        } finally {

            if (!retainConnection) {

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
                    activeTransport?.close()
                }
            }
        }
    }

    private fun publish(
        info: ConnectionInfo,
        callback: (ConnectionInfo) -> Unit
    ) {
        connectionInfo = info

        onStateChanged?.invoke(info.state)
        callback(info)
    }

    /**
     * Returns the latest wireless connection information.
     */
    fun getConnectionInfo(): ConnectionInfo? {
        return connectionInfo
    }

    /**
 * Executes a command through the authenticated wireless ADB session.
 */
fun executeCommand(
    command: String,
    timeoutMs: Long = 30000L
): AdbShellSession.CommandResult {

    val activeConnection = connection
        ?: throw IOException("Wireless ADB is not connected.")

    if (!activeConnection.isConnected()) {
        throw IOException(
            "Wireless ADB must be connected and authenticated first."
        )
    }

    return activeConnection
        .createShellSession()
        .execute(command, timeoutMs)
}

    /**
     * Disconnects the wireless ADB session.
     */
    @Synchronized
    fun disconnect() {
        cancelled.set(true)

        val activeConnection = connection
        val activeTransport = transport

        connection = null
        transport = null
        connectionInfo = null

        try {
            activeConnection?.close()
        } catch (_: Exception) {
            activeTransport?.close()
        }

        if (activeConnection == null) {
            activeTransport?.close()
        }
    }

    /**
     * Permanently releases manager resources.
     */
    @Synchronized
    fun shutdown() {
        if (closed) return

        closed = true
        disconnect()

        connectionTask?.cancel(true)
        executor.shutdownNow()
    }
}
