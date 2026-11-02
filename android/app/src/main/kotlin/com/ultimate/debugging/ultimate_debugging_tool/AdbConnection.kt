
package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Manages the ADB connection handshake and RSA authentication.
 *
 * USB transport and cryptographic authentication are separate components.
 *
 * This class must run on a background thread because USB operations block.
 */
class AdbConnection(
    private val transport: AdbUsbTransport
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
    var state: State = State.DISCONNECTED
        private set

    @Volatile
    var negotiatedVersion: Int = 0
        private set

    @Volatile
    var negotiatedMaxDataSize: Int = 0
        private set

    @Volatile
    var deviceBanner: String = ""
        private set

    @Volatile
    var deviceFeatures: List<String> = emptyList()
        private set

    /**
     * Starts the ADB handshake without performing RSA authentication.
     *
     * Retained for handshake inspection and compatibility.
     */
    @Synchronized
    @Throws(IOException::class)
    fun connect(): HandshakeResult {
        beginConnection()

        return try {
            sendConnectionRequest()
            receiveHandshakeResponse()
        } catch (error: Exception) {
            state = State.FAILED
            throw asIOException(
                "ADB connection negotiation failed.",
                error
            )
        }
    }

    /**
     * Starts the complete ADB handshake and RSA authentication.
     *
     * Authentication sequence:
     * 1. Send CNXN.
     * 2. Receive AUTH TOKEN.
     * 3. Send RSA signature.
     * 4. If challenged again, send the public key.
     * 5. Wait for a valid CNXN response.
     *
     * The target device may display an RSA authorization prompt.
     */
    @Synchronized
    @Throws(IOException::class)
    fun connect(
        authentication: AdbAuthentication
    ): HandshakeResult {

        beginConnection()

        return try {
            authentication.initialize()
            authentication.resetStage()

            sendConnectionRequest()

            var signatureSent = false
            var publicKeySent = false
            var challengeCount = 0

            while (challengeCount < MAX_AUTH_CHALLENGES) {

                val packet = transport.readPacket()

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
                            state = State.FAILED

                            return HandshakeResult.Rejected(
                                "Unsupported ADB authentication type: ${packet.arg0}"
                            )
                        }

                        if (packet.data.size != 20) {
                            state = State.FAILED

                            return HandshakeResult.Rejected(
                                "The device supplied an invalid authentication token."
                            )
                        }

                        challengeCount++

                        state = State.AUTHENTICATION_REQUIRED

                        if (!signatureSent) {

                            val signaturePacket =
                                authentication.createSignaturePacket(
                                    packet.data.copyOf()
                                )

                            transport.writePacket(signaturePacket)

                            signatureSent = true

                            state = State.CONNECTING

                        } else if (!publicKeySent) {

                            val publicKeyPacket =
                                authentication.createPublicKeyPacket()

                            transport.writePacket(publicKeyPacket)

                            authentication.markPublicKeySent()

                            publicKeySent = true

                            state = State.AUTHENTICATION_REQUIRED

                        } else {

                            state = State.FAILED

                            return HandshakeResult.Rejected(
                                "The device did not accept the supplied ADB public key."
                            )
                        }
                    }

                    AdbProtocol.CMD_CLSE -> {
                        state = State.FAILED

                        return HandshakeResult.Rejected(
                            "The device closed the ADB connection."
                        )
                    }

                    else -> {
                        state = State.FAILED

                        return HandshakeResult.Rejected(
                            "Unexpected ADB response: " +
                            AdbProtocol.commandName(packet.command)
                        )
                    }
                }
            }

            state = State.FAILED

            HandshakeResult.Rejected(
                "ADB authentication exceeded the maximum challenge attempts."
            )

        } catch (error: Exception) {

            state = State.FAILED

            throw asIOException(
                "ADB authentication or connection failed.",
                error
            )
        }
    }

    /**
     * Sends the initial host CNXN packet.
     */
    @Throws(IOException::class)
    private fun sendConnectionRequest() {

        val banner = HOST_BANNER.toByteArray(
            StandardCharsets.UTF_8
        )

        transport.writePacket(
            AdbProtocol.AdbPacket(
                command = AdbProtocol.CMD_CNXN,
                arg0 = AdbProtocol.VERSION,
                arg1 = MAX_DATA_SIZE,
                data = banner
            )
        )
    }

    /**
     * Receives and interprets a single handshake response.
     */
    @Synchronized
    @Throws(IOException::class)
    fun receiveHandshakeResponse(): HandshakeResult {

        val packet = transport.readPacket()

        return when (packet.command) {

            AdbProtocol.CMD_CNXN -> {
                handleConnectionResponse(packet)
            }

            AdbProtocol.CMD_AUTH -> {
                handleAuthenticationRequest(packet)
            }

            AdbProtocol.CMD_CLSE -> {
                state = State.FAILED

                HandshakeResult.Rejected(
                    "The device closed the ADB connection."
                )
            }

            else -> {
                state = State.FAILED

                HandshakeResult.Rejected(
                    "Unexpected ADB response: " +
                    AdbProtocol.commandName(packet.command)
                )
            }
        }
    }

    /**
     * Processes the device CNXN response.
     */
    private fun handleConnectionResponse(
        packet: AdbProtocol.AdbPacket
    ): HandshakeResult {

        if (packet.arg0 <= 0 || packet.arg1 <= 0) {
            state = State.FAILED

            return HandshakeResult.Rejected(
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

        state = State.CONNECTED

        return HandshakeResult.Connected(
            version = negotiatedVersion,
            maxDataSize = negotiatedMaxDataSize,
            deviceBanner = deviceBanner,
            features = deviceFeatures
        )
    }

    /**
     * Detects an authentication challenge without signing it.
     */
    private fun handleAuthenticationRequest(
        packet: AdbProtocol.AdbPacket
    ): HandshakeResult {

        if (packet.arg0 != AdbProtocol.AUTH_TOKEN) {
            state = State.FAILED

            return HandshakeResult.Rejected(
                "Unsupported ADB authentication message type: ${packet.arg0}"
            )
        }

        if (packet.data.size != 20) {
            state = State.FAILED

            return HandshakeResult.Rejected(
                "The device supplied an invalid authentication token."
            )
        }

        state = State.AUTHENTICATION_REQUIRED

        return HandshakeResult.AuthenticationRequired(
            authenticationType = packet.arg0,
            token = packet.data.copyOf()
        )
    }

    /**
     * Extracts advertised device features.
     */
    private fun extractFeatures(
        banner: String
    ): List<String> {

        val featureSection = banner
            .split(';')
            .firstOrNull {
                it.startsWith("features=")
            }
            ?: return emptyList()

        val value = featureSection.removePrefix("features=")

        if (value.isBlank()) {
            return emptyList()
        }

        return value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Begins a new connection attempt.
     */
    private fun beginConnection() {

        if (state == State.CONNECTING) {
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

        negotiatedVersion = 0
        negotiatedMaxDataSize = 0
        deviceBanner = ""
        deviceFeatures = emptyList()

        state = State.CONNECTING
    }

    /**
     * Reports whether the ADB handshake completed successfully.
     */
    fun isConnected(): Boolean {
        return state == State.CONNECTED
    }

    /**
     * Closes the underlying USB transport.
     */
    @Synchronized
    fun close() {

        try {
            transport.close()
        } finally {
            state = State.CLOSED
        }
    }

    /**
     * Converts unexpected exceptions into readable IOExceptions.
     */
    private fun asIOException(
        message: String,
        error: Exception
    ): IOException {

        return if (error is IOException) {
            error
        } else {
            IOException(message, error)
        }
    }
}
