package com.ultimate.debugging.ultimate_debugging_tool

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A pretend phone in recovery waiting for an update package.
 *
 * It asks for the blocks it is told to ask for, then ends the session
 * with "DONEDONE" or "FAILFAIL". Everything the host sends is kept.
 */
class FakeSideloadDevice(
    /** Block numbers to ask for, in the order they are asked for. */
    private val blocks: List<Long>,
    private val ending: String = AdbSideload.DONE,
    /** Splits commands into small reads, to test the buffering. */
    private val splitReads: Boolean = false
) : SideloadChannel {

    val received = ByteArrayOutputStream()
    var closed = false

    private val outgoing = ArrayDeque<Byte>()
    private var sentRequests = 0

    private fun queueCommand(text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        require(bytes.size == 8) { "A sideload command is eight bytes: $text" }
        bytes.forEach { outgoing.addLast(it) }
    }

    override fun read(timeoutMs: Long): ByteArray? {
        if (outgoing.isEmpty()) {
            if (sentRequests < blocks.size) {
                queueCommand(String.format("%08d", blocks[sentRequests]))
                sentRequests++
            } else if (sentRequests == blocks.size) {
                queueCommand(ending)
                sentRequests++
            } else {
                return null
            }
        }

        if (!splitReads) {
            val all = ByteArray(outgoing.size) { outgoing.removeFirst() }
            return all
        }

        return byteArrayOf(outgoing.removeFirst())
    }

    override fun write(bytes: ByteArray, timeoutMs: Long) {
        received.write(bytes)
    }

    override fun close() {
        closed = true
    }
}
