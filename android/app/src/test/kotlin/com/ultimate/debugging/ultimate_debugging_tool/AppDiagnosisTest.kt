package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppDiagnosisTest {

    private val facts = """
            versionCode=2241 minSdk=23 targetSdk=34
            versionName=2.24.1
            firstInstallTime=2024-02-01 10:00:00
            lastUpdateTime=2025-06-11 08:30:00
    """.trimIndent()

    private val services = """
        Services:
          * ServiceRecord{9f1c2b u0 com.example.app/.Sync} intent={cmp=com.example.app/.Sync}
          * ServiceRecord{a71b3c u0 com.example.app/com.example.app.Worker} intent={cmp=com.example.app/.Worker}
    """.trimIndent()

    private val crashLog = """
        --------- beginning of crash
        06-11 08:31:02.101  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
        06-11 08:31:02.102  4321  4321 E AndroidRuntime: Process: com.example.app, PID: 4321
        06-11 08:31:02.103  4321  4321 E AndroidRuntime: java.lang.NullPointerException
        06-11 08:32:00.000  1000  1000 E AndroidRuntime: Process: com.other.app, PID: 1000
    """.trimIndent()

    private val eventLog = """
        06-11 08:40:00.000  1000  1000 I am_anr  : [0,4321,com.example.app,952584,Input dispatching timed out]
        06-11 08:41:00.000  1000  1000 I am_anr  : [0,9999,com.other.app,952585,Input dispatching timed out]
    """.trimIndent()

    // ---------- Parsers ----------

    @Test
    fun readsTheVersionFacts() {
        val parsed = AppDiagnosis.parseFacts(facts)

        assertEquals("2.24.1", parsed["versionName"])
        assertEquals("2241", parsed["versionCode"])
        assertEquals("34", parsed["targetSdk"])
        assertEquals("2025-06-11 08:30:00", parsed["lastUpdateTime"])
    }

    @Test
    fun namesTheStandbyBucket() {
        assertEquals("Working set", AppDiagnosis.describeBucket("20"))
        assertEquals("Rare (may be killed when idle)", AppDiagnosis.describeBucket("40"))
        assertEquals("Not reported", AppDiagnosis.describeBucket(""))
        assertEquals("20", AppDiagnosis.parseStandbyBucket("20\nRUN_IN_BACKGROUND: deny\n"))
    }

    @Test
    fun readsTheBackgroundRestriction() {
        assertEquals(
            "Blocked from running in the background",
            AppDiagnosis.parseBackgroundOp("RUN_IN_BACKGROUND: deny; time=1234")
        )
        assertEquals(
            "Allowed to run in the background",
            AppDiagnosis.parseBackgroundOp("RUN_IN_BACKGROUND: allow")
        )
        assertEquals("Not reported", AppDiagnosis.parseBackgroundOp(""))
    }

    @Test
    fun findsTheServicesAnAppKeeps() {
        val names = AppDiagnosis.parseServiceNames(services, "com.example.app")

        assertEquals(
            listOf("com.example.app/.Sync", "com.example.app/com.example.app.Worker"),
            names
        )
    }

    @Test
    fun keepsTheWholeCrashEntryOfTheApp() {
        val lines = AppDiagnosis.crashLines(crashLog, "com.example.app")

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.any { it.contains("FATAL EXCEPTION") })
        assertTrue(lines.any { it.contains("NullPointerException") })
        assertFalse(lines.any { it.contains("com.other.app") })
    }

    @Test
    fun keepsOnlyLinesThatMentionTheApp() {
        val lines = AppDiagnosis.matchingLines(eventLog, "com.example.app")

        assertTrue(lines.isNotEmpty())
        assertFalse(lines.any { it.contains("com.other.app") })
    }

    @Test
    fun keepsTheNewestLines() {
        val many = (1..40).joinToString("\n") { "line $it com.example.app" }
        val lines = AppDiagnosis.matchingLines(many, "com.example.app", limit = 5)

        assertEquals(5, lines.size)
        assertTrue(lines.last().contains("line 40"))
    }

    // ---------- Whole diagnosis ----------

    private fun phone() = ScriptedPhone { command ->
        when {
            command.startsWith("dumpsys package") -> facts
            command.startsWith("am get-standby-bucket") -> "20\nRUN_IN_BACKGROUND: deny; time=1"
            command.startsWith("pm list packages -s") -> ""
            command.startsWith("pm list packages -d") -> ""
            command.startsWith("pm list packages -e") -> "package:com.example.app\n"
            command.startsWith("pidof") -> "4321\n"
            command.startsWith("dumpsys activity services") -> services
            command.startsWith("logcat -b crash") -> crashLog
            command.startsWith("logcat -b events") -> eventLog
            command.startsWith("dumpsys alarm") -> "3\n"
            else -> null
        }
    }

    @Test
    fun explainsAUserInstalledAppThatCrashed() {
        val report = AppDiagnosis(phone()).inspect("com.example.app")
        val map = report.toMap()

        assertEquals("2.24.1", map["versionName"])
        assertEquals(false, map["system"])
        assertEquals(true, map["enabled"])
        assertEquals(true, map["running"])
        assertEquals("Working set", map["standbyBucket"])
        assertEquals(3, map["alarmCount"])
        assertEquals(2, (map["services"] as List<*>).size)
        assertEquals(1, (map["anrs"] as List<*>).size)
        assertTrue(report.verdict.contains("crashed"))
        assertTrue(report.report().contains("APP DIAGNOSIS - com.example.app"))
    }

    @Test
    fun aQuietAppGetsACleanVerdict() {
        val quiet = ScriptedPhone { command ->
            when {
                command.startsWith("pm list packages -e") -> "package:com.example.app\n"
                command.startsWith("am get-standby-bucket") -> "10\nRUN_IN_BACKGROUND: allow"
                else -> null
            }
        }

        val report = AppDiagnosis(quiet).inspect("com.example.app")

        assertEquals(
            "No recent crash or stall was recorded for this app.",
            report.verdict
        )
        assertEquals("Active", report.standbyBucket)
        assertTrue(report.crashes.isEmpty())
    }

    @Test
    fun aDisabledAppSaysSo() {
        val disabled = ScriptedPhone { command ->
            if (command.startsWith("pm list packages -d")) {
                "package:com.example.app\n"
            } else {
                null
            }
        }

        val report = AppDiagnosis(disabled).inspect("com.example.app")

        assertFalse(report.enabled)
        assertTrue(report.verdict.contains("disabled"))
    }

    @Test
    fun aBadPackageNameIsRefusedBeforeAnyCommand() {
        val phone = ScriptedPhone { null }

        try {
            AppDiagnosis(phone).inspect("com.example.app; reboot")
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("package name"))
        }

        assertTrue(phone.commands.isEmpty())
    }

    @Test
    fun theDiagnosisOnlyReads() {
        val phone = phone()
        AppDiagnosis(phone).inspect("com.example.app")

        val forbidden = Regex(
            "\\b(rm|reboot|pm (clear|uninstall|disable|enable)|" +
                "am force-stop|cmd appops set|dd )\\b"
        )

        for (command in phone.commands) {
            assertFalse(
                "The diagnosis must not change the phone: $command",
                forbidden.containsMatchIn(command)
            )
        }
    }

    // ---------- The new app repairs ----------

    private fun repairPhone() = ScriptedPhone(34) { command ->
        when {
            command.startsWith("pm path") -> "package:/data/app/x/base.apk\n"
            command.startsWith("pm clear") || command.startsWith("pm uninstall") -> "Success\n"
            else -> null
        }
    }

    @Test
    fun theNewAppRepairsAreInTheCatalogAndKeepData() {
        val ids = listOf(
            "clear-app-cache",
            "uninstall-app-updates",
            "recompile-app",
            "reset-app-settings"
        )

        for (id in ids) {
            val service = RepairServices.CATALOG.first { it.id == id }
            assertTrue("$id must keep data", service.dataPreserved)
            assertTrue("$id must ask for an app", service.needsPackage)
        }
    }

    @Test
    fun clearingTheCacheOnlyUsesTheCacheSwitch() {
        val phone = repairPhone()
        RepairServices(phone).run("clear-app-cache", "com.example.app", true)

        val command = phone.commands.first { it.startsWith("pm clear") }
        assertTrue(command.contains("--cache-only"))
        assertTrue(command.contains("--user 0"))
    }

    @Test
    fun removingUpdatesUsesTheSystemUpdatesCommand() {
        val phone = repairPhone()
        val outcome = RepairServices(phone).run(
            "uninstall-app-updates",
            "com.example.app",
            true
        )

        assertTrue(
            phone.commands.any { it.startsWith("pm uninstall-system-updates") }
        )
        assertTrue(outcome.message.contains("updates were removed"))
    }

    @Test
    fun recompilingAndResettingSettingsReportWhatThePhoneSaid() {
        val phone = repairPhone()

        val compiled = RepairServices(phone).run("recompile-app", "com.example.app", true)
        val reset = RepairServices(phone).run("reset-app-settings", "com.example.app", true)

        assertTrue(phone.commands.any { it.startsWith("cmd package compile -m speed -f") })
        assertTrue(phone.commands.any { it.startsWith("cmd appops reset") })
        assertTrue(compiled.verification.isNotBlank())
        assertTrue(reset.verification.contains("data was not touched"))
    }
}
