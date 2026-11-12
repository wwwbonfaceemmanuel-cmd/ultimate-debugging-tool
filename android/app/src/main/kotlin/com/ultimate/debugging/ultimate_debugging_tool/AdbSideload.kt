package com.ultimate.debugging.ultimate_debugging_tool

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A byte pipe to a phone that is waiting for an update package.
 *
 * It is the small part of an ADB stream that sideload needs, so the
 * protocol below can be tested without a phone or a socket.
 */
interface SideloadChannel : Closeable {

    /** The next bytes from the phone, or null when it closed. */
    @Throws(IOException::class)
    fun read(timeoutMs: Long = 120_000L): ByteArray?

    @Throws(IOException::class)
    fun write(bytes: ByteArray, timeoutMs: Long = 120_000L)
}

/**
 * Sends an OTA package to a phone in recovery, over ADB ("Apply update
 * from ADB").
 *
 * The protocol, as minadbd speaks it:
 *  - the host opens the service `sideload-host:<size>:<block size>`;
 *  - the phone asks for a block by sending eight decimal digits;
 *  - the host answers with that block, the last block being short;
 *  - the phone ends the session with "DONEDONE" or "FAILFAIL".
 *
 * The phone reads most of the package more than once, so the progress
 * figure runs ahead of the package size: it is an estimate, and that is
 * said on screen.
 */
class AdbSideload(private val open: (String) -> SideloadChannel) {

    companion object {
        const val BLOCK_SIZE = 64 * 1024
        const val DONE = "DONEDONE"
        const val FAIL = "FAILFAIL"

        private const val READ_TIMEOUT_MS = 120_000L

        /** An update package is a zip, so it starts with "PK\3\4". */
        fun looksLikePackage(head: ByteArray): Boolean {
            return head.size >= 4 &&
                head[0] == 'P'.code.toByte() &&
                head[1] == 'K'.code.toByte() &&
                head[2] == 3.toByte() &&
                head[3] == 4.toByte()
        }

        /** Reads the first bytes of a file, for [looksLikePackage]. */
        fun headOf(file: File, count: Int = 4): ByteArray {
            val head = ByteArray(count)
            var filled = 0

            file.inputStream().use { stream ->
                while (filled < count) {
                    val read = stream.read(head, filled, count - filled)
                    if (read <= 0) break
                    filled += read
                }
            }

            return head.copyOf(filled)
        }
    }

    constructor(runner: AdbShellRunner) : this({ service ->
        val stream = runner.open(service)

        object : SideloadChannel {
            override fun read(timeoutMs: Long): ByteArray? = stream.read(timeoutMs)

            override fun write(bytes: ByteArray, timeoutMs: Long) {
                stream.write(bytes, timeoutMs)
            }

            override fun close() = stream.close()
        }
    })

    class Result(
        val bytesSent: Long,
        val blocksServed: Long,
        val packageBytes: Long,
        val message: String
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "bytesSent" to bytesSent,
            "blocksServed" to blocksServed,
            "packageBytes" to packageBytes,
            "message" to message
        )
    }

    /**
     * Serves [file] to the phone.
     *
     * [onProgress] receives 0 to 100, at a readable pace.
     */
    @Throws(IOException::class)
    fun send(file: File, onProgress: (Int) -> Unit = {}): Result {
        if (!file.isFile) {
            throw IOException("The update package is missing. Choose it again.")
        }

        val size = file.length()

        if (size <= 0L) {
            throw IOException("The update package is empty.")
        }

        if (!looksLikePackage(headOf(file))) {
            throw IOException(
                "That file is not an update package. An OTA package is a zip file."
            )
        }

        val channel = try {
            open("sideload-host:$size:$BLOCK_SIZE")
        } catch (e: IOException) {
            throw IOException(
                "The phone did not accept a sideload connection. In recovery, " +
                    "choose \"Apply update from ADB\" first. (${e.message})"
            )
        }

        channel.use {
            val source = RandomAccessFile(file, "r")
            val reader = ByteReader(channel)

            var sent = 0L
            var blocks = 0L
            var lastPercent = -1

            try {
                onProgress(0)

                while (true) {
                    val command = String(reader.readExactly(8), Charsets.US_ASCII)

                    if (command == DONE) {
                        onProgress(100)

                        return Result(
                            sent,
                            blocks,
                            size,
                            "The phone accepted the package and is installing it. " +
                                "Keep it connected until its screen says it has finished."
                        )
                    }

                    if (command == FAIL) {
                        throw IOException(
                            "The phone rejected the update package. It may belong " +
                                "to another phone, or it may be damaged."
                        )
                    }

                    val block = command.trim().toLongOrNull()
                        ?: throw IOException(
                            "The phone asked for \"$command\", which is not a block this tool knows."
                        )

                    val offset = block * BLOCK_SIZE

                    if (offset >= size) {
                        throw IOException(
                            "The phone asked for data past the end of the package."
                        )
                    }

                    val length = minOf(BLOCK_SIZE.toLong(), size - offset).toInt()
                    val buffer = ByteArray(length)

                    source.seek(offset)
                    source.readFully(buffer)

                    channel.write(buffer, READ_TIMEOUT_MS)

                    sent += length
                    blocks++

                    val percent = (sent * 47L / size).toInt().coerceIn(0, 100)

                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            } finally {
                source.close()
            }
        }
    }

    /** Hands out exactly as many bytes as asked, keeping what is left. */
    private class ByteReader(private val channel: SideloadChannel) {

        private var left = ByteArray(0)
        private var offset = 0

        fun readExactly(count: Int): ByteArray {
            val result = ByteArray(count)
            var filled = 0

            while (filled < count) {
                if (offset >= left.size) {
                    left = channel.read(READ_TIMEOUT_MS)
                        ?: throw IOException(
                            "The phone closed the sideload connection. Check its screen."
                        )
                    offset = 0

                    if (left.isEmpty()) continue
                }

                val take = minOf(count - filled, left.size - offset)
                System.arraycopy(left, offset, result, filled, take)

                offset += take
                filled += take
            }

            return result
        }
    }
}
