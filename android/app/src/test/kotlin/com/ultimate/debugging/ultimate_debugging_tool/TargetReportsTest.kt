package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class TargetReportsTest {

    private fun utc(date: String): Long {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.parse(date)!!.time
    }

    // ---------- ShellSupport ----------

    @Test
    fun sectionsAreSplitByMarker() {
        val phone = ScriptedPhone { command ->
            when (command) {
                "getprop ro.product.model" -> "Pixel 7\n"
                "missing-tool" -> "/system/bin/sh: missing-tool: not found\n"
                else -> null
            }
        }

        val result = ShellSupport.runSections(
            phone,
            linkedMapOf(
                "model" to "getprop ro.product.model",
                "bad" to "missing-tool",
                "empty" to "true"
            )
        )

        assertEquals("Pixel 7", result["model"])
        assertTrue(result["bad"]!!.contains("not found"))
        assertEquals("", result["empty"])
        assertEquals(listOf("model", "bad", "empty"), result.keys.toList())
    }

    @Test
    fun propertiesAreParsed() {
        val props = ShellSupport.parseProperties(
            "[ro.product.model]: [SM-A125F]\n" +
                "[ro.build.version.release]: [12]\n" +
                "[empty.value]: []\n" +
                "garbage line\n"
        )

        assertEquals("SM-A125F", props["ro.product.model"])
        assertEquals("12", props["ro.build.version.release"])
        assertEquals("", props["empty.value"])
        assertEquals(3, props.size)
    }

    @Test
    fun errorsAreRecognised() {
        assertTrue(ShellSupport.looksLikeError(""))
        assertTrue(ShellSupport.looksLikeError("cat: /sys/x: Permission denied"))
        assertTrue(ShellSupport.looksLikeError("/system/bin/sh: foo: not found"))
        assertFalse(ShellSupport.looksLikeError("4200"))
    }

    @Test
    fun sizesAndDurationsAreReadable() {
        assertEquals("512 B", ShellSupport.formatBytes(512))
        assertEquals("1.50 KB", ShellSupport.formatBytes(1536))
        assertEquals("2.00 GB", ShellSupport.formatBytes(2L * 1024 * 1024 * 1024))
        assertEquals("Unavailable", ShellSupport.formatBytes(-1))
        assertEquals("5 min", ShellSupport.formatDuration(300))
        assertEquals("2 h 5 min", ShellSupport.formatDuration(7500))
        assertEquals("3 d 0 h 1 min", ShellSupport.formatDuration(3 * 86400 + 60))
    }

    // ---------- Diagnostics parsers ----------

    @Test
    fun batteryDumpIsParsed() {
        val values = TargetDiagnostics.parseKeyValues(
            "Current Battery Service state:\n" +
                "  AC powered: false\n" +
                "  USB powered: true\n" +
                "  status: 2\n" +
                "  health: 2\n" +
                "  level: 85\n" +
                "  scale: 100\n" +
                "  voltage: 4123\n" +
                "  temperature: 305\n" +
                "  technology: Li-ion\n"
        )

        assertEquals("85", values["level"])
        assertEquals("true", values["USB powered"])
        assertEquals("Li-ion", values["technology"])
        assertEquals("Charging", TargetDiagnostics.batteryStatus(values["status"]))
        assertEquals("Good", TargetDiagnostics.batteryHealth(values["health"]))
        assertEquals("Unknown", TargetDiagnostics.batteryHealth(null))
        assertEquals("Overheated", TargetDiagnostics.batteryHealth("3"))
    }

    @Test
    fun memInfoAndDfAreParsed() {
        val memory = TargetDiagnostics.parseMemInfo(
            "MemTotal:        3815932 kB\nMemFree:          123456 kB\nMemAvailable:    1500000 kB\n"
        )
        assertEquals(3815932L, memory["MemTotal"])
        assertEquals(1500000L, memory["MemAvailable"])

        val volumes = TargetDiagnostics.parseDf(
            "Filesystem     1K-blocks     Used Available Use% Mounted on\n" +
                "/dev/block/dm-8 52000000 39000000  13000000  75% /data\n" +
                "tmpfs            1900000     1000   1899000   1% /dev\n" +
                "broken line\n"
        )
        assertEquals(2, volumes.size)
        assertEquals("/data", volumes[0].mount)
        assertEquals(75, volumes[0].usedPercent)
        assertEquals(13000000L, volumes[0].freeKb)
    }

    @Test
    fun uptimeWifiAndThermalAreParsed() {
        assertEquals(12345L, TargetDiagnostics.parseUptimeSeconds("12345.67 9999.00\n"))
        assertNull(TargetDiagnostics.parseUptimeSeconds("sh: not found"))

        val wifi = TargetDiagnostics.parseWifiInfo(
            "junk\nmWifiInfo SSID: \"HomeNet\", BSSID: 00:11:22:33:44:55, MAC: 02:00:00:00:00:00, " +
                "Supplicant state: COMPLETED, RSSI: -61, Link speed: 144Mbps, Frequency: 5180MHz\n"
        )
        assertEquals("HomeNet", wifi["ssid"])
        assertEquals("-61", wifi["rssi"])
        assertEquals("144", wifi["linkSpeed"])
        assertEquals("5180", wifi["frequency"])
        assertEquals("COMPLETED", wifi["state"])
        assertTrue(TargetDiagnostics.parseWifiInfo("nothing here").isEmpty())

        assertEquals("Good", TargetDiagnostics.signalQuality(-61))
        assertEquals("Excellent", TargetDiagnostics.signalQuality(-40))
        assertEquals("Very weak", TargetDiagnostics.signalQuality(-90))

        val thermal = TargetDiagnostics.parseThermalService(
            "Cached temperatures:\n" +
                "\tTemperature{mValue=99.0, mType=0, mName=OLD, mStatus=0}\n" +
                "Current temperatures from HAL:\n" +
                "\tTemperature{mValue=38.5, mType=3, mName=battery, mStatus=0}\n" +
                "\tTemperature{mValue=45.1, mType=0, mName=CPU0, mStatus=0}\n" +
                "Current cooling devices from HAL:\n" +
                "\tCoolingDevice{mValue=0, mType=2, mName=thermal-cpufreq-0}\n"
        )
        assertEquals(listOf("battery" to 38.5, "CPU0" to 45.1), thermal)
    }

    @Test
    fun batteryReportUsesThePhonesAnswers() {
        val phone = ScriptedPhone { command ->
            when {
                command == "dumpsys battery" ->
                    "  USB powered: true\n  status: 2\n  health: 2\n  level: 42\n  scale: 100\n" +
                        "  voltage: 3987\n  temperature: 312\n  technology: Li-ion\n"
                command.endsWith("/cycle_count") -> "311\n"
                command.startsWith("cat ") -> "cat: No such file or directory\n"
                else -> null
            }
        }

        val report = TargetDiagnostics(phone).run("battery")

        assertTrue(report, report.contains("42%"))
        assertTrue(report, report.contains("Good"))
        assertTrue(report, report.contains("Charging"))
        assertTrue(report, report.contains("USB"))
        assertTrue(report, report.contains("31.2"))
        assertTrue(report, report.contains("311"))
        assertFalse(report, report.contains("No such file"))
    }

    @Test
    fun batteryReportFailsClearlyWhenThePhoneSaysNothing() {
        try {
            TargetDiagnostics(ScriptedPhone()).run("battery")
            fail("expected an error")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("battery"))
        }
    }

    @Test
    fun everyListedReportExists() {
        // A phone that answers nothing must never crash a report: each one
        // either returns text or fails with a plain message.
        for ((name, _) in TargetDiagnostics.REPORTS) {
            try {
                val text = TargetDiagnostics(ScriptedPhone()).run(name)
                assertTrue(name, text.isNotBlank())
            } catch (expected: IOException) {
                assertFalse(name, expected.message!!.contains("Unknown report"))
            }
        }

        try {
            TargetDiagnostics(ScriptedPhone()).run("no-such-report")
            fail("expected an error")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("Unknown report"))
        }

        assertTrue(TargetDiagnostics(ScriptedPhone()).run("help").contains("battery"))
    }

    // ---------- Security audit ----------

    @Test
    fun patchAgeIsCountedInMonths() {
        val now = utc("2026-11-12")

        assertEquals(0, SecurityAudit.patchAgeMonths("2026-11-01", now))
        assertEquals(12, SecurityAudit.patchAgeMonths("2025-11-05", now))
        assertEquals(36, SecurityAudit.patchAgeMonths("2023-11-01", now))
        assertNull(SecurityAudit.patchAgeMonths("", now))
        assertNull(SecurityAudit.patchAgeMonths("not a date", now))
    }

    @Test
    fun installersAreParsed() {
        val installers = SecurityAudit.parseInstallers(
            "package:com.whatsapp  installer=com.android.vending\n" +
                "package:com.side.loaded  installer=null\n" +
                "package:com.other installer=com.random.store\n" +
                "noise\n"
        )

        assertEquals("com.android.vending", installers["com.whatsapp"])
        assertNull(installers["com.side.loaded"])
        assertTrue(installers.containsKey("com.side.loaded"))
        assertEquals("com.random.store", installers["com.other"])
        assertEquals(3, installers.size)
    }

    private fun auditPhone(
        patch: String,
        bootState: String = "green",
        locked: String = "1",
        enforce: String = "Enforcing",
        su: String = "",
        crypto: String = "encrypted",
        lockDisabled: String = "false",
        unknownSources: String = "0"
    ) = ScriptedPhone { command ->
        when {
            command == "getprop" ->
                "[ro.build.version.security_patch]: [$patch]\n" +
                    "[ro.boot.verifiedbootstate]: [$bootState]\n" +
                    "[ro.boot.flash.locked]: [$locked]\n" +
                    "[ro.build.tags]: [release-keys]\n" +
                    "[ro.build.type]: [user]\n" +
                    "[ro.crypto.state]: [$crypto]\n"
            command == "getenforce" -> "$enforce\n"
            command.startsWith("for p in") -> su
            command == "id" -> "uid=2000(shell) gid=2000(shell)\n"
            command == "locksettings get-disabled" -> "$lockDisabled\n"
            command == "settings get secure install_non_market_apps" -> "$unknownSources\n"
            command == "settings get global package_verifier_enable" -> "1\n"
            command == "pm list packages -3 -i" ->
                "package:com.whatsapp  installer=com.android.vending\n"
            command.startsWith("settings get") -> "null\n"
            else -> null
        }
    }

    private fun levelOf(result: SecurityAudit.Result, title: String): SecurityAudit.Level? =
        result.findings.firstOrNull { it.title == title }?.level

    @Test
    fun healthyPhoneScoresHigh() {
        val result = SecurityAudit(auditPhone("2026-10-01")) { utc("2026-11-12") }.run()

        assertEquals(result.toReport(), 100, result.score)
        assertEquals("GOOD", result.rating)
        assertEquals(SecurityAudit.Level.OK, levelOf(result, "Security patch"))
        assertEquals(SecurityAudit.Level.OK, levelOf(result, "Root access"))
        assertEquals(SecurityAudit.Level.OK, levelOf(result, "SELinux"))
        assertEquals(SecurityAudit.Level.OK, levelOf(result, "Storage encryption"))
        assertEquals(SecurityAudit.Level.OK, levelOf(result, "Screen lock"))
        assertTrue(result.toReport().contains("100"))
        assertEquals(100, result.toMap()["score"])
    }

    @Test
    fun riskyPhoneIsFlagged() {
        val phone = auditPhone(
            patch = "2022-01-01",
            bootState = "orange",
            locked = "0",
            enforce = "Permissive",
            su = "/system/xbin/su\n",
            crypto = "unencrypted",
            lockDisabled = "true",
            unknownSources = "1"
        )

        val result = SecurityAudit(phone) { utc("2026-11-12") }.run()

        assertEquals("AT RISK", result.rating)
        assertEquals(0, result.score)
        assertEquals(SecurityAudit.Level.CRITICAL, levelOf(result, "Security patch"))
        assertEquals(SecurityAudit.Level.CRITICAL, levelOf(result, "Bootloader"))
        assertEquals(SecurityAudit.Level.CRITICAL, levelOf(result, "SELinux"))
        assertEquals(SecurityAudit.Level.CRITICAL, levelOf(result, "Storage encryption"))
        assertEquals(SecurityAudit.Level.WARNING, levelOf(result, "Root access"))
        assertEquals(SecurityAudit.Level.WARNING, levelOf(result, "Screen lock"))
        assertEquals(SecurityAudit.Level.WARNING, levelOf(result, "Unknown sources"))
        assertTrue(result.findings.first { it.title == "Root access" }.detail.contains("/system/xbin/su"))
    }

    @Test
    fun auditOnlyReads() {
        val phone = auditPhone("2026-10-01")
        SecurityAudit(phone) { utc("2026-11-12") }.run()

        val changing = listOf("settings put", "settings delete", "pm ", "am ", "rm ", "svc ", "reboot", "setprop")
        for (command in phone.commands) {
            for (word in changing) {
                if (command.startsWith("pm list")) continue
                assertFalse("audit ran: $command", command.contains(word))
            }
        }
        assertTrue(phone.services.isEmpty())
    }

    @Test
    fun silentPhoneGivesNoFalseAlarms() {
        // Nothing readable must not be reported as critical.
        val result = SecurityAudit(ScriptedPhone()) { utc("2026-11-12") }.run()

        assertFalse(result.toReport(), result.findings.any { it.level == SecurityAudit.Level.CRITICAL })
        assertEquals(SecurityAudit.Level.INFO, levelOf(result, "Security patch"))
    }

    @Test
    fun scoreNeverGoesBelowZero() {
        val findings = List(10) {
            SecurityAudit.Finding(SecurityAudit.Level.CRITICAL, "x$it", "d")
        }
        assertEquals(0, SecurityAudit.Result(findings).score)

        val warnings = List(2) {
            SecurityAudit.Finding(SecurityAudit.Level.WARNING, "w$it", "d")
        }
        assertEquals(80, SecurityAudit.Result(warnings).score)
        assertEquals("FAIR", SecurityAudit.Result(warnings).rating)

        val many = List(4) {
            SecurityAudit.Finding(SecurityAudit.Level.WARNING, "w$it", "d")
        }
        assertEquals("NEEDS ATTENTION", SecurityAudit.Result(many).rating)
    }
}
