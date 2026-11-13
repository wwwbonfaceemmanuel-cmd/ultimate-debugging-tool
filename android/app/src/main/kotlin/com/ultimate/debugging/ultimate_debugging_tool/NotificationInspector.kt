package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

/**
 * Real notification + battery-drain inspector.
 * Reads dumpsys notification, appops POST_NOTIFICATION, gfxinfo jank,
 * batterystats and APN config — all via ADB shell, no root assumed.
 * Every method is pure reading; fixes go through RepairServices.
 */
class NotificationInspector(private val runner: AdbShellRunner) {

    data class NotificationEntry(
        val packageName: String,
        val blocked: Boolean,
        val importance: String
    )
    data class BatteryDrain(val packageName: String, val mah: Double)
    data class ApnEntry(val name: String, val apn: String, val current: Boolean)
    data class GfxJank(val packageName: String, val jankPercent: Double, val frames: Int)

    companion object {
        fun parseNotificationBlocked(dump: String, pkg: String): Boolean {
            // "Package blocked: true" or "importance=NONE" means blocked
            val section = extractPackageSection(dump, pkg) ?: return false
            return section.contains("blocked=true", true) ||
                    section.contains("importance=NONE", true) ||
                    section.contains("importance=0", true) ||
                    section.contains("notifications are blocked", true)
        }
        fun parseAppOpsPostNotification(dump: String): Boolean {
            // "POST_NOTIFICATION: allow" vs ignore/deny
            val line = dump.lineSequence().firstOrNull { it.contains("POST_NOTIFICATION", true) } ?: return true
            return line.contains("allow", true) && !line.contains("ignore", true) && !line.contains("deny", true)
        }
        fun parseZenMode(dump: String): String {
            val v = Regex("zen_mode=(\\d)").find(dump)?.groupValues?.get(1) ?: ""
            return when (v) { "1"->"PRIORITY"; "2"->"TOTAL_SILENCE"; "3"->"ALARMS"; "0",""->"OFF"; else->v }
        }
        fun parseBatteryDrain(dump: String): List<BatteryDrain> {
            // batterystats --checkin line: 9,c,pkg,xx,computed drain, or dumpsys batterystats Est. power use
            val result = mutableListOf<BatteryDrain>()
            for (line in dump.lineSequence()) {
                val m = Regex("""Estimated power use.*\"?([a-z0-9._]+)\"?[:\s]+([0-9.]+)\s*mAh""", RegexOption.IGNORE_CASE).find(line)
                if (m != null) result.add(BatteryDrain(m.groupValues[1], m.groupValues[2].toDoubleOrNull()?:0.0))
            }
            // fallback: checkin format
            if (result.isEmpty()) {
                for (line in dump.lineSequence()) {
                    if (line.startsWith("9,")) {
                        val parts = line.split(",")
                        if (parts.size >= 5) {
                            val pkg = parts[2].trim()
                            val mah = parts.last().toDoubleOrNull() ?: continue
                            if (pkg.isNotEmpty() && mah > 1.0) result.add(BatteryDrain(pkg, mah))
                        }
                    }
                }
            }
            return result.sortedByDescending { it.mah }.take(10)
        }
        fun parseApnList(dump: String): List<ApnEntry> {
            val list = mutableListOf<ApnEntry>()
            var curName = ""; var curApn=""; var isCur=false
            for (line in dump.lineSequence()) {
                val n = Regex("name=([^,;\\n]+)").find(line)?.groupValues?.get(1)?.trim()
                val a = Regex("apn=([^,;\\n]+)").find(line)?.groupValues?.get(1)?.trim()
                if (n != null) curName = n
                if (a != null) curApn = a
                if (line.contains("current") || line.contains("preferred")) isCur = true
                if (curName.isNotEmpty() && curApn.isNotEmpty()) {
                    list.add(ApnEntry(curName, curApn, isCur))
                    curName=""; curApn=""; isCur=false
                }
            }
            return list
        }
        fun parseGfxJank(dump: String): GfxJank? {
            // gfxinfo framestats: Janky frames: X (Y%)
            val m = Regex("""Janky frames:\s*(\d+)\s*\(?\s*([0-9.]+)%\)?""", RegexOption.IGNORE_CASE).find(dump)
            if (m != null) {
                val frames = m.groupValues[1].toIntOrNull()?:0
                val pct = m.groupValues[2].toDoubleOrNull()?:0.0
                // package name is first line "Package: com.example"
                val pkg = Regex("""Package:\s*([a-z0-9._]+)""", RegexOption.IGNORE_CASE).find(dump)?.groupValues?.get(1) ?: "unknown"
                return GfxJank(pkg, pct, frames)
            }
            return null
        }
        private fun extractPackageSection(dump: String, pkg: String): String? {
            val idx = dump.indexOf(pkg, ignoreCase = true)
            if (idx < 0) return null
            return dump.substring(idx, minOf(idx+4000, dump.length))
        }
    }

    @Throws(IOException::class)
    fun notificationReport(packageName: String?): String {
        val pkg = packageName?.takeIf { ShellSupport.isPackageName(it) }
        val dump = runner.shell("dumpsys notification --noredact 2>&1 | head -n 600", 30000L).output
        val zen = runner.shell("settings get global zen_mode 2>/dev/null; dumpsys notification 2>&1 | grep -i 'zen_mode' | head -n 3", 20000L).output
        val builder = ReportBuilder("NOTIFICATION AUDIT (CONNECTED PHONE)")
            .section("Global Do Not Disturb")
            .line("Zen mode", parseZenMode(zen))
            .text(if (parseZenMode(zen) != "OFF") "DND is ON — notifications are silenced. Run fix-mute-off or fix-notifications." else "DND is off.")
        if (pkg != null) {
            val blocked = parseNotificationBlocked(dump, pkg)
            val appOps = runner.shell("cmd appops get ${ShellSupport.quote(pkg)} POST_NOTIFICATION 2>&1", 20000L).output
            val allowed = parseAppOpsPostNotification(appOps)
            builder.section("Package: $pkg")
                .line("System blocked", if (blocked) "YES" else "No")
                .line("AppOps POST_NOTIFICATION", if (allowed) "allow" else "deny/ignore")
                .text(if (blocked || !allowed) "Fix: cmd appops set $pkg POST_NOTIFICATION allow" else "Notifications allowed for this app.")
        } else {
            // top 20 packages with blocked status
            val lines = dump.lineSequence().filter { it.contains("blocked", true) }.take(20).toList()
            builder.section("Blocked packages (sample)")
            if (lines.isEmpty()) builder.text("No blocked entries found in dumpsys sample.")
            else lines.forEach { builder.text(it.trim()) }
        }
        return builder.toString()
    }

    @Throws(IOException::class)
    fun batteryDrainReport(): String {
        val checkin = runner.shell("dumpsys batterystats --checkin 2>&1 | head -n 200", 30000L).output
        val est = runner.shell("dumpsys batterystats 2>&1 | grep -i 'Estimated power' | head -n 20", 30000L).output
        val drains = parseBatteryDrain(est.ifEmpty { checkin })
        val builder = ReportBuilder("BATTERY DRAIN TOP APPS (CONNECTED PHONE)")
        if (drains.isEmpty()) {
            builder.section("Result").text("No per-app drain data available. Charge cycle may be short; try after a full charge.")
        } else {
            builder.section("Top drainers")
            drains.forEach { builder.text("${it.packageName}: ${String.format(java.util.Locale.US, "%.1f", it.mah)} mAh") }
            builder.section("Fix").text("Restrict heavy apps: am set-standby-bucket <pkg> restricted, or fix-app-standby <pkg>.")
        }
        return builder.toString()
    }

    @Throws(IOException::class)
    fun apnReport(): String {
        val dump = runner.shell("content query --uri content://telephony/carriers --projection name:apn:current 2>&1 | head -n 80", 30000L).output
        val apns = parseApnList(dump)
        val builder = ReportBuilder("APN CONFIG (CONNECTED PHONE)")
        if (apns.isEmpty()) {
            builder.section("APNs").text(dump.take(2000).ifEmpty { "No APN data returned (permission or no SIM)." })
        } else {
            builder.section("APNs")
            apns.forEach { builder.text("${if (it.current) "* " else "  "}${it.name} — apn=${it.apn}") }
        }
        builder.section("Fix").text("Broken APN after SIM swap → run fix-apn-reset (deletes user APNs, restores defaults).")
        return builder.toString()
    }

    @Throws(IOException::class)
    fun gfxReport(packageName: String?): String {
        val pkg = packageName?.takeIf { ShellSupport.isPackageName(it) }
        val dump = if (pkg != null) runner.shell("dumpsys gfxinfo ${ShellSupport.quote(pkg)} framestats 2>&1 | tail -n 120", 30000L).output
                   else runner.shell("dumpsys gfxinfo 2>&1 | head -n 200", 30000L).output
        val jank = parseGfxJank(dump)
        val builder = ReportBuilder("GFX JANK REPORT (CONNECTED PHONE)")
        if (jank != null) {
            builder.section("Jank").line("Package", jank.packageName).line("Janky frames", "${jank.frames} (${jank.jankPercent}%)")
                .text(if (jank.jankPercent > 30) "Heavy jank — try fix-app-compile <pkg> or fix-slow-phone." else "Jank within normal range.")
        } else {
            builder.section("Raw").text(dump.take(3000).ifEmpty { "No gfxinfo data (app may not be rendering)." })
        }
        return builder.toString()
    }
}
