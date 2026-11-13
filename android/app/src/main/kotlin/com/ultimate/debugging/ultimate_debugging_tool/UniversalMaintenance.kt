package com.ultimate.debugging.ultimate_debugging_tool
import java.io.IOException
class UniversalMaintenance(private val runner: AdbShellRunner) {
    @Throws(IOException::class)
    fun duplicateReport(limitMb: Int = 10): String {
        // Fast duplicate candidates by size then md5 on small set
        val dupCandidates = runner.shell("find /sdcard -type f -size +${limitMb}M -printf \"%s %p\\n\" 2>&1 | sort -n | head -n 40", 18000L).output
        val b = ReportBuilder("DUPLICATE & CACHE MAINTENANCE (CONNECTED PHONE)")
        b.section("Large candidates >${limitMb}MB").text(dupCandidates.take(2000).ifEmpty { "None over ${limitMb}MB" })
        val perApp = runner.shell("dumpsys diskstats 2>&1 | head -n 30; pm list packages -3 2>&1 | wc -l", 12000L).output
        b.section("Per-app cache hint").text(perApp.trim())
        b.section("Fix").text("Use StorageHealth large-files + fix-caches/fix-storage. For dupes, move one copy to This phone via File Manager.")
        b.section("Debloat").text("Carrier bloat: pm list packages -s | grep -iE 'facebook|netflix|carrier' — disable-safe via App Workbench (fix-system-disable-safe). Protected cores refused.")
        return b.toString()
    }
    @Throws(IOException::class)
    fun startupReport(): String {
        val boot = runner.shell("cmd package query-receivers --components -a android.intent.action.BOOT_COMPLETED 2>&1 | head -n 60", 15000L).output
        val b = ReportBuilder("STARTUP & AUTO-START (CONNECTED PHONE)")
        b.section("BOOT receivers (sample)").text(boot.take(3000).ifEmpty { "No data (older Android)" })
        b.section("Fix").text("Disable bloat auto-start via fix-system-disable-safe for user-bloat only. Do not touch gms/systemui.")
        return b.toString()
    }
    @Throws(IOException::class)
    fun sensorReport(): String {
        val s = runner.shell("dumpsys sensorservice 2>&1 | head -n 60; dumpsys battery 2>&1 | head -n 20", 15000L).output
        val b = ReportBuilder("SENSORS & HARDWARE EXTENDED")
        b.section("Sensors").text(s.take(3000))
        b.section("Quick tests").text("• Touch: dial *#*#2664#*#* (if supported)\n• Audio: fix-volume-reset + play tone\n• Camera: launch via intent — no root needed\n• Fingerprint: settings secure fingerprint state")
        return b.toString()
    }
}
