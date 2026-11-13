package com.ultimate.debugging.ultimate_debugging_tool

import java.io.File
import java.io.IOException

/**
 * Real log-bugreport bundle: dumpsys dropbox/bugreportz → private storage.
 * Truly writes files, not just reads.
 */
class LogBundle(private val runner: AdbShellRunner, private val exportDir: File) {

    @Throws(IOException::class)
    fun collect(): Map<String, Any?> {
        exportDir.mkdirs()
        val ts = System.currentTimeMillis()
        val bugFile = File(exportDir, "bugreport_$ts.txt")
        val logFile = File(exportDir, "logcat_$ts.txt")
        // Try bugreportz (stream mode) fallback to dumpsys
        val bug = runner.shell("bugreportz 2>&1 | head -n 200", 60000L).output
        bugFile.writeText(bug.ifEmpty { runner.shell("dumpsys dropbox 2>&1 | head -n 500", 30000L).output })
        val log = runner.shell("logcat -d -t 2000 -v threadtime 2>&1 | tail -n 2000", 30000L).output
        logFile.writeText(log)
        // Trim old bundles >24h done by caller
        return mapOf(
            "bugreportPath" to bugFile.absolutePath,
            "logcatPath" to logFile.absolutePath,
            "bugreportBytes" to bugFile.length(),
            "logcatBytes" to logFile.length(),
            "bugreportLines" to bug.take(800),
            "count" to 2
        )
    }

    companion object {
        fun trimOld(dir: File, maxAgeMs: Long = 24L*60*60*1000) {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
        }
    }
}
