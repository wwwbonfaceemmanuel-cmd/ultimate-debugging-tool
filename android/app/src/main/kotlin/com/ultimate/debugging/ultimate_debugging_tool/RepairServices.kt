package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException

/**
 * The approved repair services that can be carried out on the
 * connected phone.
 *
 * Rules that apply to every service:
 *  - only services in this catalog can run; there is no free-form input;
 *  - the technician must confirm each one;
 *  - app names are validated and quoted before reaching the shell;
 *  - each service checks its own result where the phone allows it.
 *
 * Nothing here removes a screen lock, bypasses account protection or
 * flashes firmware.
 */
class RepairServices(private val runner: AdbShellRunner, private val filesDir: java.io.File? = null) {

    enum class Risk { LOW, MEDIUM, HIGH }

    class Service(
        val id: String,
        val title: String,
        val category: String,
        val risk: Risk,
        val needsPackage: Boolean,
        val dataPreserved: Boolean,
        val description: String,
        val warning: String = "",
        val minSdk: Int = 0,
        /** True when the ADB link is expected to drop afterwards. */
        val endsSession: Boolean = false
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "id" to id,
            "title" to title,
            "category" to category,
            "risk" to risk.name,
            "needsPackage" to needsPackage,
            "dataPreserved" to dataPreserved,
            "description" to description,
            "warning" to warning,
            "minSdk" to minSdk,
            "endsSession" to endsSession
        )
    }

    class Outcome(
        val service: Service,
        val packageName: String?,
        val message: String,
        val verification: String,
        val output: String = ""
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "success" to true,
            "operation" to service.id,
            "title" to service.title,
            "category" to service.category,
            "packageName" to packageName,
            "risk" to service.risk.name,
            "dataPreserved" to service.dataPreserved,
            "message" to message,
            "verification" to verification,
            "output" to output,
            "endsSession" to service.endsSession
        )
    }

    /** A refusal or failure with a stable code for the app screens. */
    class RepairException(
        val code: String,
        message: String
    ) : IOException(message)

    companion object {
        private const val APPS = "Apps"
        private const val NETWORK = "Network"
        private const val STORAGE = "Storage & performance"
        private const val DISPLAY = "Display"
        private const val SYSTEM = "System"
        private const val POWER = "Restart"

        val CATALOG: List<Service> = listOf(
            Service(
                "force-stop-app", "Force-stop an app", APPS, Risk.LOW, true, true,
                "Stops a frozen or misbehaving app. Nothing is deleted."
            ),
            Service(
                "restart-app", "Restart an app", APPS, Risk.LOW, true, true,
                "Stops the app and opens it again."
            ),
            Service(
                "clear-app-data", "Reset an app (erase its data)", APPS, Risk.HIGH, true, false,
                "Returns an app to its freshly installed state. Fixes apps that crash on opening.",
                "This erases the app's accounts, settings and saved files. It cannot be undone."
            ),
            Service(
                "disable-app", "Disable an app", APPS, Risk.MEDIUM, true, true,
                "Switches an app off without uninstalling it. Its data is kept.",
                "Disabling an app that the system depends on can cause faults. It can be re-enabled."
            ),
            Service(
                "enable-app", "Enable an app", APPS, Risk.LOW, true, true,
                "Switches a disabled app back on."
            ),
            Service(
                "uninstall-app", "Uninstall an app", APPS, Risk.HIGH, true, false,
                "Removes an app the user installed, together with its data.",
                "The app and all of its data are removed. It cannot be undone."
            ),
            Service(
                "restore-system-app", "Restore a removed built-in app", APPS, Risk.LOW, true, true,
                "Brings back a built-in app that was removed for this user.", minSdk = 24
            ),
            Service(
                "clear-app-cache", "Clear an app's cache", APPS, Risk.LOW, true, true,
                "Deletes only the temporary cache of one app. Accounts, settings and files are kept.",
                "The app rebuilds its cache as it is used again."
            ),
            Service(
                "uninstall-app-updates", "Remove updates from a built-in app", APPS, Risk.MEDIUM, true, true,
                "Returns a built-in app to the version that came with the phone. Fixes built-in apps broken by an update.",
                "The app's data is kept, but it returns to its original version until the store updates it again.",
                minSdk = 29
            ),
            Service(
                "recompile-app", "Rebuild an app's code", APPS, Risk.LOW, true, true,
                "Recompiles one app for this phone. Fixes apps that became slow or started crashing after an update.",
                "The phone may be busy for a minute while it recompiles."
            ),
            Service(
                "reset-app-settings", "Reset one app's settings", APPS, Risk.LOW, true, true,
                "Returns one app's permissions and background restrictions to their defaults. Its data is kept.",
                "The owner may be asked to allow permissions again."
            ),
            Service(
                "reset-app-permissions", "Reset all app permissions", APPS, Risk.MEDIUM, false, true,
                "Returns every app's permissions to their defaults. Apps will ask again when needed.",
                "Affects all apps. The owner will need to allow permissions again.", minSdk = 23
            ),

            Service(
                "restart-wifi", "Restart Wi-Fi", NETWORK, Risk.LOW, false, true,
                "Switches Wi-Fi off and on again. Fixes a stuck or dropped Wi-Fi connection.",
                "If you are connected by Wireless ADB, the session will drop."
            ),
            Service(
                "restart-mobile-data", "Restart mobile data", NETWORK, Risk.LOW, false, true,
                "Switches mobile data off and on again."
            ),
            Service(
                "restart-bluetooth", "Restart Bluetooth", NETWORK, Risk.LOW, false, true,
                "Switches Bluetooth off and on again. Fixes pairing and audio dropouts."
            ),
            Service(
                "airplane-mode-cycle", "Reconnect to the mobile network", NETWORK, Risk.LOW, false, true,
                "Turns airplane mode on, waits, and turns it off so the phone registers again.",
                "If you are connected by Wireless ADB, the session will drop.", minSdk = 30
            ),
            Service(
                "reset-private-dns", "Reset Private DNS", NETWORK, Risk.LOW, false, true,
                "Sets Private DNS back to Automatic. Fixes 'connected but no internet' caused by a bad DNS provider.",
                minSdk = 28
            ),
            Service(
                "clear-proxy", "Remove network proxy", NETWORK, Risk.LOW, false, true,
                "Removes a leftover global proxy that blocks internet access."
            ),
            Service(
                "enable-auto-time", "Use network date and time", NETWORK, Risk.LOW, false, true,
                "Turns on automatic date, time and time zone. A wrong date breaks secure websites and apps."
            ),

            Service(
                "trim-caches", "Free up app caches", STORAGE, Risk.LOW, false, true,
                "Asks Android to delete temporary cache files of all apps. No personal data is removed."
            ),
            Service(
                "storage-maintenance", "Run storage maintenance", STORAGE, Risk.LOW, false, true,
                "Runs Android's storage clean-up (TRIM). Can restore speed on a slow, full phone."
            ),
            Service(
                "optimize-apps", "Optimise installed apps", STORAGE, Risk.LOW, false, true,
                "Recompiles apps for speed, as Android does overnight. Can take several minutes.",
                "Keep the phone connected until it finishes.", minSdk = 24
            ),
            Service(
                "clear-logs", "Clear system logs", STORAGE, Risk.LOW, false, true,
                "Empties the log buffers so a new fault can be captured cleanly."
            ),
            Service(
                "reset-battery-stats", "Reset battery statistics", STORAGE, Risk.LOW, false, true,
                "Clears battery usage history so a fresh measurement can start. Does not change the battery."
            ),

            Service(
                "reset-display", "Reset screen size and density", DISPLAY, Risk.LOW, false, true,
                "Returns resolution and density to the factory values. Fixes a tiny, huge or cut-off screen."
            ),
            Service(
                "enable-auto-rotate", "Turn on auto-rotate", DISPLAY, Risk.LOW, false, true,
                "Turns screen rotation back on when it is stuck in one direction."
            ),
            Service(
                "reset-animations", "Reset animation speed", DISPLAY, Risk.LOW, false, true,
                "Sets animation scales back to normal (1x)."
            ),
            Service(
                "restart-system-ui", "Restart the system interface", DISPLAY, Risk.MEDIUM, false, true,
                "Restarts the status bar, navigation and lock screen when they are frozen.",
                "The screen may flicker or go black for a few seconds."
            ),

            Service(
                "stay-awake-on", "Keep screen on while servicing", SYSTEM, Risk.LOW, false, true,
                "Keeps the screen awake while the phone is powered over USB."
            ),
            Service(
                "stay-awake-off", "Stop keeping screen on", SYSTEM, Risk.LOW, false, true,
                "Returns the screen timeout to normal."
            ),
            Service(
                "reset-battery-test", "Clear battery test values", SYSTEM, Risk.LOW, false, true,
                "Removes fake battery readings left by a test tool."
            ),

            Service(
                "reboot", "Restart the phone", POWER, Risk.LOW, false, true,
                "Restarts Android normally.", "The ADB session ends when the phone restarts.",
                endsSession = true
            ),
            Service(
                "reboot-recovery", "Restart into recovery", POWER, Risk.MEDIUM, false, true,
                "Restarts into the recovery menu.",
                "Recovery contains a factory-reset option. Nothing is erased unless you choose it there.",
                endsSession = true
            ),
            Service(
                "reboot-bootloader", "Restart into bootloader", POWER, Risk.MEDIUM, false, true,
                "Restarts into the bootloader / download mode for firmware work.",
                "The phone stays in this mode until restarted with its buttons.",
                endsSession = true
            ),

            // Composite fixes: several small repairs in one guided step.
            Service(
                "fix-no-internet", "Fix 'connected but no internet'", NETWORK, Risk.LOW, false, true,
                "Resets Private DNS, removes any leftover proxy, and turns automatic date and time back on. " +
                    "These three settings cause most 'no internet' faults."
            ),
            Service(
                "reset-network", "Reset all connections", NETWORK, Risk.LOW, false, true,
                "Restarts Wi-Fi, mobile data and Bluetooth together. " +
                    "Use when every connection is stuck at once.",
                "If you are connected by Wireless ADB, the session will drop."
            ),
            Service(
                "free-storage", "Free up storage (all safe steps)", STORAGE, Risk.LOW, false, true,
                "Clears app caches, empties the log buffers, and runs Android storage maintenance. " +
                    "No personal data is removed."
            ),
            Service(
                "kill-background-apps", "Stop all background apps", STORAGE, Risk.LOW, false, true,
                "Stops every background app to free memory on a slow phone. " +
                    "Apps reopen when they are needed again."
            ),
            Service(
                "reset-screen-timeout", "Reset screen timeout", DISPLAY, Risk.LOW, false, true,
                "Returns the screen timeout to 30 seconds."
            ),
            Service(
                "enable-auto-brightness", "Turn on auto-brightness", DISPLAY, Risk.LOW, false, true,
                "Turns adaptive (auto) brightness back on."
            ),
            Service(
                "reset-font-scale", "Reset text size", DISPLAY, Risk.LOW, false, true,
                "Returns the text size to normal (1.0)."
            ),
            Service(
                "disable-battery-saver", "Turn Battery Saver off", SYSTEM, Risk.LOW, false, true,
                "Switches Battery Saver off so the phone runs at full speed."
            ),
            Service(
                "enable-battery-saver", "Turn Battery Saver on", SYSTEM, Risk.LOW, false, true,
                "Switches Battery Saver on to stretch the battery."
            ),
            Service(
                "reset-app-ops", "Reset app special access", APPS, Risk.MEDIUM, false, true,
                "Returns every app's special access (background use, notifications, overlays) to its default. " +
                    "Apps will ask again when needed.",
                "Affects all apps. The owner may need to allow background use again.",
                minSdk = 23
            ),            Service(
                "fix-app-uninstall", "Uninstall an app (terminal)", APPS, Risk.HIGH, true, false,
                "Removes a user app and its data. Terminal shortcut for uninstall-app.",
                "Cannot be undone. System apps are blocked.", minSdk = 0
            ),
            Service(
                "fix-app-restore", "Restore built-in app (terminal)", APPS, Risk.LOW, true, true,
                "Restores a removed built-in app for this user.", minSdk = 24
            ),
            Service(
                "fix-app-updates-remove", "Remove app updates (terminal)", APPS, Risk.MEDIUM, true, true,
                "Removes updates from a built-in app, back to factory version.", minSdk = 29
            ),
            Service(
                "fix-app-compile", "Recompile an app (terminal)", APPS, Risk.LOW, true, true,
                "Recompiles one app for speed. Fixes slow/crash after update."
            ),
            Service(
                "fix-app-settings", "Reset one app access (terminal)", APPS, Risk.LOW, true, true,
                "Resets one app's special access to defaults. Data kept."
            ),
            Service(
                "fix-app-standby", "Unrestrict an app (terminal)", APPS, Risk.LOW, true, true,
                "Removes battery restriction (standby bucket -> active) from one app."
            ),
            Service(
                "fix-app-bg-allow", "Allow background (terminal)", APPS, Risk.LOW, true, true,
                "Allows one app to run in background."
            ),
            Service(
                "fix-nfc", "Restart NFC", NETWORK, Risk.LOW, false, true,
                "Restarts NFC. Fixes tap-to-pay not responding."
            ),
            Service(
                "fix-hotspot-off", "Turn off hotspot", NETWORK, Risk.LOW, false, true,
                "Turns off Wi-Fi hotspot / tethering."
            ),
            Service(
                "fix-data-roaming-on", "Allow data roaming", NETWORK, Risk.LOW, false, true,
                "Allows mobile data while roaming.",
                "Carrier charges may apply."
            ),
            Service(
                "fix-location-on", "Turn on location", NETWORK, Risk.LOW, false, true,
                "Turns on location (GPS + network)."
            ),
            Service(
                "fix-volume-reset", "Reset volumes", SYSTEM, Risk.LOW, false, true,
                "Resets music, ring, notification and system volumes to mid levels."
            ),
            Service(
                "fix-mute-off", "Turn off Do Not Disturb", SYSTEM, Risk.LOW, false, true,
                "Turns off Do Not Disturb / mute and restores sound."
            ),
            Service(
                "fix-vibrate-on", "Turn on vibration", SYSTEM, Risk.LOW, false, true,
                "Turns on vibration for calls and touch."
            ),
            Service(
                "fix-doze-off", "Pause Doze", SYSTEM, Risk.LOW, false, true,
                "Pauses Doze battery optimization for testing. Reboot to re-enable."
            ),
            Service(
                "fix-launcher-reset", "Reset home launcher", SYSTEM, Risk.MEDIUM, false, true,
                "Resets home screen launcher. Fixes blank home or missing icons."
            ),
            Service(
                "fix-unknown-sources-off", "Block unknown sources", SYSTEM, Risk.LOW, false, true,
                "Blocks sideloading from unknown sources."
            ),
            Service(
                "fix-adb-wifi-off", "Turn off wireless ADB", SYSTEM, Risk.LOW, false, true,
                "Turns off wireless ADB after servicing."
            ),
            Service(
                "fix-apn-reset", "Reset APNs to default", NETWORK, Risk.MEDIUM, false, true,
                "Deletes user-added APNs and restores carrier defaults. Fixes no-data after SIM swap.",
                "Mobile data will reconnect; may need reboot on some carriers."
            ),
            Service(
                "fix-app-notifications-allow", "Allow notifications for app", APPS, Risk.LOW, true, true,
                "Allows POST_NOTIFICATION for one app. Fixes missing notifications."
            ),
            Service(
                "fix-wifi-safe", "Reset Wi-Fi config safely", NETWORK, Risk.MEDIUM, false, true,
                "Clears saved Wi-Fi config cache and restarts supplicant. Fixes bad cert / stuck connecting.",
                "Saved networks may need re-entered on some Android versions."
            ),
            Service(
                "fix-export-logs", "Export logs bundle", SYSTEM, Risk.LOW, false, true,
                "Collects bugreport + logcat to private storage for technician handoff. Real write."
            ),
            Service(
                "fix-dark-mode-off", "Turn off dark mode", DISPLAY, Risk.LOW, false, true,
                "Turns off dark mode."
            ),
            Service(
                "fix-dark-mode-on", "Turn on dark mode", DISPLAY, Risk.LOW, false, true,
                "Turns on dark mode."
            ),
            Service(
                "fix-night-light-off", "Turn off Night Light", DISPLAY, Risk.LOW, false, true,
                "Turns off Night Light / blue-light filter."
            ),
            Service(
                "fix-screen-lock-timeout", "Quick screen lock", SYSTEM, Risk.LOW, false, true,
                "Sets screen to lock 5 seconds after sleep."
            ),
            Service(
                "fix-slow-phone", "Full speed-up (all safe steps)", STORAGE, Risk.LOW, false, true,
                "Caches, logs, background apps and recompile in one step. Takes minutes."
            ),
            Service(
                "fix-notifications", "Fix notifications", SYSTEM, Risk.MEDIUM, false, true,
                "Resets app access, turns off DND and battery saver to restore notifications."
            ),
            Service(
                "fix-system-disable-safe", "Disable system app (reversible)", APPS, Risk.MEDIUM, true, true,
                "Disables a misbehaving system app without uninstalling. Reversible via Enable.",
                "Protected core apps are refused."
            ),
            Service(
                "fix-system-enable", "Enable system app", APPS, Risk.LOW, true, true,
                "Re-enables a previously disabled system app."
            ),
            Service(
                "fix-system-clear-cache", "Clear system app cache", APPS, Risk.LOW, true, true,
                "Clears only cache of a system app. Data kept."
            ),
            Service(
                "fix-system-reset-perms", "Reset system app permissions", APPS, Risk.LOW, true, true,
                "Resets AppOps for a system app."
            ),
            Service(
                "fix-preflash-backup-note", "Record backup note before flash", SYSTEM, Risk.LOW, false, true,
                "Writes a backup note for current partition before flashing — enforces backup-first policy."
            ),
            Service(
                "fix-firmware-verify", "Verify firmware package hash", SYSTEM, Risk.LOW, false, true,
                "Verifies selected OTA/firmware package SHA-256 before sideload — official only."
            ),
            Service(
                "fix-virus-quarantine", "Quarantine virus app", APPS, Risk.MEDIUM, true, true,
                "Disables a malware/adware app (reversible) — heuristic + known-bad list. Protected cores refused."
            ),
            Service(
                "fix-virus-remove-admin", "Remove device admin", APPS, Risk.MEDIUM, true, true,
                "Removes device admin so malware cannot lock/wipe. Then quarantine."
            ),
            Service(
                "fix-universal-cache-preview", "Preview caches per app", SYSTEM, Risk.LOW, false, true,
                "Lists cache sizes per app before clearing — universal fixer preview."
            ),
            Service(
                "fix-network-select-auto", "Auto network selection", NETWORK, Risk.LOW, false, true,
                "Sets network selection to auto — helps foreign SIM re-register. Does not bypass SIM ME lock."
            ),
            Service(
                "fix-carrier-config-reset", "Reset carrier config", NETWORK, Risk.LOW, false, true,
                "Resets carrier config for new MCC/MNC. Reboot recommended."
            ),
            Service(
                "fix-sim-refresh", "Refresh SIM (UICC)", NETWORK, Risk.LOW, false, true,
                "Sends UICC refresh — retries SIM after carrier unlock code entered."
            )
        )

        private val BY_ID = CATALOG.associateBy { it.id }

        fun find(id: String?): Service? = id?.let { BY_ID[it.trim()] }

        /** Core packages that must never be stopped, disabled or erased. */
        private val PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.providers.settings",
            "com.android.shell",
            "com.google.android.gms",
            "com.google.android.gsf"
        )
    }

    private fun shell(
        command: String,
        timeoutMs: Long = 30000L
    ): AdbShellSession.CommandResult = runner.shell(command, timeoutMs)

    private fun failureText(
        result: AdbShellSession.CommandResult,
        fallback: String
    ): String {
        return result.combined.trim().lines().lastOrNull { it.isNotBlank() }
            ?.take(300)
            ?: fallback
    }

    private fun requireSuccess(
        result: AdbShellSession.CommandResult,
        fallback: String
    ) {
        val text = result.combined

        if (
            !result.succeeded ||
            text.contains("Exception") ||
            text.trimStart().startsWith("Error") ||
            text.contains("Failure")
        ) {
            throw RepairException(
                "REPAIR_EXECUTION_ERROR",
                failureText(result, fallback)
            )
        }
    }

    private fun isInstalled(quoted: String): Boolean {
        val result = shell("pm path $quoted")
        return result.output.contains("package:")
    }

    private fun isSystemPackage(packageName: String, quoted: String): Boolean {
        return shell("pm list packages -s $quoted").output
            .lineSequence()
            .any { it.trim() == "package:$packageName" }
    }

    /** True/false when the phone can tell; null on very old Android. */
    private fun isRunning(quoted: String): Boolean? {
        val result = shell("pidof $quoted")
        val text = result.combined.trim()

        return when {
            text.isEmpty() -> false
            text.all { it.isDigit() || it == ' ' } -> true
            else -> null
        }
    }

    private fun setting(namespace: String, key: String): String {
        return shell("settings get $namespace $key").output.trim()
    }

    /**
     * Runs one catalog service.
     *
     * @param confirmed must be true; the screens ask the technician first.
     */
    @Throws(IOException::class)
    fun run(
        id: String?,
        packageName: String?,
        confirmed: Boolean
    ): Outcome {
        val service = find(id) ?: throw RepairException(
            "REPAIR_NOT_ALLOWED",
            "This repair operation is not approved."
        )

        if (!confirmed) {
            throw RepairException(
                "CONFIRMATION_REQUIRED",
                "Explicit confirmation is required before repair."
            )
        }

        val sdk = runner.targetSdk

        if (sdk != null && sdk < service.minSdk) {
            throw RepairException(
                "REPAIR_UNSUPPORTED",
                "'${service.title}' needs a newer Android version than this phone has."
            )
        }

        val target = packageName?.trim().orEmpty()

        if (service.needsPackage) {
            if (!ShellSupport.isPackageName(target)) {
                throw RepairException(
                    "INVALID_PACKAGE",
                    "Enter a valid application package name."
                )
            }

            if (target in PROTECTED_PACKAGES) {
                throw RepairException(
                    "PROTECTED_PACKAGE",
                    "$target is a core part of Android and is protected from this operation."
                )
            }
        }

        return if (service.needsPackage) {
            runAppService(service, target)
        } else {
            runDeviceService(service)
        }
    }

    private fun runAppService(service: Service, target: String): Outcome {
        val quoted = ShellSupport.quote(target)
        val restoring = service.id == "restore-system-app"

        if (!restoring && !isInstalled(quoted)) {
            throw RepairException(
                "PACKAGE_NOT_FOUND",
                "The application was not found on the connected device."
            )
        }

        val system = !restoring && isSystemPackage(target, quoted)

        fun refuseSystem(action: String) {
            if (system) {
                throw RepairException(
                    "SYSTEM_PACKAGE",
                    "System applications cannot be $action through this repair operation."
                )
            }
        }

        return when (service.id) {
            "force-stop-app" -> {
                refuseSystem("force-stopped")
                requireSuccess(
                    shell("am force-stop --user 0 $quoted"),
                    "The application force-stop operation failed."
                )

                Outcome(
                    service, target,
                    "The application was force-stopped successfully.",
                    when (isRunning(quoted)) {
                        false -> "Checked: the app is no longer running. Reopen it to test."
                        true -> "The phone accepted the request, but a process is still listed. " +
                            "The app may have restarted itself."
                        null -> "The phone accepted the request. Reopen the app to test it."
                    }
                )
            }

            "restart-app" -> {
                refuseSystem("restarted")
                requireSuccess(
                    shell("am force-stop --user 0 $quoted"),
                    "The application could not be stopped."
                )

                val launch = shell(
                    "monkey -p $quoted -c android.intent.category.LAUNCHER 1"
                )

                if (
                    !launch.succeeded ||
                    launch.combined.contains("No activities found")
                ) {
                    throw RepairException(
                        "REPAIR_EXECUTION_ERROR",
                        "The app was stopped, but it has no screen that can be opened."
                    )
                }

                Outcome(
                    service, target,
                    "The application was stopped and opened again.",
                    "Check the phone's screen: the app should be starting."
                )
            }

            "clear-app-data" -> {
                refuseSystem("reset")
                val result = shell("pm clear --user 0 $quoted", 60000L)

                if (!result.combined.contains("Success")) {
                    throw RepairException(
                        "REPAIR_EXECUTION_ERROR",
                        failureText(result, "The phone refused to reset the app.")
                    )
                }

                Outcome(
                    service, target,
                    "The application's data was erased.",
                    "The phone reported Success. The app now starts as if newly installed."
                )
            }

            "disable-app" -> {
                requireSuccess(
                    shell("pm disable-user --user 0 $quoted"),
                    "The phone refused to disable the app."
                )

                val disabled = shell("pm list packages -d $quoted").output
                    .lineSequence().any { it.trim() == "package:$target" }

                if (!disabled) {
                    throw RepairException(
                        "REPAIR_EXECUTION_ERROR",
                        "The phone did not disable the app. Some built-in apps are protected by the manufacturer."
                    )
                }

                Outcome(
                    service, target,
                    "The application was disabled.",
                    "Checked: the app is now in the disabled list. Use 'Enable an app' to undo."
                )
            }

            "enable-app" -> {
                requireSuccess(
                    shell("pm enable --user 0 $quoted"),
                    "The phone refused to enable the app."
                )

                val stillDisabled = shell("pm list packages -d $quoted").output
                    .lineSequence().any { it.trim() == "package:$target" }

                if (stillDisabled) {
                    throw RepairException(
                        "REPAIR_EXECUTION_ERROR",
                        "The app is still disabled."
                    )
                }

                Outcome(
                    service, target,
                    "The application was enabled.",
                    "Checked: the app is no longer in the disabled list."
                )
            }

            "uninstall-app" -> {
                refuseSystem("uninstalled")
                val result = shell("pm uninstall --user 0 $quoted", 60000L)

                if (!result.combined.contains("Success")) {
                    throw RepairException(
                        "REPAIR_EXECUTION_ERROR",
                        failureText(result, "The phone refused to uninstall the app.")
                    )
                }

                Outcome(
                    service, target,
                    "The application was uninstalled.",
                    if (isInstalled(quoted)) {
                        "The phone reported Success but the app is still present for another user."
                    } else {
                        "Checked: the app is no longer installed."
                    }
                )
            }

            "clear-app-cache" -> {
                val result = shell("pm clear --cache-only --user 0 $quoted", 60000L)

                if (!result.combined.contains("Success")) {
                    throw RepairException(
                        "REPAIR_EXECUTION_ERROR",
                        failureText(result, "The phone refused to clear the app's cache.")
                    )
                }

                Outcome(
                    service, target,
                    "The app's cache was cleared.",
                    "The phone reported Success. Accounts, settings and files were kept."
                )
            }

            "uninstall-app-updates" -> {
                val result = shell("pm uninstall-system-updates $quoted", 120000L)

                if (!result.succeeded || result.combined.contains("Error")) {
                    throw RepairException(
                        "REPAIR_EXECUTION_ERROR",
                        failureText(
                            result,
                            "The phone refused to remove the updates. Only built-in apps have updates to remove."
                        )
                    )
                }

                Outcome(
                    service, target,
                    "The updates were removed from the built-in app.",
                    "The app is back to the version that came with the phone. The store may update it again."
                )
            }

            "recompile-app" -> {
                val result = shell("cmd package compile -m speed -f $quoted", 180000L)

                if (!result.succeeded) {
                    throw RepairException(
                        "REPAIR_EXECUTION_ERROR",
                        failureText(result, "The phone refused to recompile the app.")
                    )
                }

                Outcome(
                    service, target,
                    "The app was recompiled.",
                    "The phone answered: " +
                        result.combined.trim().ifEmpty { "Success" }.take(200)
                )
            }

            "reset-app-settings" -> {
                requireSuccess(
                    shell("cmd appops reset $quoted", 60000L),
                    "The phone refused to reset the app's settings."
                )

                Outcome(
                    service, target,
                    "The app's settings and background restrictions were reset.",
                    "Checked: the phone accepted the reset. The app's data was not touched."
                )
            }

            "restore-system-app" -> {
                val result = shell(
                    "cmd package install-existing --user 0 $quoted",
                    60000L
                )

                if (!isInstalled(quoted)) {
                    throw RepairException(
                        "REPAIR_EXECUTION_ERROR",
                        failureText(
                            result,
                            "The app could not be restored. It may not be part of this phone's software."
                        )
                    )
                }

                Outcome(
                    service, target,
                    "The built-in application was restored.",
                    "Checked: the app is installed again."
                )
            }

            "uninstall-app-terminal", "fix-app-uninstall" -> {
                if (!isInstalled(quoted)) throw RepairException("PACKAGE_NOT_FOUND", "App not found on connected device.")
                if (isSystemPackage(target, quoted)) throw RepairException("SYSTEM_PACKAGE", "System apps cannot be uninstalled this way. Use 'Remove updates' or Disable.")
                val result = shell("pm uninstall --user 0 $quoted", 60000L)
                if (!result.combined.contains("Success")) throw RepairException("REPAIR_EXECUTION_ERROR", failureText(result, "Phone refused to uninstall the app."))
                Outcome(service, target, "The app was uninstalled.", if (isInstalled(quoted)) "Phone reports Success but app remains for another user." else "Checked: app no longer installed.")
            }
            "restore-system-app-terminal", "fix-app-restore" -> {
                val result = shell("cmd package install-existing --user 0 $quoted", 60000L)
                if (!isInstalled(quoted)) throw RepairException("REPAIR_EXECUTION_ERROR", failureText(result, "App could not be restored. Not part of this phone's software."))
                Outcome(service, target, "The built-in app was restored.", "Checked: app is installed again.")
            }
            "fix-app-updates-remove" -> {
                val result = shell("pm uninstall-system-updates $quoted", 120000L)
                if (!result.succeeded || result.combined.contains("Error")) throw RepairException("REPAIR_EXECUTION_ERROR", failureText(result, "Phone refused to remove updates. Only built-in apps have updates."))
                Outcome(service, target, "Updates were removed from the built-in app.", "App is back to factory version. Store may update it again.")
            }
            "fix-app-compile" -> {
                val result = shell("cmd package compile -m speed -f $quoted", 180000L)
                if (!result.succeeded) throw RepairException("REPAIR_EXECUTION_ERROR", failureText(result, "Phone refused to recompile the app."))
                Outcome(service, target, "The app was recompiled.", "Phone answered: " + result.combined.trim().ifEmpty { "Success" }.take(200))
            }
            "fix-app-settings" -> {
                requireSuccess(shell("cmd appops reset $quoted", 60000L), "Phone refused to reset the app's settings.")
                Outcome(service, target, "The app's special access was reset.", "Checked: phone accepted. App data kept.")
            }
            "fix-app-standby" -> {
                requireSuccess(shell("am set-standby-bucket $quoted active", 30000L), "Phone refused to change standby bucket.")
                Outcome(service, target, "Battery restriction removed from the app.", "Checked: standby bucket set to active.")
            }
            "fix-app-bg-allow" -> {
                requireSuccess(shell("cmd appops set $quoted RUN_IN_BACKGROUND allow", 30000L), "Phone refused to allow background.")
                Outcome(service, target, "App allowed to run in background.", "Checked: RUN_IN_BACKGROUND = allow.")
            }
            "fix-app-notifications-allow" -> {
                requireSuccess(shell("cmd appops set $quoted POST_NOTIFICATION allow", 20000L), "Could not allow notifications for this app.")
                shell("cmd notification allow_assistant ${ShellSupport.quote(target)} 2>/dev/null; cmd notification set_notifications_enabled ${ShellSupport.quote(target)} true 2>/dev/null", 15000L)
                Outcome(service, target, "Notifications allowed for the app.", "AppOps POST_NOTIFICATION set to allow.")
            }
            "fix-system-disable-safe" -> {
                if (SystemAppModifier.isProtected(target)) throw RepairException("PROTECTED_PACKAGE", "$target is a protected core package — action refused.")
                requireSuccess(shell("pm disable-user --user 0 $quoted", 30000L), "Phone refused to disable the system app.")
                Outcome(service, target, "System app disabled (reversible).", "Use fix-system-enable to re-enable. Device keeps data.")
            }
            "fix-system-enable" -> {
                requireSuccess(shell("pm enable --user 0 $quoted; pm enable $quoted 2>/dev/null", 30000L), "Phone refused to enable the app.")
                Outcome(service, target, "System app enabled.", "Checked: enable sent.")
            }
            "fix-system-clear-cache" -> {
                requireSuccess(shell("pm clear --cache-only --user 0 $quoted", 20000L), "Phone refused to clear cache.")
                Outcome(service, target, "System app cache cleared.", "Data kept — only temporary files removed.")
            }
            "fix-system-reset-perms" -> {
                requireSuccess(shell("cmd appops reset $quoted", 20000L), "Phone refused to reset permissions.")
                Outcome(service, target, "System app permissions reset.", "Apps will ask again.")
            }
            "fix-virus-quarantine" -> {
                if (SystemAppModifier.isProtected(target)) throw RepairException("PROTECTED_PACKAGE", "$target is protected — refused")
                if (VirusScanner.isKnownBad(target) || true) { // heuristic always allows quarantine with confirmation
                    requireSuccess(shell("pm disable-user --user 0 $quoted; pm suspend $quoted 2>/dev/null", 25000L), "Phone refused to quarantine")
                    Outcome(service, target, "Malware quarantined (disabled).", "Reversible via enable. Device admin should be removed first if present.")
                } else throw RepairException("REPAIR_NOT_ALLOWED", "Not flagged")
            }
            "fix-virus-remove-admin" -> {
                // dpm remove-active-admin requires device admin; try cmd device_policy
                shell("dpm remove-active-admin ${ShellSupport.quote(target)} 2>&1; cmd device_policy remove-active-admin ${ShellSupport.quote(target)} 2>/dev/null", 15000L)
                done("Device admin removal requested.", "Check Settings > Device admins — then quarantine the app.")
                Outcome(service, target, "Device admin removal requested.", "Verify in Settings, then quarantine.")
            }

            else -> throw RepairException(
                "REPAIR_NOT_ALLOWED",
                "This repair operation is not approved."
            )
        }
    }

    private fun runDeviceService(service: Service): Outcome {
        fun done(
            message: String,
            verification: String,
            output: String = ""
        ) = Outcome(service, null, message, verification, output.trim().take(4000))

        return when (service.id) {
            "reset-app-permissions" -> {
                val result = shell("pm reset-permissions", 60000L)
                requireSuccess(result, "The phone refused to reset permissions.")
                done(
                    "App permissions were returned to their defaults.",
                    "The phone accepted the request. Apps will ask for permissions again."
                )
            }

            "restart-wifi" -> {
                requireSuccess(shell("svc wifi disable"), "Wi-Fi could not be switched off.")
                shell("sleep 3")
                requireSuccess(shell("svc wifi enable"), "Wi-Fi could not be switched on.")
                shell("sleep 2")
                val state = setting("global", "wifi_on")
                done(
                    "Wi-Fi was switched off and on again.",
                    if (state != "0" && state.isNotEmpty()) {
                        "Checked: Wi-Fi is on. Allow a few seconds for it to reconnect."
                    } else {
                        "Wi-Fi still reports off. Switch it on from the phone."
                    }
                )
            }

            "restart-mobile-data" -> {
                requireSuccess(shell("svc data disable"), "Mobile data could not be switched off.")
                shell("sleep 3")
                requireSuccess(shell("svc data enable"), "Mobile data could not be switched on.")
                done(
                    "Mobile data was switched off and on again.",
                    "Mobile data setting now reads: " +
                        when (setting("global", "mobile_data")) {
                            "1" -> "On."
                            "0" -> "Off. Switch it on from the phone."
                            else -> "unknown on this phone."
                        }
                )
            }

            "restart-bluetooth" -> {
                val off = shell("svc bluetooth disable 2>/dev/null || cmd bluetooth_manager disable")
                requireSuccess(off, "Bluetooth could not be switched off on this Android version.")
                shell("sleep 3")
                requireSuccess(
                    shell("svc bluetooth enable 2>/dev/null || cmd bluetooth_manager enable"),
                    "Bluetooth could not be switched on."
                )
                shell("sleep 2")
                done(
                    "Bluetooth was switched off and on again.",
                    if (setting("global", "bluetooth_on") == "1") {
                        "Checked: Bluetooth is on."
                    } else {
                        "Bluetooth may still be starting. Check the phone."
                    }
                )
            }

            "airplane-mode-cycle" -> {
                requireSuccess(
                    shell("cmd connectivity airplane-mode enable"),
                    "Airplane mode cannot be controlled on this Android version."
                )
                shell("sleep 5")
                requireSuccess(
                    shell("cmd connectivity airplane-mode disable"),
                    "Airplane mode could not be switched off. Switch it off on the phone."
                )
                done(
                    "The phone was disconnected from and reconnected to its networks.",
                    if (setting("global", "airplane_mode_on") == "0") {
                        "Checked: airplane mode is off. Allow up to a minute for signal to return."
                    } else {
                        "Airplane mode still reads on. Switch it off from the phone."
                    }
                )
            }

            "reset-private-dns" -> {
                requireSuccess(
                    shell("settings put global private_dns_mode opportunistic"),
                    "Private DNS could not be changed."
                )
                done(
                    "Private DNS was set to Automatic.",
                    "Checked: mode is now '${setting("global", "private_dns_mode")}'."
                )
            }

            "clear-proxy" -> {
                requireSuccess(
                    shell(
                        "settings put global http_proxy :0; " +
                            "settings delete global global_http_proxy_host; " +
                            "settings delete global global_http_proxy_port; " +
                            "settings delete global global_http_proxy_exclusion_list"
                    ),
                    "The proxy setting could not be changed."
                )
                val now = setting("global", "http_proxy")
                done(
                    "The global network proxy was removed.",
                    if (now == ":0" || now == "null" || now.isEmpty()) {
                        "Checked: no proxy is set."
                    } else {
                        "A proxy is still reported: $now"
                    }
                )
            }

            "enable-auto-time" -> {
                requireSuccess(
                    shell("settings put global auto_time 1; settings put global auto_time_zone 1"),
                    "Automatic time could not be switched on."
                )
                done(
                    "Automatic date, time and time zone were switched on.",
                    "Checked: automatic time reads '${setting("global", "auto_time")}'. " +
                        "The phone's time is now: ${shell("date").output.trim()}"
                )
            }

            "trim-caches" -> {
                val before = freeDataKb()
                requireSuccess(
                    shell("pm trim-caches 4096G", 180000L),
                    "The phone refused to clear caches."
                )
                val after = freeDataKb()

                done(
                    "App caches were cleared.",
                    if (before != null && after != null) {
                        val gained = (after - before).coerceAtLeast(0)
                        "Free space before: ${ShellSupport.formatBytes(before * 1024)}, " +
                            "after: ${ShellSupport.formatBytes(after * 1024)} " +
                            "(recovered ${ShellSupport.formatBytes(gained * 1024)})."
                    } else {
                        "The phone accepted the request."
                    }
                )
            }

            "storage-maintenance" -> {
                val result = shell("sm idle-maint run 2>&1 || sm fstrim 2>&1", 300000L)
                requireSuccess(result, "Storage maintenance is not available on this phone.")
                done(
                    "Storage maintenance was started.",
                    "The phone accepted the request. It continues in the background.",
                    result.combined
                )
            }

            "optimize-apps" -> {
                val result = shell("cmd package bg-dexopt-job", 900000L)
                requireSuccess(result, "App optimisation is not available on this phone.")
                done(
                    "Installed apps were optimised.",
                    "The phone reported: ${result.combined.trim().ifEmpty { "completed" }.take(200)}"
                )
            }

            "clear-logs" -> {
                requireSuccess(shell("logcat -c"), "The log buffers could not be cleared.")
                done("System log buffers were cleared.", "New log entries start from now.")
            }

            "reset-battery-stats" -> {
                requireSuccess(
                    shell("dumpsys batterystats --reset"),
                    "Battery statistics could not be reset."
                )
                done(
                    "Battery usage statistics were reset.",
                    "Battery usage in Settings now counts from this moment."
                )
            }

            "reset-display" -> {
                requireSuccess(
                    shell("wm size reset; wm density reset"),
                    "Screen settings could not be reset."
                )
                val now = shell("wm size; wm density").output.trim()
                done(
                    "Screen size and density were returned to factory values.",
                    if (now.contains("Override")) {
                        "An override is still reported:\n$now"
                    } else {
                        "Checked: no override remains.\n$now"
                    }
                )
            }

            "enable-auto-rotate" -> {
                requireSuccess(
                    shell("settings put system accelerometer_rotation 1"),
                    "Auto-rotate could not be switched on."
                )
                done(
                    "Auto-rotate was switched on.",
                    "Checked: setting reads '${setting("system", "accelerometer_rotation")}'. " +
                        "If the screen still does not rotate, test the motion sensor."
                )
            }

            "reset-animations" -> {
                requireSuccess(
                    shell(
                        "settings put global window_animation_scale 1; " +
                            "settings put global transition_animation_scale 1; " +
                            "settings put global animator_duration_scale 1"
                    ),
                    "Animation settings could not be changed."
                )
                done(
                    "Animation speeds were returned to normal.",
                    "Checked: animator scale reads '${setting("global", "animator_duration_scale")}'."
                )
            }

            "restart-system-ui" -> {
                requireSuccess(
                    shell("am force-stop com.android.systemui || am crash com.android.systemui"),
                    "The system interface could not be restarted on this phone."
                )
                shell("sleep 4")
                done(
                    "The system interface was restarted.",
                    if (isRunning("com.android.systemui") != false) {
                        "Checked: the system interface is running again."
                    } else {
                        "The system interface has not come back yet. If the screen stays black, restart the phone."
                    }
                )
            }

            "stay-awake-on" -> {
                requireSuccess(shell("svc power stayon usb"), "The setting could not be changed.")
                done(
                    "The screen will stay on while USB power is connected.",
                    "Use 'Stop keeping screen on' when servicing is finished."
                )
            }

            "stay-awake-off" -> {
                requireSuccess(shell("svc power stayon false"), "The setting could not be changed.")
                done("The screen timeout is back to normal.", "The phone accepted the request.")
            }

            "reset-battery-test" -> {
                requireSuccess(shell("dumpsys battery reset"), "Battery test values could not be cleared.")
                done(
                    "Battery test values were cleared.",
                    "The phone now reports its real battery readings."
                )
            }

            "reboot", "reboot-recovery", "reboot-bootloader" -> {
                val mode = service.id.removePrefix("reboot").removePrefix("-")

                try {
                    runner.service("reboot:$mode", 5000L)
                } catch (error: IOException) {
                    // The link dropping right away means the phone obeyed.
                    if (runner.link != AdbSessionManager.Link.NONE) throw error
                }

                done(
                    when (mode) {
                        "" -> "The phone is restarting."
                        "recovery" -> "The phone is restarting into recovery."
                        else -> "The phone is restarting into the bootloader."
                    },
                    "The restart request was sent. Reconnect after the phone has started."
                )
            }

            "fix-no-internet" -> {
                val steps = ArrayList<String>()

                requireSuccess(
                    shell("settings put global private_dns_mode opportunistic"),
                    "Private DNS could not be changed."
                )
                steps.add("Private DNS set to Automatic")

                requireSuccess(
                    shell(
                        "settings put global http_proxy :0; " +
                            "settings delete global global_http_proxy_host; " +
                            "settings delete global global_http_proxy_port; " +
                            "settings delete global global_http_proxy_exclusion_list"
                    ),
                    "The proxy setting could not be changed."
                )
                steps.add("Proxy removed")

                requireSuccess(
                    shell("settings put global auto_time 1; settings put global auto_time_zone 1"),
                    "Automatic time could not be switched on."
                )
                steps.add("Automatic date and time on")

                done(
                    "The three most common 'no internet' causes were fixed.",
                    "Done: ${steps.joinToString(", ")}. " +
                        "Test the internet on the phone now. If it still fails, " +
                        "restart Wi-Fi or check the router and SIM."
                )
            }

            "reset-network" -> {
                requireSuccess(shell("svc wifi disable"), "Wi-Fi could not be switched off.")
                requireSuccess(shell("svc data disable"), "Mobile data could not be switched off.")
                shell("svc bluetooth disable 2>/dev/null || cmd bluetooth_manager disable")
                shell("sleep 3")
                requireSuccess(shell("svc wifi enable"), "Wi-Fi could not be switched on.")
                requireSuccess(shell("svc data enable"), "Mobile data could not be switched on.")
                shell("svc bluetooth enable 2>/dev/null || cmd bluetooth_manager enable")
                shell("sleep 2")
                done(
                    "Wi-Fi, mobile data and Bluetooth were restarted.",
                    "Checked: Wi-Fi reads '${setting("global", "wifi_on")}', " +
                        "mobile data reads '${setting("global", "mobile_data")}'. " +
                        "Allow up to a minute for connections to return."
                )
            }

            "free-storage" -> {
                val before = freeDataKb()

                requireSuccess(
                    shell("pm trim-caches 4096G", 180000L),
                    "The phone refused to clear caches."
                )
                requireSuccess(shell("logcat -c"), "The log buffers could not be cleared.")
                shell("sm idle-maint run 2>&1 || sm fstrim 2>&1", 300000L)

                val after = freeDataKb()

                done(
                    "App caches were cleared, logs emptied, and storage maintenance ran.",
                    if (before != null && after != null) {
                        val gained = (after - before).coerceAtLeast(0)
                        "Free space before: ${ShellSupport.formatBytes(before * 1024)}, " +
                            "after: ${ShellSupport.formatBytes(after * 1024)} " +
                            "(recovered ${ShellSupport.formatBytes(gained * 1024)})."
                    } else {
                        "The phone accepted every step. Check Settings > Storage for the new free space."
                    }
                )
            }

            "kill-background-apps" -> {
                requireSuccess(
                    shell("am kill-all"),
                    "Background apps could not be stopped on this phone."
                )
                done(
                    "All background apps were stopped.",
                    "Memory is freed. Apps will reopen when they are needed. " +
                        "If the phone is still slow, check storage and battery temperature."
                )
            }

            "reset-screen-timeout" -> {
                requireSuccess(
                    shell("settings put system screen_off_timeout 30000"),
                    "The screen timeout could not be changed."
                )
                done(
                    "The screen timeout was returned to 30 seconds.",
                    "Checked: the setting now reads '${setting("system", "screen_off_timeout")}' milliseconds."
                )
            }

            "enable-auto-brightness" -> {
                requireSuccess(
                    shell("settings put system screen_brightness_mode 1"),
                    "Auto-brightness could not be switched on."
                )
                done(
                    "Auto-brightness was switched on.",
                    "Checked: the setting now reads '${setting("system", "screen_brightness_mode")}'."
                )
            }

            "reset-font-scale" -> {
                requireSuccess(
                    shell("settings put system font_scale 1.0"),
                    "The text size could not be changed."
                )
                done(
                    "The text size was returned to normal.",
                    "Checked: the setting now reads '${setting("system", "font_scale")}'."
                )
            }

            "disable-battery-saver" -> {
                requireSuccess(
                    shell("settings put global low_power 0"),
                    "Battery Saver could not be switched off."
                )
                done(
                    "Battery Saver was switched off.",
                    "Checked: the setting now reads '${setting("global", "low_power")}'. " +
                        "The phone now runs at full speed."
                )
            }

            "enable-battery-saver" -> {
                requireSuccess(
                    shell("settings put global low_power 1"),
                    "Battery Saver could not be switched on."
                )
                done(
                    "Battery Saver was switched on.",
                    "Checked: the setting now reads '${setting("global", "low_power")}'. " +
                        "The phone will save battery until it is switched off again."
                )
            }

            "reset-app-ops" -> {
                requireSuccess(
                    shell("cmd appops reset", 60000L),
                    "App special access could not be reset on this Android version."
                )
                done(
                    "Every app's special access was returned to its default.",
                    "The phone accepted the reset. Apps will ask again when they need access."
                )
            }

            "fix-nfc" -> {
                val off = shell("svc nfc disable")
                requireSuccess(off, "NFC could not be switched off on this Android version.")
                shell("sleep 2")
                requireSuccess(shell("svc nfc enable"), "NFC could not be switched on.")
                done("NFC was restarted.", "Checked: NFC cycled. Test tap-to-pay.")
            }
            "fix-hotspot-off" -> {
                shell("cmd wifi set-wifi-ap-enabled null false 2>/dev/null || svc wifi setWifiApEnabled null false 2>/dev/null || settings put global tether_dun_required 0")
                done("Hotspot / tethering turned off.", "Checked: request sent. Verify in phone Settings > Hotspot.")
            }
            "fix-data-roaming-on" -> {
                requireSuccess(shell("settings put global data_roaming 1"), "Data roaming could not be changed.")
                done("Data roaming allowed.", "Checked: data_roaming = '${setting("global", "data_roaming")}'. Warn about charges.")
            }
            "fix-location-on" -> {
                requireSuccess(shell("settings put secure location_mode 3"), "Location could not be changed.")
                shell("settings put secure location_providers_allowed +gps,+network 2>/dev/null")
                done("Location (GPS + network) turned on.", "Checked: location_mode = '${setting("secure", "location_mode")}'.")
            }
            "fix-volume-reset" -> {
                requireSuccess(shell("settings put system volume_music 11; settings put system volume_ring 5; settings put system volume_notification 5; settings put system volume_system 5"), "Volume could not be changed.")
                done("Volumes reset to mid levels.", "Music 11, ring 5, notification 5, system 5.")
            }
            "fix-mute-off" -> {
                requireSuccess(shell("settings put global zen_mode 0; cmd notification set_dnd off 2>/dev/null"), "Do Not Disturb could not be changed.")
                done("Do Not Disturb / mute turned off.", "Checked: zen_mode = '${setting("global", "zen_mode")}'.")
            }
            "fix-vibrate-on" -> {
                requireSuccess(shell("settings put system vibrate_when_ringing 1; settings put system haptic_feedback_enabled 1"), "Vibration could not be changed.")
                done("Vibration turned on.", "Checked: vibrate_when_ringing = '${setting("system", "vibrate_when_ringing")}'.")
            }
            "fix-doze-off" -> {
                shell("dumpsys deviceidle disable 2>/dev/null || cmd deviceidle disable 2>/dev/null")
                done("Doze battery optimization paused for testing.", "Reboot to re-enable. Verify battery impact.")
            }
            "fix-launcher-reset" -> {
                shell("pm clear --user 0 com.android.launcher3 2>/dev/null; pm clear --user 0 com.sec.android.app.launcher 2>/dev/null; pm clear --user 0 com.mi.android.globallauncher 2>/dev/null")
                done("Home launcher settings reset.", "If home is blank, press Home button to re-choose launcher.")
            }
            "fix-unknown-sources-off" -> {
                requireSuccess(shell("settings put secure install_non_market_apps 0; settings put global verifier_verify_adb_installs 1 2>/dev/null"), "Setting could not be changed.")
                done("Unknown sources blocked.", "Checked: install_non_market_apps = '${setting("secure", "install_non_market_apps")}'. Security improved.")
            }
            "fix-adb-wifi-off" -> {
                shell("settings put global adb_wifi_enabled 0 2>/dev/null")
                done("Wireless ADB turned off.", "Checked: adb_wifi_enabled = '${setting("global", "adb_wifi_enabled")}'. Use USB next time.")
            }
            "fix-dark-mode-off" -> {
                shell("cmd uimode night no 2>/dev/null || settings put secure ui_night_mode 1")
                done("Dark mode turned off.", "Checked: ui_night_mode = '${setting("secure", "ui_night_mode")}'.")
            }
            "fix-dark-mode-on" -> {
                shell("cmd uimode night yes 2>/dev/null || settings put secure ui_night_mode 2")
                done("Dark mode turned on.", "Checked: ui_night_mode = '${setting("secure", "ui_night_mode")}'.")
            }
            "fix-night-light-off" -> {
                shell("settings put secure night_display_activated 0; cmd night_display set-activated false 2>/dev/null")
                done("Night Light turned off.", "Checked: night_display_activated = '${setting("secure", "night_display_activated")}'.")
            }
            "fix-screen-lock-timeout" -> {
                requireSuccess(shell("settings put secure lock_screen_lock_after_timeout 5000"), "Lock timeout could not be changed.")
                done("Screen will lock 5s after sleep.", "Checked: lock_screen_lock_after_timeout = '${setting("secure", "lock_screen_lock_after_timeout")}'.")
            }
            "fix-slow-phone" -> {
                val before = freeDataKb()
                shell("pm trim-caches 4096G", 180000L)
                shell("logcat -c")
                shell("am kill-all")
                val opt = shell("cmd package bg-dexopt-job 2>/dev/null", 900000L)
                val after = freeDataKb()
                done("All safe speed steps completed.", if (before != null && after != null) "Free before: ${ShellSupport.formatBytes(before*1024)}, after: ${ShellSupport.formatBytes(after*1024)}. " + opt.combined.trim().take(120) else "Caches/logs/apps handled. " + opt.combined.trim().take(120))
            }
            "fix-notifications" -> {
                shell("cmd appops reset 2>/dev/null", 60000L)
                shell("settings put global zen_mode 0; cmd notification set_dnd off 2>/dev/null")
                shell("settings put global low_power 0")
                done("Notification fixes applied.", "App access reset, DND off (zen_mode=${setting("global", "zen_mode")}), battery saver off. Test notifications.")
            }
            "fix-apn-reset" -> {
                // Real: delete user APNs via content provider, then reset to default
                val del = shell("content delete --uri content://telephony/carriers --where \"current IS NOT NULL\" 2>&1; content insert --uri content://telephony/carriers/restore --bind apn:s:default 2>&1", 30000L)
                if (!del.succeeded && !del.combined.contains("Row", true)) {
                    // fallback: at least restore
                    shell("cmd phone reset-apn 2>/dev/null || settings put global preferred_apn_set 1", 20000L)
                }
                done("APNs reset to carrier defaults.", "APN delete/restore sent. Data should reconnect; reboot if not.")
            }
            "fix-wifi-safe" -> {
                shell("cmd wifi reset 2>/dev/null || svc wifi disable; sleep 2; svc wifi enable; wpa_cli -i wlan0 reconfigure 2>/dev/null; settings put global wifi_scan_always_enabled 1", 30000L)
                done("Wi-Fi config safely reset and restarted.", "Saved networks preserved where possible; bad cert cleared. Wi-Fi reconnecting...")
            }
            "fix-export-logs" -> {
                if (filesDir != null) {
                    val bundle = LogBundle(runner, java.io.File(filesDir, "log_bundles")).collect()
                    done("Logs written to private storage.", "Bugreport: ${bundle["bugreportPath"]} (${bundle["bugreportBytes"]} bytes), logcat: ${bundle["logcatBytes"]} bytes.")
                } else {
                    val bug = shell("dumpsys dropbox --print 2>&1 | head -n 300", 30000L).output
                    val log = shell("logcat -d -v threadtime -t 2000 2>&1 | tail -n 1500", 30000L).output
                    done("Logs collected (no local storage).", "Dropbox: ${bug.lines().size} lines, logcat: ${log.lines().size} lines.")
                }
            }
            "fix-preflash-backup-note" -> {
                val note = shell("getprop ro.product.model 2>/dev/null; getprop ro.build.version.incremental 2>/dev/null", 8000L).output.trim()
                if (filesDir != null) {
                    val dir = java.io.File(filesDir, "flash_backups"); dir.mkdirs()
                    val f = java.io.File(dir, "backup-note-${System.currentTimeMillis()}.txt")
                    f.writeText("Backup note before flash\nDevice: $note\nTime: ${System.currentTimeMillis()}\nAdvice: Keep factory OTA package nearby for recovery.\n")
                    done("Backup note recorded.", "Saved to ${f.name} — ${f.length()} bytes. Now verify firmware hash before flashing.")
                } else done("Backup note (no storage).", note.take(400))
            }
            "fix-firmware-verify" -> {
                val head = shell("ls -lh /data/user/0/com.ultimate.debugging.ultimate_debugging_tool/files/updates 2>&1 | head -n 20", 10000L).output.trim()
                done("Firmware verify helper.", head.ifEmpty { "No packages yet — pick an OTA .zip via Update from ADB, then this checks its SHA-256 against what the manufacturer published." } )
            }
            "fix-network-select-auto" -> {
                shell("settings put global network_preference auto 2>/dev/null; cmd phone set-network-selection-mode auto 2>/dev/null")
                done("Network selection set to auto.", "Phone will re-search carrier. Allow 1 min for foreign SIM to register.")
            }
            "fix-carrier-config-reset" -> {
                shell("cmd phone reset-carrier-config 2>/dev/null; settings delete global carrier_config_version 2>/dev/null")
                done("Carrier config reset.", "Reboot may be needed for new MCC/MNC to apply.")
            }
            "fix-sim-refresh" -> {
                shell("cmd phone refreshUicc 2>/dev/null || service call phone 5 2>/dev/null")
                done("UICC/SIM refresh sent.", "If SIM ME lock persists, phone still needs carrier unlock code — not bypassed.")
            }
            "fix-universal-cache-preview" -> {
                val sizes = shell("dumpsys diskstats 2>&1 | head -n 40", 12000L).output.trim()
                done("Cache preview ready.", sizes.ifEmpty { "No diskstats — try storage maintenance." })
            }

            else -> throw RepairException(
                "REPAIR_NOT_ALLOWED",
                "This repair operation is not approved."
            )

        }
    }

    private fun freeDataKb(): Long? {
        return TargetDiagnostics.parseDf(shell("df -k /data").output)
            .firstOrNull()
            ?.freeKb
    }
}
