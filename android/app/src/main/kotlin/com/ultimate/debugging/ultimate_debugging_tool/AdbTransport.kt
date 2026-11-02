
package com.ultimate.debugging.ultimate_debugging_tool

import java.io.Closeable

/**
 * Shared transport interface for Android Debug Bridge.
 *
 * USB and wireless TCP transports will implement
 * this interface so they can use the same ADB engine.
 */
interface AdbTransport : Closeable {

    fun open()

    fun writePacket(packet: ByteArray)

    fun readPacket(): ByteArray

    override fun close()
}
