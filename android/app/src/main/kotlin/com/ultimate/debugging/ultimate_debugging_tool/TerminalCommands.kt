package com.ultimate.debugging.ultimate_debugging_tool

/**
 * The fixed list of commands the terminal may run on the target phone.
 *
 * Every entry only reads information. Free-form shell input is never
 * passed to the phone; a command either matches this list or is refused.
 */
object TerminalCommands {

    enum class Argument { NONE, PACKAGE, HOST }

    class Entry(
        val name: String,
        val shell: String,
        val description: String,
        val group: String,
        val argument: Argument = Argument.NONE,
        val timeoutMs: Long = 30000L
    )

    class Resolved(val entry: Entry, val shell: String)

    class Rejected(message: String) : Exception(message)

    private fun entry(
        group: String,
        name: String,
        shell: String,
        description: String,
        argument: Argument = Argument.NONE,
        timeoutMs: Long = 30000L
    ) = Entry(name, shell, description, group, argument, timeoutMs)

    val catalog: List<Entry> = listOf(
        // Identity
        entry("Device", "id", "id", "Show the current shell user and permissions."),
        entry("Device", "whoami", "whoami", "Show the current username."),
        entry("Device", "uname -a", "uname -a", "Show kernel and system information."),
        entry("Device", "getprop", "getprop", "List Android system properties."),
        entry("Device", "device-model", "getprop ro.product.model", "Show the device model."),
        entry("Device", "manufacturer", "getprop ro.product.manufacturer", "Show the manufacturer."),
        entry("Device", "device-brand", "getprop ro.product.brand", "Show the device brand."),
        entry("Device", "android-version", "getprop ro.build.version.release", "Show the Android version."),
        entry("Device", "sdk-version", "getprop ro.build.version.sdk", "Show the Android SDK version."),
        entry("Device", "security-patch", "getprop ro.build.version.security_patch", "Show the security patch date."),
        entry("Device", "build-fingerprint", "getprop ro.build.fingerprint", "Show the build fingerprint."),
        entry("Device", "cpu-architecture", "getprop ro.product.cpu.abi", "Show the primary CPU architecture."),
        entry("Device", "kernel-version", "cat /proc/version", "Show the Linux kernel version."),
        entry("Device", "cpu-info", "cat /proc/cpuinfo", "Inspect CPU information."),
        entry("Device", "baseband", "getprop gsm.version.baseband", "Show the modem firmware version."),
        entry("Device", "bootloader", "getprop ro.bootloader", "Show the bootloader version."),
        entry("Device", "features", "pm list features", "List hardware and software features."),
        entry("Device", "date", "date", "Show the phone's date and time."),

        // Boot and state
        entry("Boot", "uptime", "uptime; cat /proc/uptime", "Show time since the phone started."),
        entry("Boot", "boot-reason", "getprop sys.boot.reason; getprop ro.boot.bootreason", "Show why the phone last started."),
        entry("Boot", "boot-state", "echo verifiedbootstate=$(getprop ro.boot.verifiedbootstate); echo flash.locked=$(getprop ro.boot.flash.locked); echo vbmeta.device_state=$(getprop ro.boot.vbmeta.device_state); echo warranty_bit=$(getprop ro.boot.warranty_bit)", "Show verified boot and bootloader lock state."),
        entry("Boot", "selinux-status", "getenforce", "Show SELinux enforcement status."),
        entry("Boot", "encryption", "echo state=$(getprop ro.crypto.state); echo type=$(getprop ro.crypto.type)", "Show storage encryption state."),
        entry("Boot", "users", "pm list users", "List user profiles on the phone."),

        // Storage and memory
        entry("Storage", "df -h", "df -h", "Show filesystem storage usage."),
        entry("Storage", "partitions", "cat /proc/partitions", "List storage partitions."),
        entry("Storage", "mounts", "cat /proc/mounts", "List mounted filesystems."),
        entry("Storage", "disk-stats", "dumpsys diskstats", "Show storage use by apps, data and cache."),
        entry("Storage", "cat /proc/meminfo", "cat /proc/meminfo", "Show detailed memory information."),
        entry("Storage", "memory-by-app", "dumpsys meminfo -c | head -n 60", "Show which apps use the most memory.", timeoutMs = 60000L),

        // Battery and heat
        entry("Power", "dumpsys battery", "dumpsys battery", "Inspect battery status."),
        entry("Power", "battery-stats", "dumpsys batterystats --charged | head -n 150", "Show battery use since last full charge.", timeoutMs = 60000L),
        entry("Power", "thermal-status", "dumpsys thermalservice", "Inspect temperature sensors."),
        entry("Power", "power-info", "dumpsys power", "Inspect Android power management."),
        entry("Power", "wakelocks", "dumpsys power | grep -i -E 'wake lock|mWakefulness|mStayOn'", "Show what is keeping the phone awake."),

        // Processes and apps
        entry("Apps", "processes", "ps -A 2>/dev/null || ps", "List running processes."),
        entry("Apps", "top-cpu", "top -b -n 1 2>/dev/null | head -n 30 || top -n 1 -m 20", "Show the busiest processes right now.", timeoutMs = 45000L),
        entry("Apps", "current-app", "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|mFocusedActivity' | head -n 5", "Show the app on screen."),
        entry("Apps", "activity-processes", "dumpsys activity processes", "Inspect Android activity processes."),
        entry("Apps", "pm list packages", "pm list packages", "List installed application packages."),
        entry("Apps", "system-packages", "pm list packages -s", "List system packages."),
        entry("Apps", "third-party-packages", "pm list packages -3", "List third-party applications."),
        entry("Apps", "disabled-packages", "pm list packages -d", "List disabled packages."),
        entry("Apps", "enabled-packages", "pm list packages -e", "List enabled packages."),
        entry("Apps", "app-info", "dumpsys package %s | head -n 200", "Show details of one app. Usage: app-info <package>", Argument.PACKAGE),
        entry("Apps", "app-path", "pm path %s", "Show where an app's APK files are. Usage: app-path <package>", Argument.PACKAGE),
        entry("Apps", "app-memory", "dumpsys meminfo %s | head -n 60", "Show memory use of one app. Usage: app-memory <package>", Argument.PACKAGE),
        entry("Apps", "app-running", "pidof %s || echo 'Not running'", "Check whether an app is running. Usage: app-running <package>", Argument.PACKAGE),

        // Logs
        entry("Logs", "logcat-errors", "logcat -d -t 300 '*:E'", "Show the latest error log lines.", timeoutMs = 45000L),
        entry("Logs", "logcat-crashes", "logcat -d -b crash -t 200", "Show the latest app crashes.", timeoutMs = 45000L),
        entry("Logs", "logcat-recent", "logcat -d -t 200", "Show the latest 200 log lines.", timeoutMs = 45000L),
        entry("Logs", "anr-list", "ls -la /data/anr 2>&1 | head -n 40", "List 'app not responding' reports."),
        entry("Logs", "dropbox", "dumpsys dropbox | tail -n 60", "Show recent system error records."),

        // Network
        entry("Network", "ip-addresses", "ip addr show", "Inspect network interfaces and IP addresses."),
        entry("Network", "routes", "ip route show", "Show network routes."),
        entry("Network", "dns", "echo dns1=$(getprop net.dns1); echo dns2=$(getprop net.dns2); echo private_dns=$(settings get global private_dns_mode) $(settings get global private_dns_specifier)", "Show DNS settings."),
        entry("Network", "ping-internet", "ping -c 3 -W 3 8.8.8.8", "Test the internet connection."),
        entry("Network", "ping", "ping -c 3 -W 3 %s", "Test a connection. Usage: ping <host>", Argument.HOST),
        entry("Network", "connectivity", "dumpsys connectivity", "Inspect Android connectivity services."),
        entry("Network", "wifi-status", "dumpsys wifi", "Inspect Wi-Fi service information."),
        entry("Network", "wifi-summary", "dumpsys wifi | grep -E 'Wi-Fi is|mWifiInfo' | head -n 5", "Show Wi-Fi state, network and signal."),
        entry("Network", "bluetooth-status", "dumpsys bluetooth_manager", "Inspect Bluetooth service information."),
        entry("Network", "sim-status", "echo sim=$(getprop gsm.sim.state); echo operator=$(getprop gsm.operator.alpha); echo sim_operator=$(getprop gsm.sim.operator.alpha); echo network=$(getprop gsm.network.type); echo roaming=$(getprop gsm.operator.isroaming)", "Show SIM and mobile network state."),
        entry("Network", "telephony", "dumpsys telephony.registry | head -n 120", "Inspect mobile signal and service state."),

        // Hardware
        entry("Hardware", "usb-status", "dumpsys usb", "Inspect USB service information."),
        entry("Hardware", "display-info", "dumpsys display", "Inspect display configuration."),
        entry("Hardware", "screen-size", "wm size; wm density", "Show screen resolution and density."),
        entry("Hardware", "sensor-info", "dumpsys sensorservice", "Inspect available sensors."),
        entry("Hardware", "input-devices", "dumpsys input | head -n 150", "Inspect touchscreen and buttons."),
        entry("Hardware", "audio-status", "dumpsys audio | head -n 150", "Inspect volume and audio routing."),
        entry("Hardware", "camera-status", "dumpsys media.camera | head -n 80", "Inspect camera devices."),

        // Settings and security
        entry("Settings", "settings-global", "settings list global", "List global settings."),
        entry("Settings", "settings-secure", "settings list secure", "List secure settings."),
        entry("Settings", "settings-system", "settings list system", "List system settings."),
        entry("Settings", "device-admins", "dumpsys device_policy | head -n 120", "Show device administrator apps."),
        entry("Settings", "accessibility-services", "settings get secure enabled_accessibility_services", "Show enabled accessibility services."),
        entry("Settings", "default-keyboard", "settings get secure default_input_method", "Show the active keyboard app.")
    )

    private val byName: Map<String, Entry> = catalog.associateBy { it.name }

    // Longest names first so "pm list packages" wins over a shorter prefix.
    private val namesByLength: List<String> =
        catalog.map { it.name }.sortedByDescending { it.length }

    /**
     * Matches technician input against the catalog.
     * The command name is case-insensitive; an argument is validated and
     * quoted before it is placed into the shell command.
     */
    @Throws(Rejected::class)
    fun resolve(input: String): Resolved {
        val trimmed = input.trim().replace(Regex("\\s+"), " ")

        if (trimmed.isEmpty()) throw Rejected("Enter a command first.")

        val lower = trimmed.lowercase()

        byName[lower]?.let { exact ->
            if (exact.argument != Argument.NONE) {
                throw Rejected(exact.description)
            }
            return Resolved(exact, exact.shell)
        }

        val name = namesByLength.firstOrNull { lower.startsWith("$it ") }
            ?: throw Rejected(
                "Command not supported. Type help to see available commands."
            )

        val entry = byName.getValue(name)
        val argument = trimmed.substring(name.length).trim()

        return when (entry.argument) {
            Argument.NONE -> throw Rejected(
                "'$name' does not take any extra text."
            )

            Argument.PACKAGE -> {
                if (!ShellSupport.isPackageName(argument)) {
                    throw Rejected(
                        "Enter a valid package name, for example com.example.app."
                    )
                }
                Resolved(entry, entry.shell.replace("%s", ShellSupport.quote(argument)))
            }

            Argument.HOST -> {
                if (!ShellSupport.HOST_NAME.matches(argument)) {
                    throw Rejected("Enter a valid host name or IP address.")
                }
                Resolved(entry, entry.shell.replace("%s", ShellSupport.quote(argument)))
            }
        }
    }

    /** Wraps a catalog command so it runs as root. */
    fun asRoot(shell: String): String = "su -c ${ShellSupport.quote(shell)}"

    fun helpText(rootMode: Boolean): String = buildString {
        appendLine("ULTIMATE DEBUGGING TOOL")
        appendLine(if (rootMode) "ROOT MODE" else "ADB MODE")
        appendLine()
        appendLine("Commands run on the CONNECTED phone and only read information.")

        for ((group, entries) in catalog.groupBy { it.group }) {
            appendLine()
            appendLine(group.uppercase())
            entries.forEach {
                appendLine("${it.name.padEnd(23)} - ${it.description}")
            }
        }

        appendLine()
        appendLine("To change something on the phone, use Repair Tools.")
        append("Some results depend on Android version and manufacturer.")
    }

    /** Catalog in a form the app screens can show as suggestions. */
    fun describe(): List<Map<String, Any>> = catalog.map {
        mapOf(
            "name" to it.name,
            "description" to it.description,
            "group" to it.group,
            "needsArgument" to (it.argument != Argument.NONE)
        )
    }
}
