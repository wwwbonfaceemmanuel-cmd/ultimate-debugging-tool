package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

/**
 * Why one app on the CONNECTED phone is misbehaving.
 *
 * This is the workbench a PC debloat or logcat script would give: the
 * app's version, whether it is running or restricted, the services it
 * keeps alive, and the crashes and ANRs it caused recently.
 *
 * Every command reads. The repairs that change the app live in
 * RepairServices and are listed with their risk.
 */
class AppDiagnosis(private val runner: AdbShellRunner) {

    companion object {

        /** Keys read out of `dumpsys package`. */
        private val FACT_KEYS = listOf(
            "versionName",
            "versionCode",
            "targetSdk",
            "minSdk",
            "firstInstallTime",
            "lastUpdateTime"
        )

        private const val MAX_LOG_LINES = 12

        /** Reads "versionName=1.2.3" style lines. */
        fun parseFacts(output: String): Map<String, String> {
            val facts = LinkedHashMap<String, String>()

            for (key in FACT_KEYS) {
                // Numbers stop at the first space; dates and versions do not.
                val value = if (key.endsWith("Sdk") || key == "versionCode") {
                    "\\d+"
                } else {
                    "[^\\n]+"
                }

                val regex = Regex("(?:^|\\s)$key=($value)")
                val match = regex.find(output) ?: continue
                facts[key] = match.groupValues[1].trim()
            }

            return facts
        }

        /** Reads the number `am get-standby-bucket` prints. */
        fun parseStandbyBucket(output: String): String {
            for (line in output.lineSequence()) {
                val text = line.trim()
                if (text.isEmpty()) continue
                if (text.startsWith("RUN_IN_BACKGROUND")) continue

                if (text.toIntOrNull() != null) return text
            }

            return ""
        }

        /** Android's words for the standby buckets that matter here. */
        fun describeBucket(value: String): String = when (value.trim()) {
            "5" -> "Exempted (never restricted)"
            "10" -> "Active"
            "20" -> "Working set"
            "30" -> "Frequent (may be limited)"
            "40" -> "Rare (may be killed when idle)"
            "45" -> "Restricted (background work blocked)"
            "" -> "Not reported"
            else -> "Bucket $value"
        }

        /** Reads the RUN_IN_BACKGROUND app-op. */
        fun parseBackgroundOp(output: String): String {
            val match = Regex("RUN_IN_BACKGROUND:\\s*(\\w+)").find(output)
                ?: return "Not reported"

            return when (match.groupValues[1].lowercase()) {
                "allow" -> "Allowed to run in the background"
                "deny" -> "Blocked from running in the background"
                "ignore" -> "Ignored (no restriction applied)"
                "default" -> "Default for this app"
                else -> match.groupValues[1]
            }
        }

        /** Extracts "com.example/.Sync" style names from ServiceRecord dumps. */
        fun parseServiceNames(output: String, packageName: String): List<String> {
            // packageName is kept in the signature so callers read naturally.
            val pattern = Regex("ServiceRecord\\{[0-9a-fA-F]+ u\\d+ ([^}\\s]+)")
            val names = LinkedHashSet<String>()

            for (match in pattern.findAll(output)) {
                names.add(match.groupValues[1])
            }

            return names.toList()
        }

        /**
         * The crash entries that belong to this app.
         *
         * A crash log names the app only on its "Process:" line; the
         * stack trace below it carries no package name. So the entry is
         * taken from its FATAL EXCEPTION line to the next one.
         */
        fun crashLines(
            output: String,
            packageName: String,
            limit: Int = MAX_LOG_LINES
        ): List<String> {
            val lines = output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()

            val result = ArrayList<String>()

            for (index in lines.indices) {
                if (!lines[index].contains("Process: $packageName")) continue

                var start = index
                var back = index - 1
                while (back >= 0 && back >= index - 3) {
                    if (lines[back].contains("FATAL EXCEPTION")) {
                        start = back
                        break
                    }
                    back--
                }

                var end = index + 1
                while (
                    end < lines.size &&
                    !lines[end].contains("FATAL EXCEPTION") &&
                    !lines[end].contains("Process: ")
                ) {
                    end++
                }

                result.addAll(lines.subList(start, end))
            }

            return result.takeLast(limit).map {
                if (it.length > 300) it.take(300) else it
            }
        }

        /** The log lines that mention this app, newest last. */
        fun matchingLines(output: String, packageName: String, limit: Int = MAX_LOG_LINES): List<String> {
            return output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { !it.startsWith("---------") }
                .filter { it.contains(packageName) }
                .map { if (it.length > 300) it.take(300) else it }
                .toList()
                .takeLast(limit)
        }
    }

    /** Everything known about one app. */
    class Report(
        val packageName: String,
        val facts: Map<String, String>,
        val system: Boolean,
        val enabled: Boolean,
        val running: Boolean,
        val standbyBucket: String,
        val background: String,
        val services: List<String>,
        val crashes: List<String>,
        val anrs: List<String>,
        val alarmCount: Int
    ) {
        /** One sentence the technician can start from. */
        val verdict: String
            get() = when {
                crashes.isNotEmpty() && anrs.isNotEmpty() ->
                    "The app has both crashed and stopped responding recently."

                crashes.isNotEmpty() -> "The app has crashed recently."
                anrs.isNotEmpty() -> "The app has stopped responding recently."
                !enabled -> "The app is disabled."
                else -> "No recent crash or stall was recorded for this app."
            }

        fun report(): String {
            val builder = ReportBuilder("APP DIAGNOSIS - $packageName")
                .section("Identity")
                .line("Version", facts["versionName"])
                .line("Version code", facts["versionCode"])
                .line("Target SDK", facts["targetSdk"])
                .line("Minimum SDK", facts["minSdk"])
                .line("Type", if (system) "Built-in system app" else "Installed by the user")
                .line("State", if (enabled) "Enabled" else "Disabled")
                .line("Running now", if (running) "Yes" else "No")
                .line("Last updated", facts["lastUpdateTime"])

            builder.section("Background behaviour")
                .line("Standby bucket", standbyBucket)
                .line("Run in background", background)
                .line("Scheduled alarms", alarmCount.toString())

            builder.section("Services it keeps")
                .text(
                    if (services.isEmpty()) {
                        "None are running."
                    } else {
                        services.joinToString("\n") { "- $it" }
                    }
                )

            builder.section("Recent crashes")
                .text(if (crashes.isEmpty()) "None recorded." else crashes.joinToString("\n"))

            builder.section("Recent not-responding events")
                .text(if (anrs.isEmpty()) "None recorded." else anrs.joinToString("\n"))

            return builder.toString()
        }

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "packageName" to packageName,
            "versionName" to facts["versionName"].orEmpty(),
            "versionCode" to facts["versionCode"].orEmpty(),
            "targetSdk" to facts["targetSdk"].orEmpty(),
            "minSdk" to facts["minSdk"].orEmpty(),
            "lastUpdated" to facts["lastUpdateTime"].orEmpty(),
            "system" to system,
            "enabled" to enabled,
            "running" to running,
            "standbyBucket" to standbyBucket,
            "background" to background,
            "alarmCount" to alarmCount,
            "services" to services,
            "crashes" to crashes,
            "anrs" to anrs,
            "verdict" to verdict,
            "report" to report()
        )
    }

    /** Reads everything about [packageName]. */
    @Throws(IOException::class)
    fun inspect(packageName: String): Report {
        if (!ShellSupport.isPackageName(packageName)) {
            throw IOException("Enter a valid package name, for example com.example.app.")
        }

        val quoted = ShellSupport.quote(packageName)

        val data = ShellSupport.runSections(
            runner,
            linkedMapOf(
                "facts" to "dumpsys package $quoted | grep -m 12 -E " +
                    "'versionName=|versionCode=|targetSdk=|minSdk=|" +
                    "firstInstallTime=|lastUpdateTime='",
                "state" to "am get-standby-bucket $quoted; " +
                    "cmd appops get $quoted RUN_IN_BACKGROUND 2>&1",
                "system" to "pm list packages -s $quoted",
                "disabled" to "pm list packages -d $quoted",
                "enabled" to "pm list packages -e $quoted",
                "pid" to "pidof $quoted",
                "services" to "dumpsys activity services $quoted | head -n 60",
                "crashes" to "logcat -b crash -d -t 400",
                "anrs" to "logcat -b events -d -t 400 | grep -i -e 'am_anr' -e 'am_crash'",
                "alarms" to "dumpsys alarm | grep -c $quoted"
            ),
            45000L
        )

        val facts = parseFacts(data["facts"].orEmpty())
        val state = data["state"].orEmpty()

        fun listsPackage(key: String): Boolean = data[key].orEmpty()
            .lineSequence()
            .any { it.trim() == "package:$packageName" }

        val system = listsPackage("system")
        val enabled = listsPackage("enabled")
        val running = data["pid"].orEmpty().trim().isNotEmpty()
        val alarmCount = data["alarms"].orEmpty().trim().toIntOrNull() ?: 0

        return Report(
            packageName = packageName,
            facts = facts,
            system = system,
            enabled = enabled,
            running = running,
            standbyBucket = describeBucket(parseStandbyBucket(state)),
            background = parseBackgroundOp(state),
            services = parseServiceNames(data["services"].orEmpty(), packageName),
            crashes = crashLines(data["crashes"].orEmpty(), packageName),
            anrs = matchingLines(data["anrs"].orEmpty(), packageName),
            alarmCount = alarmCount
        )
    }
}
