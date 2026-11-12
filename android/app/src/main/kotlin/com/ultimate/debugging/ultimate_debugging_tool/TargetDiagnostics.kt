package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.util.Locale

/**
 * Diagnostic reports about the CONNECTED phone, gathered over ADB.
 *
 * Every report only reads information. Parsing is tolerant: Android
 * versions and manufacturers differ, so a missing value is shown as
 * "Unavailable" instead of failing the report.
 */
class TargetDiagnostics(private val runner: AdbShellRunner) {

    companion object {
        val REPORTS: List<Pair<String, String>> = listOf(
            "device-info" to "Device, build and hardware identity",
            "battery" to "Battery condition and charging",
            "storage" to "Storage usage",
            "memory" to "RAM usage and heaviest apps",
            "network" to "Wi-Fi, mobile network and internet test",
            "thermal" to "Temperature sensors",
            "uptime" to "Time since start and boot reason",
            "apps" to "Installed app summary",
            "security" to "Security audit",
            "technician-suite" to "All reports in one"
        )

        fun helpText(): String = buildString {
            appendLine("ULTIMATE DEBUGGING TOOL")
            appendLine("Reports about the CONNECTED phone (over ADB)")
            appendLine()
            REPORTS.forEach { (name, description) ->
                appendLine("${name.padEnd(17)}- $description")
            }
        }.trimEnd()

        // ---------- Pure parsers (unit tested) ----------

        /** Parses "  key: value" lines such as `dumpsys battery`. */
        fun parseKeyValues(output: String): Map<String, String> {
            val values = LinkedHashMap<String, String>()

            for (line in output.lineSequence()) {
                val index = line.indexOf(':')
                if (index <= 0) continue

                val key = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim()

                if (key.isNotEmpty() && !values.containsKey(key)) {
                    values[key] = value
                }
            }

            return values
        }

        /** Parses /proc/meminfo into kilobytes. */
        fun parseMemInfo(output: String): Map<String, Long> {
            val values = LinkedHashMap<String, Long>()

            for ((key, value) in parseKeyValues(output)) {
                value.split(' ').firstOrNull()?.toLongOrNull()?.let {
                    values[key] = it
                }
            }

            return values
        }

        class Volume(
            val mount: String,
            val totalKb: Long,
            val usedKb: Long,
            val freeKb: Long
        ) {
            val usedPercent: Int
                get() = if (totalKb > 0) {
                    ((usedKb * 100) / totalKb).toInt()
                } else {
                    0
                }
        }

        /** Parses `df -k` (1K-blocks, Used, Available, Use%, Mounted on). */
        fun parseDf(output: String): List<Volume> {
            val volumes = ArrayList<Volume>()

            for (line in output.lineSequence()) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 6) continue

                val total = parts[1].toLongOrNull() ?: continue
                val used = parts[2].toLongOrNull() ?: continue
                val free = parts[3].toLongOrNull() ?: continue

                if (total <= 0) continue

                volumes.add(Volume(parts.last(), total, used, free))
            }

            return volumes
        }

        fun batteryStatus(code: String?): String = when (code?.trim()) {
            "2" -> "Charging"
            "3" -> "Discharging"
            "4" -> "Not charging"
            "5" -> "Full"
            null, "", "1" -> "Unknown"
            else -> code
        }

        fun batteryHealth(code: String?): String = when (code?.trim()) {
            "2" -> "Good"
            "3" -> "Overheated"
            "4" -> "Dead"
            "5" -> "Over voltage"
            "6" -> "Failure reported"
            "7" -> "Cold"
            null, "", "1" -> "Unknown"
            else -> code
        }

        /** Uptime in whole seconds from /proc/uptime. */
        fun parseUptimeSeconds(output: String): Long? {
            return output.trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.toDoubleOrNull()
                ?.toLong()
        }

        /** Extracts SSID, signal and speed from the mWifiInfo line. */
        fun parseWifiInfo(dump: String): Map<String, String> {
            val line = dump.lineSequence()
                .firstOrNull { it.contains("mWifiInfo") }
                ?: return emptyMap()

            val result = LinkedHashMap<String, String>()

            fun grab(key: String, pattern: String) {
                Regex(pattern).find(line)?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { result[key] = it }
            }

            grab("ssid", "SSID: \"?([^,\"]*)\"?,")
            grab("rssi", "RSSI: (-?\\d+)")
            grab("linkSpeed", "Link speed: (\\d+)")
            grab("frequency", "Frequency: (\\d+)")
            grab("state", "Supplicant state: (\\w+)")

            return result
        }

        fun signalQuality(rssi: Int): String = when {
            rssi >= -55 -> "Excellent"
            rssi >= -67 -> "Good"
            rssi >= -75 -> "Fair"
            rssi >= -85 -> "Weak"
            else -> "Very weak"
        }

        /** Temperatures from `dumpsys thermalservice` (Android 10+). */
        fun parseThermalService(dump: String): List<Pair<String, Double>> {
            val readings = LinkedHashMap<String, Double>()
            val pattern = Regex("mValue=(-?[0-9.]+).*?mName=([^,}]+)")

            var inCurrent = false

            for (line in dump.lineSequence()) {
                if (line.contains("Current temperatures from HAL")) {
                    inCurrent = true
                    continue
                }

                if (inCurrent && !line.contains("Temperature{")) {
                    if (line.isNotBlank()) inCurrent = false
                    continue
                }

                if (!inCurrent) continue

                val match = pattern.find(line) ?: continue
                val value = match.groupValues[1].toDoubleOrNull() ?: continue

                readings[match.groupValues[2].trim()] = value
            }

            return readings.toList()
        }

        private fun oneDecimal(value: Double): String =
            String.format(Locale.US, "%.1f", value)
    }

    /** Runs the named report and returns its text. */
    @Throws(IOException::class)
    fun run(command: String): String {
        return when (command.trim().lowercase()) {
            "help" -> helpText()
            "device-info" -> deviceInfo()
            "battery" -> battery()
            "storage" -> storage()
            "memory" -> memory()
            "network" -> network()
            "thermal" -> thermal()
            "uptime" -> uptime()
            "apps" -> apps()
            "security" -> SecurityAudit(runner).run().toReport()
            "technician-suite" -> technicianSuite()
            else -> throw IOException(
                "Unknown report: $command. Type help to see available reports."
            )
        }
    }

    private fun sections(
        commands: Map<String, String>,
        timeoutMs: Long = 30000L
    ): Map<String, String> {
        return ShellSupport.runSections(runner, commands, timeoutMs)
    }

    private fun clean(value: String?): String? {
        return value?.trim()?.takeIf {
            it.isNotEmpty() && it != "null" && !ShellSupport.looksLikeError(it)
        }
    }

    private fun deviceInfo(): String {
        val data = sections(
            linkedMapOf(
                "props" to "getprop",
                "kernel" to "uname -r",
                "cores" to "cat /sys/devices/system/cpu/possible",
                "mem" to "cat /proc/meminfo | head -n 3",
                "size" to "wm size",
                "density" to "wm density",
                "date" to "date"
            )
        )

        val props = ShellSupport.parseProperties(data["props"].orEmpty())

        fun prop(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { clean(props[it]) }

        val memory = parseMemInfo(data["mem"].orEmpty())

        val cores = clean(data["cores"])?.let {
            val last = it.substringAfterLast('-').toIntOrNull()
            if (last != null) "${last + 1}" else it
        }

        return ReportBuilder("DEVICE INFORMATION REPORT (CONNECTED PHONE)")
            .section("Identity")
            .line("Manufacturer", prop("ro.product.manufacturer"))
            .line("Brand", prop("ro.product.brand"))
            .line("Model", prop("ro.product.model"))
            .line("Device code", prop("ro.product.device"))
            .line("Product name", prop("ro.product.name"))
            .line("Serial number", prop("ro.serialno", "ro.boot.serialno"))
            .section("Android")
            .line("Android version", prop("ro.build.version.release"))
            .line("API level", prop("ro.build.version.sdk"))
            .line("Shipped with API level", prop("ro.product.first_api_level"))
            .line("Security patch", prop("ro.build.version.security_patch"))
            .line("Build number", prop("ro.build.display.id"))
            .line("Build fingerprint", prop("ro.build.fingerprint"))
            .line("Build type", prop("ro.build.type"))
            .line("Build tags", prop("ro.build.tags"))
            .line("Build date", prop("ro.build.date"))
            .section("Firmware")
            .line("Bootloader", prop("ro.bootloader", "ro.boot.bootloader"))
            .line("Baseband", prop("gsm.version.baseband"))
            .line("Kernel", clean(data["kernel"]))
            .line("Verified boot state", prop("ro.boot.verifiedbootstate"))
            .line("Bootloader locked", when (prop("ro.boot.flash.locked")) {
                "1" -> "Yes"
                "0" -> "No"
                else -> null
            })
            .section("Hardware")
            .line("Chipset", prop("ro.soc.model", "ro.board.platform", "ro.hardware"))
            .line("Chipset maker", prop("ro.soc.manufacturer"))
            .line("Board", prop("ro.product.board"))
            .line("CPU architecture", prop("ro.product.cpu.abi"))
            .line("Supported ABIs", prop("ro.product.cpu.abilist"))
            .line("CPU cores", cores)
            .line("Total RAM", memory["MemTotal"]?.let {
                ShellSupport.formatBytes(it * 1024)
            })
            .line("Screen", clean(data["size"])?.substringAfter(": "))
            .line("Screen density", clean(data["density"])?.substringAfter(": "))
            .section("Region")
            .line("Language", prop("persist.sys.locale", "ro.product.locale"))
            .line("Time zone", prop("persist.sys.timezone"))
            .line("Phone date and time", clean(data["date"]))
            .section("Technician notes")
            .text(
                "Match firmware to the exact model, device code and build\n" +
                    "fingerprint. Never flash firmware based on model name alone."
            )
            .toString()
    }

    private fun battery(): String {
        val supply = "/sys/class/power_supply/battery"

        val data = sections(
            linkedMapOf(
                "dump" to "dumpsys battery",
                "cycles" to "cat $supply/cycle_count",
                "full" to "cat $supply/charge_full",
                "design" to "cat $supply/charge_full_design",
                "current" to "cat $supply/current_now"
            )
        )

        val values = parseKeyValues(data["dump"].orEmpty())

        if (values.isEmpty()) {
            throw IOException(
                "The phone did not return battery information."
            )
        }

        val level = values["level"]?.toIntOrNull()
        val scale = values["scale"]?.toIntOrNull() ?: 100
        val percent = level?.let { it * 100 / scale.coerceAtLeast(1) }
        val temperature = values["temperature"]?.toIntOrNull()?.let { it / 10.0 }
        val voltage = values["voltage"]?.toIntOrNull()?.let {
            if (it > 100000) it / 1_000_000.0 else it / 1000.0
        }
        val health = batteryHealth(values["health"])
        val status = batteryStatus(values["status"])

        val source = listOfNotNull(
            "AC charger".takeIf { values["AC powered"] == "true" },
            "USB".takeIf { values["USB powered"] == "true" },
            "Wireless".takeIf { values["Wireless powered"] == "true" },
            "Dock".takeIf { values["Dock powered"] == "true" }
        ).joinToString(", ").ifEmpty { "None (on battery)" }

        val full = clean(data["full"])?.toLongOrNull()
        val design = clean(data["design"])?.toLongOrNull()

        val capacityPercent = if (full != null && design != null && design > 0) {
            (full * 100 / design).toInt().takeIf { it in 1..150 }
        } else {
            null
        }

        val cycles = clean(data["cycles"])?.toIntOrNull()
        val current = clean(data["current"])?.toLongOrNull()

        val assessment = ArrayList<String>()

        if (temperature != null) {
            when {
                temperature >= 45 -> assessment.add(
                    "Battery is HOT (${oneDecimal(temperature)} °C). Stop charging and let it cool."
                )
                temperature >= 40 -> assessment.add(
                    "Battery is warm (${oneDecimal(temperature)} °C). Monitor during charging."
                )
                temperature <= 0 -> assessment.add(
                    "Battery is very cold. Charging may be blocked until it warms up."
                )
            }
        }

        if (health != "Good" && health != "Unknown") {
            assessment.add("The phone reports battery health: $health. Inspect or replace the battery.")
        }

        if (capacityPercent != null && capacityPercent < 80) {
            assessment.add(
                "Battery holds about $capacityPercent% of its original capacity. Replacement is recommended."
            )
        }

        if (cycles != null && cycles > 800) {
            assessment.add("High charge cycle count ($cycles). Expect reduced battery life.")
        }

        if (voltage != null && voltage < 3.3 && status != "Charging") {
            assessment.add("Voltage is very low. The phone may shut down suddenly.")
        }

        if (status == "Not charging" && source != "None (on battery)") {
            assessment.add(
                "A charger is connected but the battery is not charging. Check cable, port and temperature."
            )
        }

        if (assessment.isEmpty()) {
            assessment.add("No battery faults detected from the available readings.")
        }

        return ReportBuilder("BATTERY DIAGNOSTIC REPORT (CONNECTED PHONE)")
            .section("Battery condition")
            .line("Battery level", percent?.let { "$it%" })
            .line("Battery health", health)
            .line("Technology", clean(values["technology"]))
            .line("Capacity vs. new", capacityPercent?.let { "$it%" })
            .line("Charge cycles", cycles?.toString())
            .section("Charging system")
            .line("Charging status", status)
            .line("Charging source", source)
            .section("Electrical readings")
            .line("Voltage", voltage?.let { String.format(Locale.US, "%.3f V", it) })
            .line("Current now", current?.let {
                // Reported in microamps on most phones.
                String.format(Locale.US, "%.0f mA", it / 1000.0)
            })
            .section("Thermal readings")
            .line("Battery temperature", temperature?.let { "${oneDecimal(it)} °C" })
            .section("Technician assessment")
            .bullets(assessment)
            .section("Data limitations")
            .text(
                "Capacity and cycle count are only available on phones that\n" +
                    "expose them. Confirm a suspected fault with a charge test."
            )
            .toString()
    }

    private fun storage(): String {
        val data = sections(
            linkedMapOf(
                "df" to "df -k /data /storage/emulated /system /cache /vendor 2>/dev/null",
                "raw" to "df",
                "disk" to "dumpsys diskstats"
            )
        )

        val volumes = parseDf(data["df"].orEmpty()).distinctBy { it.mount }
        val disk = parseKeyValues(data["disk"].orEmpty())
        val report = ReportBuilder("STORAGE DIAGNOSTIC REPORT (CONNECTED PHONE)")
        val assessment = ArrayList<String>()

        if (volumes.isEmpty()) {
            report.section("Filesystems").text(
                clean(data["raw"]) ?: "The phone did not return storage information."
            )
        } else {
            for (volume in volumes) {
                report.section(volume.mount)
                    .line("Total", ShellSupport.formatBytes(volume.totalKb * 1024))
                    .line("Used", ShellSupport.formatBytes(volume.usedKb * 1024))
                    .line("Free", ShellSupport.formatBytes(volume.freeKb * 1024))
                    .line("Usage", "${volume.usedPercent}%")
            }

            volumes.firstOrNull { it.mount == "/data" }?.let { user ->
                when {
                    user.usedPercent >= 95 -> assessment.add(
                        "User storage is critically full (${user.usedPercent}%). Apps may crash and updates will fail."
                    )
                    user.usedPercent >= 85 -> assessment.add(
                        "User storage is nearly full (${user.usedPercent}%). Free space to restore performance."
                    )
                    else -> assessment.add("User storage has healthy free space.")
                }
            }
        }

        fun size(key: String): String? =
            disk[key]?.split(' ')?.firstOrNull()?.toLongOrNull()?.let {
                ShellSupport.formatBytes(it)
            }

        if (disk.containsKey("App Size") || disk.containsKey("Cache Size")) {
            report.section("What is using space")
                .line("Apps", size("App Size"))
                .line("App data", size("App Data Size"))
                .line("App caches", size("App Cache Size"))
                .line("Photos", size("Photos Size"))
                .line("Videos", size("Videos Size"))
                .line("Audio", size("Audio Size"))
                .line("Downloads", size("Downloads Size"))
                .line("System", size("System Size"))
                .line("Other", size("Other Size"))
        }

        disk["Latency"]?.let { report.section("Write test").line("Latency", it) }

        if (assessment.isNotEmpty()) {
            report.section("Technician assessment").bullets(assessment)
            report.text("Repair Tools > Free up app caches can recover space safely.")
        }

        return report.toString()
    }

    private fun memory(): String {
        val data = sections(
            linkedMapOf(
                "mem" to "cat /proc/meminfo",
                "top" to "ps -A -o RSS,NAME -k -RSS 2>/dev/null | head -n 11"
            )
        )

        val memory = parseMemInfo(data["mem"].orEmpty())
        val total = memory["MemTotal"]
            ?: throw IOException("The phone did not return memory information.")

        val available = memory["MemAvailable"]
            ?: ((memory["MemFree"] ?: 0) + (memory["Cached"] ?: 0))

        val used = total - available
        val usedPercent = used * 100.0 / total

        fun kb(value: Long?): String? =
            value?.let { ShellSupport.formatBytes(it * 1024) }

        val report = ReportBuilder("MEMORY DIAGNOSTIC REPORT (CONNECTED PHONE)")
            .section("RAM")
            .line("Total RAM", kb(total))
            .line("Used RAM", kb(used))
            .line("Available RAM", kb(available))
            .line("Memory usage", "${oneDecimal(usedPercent)}%")
            .section("Swap / zRAM")
            .line("Swap total", kb(memory["SwapTotal"]))
            .line("Swap free", kb(memory["SwapFree"]))

        val top = data["top"].orEmpty().lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                val rss = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val name = parts.getOrNull(1) ?: return@mapNotNull null
                "$name: ${ShellSupport.formatBytes(rss * 1024)}"
            }
            .toList()

        if (top.isNotEmpty()) {
            report.section("Heaviest processes").bullets(top)
        }

        report.section("Technician assessment").bullets(
            listOf(
                when {
                    usedPercent >= 90 ->
                        "Memory is under heavy pressure. Close or force-stop heavy apps."
                    usedPercent >= 80 ->
                        "Memory usage is high. Slowdowns and app reloads are likely."
                    else ->
                        "Memory usage is within the normal range."
                }
            )
        )

        return report.toString()
    }

    private fun network(): String {
        val data = sections(
            linkedMapOf(
                "addr" to "ip -o -4 addr show",
                "route" to "ip route show",
                "wifi" to "dumpsys wifi | grep -E 'Wi-Fi is|mWifiInfo' | head -n 4",
                "airplane" to "settings get global airplane_mode_on",
                "wifiOn" to "settings get global wifi_on",
                "data" to "settings get global mobile_data",
                "dnsMode" to "settings get global private_dns_mode",
                "dnsHost" to "settings get global private_dns_specifier",
                "proxy" to "settings get global http_proxy",
                "sim" to "getprop gsm.sim.state",
                "operator" to "getprop gsm.operator.alpha",
                "type" to "getprop gsm.network.type",
                "roaming" to "getprop gsm.operator.isroaming",
                "dns1" to "getprop net.dns1",
                "ping" to "ping -c 2 -W 2 8.8.8.8",
                "name" to "ping -c 1 -W 3 connectivitycheck.gstatic.com"
            ),
            timeoutMs = 45000L
        )

        val addresses = data["addr"].orEmpty().lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                val name = parts.getOrNull(1) ?: return@mapNotNull null
                val address = parts.getOrNull(3) ?: return@mapNotNull null
                if (name == "lo") null else "$name: $address"
            }
            .toList()

        val wifi = parseWifiInfo(data["wifi"].orEmpty())
        val rssi = wifi["rssi"]?.toIntOrNull()?.takeIf { it > -127 }

        val airplane = clean(data["airplane"]) == "1"
        val wifiOn = clean(data["wifiOn"])?.let { it != "0" }
        val internet = data["ping"].orEmpty().let {
            it.contains("bytes from") || Regex("[1-9] (packets )?received").containsMatchIn(it)
        }
        val names = data["name"].orEmpty().let {
            it.contains("bytes from") || it.contains("PING ")
        }

        val proxy = clean(data["proxy"])?.takeIf { it != ":0" }
        val dnsMode = clean(data["dnsMode"])
        val dnsHost = clean(data["dnsHost"])

        val assessment = ArrayList<String>()

        if (airplane) {
            assessment.add("Airplane mode is ON. Turn it off to restore mobile and Wi-Fi.")
        }

        if (wifiOn == false) assessment.add("Wi-Fi is switched off.")

        if (addresses.isEmpty()) {
            assessment.add("The phone has no IP address. It is not connected to any network.")
        }

        if (proxy != null) {
            assessment.add(
                "A proxy is set ($proxy). A leftover proxy often blocks internet access. " +
                    "Repair Tools > Remove network proxy clears it."
            )
        }

        if (internet && !names) {
            assessment.add(
                "Internet is reachable but website names do not resolve: DNS problem. " +
                    "Repair Tools > Reset Private DNS often fixes this."
            )
        } else if (!internet && addresses.isNotEmpty()) {
            assessment.add(
                "Connected to a network but the internet test failed. " +
                    "Check the router, data bundle or captive portal sign-in."
            )
        } else if (internet) {
            assessment.add("Internet connection test passed.")
        }

        if (rssi != null && rssi < -80) {
            assessment.add("Wi-Fi signal is weak ($rssi dBm). Move closer to the router.")
        }

        if (dnsMode == "hostname" && dnsHost == null) {
            assessment.add("Private DNS is set to a provider but no provider name is saved.")
        }

        return ReportBuilder("NETWORK DIAGNOSTIC REPORT (CONNECTED PHONE)")
            .section("Switches")
            .line("Airplane mode", if (airplane) "ON" else "Off")
            .line("Wi-Fi", wifiOn?.let { if (it) "On" else "Off" })
            .line("Mobile data", clean(data["data"])?.let { if (it == "1") "On" else "Off" })
            .section("Wi-Fi")
            .line("Network name", wifi["ssid"]?.takeIf { it != "<unknown ssid>" })
            .line("Signal", rssi?.let { "$it dBm (${signalQuality(it)})" })
            .line("Link speed", wifi["linkSpeed"]?.let { "$it Mbps" })
            .line("Frequency", wifi["frequency"]?.let { "$it MHz" })
            .line("State", wifi["state"])
            .section("Mobile network")
            .line("SIM state", clean(data["sim"]))
            .line("Operator", clean(data["operator"])?.trim(','))
            .line("Network type", clean(data["type"])?.trim(','))
            .line("Roaming", clean(data["roaming"])?.trim(','))
            .section("Addresses")
            .text(addresses.joinToString("\n").ifEmpty { "No IP address assigned." })
            .line("Default route", data["route"].orEmpty().lineSequence()
                .firstOrNull { it.startsWith("default") })
            .section("DNS and proxy")
            .line("DNS server", clean(data["dns1"]))
            .line("Private DNS mode", dnsMode)
            .line("Private DNS provider", dnsHost)
            .line("Proxy", proxy ?: "None")
            .section("Internet test")
            .line("Reach 8.8.8.8", if (internet) "PASSED" else "FAILED")
            .line("Resolve website names", if (names) "PASSED" else "FAILED")
            .section("Technician assessment")
            .bullets(assessment.ifEmpty { listOf("No network faults detected.") })
            .toString()
    }

    private fun thermal(): String {
        val data = sections(
            linkedMapOf(
                "service" to "dumpsys thermalservice",
                "zones" to "for z in /sys/class/thermal/thermal_zone*; do " +
                    "echo \"\$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done",
                "battery" to "dumpsys battery | grep temperature"
            )
        )

        var readings = parseThermalService(data["service"].orEmpty())

        if (readings.isEmpty()) {
            readings = data["zones"].orEmpty().lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(' ')
                    if (parts.size != 2) return@mapNotNull null
                    val raw = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                    // Zones report degrees or thousandths of a degree.
                    val value = if (raw > 1000 || raw < -1000) raw / 1000.0 else raw
                    if (value < -40 || value > 150) null else parts[0] to value
                }
                .toList()
        }

        val battery = parseKeyValues(data["battery"].orEmpty())["temperature"]
            ?.toIntOrNull()?.let { it / 10.0 }

        val status = Regex("Thermal Status: (\\d+)")
            .find(data["service"].orEmpty())?.groupValues?.get(1)

        val hottest = readings.maxByOrNull { it.second }

        val report = ReportBuilder("THERMAL REPORT (CONNECTED PHONE)")
            .section("Summary")
            .line("Battery temperature", battery?.let { "${oneDecimal(it)} °C" })
            .line("Hottest sensor", hottest?.let { "${it.first}: ${oneDecimal(it.second)} °C" })
            .line("Throttling level", when (status) {
                null -> null
                "0" -> "None"
                "1" -> "Light"
                "2" -> "Moderate"
                "3" -> "Severe"
                "4" -> "Critical"
                "5" -> "Emergency"
                "6" -> "Shutdown"
                else -> status
            })

        if (readings.isEmpty()) {
            report.section("Sensors")
                .text("This phone does not expose temperature sensors over ADB.")
        } else {
            report.section("Sensors").bullets(
                readings.sortedByDescending { it.second }
                    .take(25)
                    .map { "${it.first}: ${oneDecimal(it.second)} °C" }
            )
        }

        val assessment = when {
            (status?.toIntOrNull() ?: 0) >= 3 ->
                "The phone is throttling hard because of heat. Let it cool before further work."
            battery != null && battery >= 45 ->
                "Battery is hot. Stop charging and let the phone cool."
            hottest != null && hottest.second >= 70 ->
                "A sensor is very hot. Check for a runaway app (terminal: top-cpu)."
            else -> "Temperatures are within the normal range."
        }

        return report.section("Technician assessment")
            .bullets(listOf(assessment))
            .toString()
    }

    private fun uptime(): String {
        val data = sections(
            linkedMapOf(
                "uptime" to "cat /proc/uptime",
                "reason" to "getprop sys.boot.reason",
                "bootReason" to "getprop ro.boot.bootreason",
                "date" to "date",
                "completed" to "getprop sys.boot_completed"
            )
        )

        val seconds = parseUptimeSeconds(data["uptime"].orEmpty())
            ?: throw IOException("The phone did not return its uptime.")

        val reason = clean(data["reason"]) ?: clean(data["bootReason"])

        val notes = ArrayList<String>()

        if (seconds < 600) {
            notes.add("The phone started less than 10 minutes ago.")
        }

        reason?.lowercase()?.let {
            when {
                it.contains("panic") || it.contains("watchdog") -> notes.add(
                    "Last start followed a system crash ($reason). Check logcat-crashes and hardware."
                )
                it.contains("thermal") -> notes.add(
                    "Last shutdown was caused by overheating."
                )
                it.contains("battery") || it.contains("power_loss") || it.contains("uvlo") ->
                    notes.add("Last shutdown was caused by battery or power loss.")
                else -> Unit
            }
        }

        if (seconds > 14 * 86400L) {
            notes.add("The phone has run for over two weeks. A restart can clear slowdowns.")
        }

        return ReportBuilder("UPTIME REPORT (CONNECTED PHONE)")
            .section("Running time")
            .line("Time since start", ShellSupport.formatDuration(seconds))
            .line("Boot completed", if (clean(data["completed"]) == "1") "Yes" else "No")
            .line("Reason for last start", reason)
            .line("Phone date and time", clean(data["date"]))
            .section("Technician assessment")
            .bullets(notes.ifEmpty { listOf("Nothing unusual about the last start.") })
            .toString()
    }

    private fun apps(): String {
        val data = sections(
            linkedMapOf(
                "user" to "pm list packages -3",
                "system" to "pm list packages -s",
                "disabled" to "pm list packages -d"
            ),
            timeoutMs = 45000L
        )

        fun packages(key: String): List<String> =
            data[key].orEmpty().lineSequence()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .sorted()
                .toList()

        val user = packages("user")
        val disabled = packages("disabled")

        return ReportBuilder("APPLICATION SUMMARY (CONNECTED PHONE)")
            .section("Counts")
            .line("Installed by the user", user.size.toString())
            .line("System apps", packages("system").size.toString())
            .line("Disabled apps", disabled.size.toString())
            .section("Installed by the user")
            .bullets(user.ifEmpty { listOf("None") })
            .section("Disabled")
            .bullets(disabled.ifEmpty { listOf("None") })
            .toString()
    }

    private fun technicianSuite(): String {
        val names = listOf(
            "device-info", "battery", "storage", "memory",
            "network", "thermal", "uptime", "security"
        )

        return names.joinToString("\n\n" + "=".repeat(40) + "\n\n") { name ->
            try {
                run(name)
            } catch (error: IOException) {
                if (runner.link == AdbSessionManager.Link.NONE) throw error
                "${name.uppercase()}: could not be completed (${error.message})"
            }
        }
    }
}
