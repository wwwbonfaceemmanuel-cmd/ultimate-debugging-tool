package com.ultimate.debugging.ultimate_debugging_tool

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A pretend bootloader: answers fastboot commands from a table and
 * keeps every byte the tool sent.
 */
class FakeFastbootDevice(
    private val variables: Map<String, String> = emptyMap(),
    /** Command prefix that answers FAIL, for example "flash:". */
    private val refuse: String? = null,
    private val chunkSize: Long = 8L
) : FastbootTransport {

    val commands = ArrayList<String>()
    val image = ByteArrayOutputStream()
    var closed = false

    private val replies = ArrayDeque<ByteArray>()
    private var expected = 0

    private fun reply(text: String) {
        replies.addLast(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun isCommand(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.US_ASCII)
        return listOf("getvar:", "download:", "flash:", "erase:", "reboot", "oem ")
            .any { text.startsWith(it) }
    }

    override fun write(bytes: ByteArray, timeoutMs: Long) {
        if (!isCommand(bytes)) {
            image.write(bytes)

            if (image.size() < expected) {
                reply("DATA" + FastbootProtocol.hex(chunkSize))
            } else {
                reply("OKAY")
            }
            return
        }

        val text = String(bytes, Charsets.US_ASCII)
        commands.add(text)

        if (refuse != null && text.startsWith(refuse)) {
            reply("FAIL" + "this phone says no")
            return
        }

        when {
            text == "getvar:all" -> {
                for ((name, value) in variables) {
                    reply("INFO$name: $value")
                }
                reply("OKAY")
            }

            text.startsWith("getvar:") -> {
                val name = text.removePrefix("getvar:")
                val value = variables[name]

                if (value == null) {
                    reply("FAILVariable $name is not supported")
                } else {
                    reply("OKAY$value")
                }
            }

            text.startsWith("download:") -> {
                expected = FastbootProtocol.parseHex(text.removePrefix("download:"))
                    ?.toInt() ?: 0
                reply("DATA" + FastbootProtocol.hex(chunkSize))
            }

            text.startsWith("flash:") || text.startsWith("erase:") ->
                reply("OKAY")

            text.startsWith("reboot") -> reply("OKAY")

            else -> reply("OKAY")
        }
    }

    override fun read(maxBytes: Int, timeoutMs: Long): ByteArray {
        return replies.removeFirstOrNull()
            ?: throw IOException("The phone stopped answering. Check the cable.")
    }

    override fun close() {
        closed = true
    }
}
