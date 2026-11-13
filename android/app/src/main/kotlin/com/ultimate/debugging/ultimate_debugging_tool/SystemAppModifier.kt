package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

/**
 * System-app tuner: safe modifiers for system apps that misbehave.
 * Never uninstalls a protected core package; all actions are reversible.
 */
class SystemAppModifier(private val runner: AdbShellRunner) {

    companion object {
        // Core packages that must never be touched
        val PROTECTED = setOf(
            "android", "com.android.systemui", "com.android.phone",
            "com.android.settings", "com.google.android.gms"
        )
        fun isProtected(pkg: String) = pkg.lowercase() in PROTECTED
        fun safeAction(pkg: String): String? {
            if (!ShellSupport.isPackageName(pkg)) return "Invalid package name"
            if (isProtected(pkg)) return "Protected system package — action refused"
            return null
        }
    }

    @Throws(IOException::class)
    fun report(packageName: String): String {
        val pkg = ShellSupport.quote(packageName)
        val ver = runner.shell("dumpsys package $pkg 2>&1 | grep -m1 -E 'versionName|enabled|stopped' | head -n 5; cmd package list packages -s 2>&1 | grep -c $pkg 2>/dev/null", 12000L).output
        val ops = runner.shell("cmd appops get $pkg 2>&1 | head -n 20", 10000L).output
        val b = ReportBuilder("SYSTEM APP — $packageName (CONNECTED PHONE)")
        b.section("Installed").text(ver.ifEmpty { "Not found" })
        b.section("AppOps").text(ops.ifEmpty { "No appops" })
        b.section("Safe fixes").text(
            "• Clear cache: pm clear --cache-only $packageName\n" +
            "• Remove updates: pm uninstall-system-updates $packageName\n" +
            "• Disable (reversible): pm disable-user $packageName\n" +
            "• Enable: pm enable $packageName\n" +
            "• Reset permissions: cmd appops reset $packageName\n" +
            "All are reversible via Repair Services."
        )
        return b.toString()
    }
}
