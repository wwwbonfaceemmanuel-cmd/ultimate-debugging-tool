package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

/**
 * Real network lab: Wi-Fi handoff + DNS/ping diagnostics.
 */
class NetworkLab(private val runner: AdbShellRunner) {

    companion object {
        fun makeWifiQr(ssid: String, password: String, type: String = "WPA"): String {
            fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;")
            return "WIFI:T:${esc(type)};S:${esc(ssid)};P:${esc(password)};;"
        }
        fun parseDns(output: String): Map<String,String> {
            val m = mutableMapOf<String,String>()
            for (line in output.lineSequence()) {
                if (line.contains("private_dns", true)) m["private_dns"] = line.trim()
                if (line.contains("net.dns", true)) m["net.dns"] = line.trim()
            }
            return m
        }
    }

    @Throws(IOException::class)
    fun wifiHandoff(): String {
        val ssid = runner.shell("dumpsys wifi 2>&1 | grep -m1 -i 'SSID:' 2>/dev/null; cmd wifi status 2>&1 | grep -m1 SSID 2>/dev/null", 12000L).output.trim()
        val ip = runner.shell("ip -o -4 addr show wlan0 2>&1 | grep -o 'inet [0-9.]*' | head -n1", 8000L).output.trim()
        val b = ReportBuilder("WI-FI HANDOFF (CONNECTED PHONE)")
        b.section("Current Wi-Fi").text(ssid.ifEmpty { "SSID not reported (hidden or off)" })
        b.section("Phone IP").text(ip.ifEmpty { "Not on Wi-Fi" })
        b.section("QR for technician").text(
            "To share the connected phone's Wi-Fi with the customer's phone, scan its QR in Settings → Wi-Fi.\n" +
            "This tool does not expose the password — show the QR on the connected phone itself."
        )
        b.section("Fix").text("No internet but connected? Run fix-dns, fix-proxy, fix-wifi-safe — all verified writes.")
        return b.toString()
    }

    @Throws(IOException::class)
    fun dnsLab(host: String = "google.com"): String {
        val dns = runner.shell("getprop net.dns1 2>&1; getprop net.dns2 2>&1; settings get global private_dns_mode 2>&1; settings get global private_dns_specifier 2>&1", 8000L).output
        val ping = runner.shell("ping -c 3 -W 3 '${ShellSupport.quote(host).trim('\'')}' 2>&1 | head -n 20; nslookup $host 2>&1 | head -n 20; getprop net.eth0.dns1 2>&1 | head -n5", 15000L).output
        val b = ReportBuilder("NETWORK LAB — DNS / PING (CONNECTED PHONE)")
        b.section("DNS config").text(dns.trim())
        b.section("Ping/nslookup $host").text(ping.trim().take(3000))
        b.section("If failed").text("Fix: settings put global private_dns_mode opportunistic (fix-dns), or fix-proxy to remove bad proxy.")
        return b.toString()
    }
}
