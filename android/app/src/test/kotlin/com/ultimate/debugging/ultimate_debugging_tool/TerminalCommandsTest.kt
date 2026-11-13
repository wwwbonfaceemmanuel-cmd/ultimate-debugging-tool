package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TerminalCommandsTest {

    private fun rejected(input: String): String {
        try {
            TerminalCommands.resolve(input)
            fail("'$input' should have been refused")
        } catch (expected: TerminalCommands.Rejected) {
            return expected.message!!
        }
        return ""
    }

    private fun rejectedFix(input: String): String {
        try {
            TerminalCommands.resolveFix(input)
            fail("'$input' should have been refused as fix")
        } catch (expected: TerminalCommands.Rejected) {
            return expected.message!!
        }
        return ""
    }

    @Test
    fun knownCommandsResolve() {
        assertEquals("getprop ro.product.model", TerminalCommands.resolve("device-model").shell)
        assertEquals("df -h", TerminalCommands.resolve("  DF   -H ").shell)
        assertEquals("pm list packages", TerminalCommands.resolve("pm list packages").shell)
        assertTrue(TerminalCommands.resolve("uptime").shell.contains("/proc/uptime"))
    }

    @Test
    fun everyNameResolvesToItself() {
        for (entry in TerminalCommands.catalog) {
            if (entry.argument == TerminalCommands.Argument.NONE) {
                assertEquals(entry.shell, TerminalCommands.resolve(entry.name).shell)
            } else {
                assertFalse(entry.shell.isEmpty())
                assertTrue(entry.shell.contains("%s"))
            }
        }
    }

    @Test
    fun namesAreUniqueAndLowercase() {
        val names = TerminalCommands.catalog.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it == it.lowercase() })
    }

    @Test
    fun freeFormShellIsRefused() {
        rejected("rm -rf /sdcard")
        rejected("id; reboot")
        rejected("getprop && reboot")
        rejected("pm list packages -3; rm x")
        rejected("device-model | sh")
        rejected("")
    }

    @Test
    fun packageArgumentIsValidatedAndQuoted() {
        val resolved = TerminalCommands.resolve("app-path com.Example.App")
        assertEquals("pm path 'com.Example.App'", resolved.shell)

        rejected("app-path com.example.app; reboot")
        rejected("app-path \$(reboot)")
        rejected("app-path `id`")
        rejected("app-path notapackage")
        assertTrue(rejected("app-info").contains("Usage"))
    }

    @Test
    fun hostArgumentIsValidated() {
        assertEquals("ping -c 3 -W 3 '192.168.1.1'", TerminalCommands.resolve("ping 192.168.1.1").shell)
        assertEquals("ping -c 3 -W 3 'example.com'", TerminalCommands.resolve("ping example.com").shell)
        rejected("ping 8.8.8.8; reboot")
        rejected("ping -f 8.8.8.8")
        rejected("ping a b")
    }

    @Test
    fun catalogContainsNoCommandsThatChangeThePhone() {
        val forbidden = listOf(
            "rm ", "reboot", "pm uninstall", "pm clear", "pm disable", "settings put",
            "settings delete", "svc ", "am force-stop", "am start", "wipe", "format",
            "dd ", "mv ", "chmod", "setprop", "input tap", "input text", "input keyevent", "logcat -c", "pm install", " > "
        )
        for (entry in TerminalCommands.catalog) {
            for (word in forbidden) {
                assertFalse("${entry.name} contains '$word'", entry.shell.contains(word))
            }
        }
    }

    @Test
    fun rootWrapperQuotesTheWholeCommand() {
        assertEquals("su -c 'id'", TerminalCommands.asRoot("id"))
        assertEquals("su -c 'pm path '\\''a.b'\\'''", TerminalCommands.asRoot("pm path 'a.b'"))
    }

    @Test
    fun helpListsEveryCommand() {
        val help = TerminalCommands.helpText(rootMode = false)
        assertTrue(help.contains("ADB MODE"))
        assertTrue(TerminalCommands.catalog.all { help.contains(it.name) })
        assertTrue(TerminalCommands.fixCatalog.all { help.contains(it.name) })
        assertTrue(help.contains("FIX COMMANDS"))
        assertTrue(TerminalCommands.helpText(rootMode = true).contains("ROOT MODE"))
        assertEquals(TerminalCommands.catalog.size, TerminalCommands.describe().size)
        assertEquals(TerminalCommands.fixCatalog.size, TerminalCommands.describeFixes().size)
    }

    @Test
    fun shellQuoteNeutralisesQuotes() {
        assertEquals("'a'\\''b'", ShellSupport.quote("a'b"))
        assertTrue(ShellSupport.isPackageName("com.whatsapp"))
        assertFalse(ShellSupport.isPackageName("com.whatsapp;id"))
        assertFalse(ShellSupport.isPackageName("whatsapp"))
    }

    // ---- Fix catalog (new) ----

    @Test
    fun fixCatalogIsSeparateFromRead() {
        val readNames = TerminalCommands.catalog.map { it.name }.toSet()
        val fixNames = TerminalCommands.fixCatalog.map { it.name }.toSet()
        // No overlap except intentional reboot family which is fix-only now
        // Read catalog must not contain fix names, and resolve() must reject fix names
        for (name in fixNames) {
            if (name !in readNames) {
                try {
                    TerminalCommands.resolve(name)
                    fail("read catalog should not resolve fix command '$name'")
                } catch (_: TerminalCommands.Rejected) {}
            }
        }
    }

    @Test
    fun fixNamesAreUniqueAndLowercase() {
        val names = TerminalCommands.fixCatalog.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it == it.lowercase() })
        assertTrue(names.all { it.startsWith("fix-") || it.startsWith("reboot") })
    }

    @Test
    fun everyFixResolvesToItself() {
        for (entry in TerminalCommands.fixCatalog) {
            if (entry.argument == TerminalCommands.Argument.NONE) {
                assertEquals(entry.shell, TerminalCommands.resolveFix(entry.name).shell)
                assertTrue(entry.isFix)
            } else {
                assertTrue(entry.shell.contains("%s"))
                assertTrue(entry.isFix)
            }
        }
    }

    @Test
    fun fixCatalogCountsMatchDocs() {
        assertEquals(77, TerminalCommands.catalog.size)
        assertEquals(79, TerminalCommands.fixCatalog.size)
        assertEquals(156, TerminalCommands.catalog.size + TerminalCommands.fixCatalog.size)
    }

    @Test
    fun fixRiskLevelsAreValid() {
        val valid = setOf("LOW", "MEDIUM", "HIGH")
        for (entry in TerminalCommands.fixCatalog) {
            assertTrue("${entry.name} has valid risk", entry.risk in valid)
        }
        // HIGH is only for data-loss commands
        val high = TerminalCommands.fixCatalog.filter { it.risk == "HIGH" }.map { it.name }
        assertTrue(high.all { it.contains("uninstall") || it.contains("data") })
        assertTrue(high.isNotEmpty())
    }

    @Test
    fun fixPackageArgsAreValidated() {
        val resolved = TerminalCommands.resolveFix("fix-app-cache com.example.app")
        assertEquals("pm clear --cache-only --user 0 'com.example.app'", resolved.shell)
        rejectedFix("fix-app-cache com.example.app; reboot")
        rejectedFix("fix-app-cache notapackage")
        assertTrue(rejectedFix("fix-app-stop").contains("Usage"))
        // reboot family has no args
        rejectedFix("reboot extra")
    }

    @Test
    fun fixFreeFormIsRefused() {
        rejectedFix("fix-wifi; reboot")
        rejectedFix("fix-app-data com.example.app && rm -rf /")
        rejectedFix("")
        rejectedFix("rm -rf /sdcard")
    }

    @Test
    fun describeFixesMirrorsCatalog() {
        val described = TerminalCommands.describeFixes()
        assertEquals(TerminalCommands.fixCatalog.size, described.size)
        for (m in described) {
            assertTrue(m.containsKey("isFix"))
            assertTrue(m.containsKey("risk"))
            assertEquals(true, m["isFix"])
        }
    }
}
