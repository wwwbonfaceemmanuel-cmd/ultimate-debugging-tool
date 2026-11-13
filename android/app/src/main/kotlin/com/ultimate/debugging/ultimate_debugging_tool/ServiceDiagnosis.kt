package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.util.Locale

/**
 * The full service diagnosis of the CONNECTED phone.
 *
 * This is the engine behind the Debugging Service screen. It runs a
 * battery of checks over ADB, grades each one, and — where a fault can
 * be fixed safely — names the repair service that fixes it. The screen
 * then runs that repair (with the technician's confirmation) and checks
 * the phone again, so the whole job is diagnose, fix, verify.
 *
 * Diagnosis itself only reads. Every fix runs through [RepairServices],
 * which confirms, verifies and records it.
 */
class ServiceDiagnosis(private val runner: AdbShellRunner) {

    enum class Status { OK, INFO, WARNING, FAULT, CRITICAL }

    class Check(
        val id: String,
        val title: String,
        val status: Status,
        val detail: String,
        val evidence: String = "",
        /** Repair service id that fixes this, or null when it cannot be fixed here. */
        val fixId: String? = null
    ) {
        val autoFixable: Boolean get() = fixId != null

        val fixTitle: String
            get() = fixId?.let { RepairServices.find(it)?.title ?: it } ?: ""

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "id" to id,
            "title" to title,
            "status" to status.name,
            "detail" to detail,
            "evidence" to evidence,
            "fixId" to fixId,
            "fixTitle" to fixTitle,
            "autoFixable" to autoFixable
        )
    }

    class Result(val checks: List<Check>) {

        val score: Int get() = scoreFor(checks)

        val verdict: String get() = verdictFor(score)

        val faultCount: Int
            get() = checks.count {
                it.status == Status.FAULT || it.status == Status.CRITICAL
            }

        val attentionCount: Int
            get() = checks.count {
                it.status == Status.WARNING ||
                    it.status == Status.FAULT ||
                    it.status == Status.CRITICAL
            }

        val fixableCount: Int
            get() = checks.count { it.autoFixable && it.status != Status.OK }

        fun summary(): String {
            val total = checks.size
            return when {
                attentionCount == 0 -> "$total checks, all healthy."
                faultCount > 0 -> "$total checks, $attentionCount needing attention ($faultCount faults)."
                else -> "$total checks, $attentionCount needing attention."
            }
        }

        fun report(): String {
            val builder = ReportBuilder("SERVICE DIAGNOSIS (CONNECTED PHONE)")
                .section("Result")
                .line("Verdict", verdict)
                .line("Health score", "$score / 100")
                .line("Summary", summary())

            val problems = checks.filter { it.status != Status.OK }
            if (problems.isEmpty()) {
                builder.section("Checks").text("Every check passed. No faults found.")
            } else {
                builder.section("Faults and warnings")
                for (check in problems) {
                    builder.text("- [${check.status}] ${check.title}: ${check.detail}")
                    if (check.evidence.isNotBlank()) {
                        builder.text("  Evidence: ${check.evidence}")
                    }
                    if (check.autoFixable) {
                        builder.text("  Fix: ${check.fixTitle}")
                    }
                }
            }

            val passed = checks.filter { it.status == Status.OK }
            if (passed.isNotEmpty()) {
                builder.section("Passed checks").bullets(passed.map { it.title })
            }

            return builder
                .section("Next steps")
                .text(
                    "Run each named fix from the Debugging Service screen, then verify " +
                        "with a fresh diagnosis. Hardware faults (battery, encryption, " +
                        "SELinux) cannot be fixed over ADB and must be explained to the owner."
                )
                .toString()
        }

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "score" to score,
            "verdict" to verdict,
            "summary" to summary(),
            "faultCount" to faultCount,
            "attentionCount" to attentionCount,
            "fixableCount" to fixableCount,
            "checks" to checks.map { it.toMap() },
            "report" to report()
        )
    }

    companion object {

        // ---------- Pure rules (unit tested) ----------

        /** Health score: 100 is perfect. */
        fun scoreFor(checks: List<Check>): Int {
            val penalty = checks.sumOf {
                when (it.status) {
                    Status.CRITICAL -> 20
                    Status.FAULT -> 12
                    Status.WARNING -> 6
                    Status.INFO -> 1
                    Status.OK -> 0
                }
            }
            return (100 - penalty).coerceIn(0, 100)
        }

        fun verdictFor(score: Int): String = when {
            score >= 90 -> "Healthy"
            score >= 70 -> "Needs attention"
            score >= 40 -> "Faults found"
            else -> "Critical"
        }

        fun storageStatus(usedPercent: Int): Status = when {
            usedPercent >= 95 -> Status.FAULT
            usedPercent >= 85 -> Status.WARNING
            else -> Status.OK
        }

        fun memoryStatus(availablePercent: Double): Status = when {
            availablePercent < 10.0 -> Status.FAULT
            availablePercent < 20.0 -> Status.WARNING
            else -> Status.OK
        }

        fun batteryTempStatus(tempC: Double): Status = when {
            tempC >= 50.0 -> Status.CRITICAL
            tempC >= 42.0 -> Status.WARNING
            else -> Status.OK
        }

        fun batteryLevelStatus(levelPercent: Int): Status =
            if (levelPercent <= 10) Status.WARNING else Status.OK

        fun batteryHealthStatus(health: String): Status =
            if (health == "Good" || health == "Unknown") Status.OK else Status.WARNING

        /** True when a global proxy is set (":0" means none). */
        fun isProxySet(proxy: String?): Boolean {
            val value = proxy?.trim().orEmpty()
            return value.isNotEmpty() && value != ":0" && value != "null"
        }

        /** True when Private DNS points at a custom provider. */
        fun isCustomDns(mode: String?, specifier: String?): Boolean {
            val m = mode?.trim().orEmpty()
            val s = specifier?.trim().orEmpty()
            return m == "hostname" || (m.isNotEmpty() && m != "opportunistic" && s.isNotEmpty())
        }

        /** Counts crash records in `dumpsys dropbox` output. */
        fun countCrashes(dropbox: String): Int =
            dropbox.lineSequence().count { it.contains("crash", ignoreCase = true) }

        /** Counts disabled third-party packages in `pm list packages -3d` output. */
        fun countDisabled(output: String): Int =
            output.lineSequence().count { it.trim().startsWith("package:") }

        private fun oneDecimal(value: Double): String =
            String.format(Locale.US, "%.1f", value)
    }

    private fun clean(value: String?): String? {
        return value?.trim()?.takeIf {
            it.isNotEmpty() && it != "null" && !ShellSupport.looksLikeError(it)
        }
    }

    /** Runs every check and returns the graded result. */
    @Throws(IOException::class)
    fun run(): Result {
        val fast = ShellSupport.runSections(
            runner,
            linkedMapOf(
                "df" to "df -k /data 2>/dev/null",
                "mem" to "cat /proc/meminfo",
                "battery" to "dumpsys battery",
                "airplane" to "settings get global airplane_mode_on",
                "wifiOn" to "settings get global wifi_on",
                "data" to "settings get global mobile_data",
                "dnsMode" to "settings get global private_dns_mode",
                "dnsHost" to "settings get global private_dns_specifier",
                "proxy" to "settings get global http_proxy",
                "autoTime" to "settings get global auto_time",
                "display" to "wm size; wm density",
                "animWindow" to "settings get global window_animation_scale",
                "animTransition" to "settings get global transition_animation_scale",
                "animAnimator" to "settings get global animator_duration_scale",
                "stayOn" to "settings get global stay_on_while_plugged_in",
                "lowPower" to "settings get global low_power",
                "screenTimeout" to "settings get system screen_off_timeout",
                "brightnessMode" to "settings get system screen_brightness_mode",
                "fontScale" to "settings get system font_scale",
                "rotate" to "settings get system accelerometer_rotation",
                "selinux" to "getenforce",
                "crypto" to "getprop ro.crypto.state",
                "flashLocked" to "getprop ro.boot.flash.locked",
                "uptime" to "cat /proc/uptime"
            ),
            45000L
        )

        val slow = ShellSupport.runSections(
            runner,
            linkedMapOf(
                "ping" to "ping -c 2 -W 2 8.8.8.8",
                "dropbox" to "dumpsys dropbox | tail -n 80",
                "disabled" to "pm list packages -3d 2>/dev/null | head -n 200"
            ),
            60000L
        )

        val checks = ArrayList<Check>()

        // --- Storage ---
        val dataVolume = TargetDiagnostics.parseDf(fast["df"].orEmpty())
            .firstOrNull { it.mount == "/data" }
        if (dataVolume != null) {
            val used = dataVolume.usedPercent
            val status = storageStatus(used)
            checks.add(
                Check(
                    "storage", "Storage", status,
                    when (status) {
                        Status.FAULT -> "User storage is critically full (${used}%). Apps crash and updates fail."
                        Status.WARNING -> "User storage is nearly full (${used}%). The phone will slow down."
                        else -> "User storage has healthy free space (${used}% used)."
                    },
                    "Free: ${ShellSupport.formatBytes(dataVolume.freeKb * 1024)} of " +
                        ShellSupport.formatBytes(dataVolume.totalKb * 1024),
                    if (status == Status.OK) null else "free-storage"
                )
            )
        } else {
            checks.add(
                Check("storage", "Storage", Status.INFO, "Storage usage could not be read on this phone.")
            )
        }

        // --- Memory ---
        val memory = TargetDiagnostics.parseMemInfo(fast["mem"].orEmpty())
        val totalKb = memory["MemTotal"]
        if (totalKb != null && totalKb > 0) {
            val availableKb = memory["MemAvailable"]
                ?: ((memory["MemFree"] ?: 0) + (memory["Cached"] ?: 0))
            val availablePercent = availableKb * 100.0 / totalKb
            val status = memoryStatus(availablePercent)
            checks.add(
                Check(
                    "memory", "Memory", status,
                    when (status) {
                        Status.FAULT -> "Memory is under heavy pressure. The phone will freeze and reload apps."
                        Status.WARNING -> "Memory usage is high. Slowdowns are likely."
                        else -> "Memory usage is within the normal range."
                    },
                    "Available: ${ShellSupport.formatBytes(availableKb * 1024)} of " +
                        ShellSupport.formatBytes(totalKb * 1024),
                    if (status == Status.OK) null else "kill-background-apps"
                )
            )
        } else {
            checks.add(
                Check("memory", "Memory", Status.INFO, "Memory usage could not be read on this phone.")
            )
        }

        // --- Battery ---
        val battery = TargetDiagnostics.parseKeyValues(fast["battery"].orEmpty())
        val level = battery["level"]?.toIntOrNull()
        val tempC = battery["temperature"]?.toIntOrNull()?.let { it / 10.0 }
        val health = TargetDiagnostics.batteryHealth(battery["health"])

        checks.add(
            Check(
                "battery-health", "Battery health", batteryHealthStatus(health),
                if (health == "Good" || health == "Unknown") {
                    "The phone reports the battery as $health."
                } else {
                    "The phone reports battery health: $health. Inspect or replace the battery."
                },
                "Health: $health"
            )
        )

        if (tempC != null) {
            val status = batteryTempStatus(tempC)
            checks.add(
                Check(
                    "battery-temp", "Battery temperature", status,
                    when (status) {
                        Status.CRITICAL -> "The battery is HOT (${oneDecimal(tempC)} C). Stop charging and let it cool before continuing."
                        Status.WARNING -> "The battery is warm (${oneDecimal(tempC)} C). Monitor it during charging."
                        else -> "Battery temperature is normal (${oneDecimal(tempC)} C)."
                    },
                    "Temperature: ${oneDecimal(tempC)} C"
                )
            )
        }

        if (level != null) {
            val status = batteryLevelStatus(level)
            checks.add(
                Check(
                    "battery-level", "Battery level", status,
                    if (status == Status.WARNING) {
                        "Battery is nearly empty ($level%). Charge it before long repairs."
                    } else {
                        "Battery level is $level%."
                    },
                    "Level: $level%"
                )
            )
        }

        // --- Internet ---
        val pingOutput = slow["ping"].orEmpty()
        val internetOk = pingOutput.contains("bytes from") ||
            Regex("[1-9] (packets )?received").containsMatchIn(pingOutput)
        checks.add(
            Check(
                "internet", "Internet connection",
                if (internetOk) Status.OK else Status.FAULT,
                if (internetOk) {
                    "The phone reached the internet (8.8.8.8)."
                } else {
                    "The phone could not reach the internet. Check Wi-Fi, mobile data, DNS and proxy."
                },
                "Ping 8.8.8.8: ${if (internetOk) "replied" else "no reply"}",
                if (internetOk) null else "fix-no-internet"
            )
        )

        // --- Private DNS ---
        val dnsMode = clean(fast["dnsMode"])
        val dnsHost = clean(fast["dnsHost"])
        val customDns = isCustomDns(dnsMode, dnsHost)
        checks.add(
            Check(
                "private-dns", "Private DNS",
                if (customDns) Status.WARNING else Status.OK,
                if (customDns) {
                    "Private DNS points at a custom provider (${dnsHost ?: dnsMode}). " +
                        "A dead provider causes 'connected but no internet'."
                } else {
                    "Private DNS is on Automatic."
                },
                "Mode: ${dnsMode ?: "opportunistic"}",
                if (customDns) "reset-private-dns" else null
            )
        )

        // --- Proxy ---
        val proxy = clean(fast["proxy"])
        val proxySet = isProxySet(proxy)
        checks.add(
            Check(
                "proxy", "Network proxy",
                if (proxySet) Status.FAULT else Status.OK,
                if (proxySet) {
                    "A global proxy is set ($proxy) and blocks internet access."
                } else {
                    "No global proxy is set."
                },
                "Proxy: ${proxy ?: "none"}",
                if (proxySet) "clear-proxy" else null
            )
        )

        // --- Automatic time ---
        val autoTime = clean(fast["autoTime"])
        val autoTimeOn = autoTime == "1"
        checks.add(
            Check(
                "auto-time", "Date and time",
                if (autoTimeOn) Status.OK else Status.WARNING,
                if (autoTimeOn) {
                    "Automatic date and time is on."
                } else {
                    "Automatic date and time is off. A wrong date breaks secure websites and apps."
                },
                "auto_time: ${autoTime ?: "unknown"}",
                if (autoTimeOn) null else "enable-auto-time"
            )
        )

        // --- Airplane mode ---
        val airplane = clean(fast["airplane"]) == "1"
        checks.add(
            Check(
                "airplane", "Airplane mode",
                if (airplane) Status.FAULT else Status.OK,
                if (airplane) {
                    "Airplane mode is ON. All radios are switched off."
                } else {
                    "Airplane mode is off."
                },
                "airplane_mode_on: ${if (airplane) "1" else "0"}",
                if (airplane) "airplane-mode-cycle" else null
            )
        )

        // --- Wi-Fi ---
        val wifiOn = clean(fast["wifiOn"])
        val wifiOff = wifiOn == "0"
        checks.add(
            Check(
                "wifi-on", "Wi-Fi",
                if (wifiOff) Status.WARNING else Status.OK,
                if (wifiOff) {
                    "Wi-Fi is switched off."
                } else {
                    "Wi-Fi is switched on."
                },
                "wifi_on: ${wifiOn ?: "unknown"}",
                if (wifiOff) "restart-wifi" else null
            )
        )

        // --- Mobile data ---
        val dataOn = clean(fast["data"])
        val dataOff = dataOn == "0"
        checks.add(
            Check(
                "mobile-data", "Mobile data",
                if (dataOff) Status.INFO else Status.OK,
                if (dataOff) {
                    "Mobile data is switched off."
                } else {
                    "Mobile data is switched on."
                },
                "mobile_data: ${dataOn ?: "unknown"}",
                if (dataOff) "restart-mobile-data" else null
            )
        )

        // --- Display override ---
        val display = fast["display"].orEmpty()
        val overridden = display.contains("Override")
        checks.add(
            Check(
                "display-override", "Screen size and density",
                if (overridden) Status.WARNING else Status.OK,
                if (overridden) {
                    "The screen size or density was changed from factory values. " +
                        "The screen may look tiny, huge or cut off."
                } else {
                    "Screen size and density are at factory values."
                },
                display.trim().replace('\n', ' ').take(160),
                if (overridden) "reset-display" else null
            )
        )

        // --- Animations ---
        val anims = listOf(
            clean(fast["animWindow"]), clean(fast["animTransition"]), clean(fast["animAnimator"])
        )
        val animChanged = anims.any { it != null && it != "1" && it != "1.0" }
        checks.add(
            Check(
                "animations", "Animation speed",
                if (animChanged) Status.INFO else Status.OK,
                if (animChanged) {
                    "Animation speed is not normal. The phone may feel slow or jumpy."
                } else {
                    "Animation speed is normal."
                },
                "Scales: ${anims.joinToString(", ") { it ?: "?" }}",
                if (animChanged) "reset-animations" else null
            )
        )

        // --- Stay awake ---
        val stayOn = clean(fast["stayOn"])
        val stayOnSet = stayOn != null && stayOn != "0"
        checks.add(
            Check(
                "stay-awake", "Screen stays on",
                if (stayOnSet) Status.INFO else Status.OK,
                if (stayOnSet) {
                    "The screen is set to stay on while charging. It drains the battery and heats the phone."
                } else {
                    "The screen timeout is normal."
                },
                "stay_on_while_plugged_in: ${stayOn ?: "0"}",
                if (stayOnSet) "stay-awake-off" else null
            )
        )

        // --- Battery saver ---
        val lowPower = clean(fast["lowPower"]) == "1"
        checks.add(
            Check(
                "battery-saver", "Battery Saver",
                if (lowPower) Status.INFO else Status.OK,
                if (lowPower) {
                    "Battery Saver is on. The phone runs slower to save battery."
                } else {
                    "Battery Saver is off."
                },
                "low_power: ${if (lowPower) "1" else "0"}",
                if (lowPower) "disable-battery-saver" else null
            )
        )

        // --- Screen timeout ---
        val timeoutMs = clean(fast["screenTimeout"])?.toLongOrNull()
        val timeoutLong = timeoutMs != null && timeoutMs > 120000L
        checks.add(
            Check(
                "screen-timeout", "Screen timeout",
                if (timeoutLong) Status.INFO else Status.OK,
                if (timeoutLong) {
                    "The screen stays on for more than 2 minutes. It drains the battery."
                } else {
                    "The screen timeout is normal."
                },
                "screen_off_timeout: ${timeoutMs ?: "?"} ms",
                if (timeoutLong) "reset-screen-timeout" else null
            )
        )

        // --- Auto-brightness ---
        val brightnessAuto = clean(fast["brightnessMode"]) == "1"
        checks.add(
            Check(
                "brightness", "Auto-brightness",
                if (brightnessAuto) Status.OK else Status.INFO,
                if (brightnessAuto) {
                    "Auto-brightness is on."
                } else {
                    "Auto-brightness is off. The screen may be too dim or too bright."
                },
                "screen_brightness_mode: ${if (brightnessAuto) "1" else "0"}",
                if (brightnessAuto) null else "enable-auto-brightness"
            )
        )

        // --- Font scale ---
        val fontScale = clean(fast["fontScale"])
        val fontChanged = fontScale != null && fontScale != "1.0" && fontScale != "1"
        checks.add(
            Check(
                "font-scale", "Text size",
                if (fontChanged) Status.INFO else Status.OK,
                if (fontChanged) {
                    "The text size is not normal ($fontScale). Screens may look wrong."
                } else {
                    "The text size is normal."
                },
                "font_scale: ${fontScale ?: "1.0"}",
                if (fontChanged) "reset-font-scale" else null
            )
        )

        // --- Auto-rotate ---
        val rotateOn = clean(fast["rotate"]) == "1"
        checks.add(
            Check(
                "auto-rotate", "Auto-rotate",
                if (rotateOn) Status.OK else Status.INFO,
                if (rotateOn) {
                    "Auto-rotate is on."
                } else {
                    "Auto-rotate is off. The screen will not turn with the phone."
                },
                "accelerometer_rotation: ${if (rotateOn) "1" else "0"}",
                if (rotateOn) null else "enable-auto-rotate"
            )
        )

        // --- Crashes ---
        val crashes = countCrashes(slow["dropbox"].orEmpty())
        checks.add(
            Check(
                "crashes", "Recent crashes",
                if (crashes > 0) Status.WARNING else Status.OK,
                if (crashes > 0) {
                    "The phone recorded $crashes crash record(s). An app may be failing repeatedly."
                } else {
                    "No recent crash records."
                },
                "Crash records: $crashes",
                if (crashes > 0) "clear-logs" else null
            )
        )

        // --- SELinux ---
        val selinux = clean(fast["selinux"])
        val selinuxOk = selinux == "Enforcing"
        checks.add(
            Check(
                "selinux", "SELinux",
                if (selinuxOk) Status.OK else Status.CRITICAL,
                if (selinuxOk) {
                    "SELinux is enforcing. The phone is protected."
                } else {
                    "SELinux is not enforcing (${selinux ?: "unknown"}). " +
                        "This phone may be modified or compromised. Explain this to the owner."
                },
                "getenforce: ${selinux ?: "unknown"}"
            )
        )

        // --- Encryption ---
        val crypto = clean(fast["crypto"])
        val encrypted = crypto == "encrypted"
        checks.add(
            Check(
                "encryption", "Storage encryption",
                if (encrypted) Status.OK else Status.CRITICAL,
                if (encrypted) {
                    "Storage is encrypted."
                } else {
                    "Storage encryption is not active (${crypto ?: "unknown"}). " +
                        "Data on this phone is not protected."
                },
                "ro.crypto.state: ${crypto ?: "unknown"}"
            )
        )

        // --- Bootloader lock ---
        val locked = clean(fast["flashLocked"])
        val unlocked = locked == "0"
        checks.add(
            Check(
                "bootloader-lock", "Bootloader",
                if (unlocked) Status.WARNING else Status.OK,
                if (unlocked) {
                    "The bootloader is unlocked. The phone can run modified software. " +
                        "Tell the owner; do not relock it without their consent."
                } else {
                    "The bootloader is locked."
                },
                "ro.boot.flash.locked: ${locked ?: "unknown"}"
            )
        )

        // --- Uptime ---
        val uptimeSeconds = TargetDiagnostics.parseUptimeSeconds(fast["uptime"].orEmpty())
        if (uptimeSeconds != null) {
            val recent = uptimeSeconds < 3600
            checks.add(
                Check(
                    "uptime", "Time since start",
                    if (recent) Status.INFO else Status.OK,
                    if (recent) {
                        "The phone was started less than an hour ago (${ShellSupport.formatDuration(uptimeSeconds)}). " +
                            "Some faults clear themselves after a restart."
                    } else {
                        "The phone has been running for ${ShellSupport.formatDuration(uptimeSeconds)}."
                    },
                    "Uptime: ${ShellSupport.formatDuration(uptimeSeconds)}"
                )
            )
        }

        // --- Notifications DND (real shell check) ---
        val zenVal = runner.shell("settings get global zen_mode 2>/dev/null", 5000L).output.trim()
        if (zenVal == "1" || zenVal == "2" || zenVal == "3") {
            checks.add(
                Check(
                    "notifications-dnd", "Notifications (DND)", Status.WARNING,
                    "Do Not Disturb is ON (zen_mode=$zenVal). Notifications are silenced.",
                    "zen_mode=$zenVal", "fix-mute-off"
                )
            )
        }
        // --- APN config (real — does no harm, read-only query) ---
        val apnCountRaw = runner.shell(
            "content query --uri content://telephony/carriers --projection name 2>&1 | grep -c Row 2>/dev/null",
            8000L
        ).output.trim()
        val apnCount = apnCountRaw.toIntOrNull()
        if (apnCount != null && apnCount == 0) {
            checks.add(
                Check(
                    "apn-missing", "APN config", Status.WARNING,
                    "No APNs found — mobile data will not work after SIM swap.",
                    "APN rows=0", "fix-apn-reset"
                )
            )
        }
        // --- Battery drain (informational — dumpsys batterystats) ---
        val est = runner.shell(
            "dumpsys batterystats 2>&1 | grep -m1 'Estimated power' 2>/dev/null",
            8000L
        ).output
        if (est.isNotBlank() && est.contains("mAh", true)) {
            checks.add(
                Check(
                    "battery-drain", "Battery drain (top app)", Status.INFO,
                    "Per-app drain data available: ${est.trim().take(120)}",
                    est.trim().take(200), null
                )
            )
        }

        // --- Disabled apps (information only) ---
        val disabled = countDisabled(slow["disabled"].orEmpty())
        if (disabled > 0) {
            checks.add(
                Check(
                    "disabled-apps", "Disabled apps", Status.INFO,
                    "$disabled third-party app(s) are disabled. " +
                        "If the owner misses one, re-enable it from Apps.",
                    "Disabled: $disabled"
                )
            )
        }

        return Result(checks)
    }
}
