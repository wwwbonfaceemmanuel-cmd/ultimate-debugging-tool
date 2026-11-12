package com.ultimate.debugging.ultimate_debugging_tool

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Executes shell commands on the connected phone.
 *
 * Supports the modern shell_v2 protocol (separate stdout, stderr and
 * exit code) and the legacy shell used by Android 6 and older, where
 * the exit code is recovered with a marker line.
 *
 * A command that times out or fails only closes its own stream. The
 * ADB connection stays usable for the next command.
 */
class AdbShellSession(
    private val connection: AdbConnection,
    private val supportsShellV2: Boolean
) {

    data class CommandResult(
        val output: String,
        val errorOutput: String,
        val exitCode: Int?,
        val usedShellV2: Boolean,
        val truncated: Boolean = false
    ) {
        /** True when the command reported success (or gave no exit code). */
        val succeeded: Boolean
            get() = exitCode == null || exitCode == 0

        /** stdout followed by stderr, for commands that mix both. */
        val combined: String
            get() = when {
                errorOutput.isBlank() -> output
                output.isBlank() -> errorOutput
                else -> output.trimEnd('\n') + "\n" + errorOutput
            }
    }

    companion object {
        private const val CHANNEL_STDIN = 0
        private const val CHANNEL_STDOUT = 1
        private const val CHANNEL_STDERR = 2
        private const val CHANNEL_EXIT = 3

        const val DEFAULT_TIMEOUT_MS = 30000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 4 * 1024 * 1024

        private const val EXIT_MARKER = "__UDT_EXIT_CODE__="
    }

    /** Executes one command and collects its complete output. */
    @Throws(IOException::class)
    fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES
    ): CommandResult {
        if (command.isBlank()) {
            throw IOException("Command cannot be empty.")
        }

        if (timeoutMs <= 0) {
            throw IOException("Command timeout must be positive.")
        }

        val service = if (supportsShellV2) {
            "shell,v2,raw:$command"
        } else {
            // Legacy shells do not report an exit code, so print one.
            "shell:$command\necho \"$EXIT_MARKER\$?\""
        }

        val deadline = System.nanoTime() + timeoutMs * 1_000_000L

        val stream = connection.openStream(
            service,
            minOf(timeoutMs, 10000L)
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val frameBuffer = ByteArrayOutputStream()

        var exitCode: Int? = null
        var truncated = false

        try {
            while (true) {
                val remainingMs =
                    (deadline - System.nanoTime()) / 1_000_000L

                if (remainingMs <= 0) {
                    throw AdbTimeoutException(
                        "The command did not finish within " +
                            "${timeoutMs / 1000} seconds."
                    )
                }

                val chunk = try {
                    stream.read(remainingMs)
                } catch (_: AdbTimeoutException) {
                    throw AdbTimeoutException(
                        "The command did not finish within " +
                            "${timeoutMs / 1000} seconds."
                    )
                } ?: break

                if (supportsShellV2) {
                    val code = processShellV2Data(
                        incoming = chunk,
                        buffer = frameBuffer,
                        stdout = stdout,
                        stderr = stderr
                    )

                    if (code != null) exitCode = code
                } else {
                    stdout.write(chunk)
                }

                if (stdout.size() + stderr.size() > maxOutputBytes) {
                    truncated = true
                    break
                }
            }
        } finally {
            stream.close()
        }

        var outputText = decode(stdout, maxOutputBytes)
        val errorText = decode(stderr, maxOutputBytes)

        if (!supportsShellV2) {
            val markerIndex = outputText.lastIndexOf(EXIT_MARKER)

            if (markerIndex >= 0) {
                exitCode = outputText
                    .substring(markerIndex + EXIT_MARKER.length)
                    .trim()
                    .toIntOrNull()

                outputText = outputText.substring(0, markerIndex)
            }

            // Legacy shells use a terminal and send CR LF line endings.
            outputText = outputText.replace("\r\n", "\n")
        }

        return CommandResult(
            output = outputText,
            errorOutput = errorText,
            exitCode = exitCode,
            usedShellV2 = supportsShellV2,
            truncated = truncated
        )
    }

    private fun decode(
        bytes: ByteArrayOutputStream,
        limit: Int
    ): String {
        val raw = bytes.toByteArray()
        val length = minOf(raw.size, limit)

        return String(raw, 0, length, StandardCharsets.UTF_8)
    }

    /**
     * Decodes shell_v2 frames: one channel byte, a 4-byte little-endian
     * length, then the data. Returns the exit code when it arrives.
     */
    private fun processShellV2Data(
        incoming: ByteArray,
        buffer: ByteArrayOutputStream,
        stdout: ByteArrayOutputStream,
        stderr: ByteArrayOutputStream
    ): Int? {
        buffer.write(incoming)

        val data = buffer.toByteArray()
        var offset = 0
        var exitCode: Int? = null

        while (data.size - offset >= 5) {
            val channel = data[offset].toInt() and 0xFF

            val length = ByteBuffer
                .wrap(data, offset + 1, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

            if (length < 0 || length > AdbProtocol.MAX_DATA_SIZE) {
                throw IOException("Invalid ADB shell_v2 frame length.")
            }

            val frameSize = 5 + length

            if (data.size - offset < frameSize) {
                break
            }

            when (channel) {
                CHANNEL_STDIN -> {
                    // No stdin data is expected from the device.
                }

                CHANNEL_STDOUT ->
                    stdout.write(data, offset + 5, length)

                CHANNEL_STDERR ->
                    stderr.write(data, offset + 5, length)

                CHANNEL_EXIT -> {
                    if (length > 0) {
                        exitCode = data[offset + 5].toInt() and 0xFF
                    }
                }

                else -> {
                    // Unknown channels are ignored for forward compatibility.
                }
            }

            offset += frameSize
        }

        buffer.reset()

        if (offset < data.size) {
            buffer.write(data, offset, data.size - offset)
        }

        return exitCode
    }
}
