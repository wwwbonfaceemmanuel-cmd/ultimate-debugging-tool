package com.ultimate.debugging.ultimate_debugging_tool

import java.util.Locale

/**
 * The fastboot protocol, as the bootloader speaks it.
 *
 * Every message is a short ASCII string. The host sends a command;
 * the phone answers with one of:
 *
 *   OKAY<payload>   the command worked
 *   FAIL<payload>   it was refused, with a reason
 *   INFO<payload>   a line of information, more may follow
 *   DATA<hex size>  how many bytes to send next
 *
 * Only reading and encoding live here, so the format can be tested
 * without a phone.
 */
object FastbootProtocol {

    /** The USB packet size every bootloader understands. */
    const val MAX_PACKET = 64

    const val OKAY = "OKAY"
    const val FAIL = "FAIL"
    const val INFO = "INFO"
    const val DATA = "DATA"

    sealed class Response {
        class Okay(val payload: String) : Response()
        class Data(val size: Long) : Response()
        class Info(val payload: String) : Response()
        class Fail(val payload: String) : Response()
        class Unknown(val text: String) : Response()
    }

    fun encodeCommand(command: String): ByteArray =
        command.toByteArray(Charsets.US_ASCII)

    /** Reads one reply packet; anything after a zero byte is padding. */
    fun parse(bytes: ByteArray): Response {
        val text = String(bytes, Charsets.ISO_8859_1)
            .substringBefore('\u0000')
            .trim()

        if (text.length < 4) return Response.Unknown(text)

        val head = text.substring(0, 4)
        val body = text.substring(4).trim()

        return when (head) {
            OKAY -> Response.Okay(body)
            FAIL -> Response.Fail(body)
            INFO -> Response.Info(body)
            DATA -> Response.Data(parseHex(body) ?: -1L)
            else -> Response.Unknown(text)
        }
    }

    /** 8 hexadecimal digits, lower case, as fastboot expects. */
    fun hex(value: Long): String = String.format(Locale.US, "%08x", value)

    /** Reads "0x1234", "1234" and " 0x1234 " alike. */
    fun parseHex(text: String): Long? {
        val clean = text.trim().removePrefix("0x").removePrefix("0X")
        return clean.toLongOrNull(16)
    }

    /**
     * Splits "version: 0.4" into its two halves.
     *
     * The last colon is the separator, because variable names may
     * contain one: "partition-size:boot: 0x1000000".
     */
    fun parseVariable(line: String): Pair<String, String>? {
        val index = line.lastIndexOf(':')
        if (index <= 0) return null

        val name = line.substring(0, index).trim()
        val value = line.substring(index + 1).trim()

        if (name.isEmpty()) return null

        return name to value
    }

    /** Partition names are plain: letters, digits, dash and underscore. */
    fun isPartitionName(value: String): Boolean =
        Regex("[A-Za-z0-9_-]{1,36}").matches(value)
}
