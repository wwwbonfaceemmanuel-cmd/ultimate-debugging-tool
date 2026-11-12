package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

/**
 * A pretend phone for tests of reports and repair services: answers
 * shell commands from a table and records everything it was asked.
 */
class ScriptedPhone(
    override val targetSdk: Int? = 33,
    private val answers: (String) -> String? = { null }
) : AdbShellRunner {

    val commands = ArrayList<String>()
    val services = ArrayList<String>()
    val state = HashMap<String, String>()

    override var link = AdbSessionManager.Link.USB

    /** Replies for exact commands, checked before [answers]. */
    val replies = HashMap<String, AdbShellSession.CommandResult>()

    fun reply(command: String, output: String, exitCode: Int = 0) {
        replies[command] = AdbShellSession.CommandResult(output, "", exitCode, true)
    }

    private fun single(command: String): AdbShellSession.CommandResult {
        commands.add(command)
        replies[command]?.let { return it }
        return AdbShellSession.CommandResult(answers(command) ?: "", "", 0, true)
    }

    override fun shell(
        command: String,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): AdbShellSession.CommandResult {
        if (link == AdbSessionManager.Link.NONE) throw IOException("No phone is connected over ADB.")

        // Batched scripts built by ShellSupport.runSections.
        if (command.startsWith("echo '@@UDT@@")) {
            val output = StringBuilder()
            val lines = command.trimEnd('\n').split('\n')
            var index = 0
            while (index + 1 < lines.size) {
                output.append(lines[index].removePrefix("echo '").removeSuffix("'")).append('\n')
                val inner = lines[index + 1].removePrefix("( ").removeSuffix(" ) 2>&1")
                output.append(single(inner).output.trimEnd('\n')).append('\n')
                index += 2
            }
            return AdbShellSession.CommandResult(output.toString(), "", 0, true)
        }

        return single(command)
    }

    override fun service(name: String, timeoutMs: Long): String {
        services.add(name)
        if (name.startsWith("reboot:")) {
            link = AdbSessionManager.Link.NONE
            throw IOException("The device was unplugged.")
        }
        return ""
    }

    override fun open(service: String): AdbStream = throw IOException("not supported in this test")
}
