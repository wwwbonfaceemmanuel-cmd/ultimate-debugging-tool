package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

/** Small helpers shared by everything that talks to the target shell. */
object ShellSupport {

    /** Android package name, for example "com.example.app". */
    val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

    /** Host name or IPv4 address. */
    val HOST_NAME = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")

    private const val MARKER = "@@UDT@@"
    private const val MAX_SCRIPT_BYTES = 3000

    fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    /** Quotes a value so the shell treats it as one literal word. */
    fun quote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * Runs several commands in as few round trips as possible and
     * returns each command's output under its own key.
     *
     * A command that fails or does not exist yields its error text or an
     * empty string; it never stops the other commands.
     */
    @Throws(IOException::class)
    fun runSections(
        runner: AdbShellRunner,
        sections: Map<String, String>,
        timeoutMs: Long = 30000L
    ): Map<String, String> {
        val results = LinkedHashMap<String, String>()
        val batch = StringBuilder()

        fun flush() {
            if (batch.isEmpty()) return

            val output = runner.shell(batch.toString(), timeoutMs).output
            var current: String? = null
            val text = StringBuilder()

            for (line in output.split('\n')) {
                val clean = line.trimEnd('\r')

                if (clean.startsWith(MARKER)) {
                    current?.let { results[it] = text.toString().trim() }
                    current = clean.removePrefix(MARKER)
                    text.setLength(0)
                } else {
                    text.append(clean).append('\n')
                }
            }

            current?.let { results[it] = text.toString().trim() }
            batch.setLength(0)
        }

        for ((key, command) in sections) {
            val part = "echo '$MARKER$key'\n( $command ) 2>&1\n"

            if (batch.length + part.length > MAX_SCRIPT_BYTES) flush()

            batch.append(part)
        }

        flush()

        for (key in sections.keys) {
            results.putIfAbsent(key, "")
        }

        return results
    }

    /** Parses `getprop` output of the form "[key]: [value]". */
    fun parseProperties(output: String): Map<String, String> {
        val properties = LinkedHashMap<String, String>()
        val pattern = Regex("^\\[([^\\]]+)]: \\[(.*)]$")

        for (line in output.lineSequence()) {
            val match = pattern.find(line.trim()) ?: continue
            properties[match.groupValues[1]] = match.groupValues[2]
        }

        return properties
    }

    /** True when the text looks like a shell error rather than data. */
    fun looksLikeError(text: String): Boolean {
        val lower = text.lowercase()

        return lower.isBlank() ||
            lower.contains("permission denied") ||
            lower.contains("not found") ||
            lower.contains("no such file") ||
            lower.contains("can't find service") ||
            lower.contains("inaccessible") ||
            lower.startsWith("error:")
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "Unavailable"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0

        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }

        return if (unit == 0) {
            "$bytes B"
        } else {
            String.format(java.util.Locale.US, "%.2f %s", value, units[unit])
        }
    }

    fun formatDuration(totalSeconds: Long): String {
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60

        return buildString {
            if (days > 0) append(days).append(" d ")
            if (days > 0 || hours > 0) append(hours).append(" h ")
            append(minutes).append(" min")
        }
    }
}

/** Builds plain-text reports with consistent layout. */
class ReportBuilder(title: String) {

    private val text = StringBuilder(title).append('\n')

    fun section(name: String): ReportBuilder {
        text.append('\n').append('[').append(name.uppercase()).append("]\n")
        return this
    }

    fun line(label: String, value: String?): ReportBuilder {
        text.append(label).append(": ")
            .append(value?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unavailable")
            .append('\n')
        return this
    }

    fun text(value: String): ReportBuilder {
        text.append(value.trimEnd()).append('\n')
        return this
    }

    fun bullets(items: List<String>): ReportBuilder {
        items.forEach { text.append("- ").append(it).append('\n') }
        return this
    }

    override fun toString(): String = text.toString().trimEnd()
}
