package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

/**
 * Real AppOps deep audit: CAMERA, MIC, LOCATION background, etc.
 */
class AppOpsInspector(private val runner: AdbShellRunner) {

    companion object {
        val RISKY_OPS = listOf("CAMERA","RECORD_AUDIO","COARSE_LOCATION","FINE_LOCATION","POST_NOTIFICATION","RUN_IN_BACKGROUND","SYSTEM_ALERT_WINDOW")
        fun parseOps(output: String): Map<String,String> {
            val m = mutableMapOf<String,String>()
            for (line in output.lineSequence()) {
                for (op in RISKY_OPS) if (line.contains(op, true)) { m[op] = line.trim(); break }
            }
            return m
        }
    }

    @Throws(IOException::class)
    fun audit(packageName: String?): String {
        val pkg = packageName?.takeIf { ShellSupport.isPackageName(it) }
        val b = ReportBuilder("APPOPS DEEP AUDIT (CONNECTED PHONE)")
        if (pkg != null) {
            val dump = runner.shell("cmd appops get ${ShellSupport.quote(pkg)} 2>&1 | head -n 80", 15000L).output
            b.section("Package: $pkg").text(dump.ifEmpty { "No appops data (old Android or permission denied)" })
            val ops = parseOps(dump)
            if (ops.isNotEmpty()) { b.section("Risks"); ops.forEach { (k,v) -> b.text("$k → $v") } }
            b.section("Fix").text("Reset: cmd appops reset $pkg (fix-app-settings). Block bg: cmd appops set $pkg RUN_IN_BACKGROUND ignore")
        } else {
            val all = runner.shell("cmd appops query --user 0 --op RUN_IN_BACKGROUND 2>&1 | head -n 40; cmd appops query --user 0 --op CAMERA 2>&1 | head -n 40", 20000L).output
            b.section("Background / Camera users (sample)").text(all.ifEmpty { "No data — try per-app audit" })
            b.section("Fix").text("Use fix-app-bg-allow / fix-app-standby / fix-app-settings per app in App Workbench")
        }
        return b.toString()
    }
}
