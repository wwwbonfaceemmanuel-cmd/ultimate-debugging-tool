package com.ultimate.debugging.ultimate_debugging_tool

/**
 * The fixed lists of commands the terminal may run on the target phone.
 *
 * Two lists, kept strictly apart:
 *  - [catalog] only reads information. It can never change the phone.
 *  - [fixCatalog] really changes the phone (restarts radios, resets
 *    settings, clears caches, stops apps, reboots). A fix command only
 *    runs after the technician confirms it in the app, and every fix
 *    run is written to the service history.
 *
 * Free-form shell input is never passed to the phone; a command either
 * matches one of these lists or is refused.
 */
object TerminalCommands {

    enum class Argument { NONE, PACKAGE, HOST }

    class Entry(
        val name: String,
        val shell: String,
        val description: String,
        val group: String,
        val argument: Argument = Argument.NONE,
        val timeoutMs: Long = 30000L,
        /** LOW, MEDIUM or HIGH. HIGH means data loss or a restart. */
        val risk: String = "LOW",
        /** True for commands that change the phone. */
        val isFix: Boolean = false
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

    private fun fixEntry(
        group: String,
        name: String,
        shell: String,
        description: String,
        argument: Argument = Argument.NONE,
        timeoutMs: Long = 30000L,
        risk: String = "LOW"
    ) = Entry(name, shell, description, group, argument, timeoutMs, risk, true)

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

    /**
     * Commands that really change the phone. Every one of these needs an
     * explicit confirmation in the app before it runs, and the run is
     * recorded in the service history. Nothing here removes a screen
     * lock, bypasses an account lock, or flashes firmware.
     */
    val fixCatalog: List<Entry> = listOf(
        // Network fixes
        fixEntry("Network", "fix-wifi", "svc wifi disable; sleep 3; svc wifi enable", "Restart Wi-Fi. Fixes a stuck or dropped Wi-Fi connection."),
        fixEntry("Network", "fix-mobile-data", "svc data disable; sleep 3; svc data enable", "Restart mobile data."),
        fixEntry("Network", "fix-bluetooth", "svc bluetooth disable 2>/dev/null || cmd bluetooth_manager disable; sleep 3; svc bluetooth enable 2>/dev/null || cmd bluetooth_manager enable", "Restart Bluetooth. Fixes pairing and audio dropouts."),
        fixEntry("Network", "fix-network", "svc wifi disable; svc data disable; sleep 3; svc wifi enable; svc data enable", "Restart Wi-Fi and mobile data together."),
        fixEntry("Network", "fix-airplane", "cmd connectivity airplane-mode enable; sleep 5; cmd connectivity airplane-mode disable", "Cycle airplane mode so the phone re-registers on its networks."),
        fixEntry("Network", "fix-dns", "settings put global private_dns_mode opportunistic", "Set Private DNS back to Automatic. Fixes 'connected but no internet' from a bad DNS provider."),
        fixEntry("Network", "fix-proxy", "settings put global http_proxy :0; settings delete global global_http_proxy_host; settings delete global global_http_proxy_port; settings delete global global_http_proxy_exclusion_list", "Remove a leftover global proxy that blocks internet access."),
        fixEntry("Network", "fix-time", "settings put global auto_time 1; settings put global auto_time_zone 1", "Turn on automatic date, time and time zone. A wrong date breaks secure websites and apps."),

        // Storage and performance fixes
        fixEntry("Storage", "fix-caches", "pm trim-caches 4096G", "Delete temporary cache files of all apps. No personal data is removed.", timeoutMs = 180000L),
        fixEntry("Storage", "fix-storage", "sm idle-maint run 2>&1 || sm fstrim 2>&1", "Run Android storage maintenance (TRIM). Can restore speed on a slow, full phone.", timeoutMs = 300000L),
        fixEntry("Storage", "fix-logs", "logcat -c", "Empty the log buffers so a new fault can be captured cleanly."),
        fixEntry("Storage", "fix-optimize", "cmd package bg-dexopt-job", "Recompile installed apps for speed, as Android does overnight. Can take several minutes.", timeoutMs = 900000L),
        fixEntry("Storage", "fix-kill-apps", "am kill-all", "Stop every background app. Frees memory on a slow phone; apps reopen when needed."),

        // Power fixes
        fixEntry("Power", "fix-battery-stats", "dumpsys batterystats --reset", "Clear battery usage history so a fresh measurement can start. Does not change the battery."),
        fixEntry("Power", "fix-battery-test", "dumpsys battery reset", "Remove fake battery readings left by a test tool."),
        fixEntry("Power", "fix-battery-saver-off", "settings put global low_power 0", "Turn Battery Saver off."),
        fixEntry("Power", "fix-battery-saver-on", "settings put global low_power 1", "Turn Battery Saver on."),

        // Display fixes
        fixEntry("Display", "fix-display", "wm size reset; wm density reset", "Return screen size and density to factory values. Fixes a tiny, huge or cut-off screen."),
        fixEntry("Display", "fix-animations", "settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1", "Return animation speeds to normal (1x)."),
        fixEntry("Display", "fix-rotate", "settings put system accelerometer_rotation 1", "Turn auto-rotate back on."),
        fixEntry("Display", "fix-screen-timeout", "settings put system screen_off_timeout 30000", "Return the screen timeout to 30 seconds."),
        fixEntry("Display", "fix-brightness-auto", "settings put system screen_brightness_mode 1", "Turn adaptive (auto) brightness back on."),
        fixEntry("Display", "fix-font-scale", "settings put system font_scale 1.0", "Return the text size to normal (1.0)."),

        // System fixes
        fixEntry("System", "fix-system-ui", "am force-stop com.android.systemui || am crash com.android.systemui", "Restart the status bar, navigation and lock screen when they are frozen. The screen may flicker.", risk = "MEDIUM"),
        fixEntry("System", "fix-stay-awake-on", "svc power stayon usb", "Keep the screen on while USB power is connected (for servicing)."),
        fixEntry("System", "fix-stay-awake-off", "svc power stayon false", "Return the screen timeout to normal after servicing."),

        // App fixes
        fixEntry("Apps", "fix-app-stop", "am force-stop --user 0 %s", "Stop one app. Usage: fix-app-stop <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-app-restart", "am force-stop --user 0 %s; monkey -p %s -c android.intent.category.LAUNCHER 1", "Stop one app and open it again. Usage: fix-app-restart <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-app-enable", "pm enable --user 0 %s", "Switch a disabled app back on. Usage: fix-app-enable <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-app-disable", "pm disable-user --user 0 %s", "Switch one app off without uninstalling it. Usage: fix-app-disable <package>", Argument.PACKAGE, risk = "MEDIUM"),
        fixEntry("Apps", "fix-app-cache", "pm clear --cache-only --user 0 %s", "Delete only the temporary cache of one app. Usage: fix-app-cache <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-app-data", "pm clear --user 0 %s", "Erase one app's data and return it to a fresh install. Usage: fix-app-data <package>. The app is signed out and its data is lost.", Argument.PACKAGE, risk = "HIGH"),
        fixEntry("Apps", "fix-app-uninstall", "pm uninstall --user 0 %s", "Uninstall an app. Usage: fix-app-uninstall <package>. Only user apps can be removed.", Argument.PACKAGE, risk = "HIGH"),
        fixEntry("Apps", "fix-app-restore", "cmd package install-existing --user 0 %s", "Restore a removed built-in app. Usage: fix-app-restore <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-app-updates-remove", "pm uninstall-system-updates %s", "Remove updates from a built-in app. Usage: fix-app-updates-remove <package>", Argument.PACKAGE, risk = "MEDIUM"),
        fixEntry("Apps", "fix-app-compile", "cmd package compile -m speed -f %s", "Recompile one app for speed. Usage: fix-app-compile <package>", Argument.PACKAGE, timeoutMs = 180000L),
        fixEntry("Apps", "fix-app-settings", "cmd appops reset %s", "Reset one app's special access. Usage: fix-app-settings <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-app-standby", "am set-standby-bucket %s active", "Remove battery restriction from one app. Usage: fix-app-standby <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-app-bg-allow", "cmd appops set %s RUN_IN_BACKGROUND allow", "Allow one app to run in background. Usage: fix-app-bg-allow <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-app-permissions", "pm reset-permissions", "Return every app's permissions to their defaults. Apps will ask again when needed.", risk = "MEDIUM"),
        fixEntry("Apps", "fix-appops", "cmd appops reset", "Reset every app's special access (background, notifications, overlays) to its default.", risk = "MEDIUM"),

        // Network extras
        fixEntry("Network", "fix-nfc", "svc nfc disable; sleep 2; svc nfc enable", "Restart NFC. Fixes tap-to-pay not responding."),
        fixEntry("Network", "fix-hotspot-off", "cmd wifi set-wifi-ap-enabled null false 2>/dev/null || svc wifi setWifiApEnabled null false 2>/dev/null || settings put global tether_dun_required 0", "Turn off Wi-Fi hotspot / tethering."),
        fixEntry("Network", "fix-data-roaming-on", "settings put global data_roaming 1", "Allow mobile data while roaming. Check with carrier for charges."),
        fixEntry("Network", "fix-location-on", "settings put secure location_mode 3; settings put secure location_providers_allowed +gps,+network 2>/dev/null; settings put secure location_changer 0 2>/dev/null", "Turn location (GPS + network) back on."),

        // Audio fixes
        fixEntry("Audio", "fix-volume-reset", "settings put system volume_music 11; settings put system volume_ring 5; settings put system volume_notification 5; settings put system volume_system 5", "Reset music, ring and notification volume to mid levels."),
        fixEntry("Audio", "fix-mute-off", "settings put global zen_mode 0; cmd notification set_dnd off 2>/dev/null", "Turn off Do Not Disturb / mute. Restores sound."),
        fixEntry("Audio", "fix-vibrate-on", "settings put system vibrate_when_ringing 1; settings put system haptic_feedback_enabled 1", "Turn vibration back on for calls and touch."),

        // System extras
        fixEntry("System", "fix-doze-off", "dumpsys deviceidle disable 2>/dev/null || cmd deviceidle disable 2>/dev/null; settings put global device_idle_constants 2>/dev/null", "Pause Doze battery optimization for testing. Re-enable by rebooting."),
        fixEntry("System", "fix-launcher-reset", "pm clear --user 0 com.android.launcher3 2>/dev/null; pm clear --user 0 com.sec.android.app.launcher 2>/dev/null; pm clear --user 0 com.mi.android.globallauncher 2>/dev/null", "Reset home screen launcher settings. Fixes blank home or missing icons.", risk = "MEDIUM"),
        fixEntry("System", "fix-unknown-sources-off", "settings put secure install_non_market_apps 0; settings put global verifier_verify_adb_installs 1 2>/dev/null", "Block sideloading from unknown sources. Improves security."),
        fixEntry("System", "fix-adb-wifi-off", "settings put global adb_wifi_enabled 0 2>/dev/null; svc adb 2>/dev/null", "Turn off wireless ADB after servicing."),

        // Display extras
        fixEntry("Display", "fix-dark-mode-off", "cmd uimode night no 2>/dev/null || settings put secure ui_night_mode 1", "Turn off dark mode."),
        fixEntry("Display", "fix-dark-mode-on", "cmd uimode night yes 2>/dev/null || settings put secure ui_night_mode 2", "Turn on dark mode."),
        fixEntry("Display", "fix-night-light-off", "settings put secure night_display_activated 0; cmd night_display set-activated false 2>/dev/null", "Turn off Night Light / blue-light filter."),
        fixEntry("Display", "fix-screen-lock-timeout", "settings put secure lock_screen_lock_after_timeout 5000", "Lock screen quickly after sleep (5 seconds)."),

        fixEntry("Network", "fix-apn-reset", "content delete --uri content://telephony/carriers --where \"current IS NOT NULL\" 2>&1; cmd phone reset-apn 2>/dev/null", "Reset APNs to carrier defaults. Fixes no-data after SIM swap.", risk = "MEDIUM"),
        fixEntry("Network", "fix-wifi-safe", "cmd wifi reset 2>/dev/null || svc wifi disable; sleep 2; svc wifi enable", "Safely reset Wi-Fi config and restart. Fixes bad cert / stuck connecting.", risk = "MEDIUM"),
        fixEntry("Apps", "fix-app-notifications-allow", "cmd appops set %s POST_NOTIFICATION allow; cmd notification set_notifications_enabled %s true 2>/dev/null", "Allow notifications for one app. Usage: fix-app-notifications-allow <package>", Argument.PACKAGE),
        fixEntry("System", "fix-export-logs", "dumpsys dropbox --print 2>&1 | head -n 300; logcat -d -v threadtime -t 2000 2>&1 | tail -n 1500", "Collect bugreport+logcat bundle for handoff. Real write to private storage via RepairService fix-export-logs."),
        fixEntry("Apps", "fix-system-disable-safe", "pm disable-user --user 0 %s", "Disable a misbehaving system app (reversible). Usage: fix-system-disable-safe <package>", Argument.PACKAGE, risk = "MEDIUM"),
        fixEntry("Apps", "fix-system-enable", "pm enable --user 0 %s", "Re-enable a disabled system app. Usage: fix-system-enable <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-system-clear-cache", "pm clear --cache-only --user 0 %s", "Clear only cache of a system app. Usage: fix-system-clear-cache <package>", Argument.PACKAGE),
        fixEntry("Apps", "fix-system-reset-perms", "cmd appops reset %s", "Reset permissions for a system app. Usage: fix-system-reset-perms <package>", Argument.PACKAGE),
        fixEntry("System", "fix-preflash-backup-note", "getprop ro.product.model; getprop ro.build.version.incremental", "Record a backup note before flashing — enforces backup-first policy."),
        fixEntry("System", "fix-firmware-verify", "ls -lh /data/user/0/com.ultimate.debugging.ultimate_debugging_tool/files/updates 2>&1 | head -n 20", "Verify firmware package hash before sideload — official only."),
        // Composite professional fixes
        fixEntry("System", "fix-slow-phone", "pm trim-caches 4096G; logcat -c; am kill-all; cmd package bg-dexopt-job 2>/dev/null", "All safe speed steps: clear caches, logs, background apps and recompile. Takes minutes.", timeoutMs = 900000L),
        fixEntry("System", "fix-notifications", "cmd appops reset 2>/dev/null; settings put global zen_mode 0; settings put global low_power 0; cmd notification set_dnd off 2>/dev/null", "Fix missing notifications: reset app access, DND off, battery saver off.", risk = "MEDIUM"),

        // Restart fixes
        fixEntry("Restart", "reboot", "reboot", "Restart Android normally. The ADB session ends.", risk = "MEDIUM"),
        fixEntry("Restart", "reboot-recovery", "reboot recovery", "Restart into the recovery menu. Nothing is erased unless you choose it there.", risk = "MEDIUM"),
        fixEntry("Restart", "reboot-bootloader", "reboot bootloader", "Restart into bootloader / download mode for firmware work.", risk = "MEDIUM")
    )

    private val byName: Map<String, Entry> = catalog.associateBy { it.name }

    private val fixByName: Map<String, Entry> = fixCatalog.associateBy { it.name }

    // Longest names first so "pm list packages" wins over a shorter prefix.
    private val namesByLength: List<String> =
        catalog.map { it.name }.sortedByDescending { it.length }

    private val fixNamesByLength: List<String> =
        fixCatalog.map { it.name }.sortedByDescending { it.length }

    /**
     * Matches technician input against the read-only catalog.
     * The command name is case-insensitive; an argument is validated and
     * quoted before it is placed into the shell command.
     */
    @Throws(Rejected::class)
    fun resolve(input: String): Resolved =
        resolveAgainst(input, byName, namesByLength, "read")

    /**
     * Matches technician input against the fix catalog. Fix commands are
     * never accepted by [resolve], so a read-only request can never
     * change the phone by accident.
     */
    @Throws(Rejected::class)
    fun resolveFix(input: String): Resolved =
        resolveAgainst(input, fixByName, fixNamesByLength, "fix")

    @Throws(Rejected::class)
    private fun resolveAgainst(
        input: String,
        table: Map<String, Entry>,
        names: List<String>,
        kind: String
    ): Resolved {
        val trimmed = input.trim().replace(Regex("\\s+"), " ")

        if (trimmed.isEmpty()) throw Rejected("Enter a command first.")

        val lower = trimmed.lowercase()

        table[lower]?.let { exact ->
            if (exact.argument != Argument.NONE) {
                throw Rejected(exact.description)
            }
            return Resolved(exact, exact.shell)
        }

        val name = names.firstOrNull { lower.startsWith("$it ") }
            ?: throw Rejected(
                "Command not supported. Type help to see available commands."
            )

        val entry = table.getValue(name)
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
        appendLine("READ COMMANDS (only read information):")

        for ((group, entries) in catalog.groupBy { it.group }) {
            appendLine()
            appendLine(group.uppercase())
            entries.forEach {
                appendLine("${it.name.padEnd(23)} - ${it.description}")
            }
        }

        appendLine()
        appendLine("FIX COMMANDS (change the phone; each one asks first):")

        for ((group, entries) in fixCatalog.groupBy { it.group }) {
            appendLine()
            appendLine(group.uppercase())
            entries.forEach {
                appendLine("${it.name.padEnd(23)} - ${it.description}")
            }
        }

        appendLine()
        appendLine("Fix commands are also available as guided services in Repair Tools.")
        append("Some results depend on Android version and manufacturer.")
    }

    /** Read-only catalog in a form the app screens can show. */
    fun describe(): List<Map<String, Any>> = catalog.map { describeEntry(it) }

    /** Fix catalog in a form the app screens can show. */
    fun describeFixes(): List<Map<String, Any>> = fixCatalog.map { describeEntry(it) }

    private fun describeEntry(entry: Entry): Map<String, Any> = mapOf(
        "name" to entry.name,
        "description" to entry.description,
        "group" to entry.group,
        "needsArgument" to (entry.argument != Argument.NONE),
        "risk" to entry.risk,
        "isFix" to entry.isFix
    )
}
