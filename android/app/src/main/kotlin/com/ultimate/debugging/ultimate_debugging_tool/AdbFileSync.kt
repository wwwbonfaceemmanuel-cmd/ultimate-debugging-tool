package com.ultimate.debugging.ultimate_debugging_tool

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * ADB file transfer ("sync") client: list folders, read file details,
 * copy files from the phone and copy files to the phone.
 *
 * Works on every Android version and is binary safe.
 */
class AdbFileSync(private val stream: AdbStream) : Closeable {

    data class Entry(
        val name: String,
        val mode: Int,
        val size: Long,
        val modifiedSeconds: Long
    ) {
        val isDirectory: Boolean get() = mode and 0xF000 == 0x4000
        val isLink: Boolean get() = mode and 0xF000 == 0xA000
        val isFile: Boolean get() = mode and 0xF000 == 0x8000

        /** For example "rwxr-x---". */
        val permissions: String
            get() {
                val flags = "rwxrwxrwx"
                return String(
                    CharArray(9) { index ->
                        if (mode and (1 shl (8 - index)) != 0) {
                            flags[index]
                        } else {
                            '-'
                        }
                    }
                )
            }

        fun toMap(): Map<String, Any> = mapOf(
            "name" to name,
            "type" to when {
                isDirectory -> "directory"
                isLink -> "link"
                isFile -> "file"
                else -> "other"
            },
            "sizeBytes" to size,
            "modified" to modifiedSeconds * 1000L,
            "permissions" to permissions
        )
    }

    companion object {
        private const val TIMEOUT_MS = 30000L
        private const val MAX_PATH = 1024
        private const val DATA_CHUNK = 64 * 1024

        @Throws(IOException::class)
        fun open(runner: AdbShellRunner): AdbFileSync {
            return AdbFileSync(runner.open("sync:"))
        }
    }

    private var pending = ByteArray(0)
    private var pendingOffset = 0

    /** Lists a folder. A missing or unreadable folder gives no entries. */
    @Throws(IOException::class)
    fun list(path: String): List<Entry> {
        request("LIST", path)

        val entries = ArrayList<Entry>()

        while (true) {
            val id = readId()

            if (id == "DONE") {
                readExact(16)
                return entries
            }

            if (id != "DENT") throw unexpected(id)

            val fields = ByteBuffer.wrap(readExact(16))
                .order(ByteOrder.LITTLE_ENDIAN)

            val mode = fields.int
            val size = fields.int.toLong() and 0xFFFFFFFFL
            val time = fields.int.toLong() and 0xFFFFFFFFL
            val nameLength = fields.int

            if (nameLength < 0 || nameLength > 4096) {
                throw IOException("The device sent an invalid file name.")
            }

            val name = String(readExact(nameLength), StandardCharsets.UTF_8)

            if (name != "." && name != "..") {
                entries.add(Entry(name, mode, size, time))
            }
        }
    }

    /** Returns details of one path, or null when it does not exist. */
    @Throws(IOException::class)
    fun stat(path: String): Entry? {
        request("STAT", path)

        val id = readId()
        if (id != "STAT") throw unexpected(id)

        val fields = ByteBuffer.wrap(readExact(12))
            .order(ByteOrder.LITTLE_ENDIAN)

        val mode = fields.int
        val size = fields.int.toLong() and 0xFFFFFFFFL
        val time = fields.int.toLong() and 0xFFFFFFFFL

        if (mode == 0 && size == 0L && time == 0L) return null

        return Entry(path.substringAfterLast('/'), mode, size, time)
    }

    /**
     * Copies a file from the phone into [output].
     * Returns the number of bytes copied.
     */
    @Throws(IOException::class)
    fun pull(
        path: String,
        output: OutputStream,
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        request("RECV", path)

        var total = 0L

        while (true) {
            when (val id = readId()) {
                "DATA" -> {
                    val length = readInt()

                    if (length < 0 || length > DATA_CHUNK) {
                        throw IOException("The device sent an invalid data block.")
                    }

                    total += length

                    if (total > maxBytes) {
                        throw IOException("The file is larger than the allowed size.")
                    }

                    output.write(readExact(length))
                    onProgress?.invoke(total)
                }

                "DONE" -> {
                    readInt()
                    return total
                }

                "FAIL" -> throw IOException(readFailure())

                else -> throw unexpected(id)
            }
        }
    }

    /** Copies [input] to a file on the phone. Returns bytes sent. */
    @Throws(IOException::class)
    fun push(
        input: InputStream,
        remotePath: String,
        mode: Int = 420, // rw-r--r--
        modifiedSeconds: Long = System.currentTimeMillis() / 1000L,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        request("SEND", "$remotePath,$mode")

        val buffer = ByteArray(DATA_CHUNK)
        var total = 0L

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue

            stream.write(header("DATA", count) + buffer.copyOf(count), TIMEOUT_MS)
            total += count
            onProgress?.invoke(total)
        }

        stream.write(header("DONE", modifiedSeconds.toInt()), TIMEOUT_MS)

        return when (val id = readId()) {
            "OKAY" -> {
                readInt()
                total
            }

            "FAIL" -> throw IOException(readFailure())

            else -> throw unexpected(id)
        }
    }

    private fun request(id: String, path: String) {
        val bytes = path.toByteArray(StandardCharsets.UTF_8)

        if (bytes.isEmpty() || bytes.size > MAX_PATH) {
            throw IOException("The file path is empty or too long.")
        }

        stream.write(header(id, bytes.size) + bytes, TIMEOUT_MS)
    }

    private fun header(id: String, value: Int): ByteArray {
        return ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(StandardCharsets.US_ASCII))
            .putInt(value)
            .array()
    }

    private fun readId(): String {
        return String(readExact(4), StandardCharsets.US_ASCII)
    }

    private fun readInt(): Int {
        return ByteBuffer.wrap(readExact(4))
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }

    private fun readFailure(): String {
        val length = readInt()

        if (length < 0 || length > 4096) {
            return "The phone refused the file operation."
        }

        return String(readExact(length), StandardCharsets.UTF_8)
            .ifBlank { "The phone refused the file operation." }
    }

    private fun unexpected(id: String): IOException {
        return IOException("Unexpected file transfer reply from the phone: $id")
    }

    private fun readExact(length: Int): ByteArray {
        val result = ByteArray(length)
        var filled = 0

        while (filled < length) {
            if (pendingOffset >= pending.size) {
                pending = stream.read(TIMEOUT_MS)
                    ?: throw IOException(
                        "The phone ended the file transfer unexpectedly."
                    )
                pendingOffset = 0
                continue
            }

            val count = minOf(length - filled, pending.size - pendingOffset)
            System.arraycopy(pending, pendingOffset, result, filled, count)
            pendingOffset += count
            filled += count
        }

        return result
    }

    override fun close() {
        try {
            stream.write(header("QUIT", 0), 2000L)
        } catch (_: IOException) {
            // The stream is being closed anyway.
        }

        stream.close()
    }
}
