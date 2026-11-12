package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Anything that can run shell commands on the target phone.
 * Diagnostics, the terminal and repair services depend only on this.
 */
interface AdbShellRunner {

    /** How this session reaches the phone. */
    val link: AdbSessionManager.Link

    /** Android API level of the target, or null when unknown. */
    val targetSdk: Int?

    @Throws(IOException::class)
    fun shell(
        command: String,
        timeoutMs: Long = AdbShellSession.DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = AdbShellSession.DEFAULT_MAX_OUTPUT_BYTES
    ): AdbShellSession.CommandResult

    @Throws(IOException::class)
    fun service(name: String, timeoutMs: Long = 10000L): String
    /** Opens a raw ADB service stream, for example "sync:". */
    @Throws(IOException::class)
    fun open(service: String): AdbStream
}

/**
 * Owns the single active ADB session, whether it runs over USB or over
 * the network. Only one target phone is serviced at a time.
 *
 * All blocking work happens on this class's own threads. Listener and
 * callback functions are never invoked while an internal lock is held.
 */
class AdbSessionManager(
    private val authentication: AdbAuthentication,
    private val onChanged: (Snapshot) -> Unit = {}
) : AdbShellRunner {

    enum class Link { NONE, USB, WIRELESS }

    /** Immutable description of the current session. */
    data class Snapshot(
        val link: Link = Link.NONE,
        val state: AdbConnection.State = AdbConnection.State.DISCONNECTED,
        val target: String? = null,
        val host: String? = null,
        val port: Int? = null,
        val version: Int = 0,
        val maxDataSize: Int = 0,
        val deviceBanner: String = "",
        val features: List<String> = emptyList(),
        val device: Map<String, String> = emptyMap(),
        val error: String? = null
    ) {
        val connected: Boolean
            get() = state == AdbConnection.State.CONNECTED

        val busy: Boolean
            get() = state == AdbConnection.State.CONNECTING ||
                state == AdbConnection.State.AUTHENTICATION_REQUIRED ||
                state == AdbConnection.State.CONNECTED

        /** Short label such as "Samsung SM-A125F (Android 12)". */
        val deviceLabel: String
            get() {
                val maker = device["manufacturer"].orEmpty()
                val model = device["model"].orEmpty()
                val release = device["android"].orEmpty()

                val name = listOf(maker, model)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { target ?: "Unknown device" }

                return if (release.isNotBlank()) {
                    "$name (Android $release)"
                } else {
                    name
                }
            }

        fun toMap(): Map<String, Any?> {
            return mapOf(
                "link" to link.name,
                "status" to state.name,
                "connected" to connected,
                "busy" to busy,
                "target" to target,
                "host" to host,
                "port" to port,
                "version" to version,
                "maxDataSize" to maxDataSize,
                "deviceBanner" to deviceBanner,
                "features" to features,
                "device" to device,
                "deviceLabel" to if (connected) deviceLabel else null,
                "error" to error
            )
        }
    }

    sealed class ConnectResult {
        data class Connected(val snapshot: Snapshot) : ConnectResult()
        data class Failed(val code: String, val message: String) : ConnectResult()
        object Cancelled : ConnectResult()
    }

    private val lock = Any()
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { task ->
            Thread(task, "UDT-ADB-Session").apply { isDaemon = true }
        }

    // All fields below are protected by lock.
    private var snapshot = Snapshot()
    private var attemptId = 0L
    private var connection: AdbConnection? = null
    private var transport: AdbTransport? = null
    private var shutDown = false

    fun snapshot(): Snapshot = synchronized(lock) { snapshot }

    override val link: Link
        get() = snapshot().let {
            if (it.connected) it.link else Link.NONE
        }

    override val targetSdk: Int?
        get() = snapshot().device["sdk"]?.toIntOrNull()

    /**
     * Starts connecting. [callback] is invoked exactly once, on a
     * background thread, when the attempt succeeds, fails or is cancelled.
     *
     * The attempt waits for as long as the phone shows its approval
     * prompt. Call disconnect() to cancel it.
     */
    fun connect(
        link: Link,
        target: String,
        host: String? = null,
        port: Int? = null,
        transportFactory: () -> AdbTransport,
        callback: (ConnectResult) -> Unit
    ) {
        require(link != Link.NONE) { "A connection type is required." }

        val delivered = AtomicBoolean(false)

        fun deliver(result: ConnectResult) {
            if (delivered.compareAndSet(false, true)) {
                try {
                    callback(result)
                } catch (_: Exception) {
                    // Callback failures must not affect the session.
                }
            }
        }

        var rejection: ConnectResult.Failed? = null
        var attempt = 0L
        var changed: Snapshot? = null

        synchronized(lock) {
            if (shutDown) {
                rejection = ConnectResult.Failed(
                    "ADB_CLOSED",
                    "The ADB engine has been shut down."
                )
            } else if (snapshot.busy) {
                val active = when (snapshot.link) {
                    Link.USB -> "USB"
                    Link.WIRELESS -> "Wireless"
                    Link.NONE -> "Another"
                }

                rejection = ConnectResult.Failed(
                    "ADB_BUSY",
                    "$active ADB is already active or connecting. " +
                        "Disconnect it first."
                )
            } else {
                attemptId++
                attempt = attemptId

                snapshot = Snapshot(
                    link = link,
                    state = AdbConnection.State.CONNECTING,
                    target = target,
                    host = host,
                    port = port
                )

                changed = snapshot
            }
        }

        rejection?.let {
            deliver(it)
            return
        }

        changed?.let { notifyChanged(it) }

        try {
            executor.execute {
                runAttempt(attempt, transportFactory, ::deliver)
            }
        } catch (error: Exception) {
            finishAttempt(
                attempt,
                AdbConnection.State.FAILED,
                error.message ?: "Unable to start the ADB connection."
            )

            deliver(
                ConnectResult.Failed(
                    "ADB_EXECUTOR_ERROR",
                    error.message ?: "Unable to start the ADB connection."
                )
            )
        }
    }

    private fun runAttempt(
        attempt: Long,
        transportFactory: () -> AdbTransport,
        deliver: (ConnectResult) -> Unit
    ) {
        var newTransport: AdbTransport? = null
        var newConnection: AdbConnection? = null

        try {
            newTransport = transportFactory()

            newConnection = AdbConnection(newTransport) { state ->
                onConnectionState(attempt, state)
            }

            synchronized(lock) {
                if (attemptId != attempt || shutDown) {
                    throw AdbConnectionCancelledException()
                }

                transport = newTransport
                connection = newConnection
            }

            newTransport.open()

            when (val result = newConnection.connect(authentication)) {

                is AdbConnection.HandshakeResult.Connected -> {
                    val identity = readDeviceIdentity(newConnection)
                    var connected: Snapshot? = null

                    synchronized(lock) {
                        if (
                            attemptId == attempt &&
                            connection === newConnection &&
                            newConnection.isConnected()
                        ) {
                            snapshot = snapshot.copy(
                                state = AdbConnection.State.CONNECTED,
                                version = result.version,
                                maxDataSize = result.maxDataSize,
                                deviceBanner = result.deviceBanner,
                                features = result.features,
                                device = identity,
                                error = null
                            )

                            connected = snapshot
                        }
                    }

                    val finalSnapshot = connected

                    if (finalSnapshot == null) {
                        closeQuietly(newConnection, newTransport)

                        if (isCurrent(attempt)) {
                            val reason = newConnection.failureReason
                                ?: "The ADB connection was lost."

                            finishAttempt(
                                attempt,
                                AdbConnection.State.FAILED,
                                reason
                            )

                            deliver(
                                ConnectResult.Failed(
                                    "ADB_CONNECTION_ERROR",
                                    reason
                                )
                            )
                        } else {
                            deliver(ConnectResult.Cancelled)
                        }
                    } else {
                        notifyChanged(finalSnapshot)
                        deliver(ConnectResult.Connected(finalSnapshot))
                    }
                }

                is AdbConnection.HandshakeResult.Rejected -> {
                    closeQuietly(newConnection, newTransport)

                    finishAttempt(
                        attempt,
                        AdbConnection.State.FAILED,
                        result.reason
                    )

                    deliver(
                        ConnectResult.Failed("ADB_REJECTED", result.reason)
                    )
                }
            }

        } catch (error: Exception) {
            closeQuietly(newConnection, newTransport)

            val cancelled =
                error is AdbConnectionCancelledException ||
                    !isCurrent(attempt)

            if (cancelled) {
                deliver(ConnectResult.Cancelled)
            } else {
                val message = describe(error)

                finishAttempt(
                    attempt,
                    AdbConnection.State.FAILED,
                    message
                )

                deliver(
                    ConnectResult.Failed("ADB_CONNECTION_ERROR", message)
                )
            }
        }
    }

    private fun describe(error: Throwable): String {
        val message = error.message

        return if (message.isNullOrBlank()) {
            "The ADB connection failed (${error.javaClass.simpleName})."
        } else {
            message
        }
    }

    private fun isCurrent(attempt: Long): Boolean {
        return synchronized(lock) { attemptId == attempt && !shutDown }
    }

    /** Reflects handshake progress and later connection loss. */
    private fun onConnectionState(
        attempt: Long,
        state: AdbConnection.State
    ) {
        var changed: Snapshot? = null
        var lost: AdbConnection? = null

        synchronized(lock) {
            if (attemptId != attempt) return

            when (state) {
                AdbConnection.State.AUTHENTICATION_REQUIRED -> {
                    if (snapshot.state == AdbConnection.State.CONNECTING) {
                        snapshot = snapshot.copy(state = state)
                        changed = snapshot
                    }
                }

                AdbConnection.State.FAILED -> {
                    // Only handle loss of an established session here.
                    // Handshake failures are reported by runAttempt().
                    if (snapshot.state == AdbConnection.State.CONNECTED) {
                        lost = connection

                        snapshot = Snapshot(
                            link = snapshot.link,
                            state = AdbConnection.State.FAILED,
                            target = snapshot.target,
                            host = snapshot.host,
                            port = snapshot.port,
                            error = lost?.failureReason
                                ?: "The ADB connection was lost."
                        )

                        connection = null
                        transport = null
                        changed = snapshot
                    }
                }

                else -> {
                    // CONNECTING, CONNECTED and CLOSED are set elsewhere.
                }
            }
        }

        changed?.let { notifyChanged(it) }
    }

    private fun finishAttempt(
        attempt: Long,
        state: AdbConnection.State,
        error: String?
    ) {
        var changed: Snapshot? = null

        synchronized(lock) {
            if (attemptId != attempt) return

            snapshot = Snapshot(
                link = snapshot.link,
                state = state,
                target = snapshot.target,
                host = snapshot.host,
                port = snapshot.port,
                error = error
            )

            connection = null
            transport = null
            changed = snapshot
        }

        changed?.let { notifyChanged(it) }
    }

    /** Reads the basic identity of the connected phone. Best effort. */
    private fun readDeviceIdentity(
        activeConnection: AdbConnection
    ): Map<String, String> {
        val properties = linkedMapOf(
            "manufacturer" to "ro.product.manufacturer",
            "brand" to "ro.product.brand",
            "model" to "ro.product.model",
            "device" to "ro.product.device",
            "android" to "ro.build.version.release",
            "sdk" to "ro.build.version.sdk",
            "securityPatch" to "ro.build.version.security_patch",
            "serial" to "ro.serialno",
            "buildId" to "ro.build.display.id"
        )

        return try {
            val script = properties.values.joinToString("; ") {
                "getprop $it"
            }

            val lines = activeConnection
                .createShellSession()
                .execute(script, 8000L)
                .output
                .split('\n')
                .map { it.trim() }

            val identity = linkedMapOf<String, String>()

            properties.keys.forEachIndexed { index, key ->
                val value = lines.getOrNull(index).orEmpty()

                if (value.isNotBlank()) {
                    identity[key] = value
                }
            }

            identity
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Ends the session or cancels a pending connection attempt.
     * Returns true when something was active.
     */
    fun disconnect(): Boolean {
        val oldConnection: AdbConnection?
        val oldTransport: AdbTransport?
        val wasActive: Boolean
        val changed: Snapshot

        synchronized(lock) {
            wasActive = snapshot.busy
            attemptId++

            oldConnection = connection
            oldTransport = transport
            connection = null
            transport = null

            snapshot = Snapshot()
            changed = snapshot
        }

        closeQuietly(oldConnection, oldTransport)
        notifyChanged(changed)

        return wasActive
    }

    private fun closeQuietly(
        oldConnection: AdbConnection?,
        oldTransport: AdbTransport?
    ) {
        try {
            oldConnection?.close()
        } catch (_: Exception) {
            // Continue with transport cleanup.
        }

        try {
            oldTransport?.close()
        } catch (_: Exception) {
            // The transport may already be closed.
        }
    }

    private fun requireConnection(): AdbConnection {
        return synchronized(lock) {
            connection?.takeIf {
                snapshot.connected && it.isConnected()
            }
        } ?: throw IOException(
            "No phone is connected over ADB. Connect the target phone " +
                "using USB or Wireless ADB first."
        )
    }

    @Throws(IOException::class)
    override fun shell(
        command: String,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): AdbShellSession.CommandResult {
        return requireConnection()
            .createShellSession()
            .execute(command, timeoutMs, maxOutputBytes)
    }

    @Throws(IOException::class)
    override fun service(name: String, timeoutMs: Long): String {
        return requireConnection().runService(name, timeoutMs)
    }

    @Throws(IOException::class)
    override fun open(service: String): AdbStream {
        return requireConnection().openStream(service)
    }

    private fun notifyChanged(changed: Snapshot) {
        try {
            onChanged(changed)
        } catch (_: Exception) {
            // Listener failures must not affect the session.
        }
    }

    /** Permanently stops the engine. */
    fun shutdown() {
        synchronized(lock) {
            if (shutDown) return
            shutDown = true
        }

        disconnect()
        executor.shutdownNow()
    }
}
