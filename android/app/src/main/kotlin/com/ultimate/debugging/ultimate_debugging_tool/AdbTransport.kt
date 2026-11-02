
package com.ultimate.debugging.ultimate_debugging_tool

import java.io.Closeable
import java.io.IOException

/**
 * Shared transport interface for ADB communication.
 * Implemented by USB and wireless TCP transports.
 */
interface AdbTransport : Closeable {

    @Throws(IOException::class)
    fun open()

    @Throws(IOException::class)
    fun writePacket(packet: AdbProtocol.AdbPacket)

    @Throws(IOException::class)
    fun readPacket(): AdbProtocol.AdbPacket

    override fun close()
}
