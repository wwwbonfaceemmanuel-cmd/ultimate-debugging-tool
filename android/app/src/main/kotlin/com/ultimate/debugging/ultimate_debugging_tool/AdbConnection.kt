
package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * A distinct exception allows MainActivity to distinguish
 * an intentional cancellation from a genuine connection failure.
 */
class AdbConnectionCancelledException(
    message: String = "ADB connection was cancelled.",
    cause: Throwable? = null
) : IOException(message, cause)

class AdbConnection(
    private val transport: AdbTransport,
    private val onStateChanged: ((State) -> Unit)? = null
) {

    companion object {
        private const val MAX_DATA_SIZE = 1024 * 1024
        private const val MAX_AUTH_CHALLENGES = 8

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

        data class AuthenticationRequired(
            val authenticationType: Int,
            val token: ByteArray
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

    @Volatile
    private var cancelled = false

    private val connectionLock = Any()

    /**
     * Safely notifies the owner whenever the connection state changes.
     * Listener errors must never interrupt ADB communication.
     */
    private fun notifyStateChanged(newState: State) {
        try {
            onStateChanged?.invoke(newState)
        } catch (_: Exception) {
            // A UI/event listener must not break the ADB connection.
        }
    }

    /**
     * Starts a basic ADB connection negotiation.
     */
    @Throws(IOException::class)
    fun connect(): HandshakeResult {

        beginConnection()

        return try {
            checkNotCancelled()
            sendConnectionRequest()
            checkNotCancelled()

            receiveHandshakeResponse()

        } catch (error: Exception) {
            handleConnectionError(
                "ADB connection negotiation failed.",
                error
            )
        }
    }

    /**
     * Starts ADB negotiation with RSA authentication.
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

                        if (packet.data.size != 20) {
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
                            checkNotCancelled()

                            signatureSent = true

                        } else if (!publicKeySent) {

                            val publicKeyPacket =
                                authentication.createPublicKeyPacket()

                            checkNotCancelled()
                            transport.writePacket(publicKeyPacket)
                            checkNotCancelled()

                            authentication.markPublicKeySent()

                            publicKeySent = true

                            /*
                             * IMPORTANT:
                             * The public key has now been sent.
                             * Android can display its USB debugging
                             * authorization prompt at this point.
                             *
                             * Notify MainActivity immediately so it
                             * can update the Flutter interface.
                             */
                            updateState(
                                State.AUTHENTICATION_REQUIRED
                            )

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

    /**
     * Sends the host connection request.
     */
    @Throws(IOException::class)
    private fun sendConnectionRequest() {

        val banner = HOST_BANNER.toByteArray(
            StandardCharsets.UTF_8
        )

        transport.writePacket(
            AdbProtocol.AdbPacket(
                AdbProtocol.CMD_CNXN,
                AdbProtocol.VERSION,
                MAX_DATA_SIZE,
                banner
            )
        )
    }

    /**
     * Receives the initial ADB handshake response.
     */
    @Throws(IOException::class)
    fun receiveHandshakeResponse(): HandshakeResult {

        checkNotCancelled()

        val packet = transport.readPacket()

        checkNotCancelled()

        return when (packet.command) {

            AdbProtocol.CMD_CNXN ->
                handleConnectionResponse(packet)

            AdbProtocol.CMD_AUTH ->
                handleAuthenticationRequest(packet)

            AdbProtocol.CMD_CLSE ->
                reject("The device closed the ADB connection.")

            else ->
                reject(
                    "Unexpected ADB response: " +
                        AdbProtocol.commandName(packet.command)
                )
        }
    }

    /**
     * Processes an accepted connection response.
     */
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

        negotiatedMaxDataSize = minOf(
            packet.arg1,
            MAX_DATA_SIZE
        )

        deviceBanner = String(
            packet.data,
            StandardCharsets.UTF_8
        ).trimEnd('\u0000')

        deviceFeatures = extractFeatures(deviceBanner)

        updateState(State.CONNECTED)

        return HandshakeResult.Connected(
            negotiatedVersion,
            negotiatedMaxDataSize,
            deviceBanner,
            deviceFeatures
        )
    }

    /**
     * Processes an authentication request without
     * automatically completing authentication.
     */
    private fun handleAuthenticationRequest(
        packet: AdbProtocol.AdbPacket
    ): HandshakeResult {

        if (packet.arg0 != AdbProtocol.AUTH_TOKEN) {
            return reject(
                "Unsupported ADB authentication message type: ${packet.arg0}"
            )
        }

        if (packet.data.size != 20) {
            return reject(
                "The device supplied an invalid authentication token."
            )
        }

        updateState(State.AUTHENTICATION_REQUIRED)

        return HandshakeResult.AuthenticationRequired(
            packet.arg0,
            packet.data.copyOf()
        )
    }

    /**
     * Extracts supported features from the device banner.
     */
    private fun extractFeatures(
        banner: String
    ): List<String> {

        val section = banner
            .split(';')
            .firstOrNull {
                it.startsWith("features=")
            } ?: return emptyList()

        val value = section.removePrefix("features=")

        if (value.isBlank()) {
            return emptyList()
        }

        return value
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Starts a fresh connection attempt.
     */
    private fun beginConnection() {

        synchronized(connectionLock) {

            if (state == State.CONNECTING ||
                state == State.AUTHENTICATION_REQUIRED
            ) {
                throw IOException(
                    "An ADB connection attempt is already running."
                )
            }

            if (state == State.CONNECTED) {
                throw IOException(
                    "ADB connection is already established."
                )
            }

            if (state == State.CLOSED) {
                throw IOException(
                    "ADB connection has been closed."
                )
            }

            cancelled = false

            negotiatedVersion = 0
            negotiatedMaxDataSize = 0
            deviceBanner = ""
            deviceFeatures = emptyList()

            state = State.CONNECTING
        }

        notifyStateChanged(State.CONNECTING)
    }

    /**
     * Updates the connection state unless cancellation
     * has already occurred.
     */
    private fun updateState(newState: State) {

        synchronized(connectionLock) {

            if (cancelled || state == State.CLOSED) {
                throw AdbConnectionCancelledException()
            }

            state = newState
        }

        notifyStateChanged(newState)
    }

    /**
     * Checks whether the current connection was cancelled.
     */
    private fun checkNotCancelled() {

        if (cancelled || state == State.CLOSED) {
            throw AdbConnectionCancelledException()
        }
    }

    /**
     * Returns a rejected handshake result.
     */
    private fun reject(
        reason: String
    ): HandshakeResult.Rejected {

        updateState(State.FAILED)

        return HandshakeResult.Rejected(reason)
    }

    /**
     * Handles errors without overwriting the CLOSED state.
     *
     * Intentional cancellation is reported separately from
     * genuine connection failures.
     */
    private fun handleConnectionError(
        message: String,
        error: Exception
    ): Nothing {

        var cancelledConnection = false

        synchronized(connectionLock) {

            if (cancelled || state == State.CLOSED) {
                cancelled = true
                state = State.CLOSED
                cancelledConnection = true
            } else {
                state = State.FAILED
            }
        }

        if (cancelledConnection) {
            throw AdbConnectionCancelledException(
                "ADB connection was cancelled.",
                error
            )
        }

        notifyStateChanged(State.FAILED)

        if (error is IOException) {
            throw error
        }

        throw IOException(message, error)
    }

    /**
     * Indicates whether the ADB connection is established.
     */
    fun isConnected(): Boolean {
        return state == State.CONNECTED
    }

    /**
     * Indicates whether the connection was cancelled.
     */
    fun isCancelled(): Boolean {
        return cancelled || state == State.CLOSED
    }

    /**
     * Cancels the connection and releases USB resources.
     *
     * This method deliberately does not wait for the
     * connection negotiation lock.
     */
    fun close() {

        val shouldNotify = synchronized(connectionLock) {

            if (state == State.CLOSED && cancelled) {
                false
            } else {
                cancelled = true
                state = State.CLOSED
                true
            }
        }

        if (shouldNotify) {
            notifyStateChanged(State.CLOSED)
        }

        try {
            transport.close()
        } catch (_: Exception) {
            // Continue with the closed state.
        }
    }
}
