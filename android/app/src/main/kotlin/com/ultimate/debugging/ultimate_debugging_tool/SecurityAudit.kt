package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Security check-up of the connected phone.
 *
 * Read-only: it inspects settings and state, and changes nothing.
 * It is a technician aid, not a malware scanner.
 */
class SecurityAudit(
    private val runner: AdbShellRunner,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    enum class Level { OK, INFO, WARNING, CRITICAL }

    class Finding(
        val level: Level,
        val title: String,
        val detail: String,
        val advice: String = ""
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "level" to level.name,
            "title" to title,
            "detail" to detail,
            "advice" to advice
        )
    }

    class Result(val findings: List<Finding>) {

        /** 100 is best. Each warning or critical finding lowers it. */
        val score: Int
            get() = (100 - findings.sumOf {
                when (it.level) {
                    Level.CRITICAL -> 25
                    Level.WARNING -> 10
                    else -> 0
                }.toInt()
            }).coerceIn(0, 100)

        val rating: String
            get() = when {
                findings.any { it.level == Level.CRITICAL } -> "AT RISK"
                score >= 90 -> "GOOD"
                score >= 70 -> "FAIR"
                else -> "NEEDS ATTENTION"
            }

        fun toMap(): Map<String, Any> = mapOf(
            "score" to score,
            "rating" to rating,
            "findings" to findings.map { it.toMap() },
            "report" to toReport()
        )

        fun toReport(): String {
            val report = ReportBuilder("SECURITY AUDIT (CONNECTED PHONE)")
                .section("Result")
                .line("Rating", rating)
                .line("Score", "$score / 100")

            for (level in listOf(Level.CRITICAL, Level.WARNING, Level.INFO, Level.OK)) {
                val group = findings.filter { it.level == level }
                if (group.isEmpty()) continue

                report.section(
                    when (level) {
                        Level.CRITICAL -> "Critical"
                        Level.WARNING -> "Warnings"
                        Level.INFO -> "Information"
                        Level.OK -> "Passed checks"
                    }
                )

                for (finding in group) {
                    report.text("- ${finding.title}: ${finding.detail}")
                    if (finding.advice.isNotBlank()) {
                        report.text("  Advice: ${finding.advice}")
                    }
                }
            }

            return report.section("Limitations")
                .text(
                    "This audit reads settings over ADB. It cannot detect every\n" +
                        "threat and is not a substitute for a malware scan."
                )
                .toString()
        }
    }

    companion object {
        private val KNOWN_STORES = setOf(
            "com.android.vending",
            "com.sec.android.app.samsungapps",
            "com.huawei.appmarket",
            "com.xiaomi.mipicks",
            "com.xiaomi.market",
            "com.heytap.market",
            "com.oppo.market",
            "com.bbk.appstore",
            "com.vivo.appstore",
            "com.amazon.venezia",
            "com.transsion.aha",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )

        /** Whole months between a "yyyy-MM-dd" patch date and now. */
        fun patchAgeMonths(patch: String, nowMillis: Long): Int? {
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.isLenient = false

                val time = format.parse(patch.trim())?.time ?: return null
                ((nowMillis - time) / (30.44 * 86400000.0)).toInt()
            } catch (_: Exception) {
                null
            }
        }

        /** Parses `pm list packages -3 -i` into package -> installer. */
        fun parseInstallers(output: String): Map<String, String?> {
            val result = LinkedHashMap<String, String?>()

            for (line in output.lineSequence()) {
                if (!line.startsWith("package:")) continue

                val parts = line.removePrefix("package:").trim().split(Regex("\\s+"))
                val installer = parts.getOrNull(1)
                    ?.removePrefix("installer=")
                    ?.takeIf { it.isNotEmpty() && it != "null" }

                result[parts[0]] = installer
            }

            return result
        }
    }

    @Throws(IOException::class)
    fun run(): Result {
        val data = ShellSupport.runSections(
            runner,
            linkedMapOf(
                "props" to "getprop",
                "enforce" to "getenforce",
                "su" to "for p in /system/bin/su /system/xbin/su /sbin/su /su/bin/su " +
                    "/system/sbin/su /vendor/bin/su /data/adb/magisk /data/adb/ksu; do " +
                    "[ -e \$p ] && echo \$p; done; which su",
                "id" to "id",
                "adbWifi" to "settings get global adb_wifi_enabled",
                "unknown" to "settings get secure install_non_market_apps",
                "verifier" to "settings get global package_verifier_enable",
                "verifierAdb" to "settings get global verifier_verify_adb_installs",
                "access" to "settings get secure enabled_accessibility_services",
                "lock" to "locksettings get-disabled",
                "admins" to "dumpsys device_policy | grep -E 'admin=|ComponentInfo' | head -n 20",
                "installers" to "pm list packages -3 -i",
                "notif" to "settings get secure enabled_notification_listeners"
            ),
            timeoutMs = 45000L
        )

        val props = ShellSupport.parseProperties(data["props"].orEmpty())
        val findings = ArrayList<Finding>()

        fun value(key: String): String? = data[key]?.trim()?.takeIf {
            it.isNotEmpty() && it != "null" && !ShellSupport.looksLikeError(it)
        }

        // Security patch age
        val patch = props["ro.build.version.security_patch"].orEmpty()
        val age = patchAgeMonths(patch, nowMillis())

        findings.add(
            when {
                age == null -> Finding(
                    Level.INFO, "Security patch", "The patch date could not be read."
                )
                age >= 24 -> Finding(
                    Level.CRITICAL, "Security patch",
                    "Last security update is $patch (about $age months old).",
                    "Install system updates. If none exist, the phone is no longer supported."
                )
                age >= 6 -> Finding(
                    Level.WARNING, "Security patch",
                    "Last security update is $patch (about $age months old).",
                    "Check Settings > System update."
                )
                else -> Finding(Level.OK, "Security patch", "Up to date ($patch).")
            }
        )

        // Bootloader and verified boot
        val bootState = props["ro.boot.verifiedbootstate"].orEmpty()
        val locked = props["ro.boot.flash.locked"]
        val deviceState = props["ro.boot.vbmeta.device_state"].orEmpty()

        findings.add(
            when {
                locked == "0" || deviceState == "unlocked" || bootState == "orange" ->
                    Finding(
                        Level.CRITICAL, "Bootloader",
                        "The bootloader is unlocked. System software can be replaced.",
                        "Relock only with original firmware installed; relocking erases data."
                    )
                bootState == "yellow" || bootState == "red" -> Finding(
                    Level.WARNING, "Verified boot",
                    "Verified boot state is '$bootState': the system is not the original.",
                    "Reinstall official firmware if this is unexpected."
                )
                bootState == "green" -> Finding(
                    Level.OK, "Verified boot", "System software is verified (green)."
                )
                else -> Finding(
                    Level.INFO, "Verified boot", "This phone does not report its boot state."
                )
            }
        )

        // Root
        val suPaths = data["su"].orEmpty().lines().map { it.trim() }
            .filter { it.startsWith("/") && !it.contains(' ') && !it.contains(':') }
            .distinct()
        val shellIsRoot = data["id"].orEmpty().contains("uid=0")

        findings.add(
            if (suPaths.isNotEmpty() || shellIsRoot) {
                Finding(
                    Level.WARNING, "Root access",
                    if (shellIsRoot) "ADB runs as root." else "Root tools found: ${suPaths.joinToString()}.",
                    "Root removes Android's app isolation. Banking apps may refuse to run."
                )
            } else {
                Finding(Level.OK, "Root access", "No root tools found.")
            }
        )

        // Build type
        val tags = props["ro.build.tags"].orEmpty()
        val type = props["ro.build.type"].orEmpty()

        if (tags.contains("test-keys") || (type.isNotEmpty() && type != "user")) {
            findings.add(
                Finding(
                    Level.WARNING, "System build",
                    "Not a standard retail build (type '$type', tags '$tags').",
                    "Custom or engineering firmware does not get official security updates."
                )
            )
        }

        // SELinux
        when (value("enforce")?.lowercase()) {
            "enforcing" -> findings.add(Finding(Level.OK, "SELinux", "Enforcing."))
            "permissive", "disabled" -> findings.add(
                Finding(
                    Level.CRITICAL, "SELinux",
                    "SELinux is ${value("enforce")}. A core Android protection is off.",
                    "Reinstall official firmware."
                )
            )
            else -> Unit
        }

        // Encryption
        when (props["ro.crypto.state"]) {
            "encrypted" -> findings.add(
                Finding(Level.OK, "Storage encryption", "User data is encrypted.")
            )
            "unencrypted" -> findings.add(
                Finding(
                    Level.CRITICAL, "Storage encryption",
                    "User data is NOT encrypted. Anyone with the phone can read it.",
                    "Enable encryption in Settings > Security, after a backup."
                )
            )
            else -> Unit
        }

        // Screen lock
        when (value("lock")?.lowercase()) {
            "true" -> findings.add(
                Finding(
                    Level.WARNING, "Screen lock",
                    "No screen lock is set.",
                    "Set a PIN, pattern or password."
                )
            )
            "false" -> findings.add(Finding(Level.OK, "Screen lock", "A screen lock is set."))
            else -> Unit
        }

        // Debugging
        findings.add(
            Finding(
                Level.INFO, "USB debugging",
                "USB debugging is ON (needed for this servicing session).",
                "Turn it off in Developer options when the repair is finished."
            )
        )

        val tcpPort = props["service.adb.tcp.port"].orEmpty()

        if (value("adbWifi") == "1" || (tcpPort.isNotEmpty() && tcpPort != "-1" && tcpPort != "0")) {
            findings.add(
                Finding(
                    Level.WARNING, "Debugging over network",
                    "The phone accepts ADB connections over Wi-Fi.",
                    "Restart the phone or turn off Wireless debugging after servicing."
                )
            )
        }

        // App installs
        if (value("unknown") == "1") {
            findings.add(
                Finding(
                    Level.WARNING, "Unknown sources",
                    "Installing apps from outside the app store is allowed for all apps.",
                    "Turn off 'Unknown sources' in Settings > Security."
                )
            )
        }

        if (value("verifier") == "0") {
            findings.add(
                Finding(
                    Level.WARNING, "Play Protect",
                    "App scanning is switched off.",
                    "Enable Play Protect in the Play Store."
                )
            )
        } else if (value("verifier") == "1") {
            findings.add(Finding(Level.OK, "Play Protect", "App scanning is on."))
        }

        val installers = parseInstallers(data["installers"].orEmpty())
        val sideloaded = installers.filterValues { it == null || it !in KNOWN_STORES }.keys

        if (installers.isNotEmpty()) {
            findings.add(
                if (sideloaded.isEmpty()) {
                    Finding(Level.OK, "App sources", "All ${installers.size} user apps came from an app store.")
                } else {
                    Finding(
                        Level.INFO, "App sources",
                        "${sideloaded.size} of ${installers.size} user apps were not installed from an app store: " +
                            sideloaded.take(15).joinToString() +
                            if (sideloaded.size > 15) " …" else "",
                        "Ask the owner about apps they do not recognise."
                    )
                }
            )
        }

        // Powerful app permissions
        val accessibility = value("access")?.split(':')
            ?.map { it.substringBefore('/') }?.filter { it.isNotBlank() }?.distinct().orEmpty()

        if (accessibility.isNotEmpty()) {
            findings.add(
                Finding(
                    Level.WARNING, "Accessibility services",
                    "These apps can read the screen and act for the user: ${accessibility.joinToString()}.",
                    "Remove any the owner does not recognise. Scam and spy apps abuse this."
                )
            )
        } else {
            findings.add(Finding(Level.OK, "Accessibility services", "None enabled."))
        }

        val admins = Regex("ComponentInfo\\{([^/}]+)/")
            .findAll(data["admins"].orEmpty())
            .map { it.groupValues[1] }.distinct().toList()

        if (admins.isNotEmpty()) {
            findings.add(
                Finding(
                    Level.INFO, "Device administrators",
                    "Apps with administrator rights: ${admins.joinToString()}.",
                    "Unknown administrator apps should be deactivated in Settings > Security."
                )
            )
        }

        val listeners = value("notif")?.split(':')
            ?.map { it.substringBefore('/') }?.filter { it.isNotBlank() }?.distinct().orEmpty()

        if (listeners.isNotEmpty()) {
            findings.add(
                Finding(
                    Level.INFO, "Notification access",
                    "Apps that can read notifications (including codes): ${listeners.joinToString()}."
                )
            )
        }

        return Result(findings)
    }
}
