package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

class ThermalLog(private val runner: AdbShellRunner) {
    @Throws(IOException::class)
    fun report(): String {
        val th = runner.shell("dumpsys thermalservice 2>&1 | head -n 80; dumpsys batteryproperties 2>&1 | head -n 40", 15000L).output
        val bat = runner.shell("dumpsys battery 2>&1 | grep -iE 'temp|health|status|charging' | head -n 20; cat /sys/class/power_supply/battery/temp 2>&1 | head -n 3", 12000L).output
        val b = ReportBuilder("THERMAL & CHARGING (CONNECTED PHONE)")
        b.section("Thermal service").text(th.ifEmpty { "No thermalservice data" }.take(3000))
        b.section("Battery temp/health").text(bat.trim())
        b.section("Advice").text("If throttling/45°C+: remove case, stop charging, close heavy apps. Check hardware screen for ATTENTION readings.")
        return b.toString()
    }
}
