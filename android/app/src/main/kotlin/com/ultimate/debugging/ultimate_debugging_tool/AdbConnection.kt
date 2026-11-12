package com.ultimate.debugging.ultimate_debugging_tool

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * A distinct exception allows callers to distinguish an intentional
 * cancellation from a genuine connection failure.
 */
class AdbConnectionCancelledException(
    message: String = "ADB connection was cancelled.",
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * One authenticated ADB connection over any transport (USB or TCP).
 *
 * After the handshake a single reader thread routes packets to streams,
 * so several commands can be in flight and an idle connection stays open.
 */
class AdbConnection(
    private val transport: AdbTransport,
    private val onStateChanged: ((State) -> Unit)? = null
) {

    companion object {
        private const val MAX_DATA_SIZE = AdbProtocol.MAX_DATA_SIZE
        private const val MAX_AUTH_CHALLENGES = 8
        private const val DEFAULT_OPEN_TIMEOUT_MS = 10000L

        private const val HOST_BANNER =
            "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir," +
                "apex,abb,abb_exec,remount_shell,track_app,sendrecv_v2\u0000"
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATION_REQUIRED,
        CONNECTED,
        FAILED,
        CLOSED
    }

    sealed class HandshakeResult {

        data class Connected(
            val version: Int,
            val maxDataSize: Int,
            val deviceBanner: String,
            val features: List<String>
        ) : HandshakeResult()

        data class Rejected(
            val reason: String
        ) : HandshakeResult()
    }

    @Volatile
    var state = State.DISCONNECTED
        private set

    @Volatile
    var negotiatedVersion = 0
        private set

    @Volatile
    var negotiatedMaxDataSize = 0
        private set

    @Volatile
    var deviceBanner = ""
        private set

    @Volatile
    var deviceFeatures: List<String> = emptyList()
        private set

    /** Why the connection stopped working, when known. */
    @Volatile
    var failureReason: String? = null
        private set

    @Volatile
    private var cancelled = false

    @Volatile
    private var packetReader: AdbPacketReader? = null

    @Volatile
    private var shellSession: AdbShellSession? = null

    private val nextLocalId = AtomicInteger(1)

    private val connectionLock = Any()
    private val stateNotificationLock = Any()

    // Protected by connectionLock.
    private var stateRevision = 0L

    /**
     * Notifies the owner of a state change.
     *
     * Older notifications are discarded if a newer state transition has
     * already occurred. The listener runs without connectionLock held so
     * it may safely call back into this connection.
     */
    private fun notifyStateChanged(
        newState: State,
        revision: Long
    ) {
        synchronized(stateNotificationLock) {
            val current = synchronized(connectionLock) {
                stateRevision == revision && state == newState
            }

            if (!current) return

            try {
                onStateChanged?.invoke(newState)
            } catch (_: Exception) {
                // Listener errors must never interrupt ADB communication.
            }
        }
    }

    /**
     * Performs the ADB handshake with RSA authentication.
     *
     * Blocks while the target phone shows its "Allow USB debugging?"
     * prompt. Call close() from another thread to cancel.
     */
    @Throws(IOException::class)
    fun connect(
        authentication: AdbAuthentication
    ): HandshakeResult {
        beginConnection()

        return try {
            authentication.initialize()
            authentication.resetStage()

            checkNotCancelled()
            sendConnectionRequest()

            var signatureSent = false
            var publicKeySent = false
            var challengeCount = 0

            while (challengeCount < MAX_AUTH_CHALLENGES) {
                checkNotCancelled()

                val packet = transport.readPacket()

                checkNotCancelled()

                when (packet.command) {

                    AdbProtocol.CMD_CNXN -> {
                        val result = handleConnectionResponse(packet)

                        if (result is HandshakeResult.Connected) {
                            authentication.markAuthorized()
                        }

                        return result
                    }

                    AdbProtocol.CMD_AUTH -> {
                        if (packet.arg0 != AdbProtocol.AUTH_TOKEN) {
                            return reject(
                                "Unsupported ADB authentication type: ${packet.arg0}"
                            )
                        }

                        if (packet.data.size != AdbKeyCodec.TOKEN_SIZE) {
                            return reject(
                                "The device supplied an invalid authentication token."
                            )
                        }

                        challengeCount++

                        if (!signatureSent) {
                            val signaturePacket =
                                authentication.createSignaturePacket(
                                    packet.data.copyOf()
                                )

                            checkNotCancelled()
                            transport.writePacket(signaturePacket)
                            signatureSent = true

                        } else if (!publicKeySent) {
                            val publicKeyPacket =
                                authentication.createPublicKeyPacket()

                            checkNotCancelled()
                            transport.writePacket(publicKeyPacket)

                            authentication.markPublicKeySent()
                            publicKeySent = true

                            // The phone is now showing its approval prompt.
                            updateState(State.AUTHENTICATION_REQUIRED)

                        } else {
                            return reject(
                                "The device did not accept the supplied ADB public key."
                            )
                        }
                    }

                    AdbProtocol.CMD_CLSE -> {
                        return reject(
                            "The device closed the ADB connection."
                        )
                    }

                    else -> {
                        return reject(
                            "Unexpected ADB response: " +
                                AdbProtocol.commandName(packet.command)
                        )
                    }
                }
            }

            reject(
                "ADB authentication exceeded the maximum challenge attempts."
            )

        } catch (error: Exception) {
            handleConnectionError(
                "ADB authentication or connection failed.",
                error
            )
        }
    }

    @Throws(IOException::class)
    private fun sendConnectionRequest() {
        transport.writePacket(
            AdbProtocol.AdbPacket(
                AdbProtocol.CMD_CNXN,
                AdbProtocol.VERSION,
                MAX_DATA_SIZE,
                HOST_BANNER.toByteArray(StandardCharsets.UTF_8)
            )
        )
    }

    /** Processes an accepted connection response. */
    private fun handleConnectionResponse(
        packet: AdbProtocol.AdbPacket
    ): HandshakeResult {
        checkNotCancelled()

        if (packet.arg0 <= 0 || packet.arg1 <= 0) {
            return reject(
                "The device supplied invalid ADB connection parameters."
            )
        }

        negotiatedVersion = packet.arg0
        negotiatedMaxDataSize = minOf(packet.arg1, MAX_DATA_SIZE)

        deviceBanner = String(
            packet.data,
            StandardCharsets.UTF_8
        ).trimEnd('\u0000')

        deviceFeatures = extractFeatures(deviceBanner)

        // The reader must be running before anyone is told the
        // connection is usable.
        val reader = AdbPacketReader(
            transport = transport,
            onReaderError = { error -> handleTransportFailure(error) }
        )

        synchronized(connectionLock) {
            if (cancelled || state == State.CLOSED) {
                throw AdbConnectionCancelledException()
            }
            packetReader = reader
        }

        reader.start()

        updateState(State.CONNECTED)

        return HandshakeResult.Connected(
            negotiatedVersion,
            negotiatedMaxDataSize,
            deviceBanner,
            deviceFeatures
        )
    }

    /**
     * Extracts supported features from the device banner, for example
     * "device::ro.product.name=x;ro.product.model=y;features=a,b,c".
     */
    private fun extractFeatures(
        banner: String
    ): List<String> {
        val section = banner
            .split(';')
            .map { it.substringAfterLast("::") }
            .firstOrNull { it.startsWith("features=") }
            ?: return emptyList()

        return section
            .removePrefix("features=")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Starts a fresh connection attempt. A failed or closed connection
     * must use a new AdbConnection instance for another attempt.
     */
    private fun beginConnection() {
        val revision = synchronized(connectionLock) {
            if (state != State.DISCONNECTED) {
                throw IOException(
                    when (state) {
                        State.CONNECTING ->
                            "An ADB connection attempt is already running."

                        State.AUTHENTICATION_REQUIRED ->
                            "ADB authentication is already in progress."

                        State.CONNECTED ->
                            "ADB connection is already established."

                        State.FAILED ->
                            "The previous ADB connection failed. Create a new connection instance."

                        State.CLOSED ->
                            "ADB connection has been closed."

                        State.DISCONNECTED ->
                            "ADB is disconnected."
                    }
                )
            }

            cancelled = false
            failureReason = null
            state = State.CONNECTING
            stateRevision++
            stateRevision
        }

        notifyStateChanged(State.CONNECTING, revision)
        checkNotCancelled()
    }

    private fun updateState(newState: State) {
        val revision = synchronized(connectionLock) {
            if (cancelled || state == State.CLOSED) {
                throw AdbConnectionCancelledException()
            }

            state = newState
            stateRevision++
            stateRevision
        }

        notifyStateChanged(newState, revision)
    }

    private fun checkNotCancelled() {
        if (cancelled || state == State.CLOSED) {
            throw AdbConnectionCancelledException()
        }
    }

    private fun reject(
        reason: String
    ): HandshakeResult.Rejected {
        failureReason = reason
        updateState(State.FAILED)
        return HandshakeResult.Rejected(reason)
    }

    /** Handles errors without overwriting the CLOSED state. */
    private fun handleConnectionError(
        message: String,
        error: Exception
    ): Nothing {
        var cancelledConnection = false
        var revision = 0L

        synchronized(connectionLock) {
            if (cancelled || state == State.CLOSED) {
                cancelled = true
                state = State.CLOSED
                cancelledConnection = true
            } else {
                state = State.FAILED
                failureReason = error.message ?: message
            }

            stateRevision++
            revision = stateRevision
        }

        if (cancelledConnection) {
            throw AdbConnectionCancelledException(
                "ADB connection was cancelled.",
                error
            )
        }

        notifyStateChanged(State.FAILED, revision)

        if (error is IOException) {
            throw error
        }

        throw IOException(message, error)
    }

    /** Called by the reader thread when the transport stops working. */
    private fun handleTransportFailure(error: IOException) {
        var revision: Long? = null
        var reader: AdbPacketReader? = null

        synchronized(connectionLock) {
            if (!cancelled && state != State.CLOSED) {
                state = State.FAILED
                failureReason = error.message
                    ?: "The ADB connection was lost."
                stateRevision++
                revision = stateRevision
                reader = packetReader
                packetReader = null
                shellSession = null
            }
        }

        val failedRevision = revision ?: return

        try {
            reader?.close()
        } catch (_: Exception) {
            // Continue with the failed connection state.
        }

        notifyStateChanged(State.FAILED, failedRevision)
    }

    fun isConnected(): Boolean {
        return !cancelled && state == State.CONNECTED
    }

    fun isCancelled(): Boolean {
        return cancelled || state == State.CLOSED
    }

    fun supportsFeature(feature: String): Boolean {
        return deviceFeatures.contains(feature)
    }

    /**
     * Opens an ADB service such as "shell:id" and waits for the device
     * to accept it.
     */
    @Throws(IOException::class)
    fun openStream(
        service: String,
        openTimeoutMs: Long = DEFAULT_OPEN_TIMEOUT_MS
    ): AdbStream {
        val reader = synchronized(connectionLock) {
            if (cancelled || state != State.CONNECTED) {
                throw IOException(
                    "ADB is not connected. Connect a device first."
                )
            }

            packetReader ?: throw IOException(
                "ADB is not connected. Connect a device first."
            )
        }

        val payload = (service + "\u0000").toByteArray(
            StandardCharsets.UTF_8
        )

        if (payload.size > negotiatedMaxDataSize) {
            throw IOException(
                "The request exceeds the negotiated ADB packet size."
            )
        }

        val localId = nextLocalId.getAndIncrement()
        val stream = AdbStream(
            reader,
            localId,
            service,
            negotiatedMaxDataSize.coerceIn(4096, 64 * 1024)
        )

        reader.registerStream(localId, stream)

        try {
            reader.writePacket(
                AdbProtocol.AdbPacket(
                    command = AdbProtocol.CMD_OPEN,
                    arg0 = localId,
                    arg1 = 0,
                    data = payload
                )
            )

            stream.awaitOpen(openTimeoutMs)
        } catch (error: Exception) {
            stream.close()

            if (error is IOException) throw error

            throw IOException("Unable to open the ADB service.", error)
        }

        return stream
    }

    /**
     * Runs a one-shot ADB service (for example "tcpip:5555" or "reboot:")
     * and returns whatever text the device sent.
     *
     * These services often restart the ADB daemon, so a connection that
     * drops after the request was accepted is treated as success.
     */
    @Throws(IOException::class)
    fun runService(
        service: String,
        timeoutMs: Long = 10000L
    ): String {
        val stream = openStream(service, minOf(timeoutMs, DEFAULT_OPEN_TIMEOUT_MS))
        val output = ByteArrayOutputStream()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L

        try {
            while (true) {
                val remainingMs =
                    (deadline - System.nanoTime()) / 1_000_000L

                if (remainingMs <= 0) break

                val chunk = stream.read(remainingMs) ?: break
                output.write(chunk)
            }
        } catch (_: IOException) {
            // Accepted request; the device went away while answering.
        } finally {
            stream.close()
        }

        return output.toString(StandardCharsets.UTF_8.name())
    }

    /** Returns the shell helper bound to this connection. */
    fun createShellSession(): AdbShellSession {
        synchronized(connectionLock) {
            if (cancelled || state != State.CONNECTED) {
                throw IOException(
                    "ADB must be connected and authenticated before opening a shell."
                )
            }

            val existingSession = shellSession

            if (existingSession != null) {
                return existingSession
            }

            val session = AdbShellSession(
                connection = this,
                supportsShellV2 = deviceFeatures.contains("shell_v2")
            )

            shellSession = session
            return session
        }
    }

    /** Cancels the connection and releases USB or TCP resources. */
    fun close() {
        var revision: Long? = null
        var reader: AdbPacketReader? = null

        synchronized(connectionLock) {
            if (state == State.CLOSED && cancelled) {
                return
            }

            cancelled = true
            state = State.CLOSED
            stateRevision++
            revision = stateRevision

            reader = packetReader
            packetReader = null
            shellSession = null
        }

        try {
            reader?.close()
        } catch (_: Exception) {
            // Continue with transport cleanup.
        }

        try {
            transport.close()
        } catch (_: Exception) {
            // Continue with cleanup.
        }

        notifyStateChanged(State.CLOSED, revision!!)
    }
}
