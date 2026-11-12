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
class RepairServices(private val runner: AdbShellRunner) {

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
