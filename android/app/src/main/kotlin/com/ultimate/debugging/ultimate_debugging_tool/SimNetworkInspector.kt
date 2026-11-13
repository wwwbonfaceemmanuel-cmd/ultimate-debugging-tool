package com.ultimate.debugging.ultimate_debugging_tool

import java.io.File
import java.io.IOException

/**
 * Lawful Network & SIM inspector — never bypasses carrier SIM ME lock.
 * Reads lock state, explains carrier, and builds official unlock packet.
 * All bypass attempts are refused and logged.
 */
class SimNetworkInspector(private val runner: AdbShellRunner, private val filesDir: File) {

    companion object {
        val OFFICIAL_CARRIER_PORTALS = linkedMapOf(
            "T-Mobile US" to "https://www.t-mobile.com/support/devices/sim-unlock",
            "AT&T" to "https://www.att.com/deviceunlock/",
            "Orange" to "https://www.orange.com/en/support",
            "Vodafone" to "https://www.vodafone.com/support",
            "MTN" to "https://www.mtn.com/support",
            "Airtel" to "https://www.airtel.in/support",
            "Generic CTIA/EU info" to "https://www.ctia.org/consumer-resources/unlocking"
        )
        val REFUSED_BYPASS = listOf(
            "patch sec_efs / nv_data.bin",
            "AT+EGMR AT+CLCK brute-force",
            "flash carrier partition",
            "imei patch",
            "paid unlock code generator"
        )
        fun parseSimState(output: String): String {
            val line = output.lineSequence().firstOrNull { it.contains("gsm.sim.state", true) } ?: return "unknown"
            return when {
                line.contains("ABSENT", true) -> "No SIM"
                line.contains("READY", true) -> "Ready"
                line.contains("NETWORK_LOCKED", true) -> "NETWORK_LOCKED — carrier SIM ME lock active"
                line.contains("PIN_REQUIRED", true) -> "PIN required"
                line.contains("PUK_REQUIRED", true) -> "PUK required"
                else -> line.trim().take(120)
            }
        }
    }

    @Throws(IOException::class)
    fun simLockReport(): String {
        val props = runner.shell("getprop | grep -iE 'gsm\\.(sim|operator|baseband|version)' 2>&1 | head -n 40; getprop ro.product.model 2>&1; getprop ro.build.version.incremental 2>&1", 12000L).output
        val tele = runner.shell("dumpsys telephony.registry 2>&1 | grep -iE 'mServiceState|mOperatorAlpha|mcc|mnc' | head -n 20; service call iphonesubinfo 1 2>&1 | head -n 5; cmd phone get-imei 2>&1 | head -n 5", 12000L).output
        val simState = parseSimState(props)
        val b = ReportBuilder("SIM ME / NETWORK LOCK — STATUS (CONNECTED PHONE)")
        b.section("SIM state").text(simState)
        b.section("Props (gsm.*)").text(props.take(3000))
        b.section("Telephony").text(tele.take(2500))
        b.section("What this means").text(
            if (simState.contains("NETWORK_LOCKED")) "This phone is carrier Network-locked (SIM ME). It asks for an unlock code after foreign SIM. Only the carrier can provide the lawful code — do not attempt bypass."
            else if (simState == "No SIM") "Insert the customer SIM and re-run."
            else "If it shows 'Ready' but no data, check APN/MCC mismatch — run fix-apn-reset or fix-carrier-config-reset."
        )
        b.section("Lawful next step").let { s ->
            s.text("1) Record ownership attestation (Reset & Wipe screen) — carrier requires it.")
            s.text("2) Use 'Generate unlock packet' to build IMEI/serial packet for carrier request.")
            OFFICIAL_CARRIER_PORTALS.forEach { (k,v) -> s.text("$k: $v") }
            s.text("Refused by this tool: ${REFUSED_BYPASS.joinToString(", ")} — logged if attempted.")
            s
        }
        return b.toString()
    }

    @Throws(IOException::class)
    fun generateUnlockPacket(): Map<String,Any?> {
        val imei = runner.shell("service call iphonesubinfo 1 2>&1 | cut -c 52-66 | tr -d '.[:space:]' 2>/dev/null; cmd phone get-imei 2>&1 | head -n1", 8000L).output.trim().take(20)
        val serial = runner.shell("getprop ro.serialno 2>/dev/null", 5000L).output.trim()
        val model = runner.shell("getprop ro.product.model 2>/dev/null", 5000L).output.trim()
        val baseband = runner.shell("getprop gsm.version.baseband 2>/dev/null", 5000L).output.trim()
        val operator = runner.shell("getprop gsm.sim.operator.alpha 2>/dev/null; getprop gsm.operator.alpha 2>/dev/null", 5000L).output.trim()
        val ts = System.currentTimeMillis()
        val dir = File(filesDir, "unlock_packets"); dir.mkdirs()
        val f = File(dir, "unlock-packet-$ts.txt")
        f.writeText(buildString {
            appendLine("CARRIER UNLOCK PACKET — LAWFUL REQUEST ONLY")
            appendLine("Model: $model"); appendLine("Baseband: $baseband"); appendLine("Operator: $operator")
            appendLine("Serial: $serial"); appendLine("IMEI (redacted): ${imei.take(6)}****")
            appendLine("Timestamp: $ts")
            appendLine("Next: submit to carrier's official unlock portal with ownership proof.")
            appendLine("Official portals:")
            OFFICIAL_CARRIER_PORTALS.forEach { (k,v) -> appendLine("$k: $v") }
            appendLine("This tool never bypasses SIM ME lock.")
        })
        return mapOf(
            "packetPath" to f.absolutePath,
            "imeiRedacted" to (imei.take(6) + "****"),
            "serial" to serial, "model" to model, "operator" to operator,
            "portals" to OFFICIAL_CARRIER_PORTALS,
            "refused" to REFUSED_BYPASS,
            "advice" to "Contact carrier for Network unlock code; do not flash modem/efs."
        )
    }
}
