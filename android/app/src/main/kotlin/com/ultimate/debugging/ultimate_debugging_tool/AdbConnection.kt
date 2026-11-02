
package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Handles the initial ADB connection handshake.
 *
 * Responsibilities:
 * - Send the host CNXN packet.
 * - Receive the device handshake response.
 * - Identify successful connection negotiation.
 * - Detect ADB authentication challenges.
 *
 * USB transport and cryptographic authentication are separate layers.
 */
class AdbConnection(
    private val transport: AdbUsbTransport
) {

    companion object {
        private const val MAX_DATA_SIZE = 1024 * 1024

        private const val HOST_BANNER =
            "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir," +
            "apex,abb,abb_exec,remount_shell,track_app,sendrecv_v2\${'\u0000'}"
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

    var negotiatedVersion: Int = 0
        private set

    var negotiatedMaxDataSize: Int = 0
        private set

    var deviceBanner: String = ""
        private set

    var deviceFeatures: List<String> = emptyList()
        private set

    /**
     * Starts ADB connection negotiation.
     *
     * Call from a background thread because USB operations block.
     */
    @Synchronized
    @Throws(IOException::class)
    fun connect(): HandshakeResult {

        if (state == State.CONNECTED) {
            throw IOException("ADB connection is already established.")
        }

        if (state == State.CLOSED) {
            throw IOException("ADB connection has been closed.")
        }

        state = State.CONNECTING

        try {
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

            return receiveHandshakeResponse()

        } catch (error: Exception) {
            state = State.FAILED

            if (error is IOException) {
                throw error
            }

            throw IOException(
                "ADB connection negotiation failed.",
                error
            )
        }
    }

    /**
     * Receives and interprets the first device handshake response.
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
     * Processes an ADB authentication challenge.
     *
     * This detects the challenge but does not sign it.
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

        if (packet.data.isEmpty()) {
            state = State.FAILED

            return HandshakeResult.Rejected(
                "The device supplied an empty authentication token."
            )
        }

        state = State.AUTHENTICATION_REQUIRED

        return HandshakeResult.AuthenticationRequired(
            authenticationType = packet.arg0,
            token = packet.data.copyOf()
        )
    }

    /**
     * Extracts advertised features from the device banner.
     */
    private fun extractFeatures(banner: String): List<String> {

        val featureSection = banner
            .split(';')
            .firstOrNull { it.startsWith("features=") }
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
     * Reports whether connection negotiation succeeded.
     *
     * This does not independently verify USB authorization.
     */
    fun isConnected(): Boolean {
        return state == State.CONNECTED
    }

    /**
     * Closes the USB transport.
     */
    @Synchronized
    fun close() {
        transport.close()
        state = State.CLOSED
    }
}
