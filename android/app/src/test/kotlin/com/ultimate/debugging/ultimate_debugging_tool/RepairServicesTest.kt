package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RepairServicesTest {

    private fun phone(sdk: Int? = 33) = ScriptedPhone(sdk).apply {
        reply("pm path 'com.example.app'", "package:/data/app/com.example.app/base.apk\n")
        reply("pm path 'com.android.chrome'", "package:/system/app/Chrome/Chrome.apk\n")
        reply("pm list packages -s 'com.android.chrome'", "package:com.android.chrome\n")
    }

    private fun refused(phone: ScriptedPhone, id: String?, pkg: String?, confirmed: Boolean = true): String {
        try {
            RepairServices(phone).run(id, pkg, confirmed)
            fail("expected a refusal")
        } catch (expected: RepairServices.RepairException) {
            return expected.code
        }
        return ""
    }

    @Test
    fun unknownOperationIsRefused() {
        val phone = phone()
        assertEquals("REPAIR_NOT_ALLOWED", refused(phone, "factory-reset", null))
        assertEquals("REPAIR_NOT_ALLOWED", refused(phone, "rm -rf /", null))
        assertEquals("REPAIR_NOT_ALLOWED", refused(phone, null, null))
        assertTrue(phone.commands.isEmpty())
    }

    @Test
    fun confirmationIsAlwaysRequired() {
        val phone = phone()
        for (service in RepairServices.CATALOG) {
            assertEquals(
                "CONFIRMATION_REQUIRED",
                refused(phone, service.id, "com.example.app", confirmed = false)
            )
        }
        assertTrue(phone.commands.isEmpty())
        assertTrue(phone.services.isEmpty())
    }

    @Test
    fun packageNamesAreValidatedBeforeAnythingRuns() {
        val phone = phone()
        assertEquals("INVALID_PACKAGE", refused(phone, "force-stop-app", "com.a; reboot"))
        assertEquals("INVALID_PACKAGE", refused(phone, "clear-app-data", "\$(reboot)"))
        assertEquals("INVALID_PACKAGE", refused(phone, "uninstall-app", ""))
        assertEquals("INVALID_PACKAGE", refused(phone, "disable-app", null))
        assertTrue(phone.commands.isEmpty())
    }

    @Test
    fun corePackagesAreProtected() {
        val phone = phone()
        assertEquals("PROTECTED_PACKAGE", refused(phone, "disable-app", "com.android.systemui"))
        assertEquals("PROTECTED_PACKAGE", refused(phone, "clear-app-data", "com.google.android.gms"))
        assertTrue(phone.commands.isEmpty())
    }

    @Test
    fun systemAppsCannotBeStoppedErasedOrUninstalled() {
        val phone = phone()
        for (id in listOf("force-stop-app", "clear-app-data", "uninstall-app", "restart-app")) {
            assertEquals("SYSTEM_PACKAGE", refused(phone, id, "com.android.chrome"))
        }
        assertFalse(phone.commands.any { it.startsWith("am ") || it.startsWith("pm clear") || it.startsWith("pm uninstall") })
    }

    @Test
    fun missingAppIsReported() {
        assertEquals("PACKAGE_NOT_FOUND", refused(phone(), "force-stop-app", "com.not.installed"))
    }

    @Test
    fun forceStopRunsAndVerifies() {
        val phone = phone()
        val outcome = RepairServices(phone).run("force-stop-app", " com.example.app ", true)

        assertTrue(phone.commands.contains("am force-stop --user 0 'com.example.app'"))
        assertTrue(outcome.verification.contains("no longer running"))
        assertEquals("LOW", outcome.toMap()["risk"])
        assertEquals("com.example.app", outcome.toMap()["packageName"])
        assertEquals(true, outcome.toMap()["success"])
    }

    @Test
    fun forceStopWorksWithoutExitCodeOnOldAndroid() {
        // Android 6 and older report no exit code; that must not fail.
        val phone = phone(sdk = 22)
        phone.replies["am force-stop --user 0 'com.example.app'"] =
            AdbShellSession.CommandResult("", "", null, false)
        phone.reply("pidof 'com.example.app'", "/system/bin/sh: pidof: not found\n", 127)

        val outcome = RepairServices(phone).run("force-stop-app", "com.example.app", true)
        assertTrue(outcome.verification.contains("accepted"))
    }

    @Test
    fun failedCommandIsReportedWithThePhonesMessage() {
        val phone = phone()
        phone.reply("am force-stop --user 0 'com.example.app'", "Error: not allowed\n", 1)

        try {
            RepairServices(phone).run("force-stop-app", "com.example.app", true)
            fail("expected failure")
        } catch (expected: RepairServices.RepairException) {
            assertEquals("REPAIR_EXECUTION_ERROR", expected.code)
            assertTrue(expected.message!!.contains("not allowed"))
        }
    }

    @Test
    fun clearDataNeedsSuccessFromThePhone() {
        val phone = phone()
        phone.reply("pm clear --user 0 'com.example.app'", "Failed\n")
        assertEquals("REPAIR_EXECUTION_ERROR", refused(phone, "clear-app-data", "com.example.app"))

        phone.reply("pm clear --user 0 'com.example.app'", "Success\n")
        val outcome = RepairServices(phone).run("clear-app-data", "com.example.app", true)
        assertEquals(false, outcome.toMap()["dataPreserved"])
        assertEquals("HIGH", outcome.toMap()["risk"])
    }

    @Test
    fun disableIsVerifiedAgainstTheDisabledList() {
        val phone = phone()
        assertEquals("REPAIR_EXECUTION_ERROR", refused(phone, "disable-app", "com.example.app"))

        phone.reply("pm list packages -d 'com.example.app'", "package:com.example.app\n")
        val outcome = RepairServices(phone).run("disable-app", "com.example.app", true)
        assertTrue(outcome.verification.contains("disabled list"))
    }

    @Test
    fun oldAndroidIsToldWhenAServiceIsUnsupported() {
        assertEquals("REPAIR_UNSUPPORTED", refused(phone(sdk = 25), "reset-private-dns", null))
        assertEquals("REPAIR_UNSUPPORTED", refused(phone(sdk = 28), "airplane-mode-cycle", null))
    }

    @Test
    fun restartWifiTogglesAndChecks() {
        val phone = phone()
        phone.reply("settings get global wifi_on", "1\n")

        val outcome = RepairServices(phone).run("restart-wifi", null, true)

        val off = phone.commands.indexOf("svc wifi disable")
        val on = phone.commands.indexOf("svc wifi enable")
        assertTrue(off >= 0 && on > off)
        assertTrue(outcome.verification.contains("Wi-Fi is on"))
    }

    @Test
    fun trimCachesReportsRecoveredSpace() {
        val phone = phone()
        var calls = 0
        val header = "Filesystem 1K-blocks Used Available Use% Mounted on\n"
        val runner = ScriptedPhone(33) { command ->
            if (command == "df -k /data") {
                calls++
                header + if (calls == 1) "/dev/x 1000000 900000 100000 90% /data\n"
                else "/dev/x 1000000 600000 400000 60% /data\n"
            } else null
        }

        val outcome = RepairServices(runner).run("trim-caches", null, true)

        assertTrue(runner.commands.contains("pm trim-caches 4096G"))
        assertTrue(outcome.verification, outcome.verification.contains("recovered 292.97 MB"))
        assertNotNull(phone)
    }

    @Test
    fun rebootTreatsDroppedLinkAsSuccess() {
        val phone = phone()
        val outcome = RepairServices(phone).run("reboot", null, true)

        assertEquals(listOf("reboot:"), phone.services)
        assertEquals(true, outcome.toMap()["endsSession"])

        val recovery = phone()
        RepairServices(recovery).run("reboot-recovery", null, true)
        assertEquals(listOf("reboot:recovery"), recovery.services)
    }

    @Test
    fun catalogIsConsistent() {
        val ids = RepairServices.CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        for (service in RepairServices.CATALOG) {
            assertTrue(service.title.isNotBlank() && service.description.isNotBlank())
            // Anything that destroys data must be HIGH risk and say so.
            if (!service.dataPreserved) {
                assertEquals(RepairServices.Risk.HIGH, service.risk)
                assertTrue(service.warning.isNotBlank())
            }
            assertEquals(service.id, service.toMap()["id"])
        }
    }

    @Test
    fun everyDeviceServiceIsImplemented() {
        for (service in RepairServices.CATALOG.filter { !it.needsPackage }) {
            val phone = ScriptedPhone(34) { command ->
                when {
                    command.startsWith("df -k") ->
                        "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/x 100 50 50 50% /data\n"
                    command.startsWith("pidof") -> "1234\n"
                    else -> null
                }
            }
            val outcome = RepairServices(phone).run(service.id, null, true)
            assertTrue(service.id, outcome.message.isNotBlank())
        }
    }

    @Test
    fun everyAppServiceIsImplemented() {
        for (service in RepairServices.CATALOG.filter { it.needsPackage }) {
            val phone = ScriptedPhone(34) { command ->
                when {
                    command.startsWith("pm path") -> "package:/data/app/x/base.apk\n"
                    command.startsWith("pm clear") || command.startsWith("pm uninstall") -> "Success\n"
                    command.startsWith("pm list packages -d") ->
                        if (service.id == "disable-app") "package:com.example.app\n" else ""
                    else -> null
                }
            }
            val outcome = RepairServices(phone).run(service.id, "com.example.app", true)
            assertTrue(service.id, outcome.message.isNotBlank())
        }
    }
}
