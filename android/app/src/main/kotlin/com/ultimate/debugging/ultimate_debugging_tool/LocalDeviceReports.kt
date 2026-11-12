package com.ultimate.debugging.ultimate_debugging_tool

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Reports about the phone this app is running on (the technician's
 * workstation). Reports about the connected target phone are produced
 * by TargetDiagnostics over ADB.
 */
class LocalDeviceReports(private val activity: Activity) {

    private val usbManager: UsbManager
        get() = activity.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Runs one report. May take a moment; call off the main thread. */
    fun run(command: String): String {
        return activity.buildReport(command)
    }

    /**
     * Report templates are indented in the source for readability.
     * Values inserted into them may span several lines, so each line is
     * aligned individually instead of using trimIndent().
     */
    private fun String.tidyReport(): String {
        return lines().joinToString("\n") { it.trimStart() }.trim()
    }

    private fun Activity.buildReport(
        command: String
    ): String {

        return when (command.trim().lowercase()) {

            "help" -> {
                listOf(
                    "ULTIMATE DEBUGGING TOOL",
                    "Reports about THIS phone (the workstation)",
                    "",
                    "help             - Show available reports",
                    "device-info      - Device, build and hardware identity",
                    "battery          - Battery condition and charging",
                    "storage          - Storage volumes and usage",
                    "memory           - RAM usage",
                    "security         - Security state",
                    "network          - Network state",
                    "uptime           - Time since this phone started",
                    "technician-suite - Full technician report"
                ).joinToString("\n")
            }

            "uptime" -> {
                val elapsedMs = android.os.SystemClock.elapsedRealtime()
                val awakeMs = android.os.SystemClock.uptimeMillis()

                fun describe(milliseconds: Long): String {
                    val totalMinutes = milliseconds / 60000
                    val days = totalMinutes / 1440
                    val hours = (totalMinutes % 1440) / 60
                    val minutes = totalMinutes % 60
                    return "$days d $hours h $minutes min"
                }

                val startedAt = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm",
                    java.util.Locale.US
                ).format(
                    java.util.Date(System.currentTimeMillis() - elapsedMs)
                )

                listOf(
                    "UPTIME REPORT",
                    "",
                    "Time since start: ${describe(elapsedMs)}",
                    "Time awake: ${describe(awakeMs)}",
                    "Time in deep sleep: ${describe(elapsedMs - awakeMs)}",
                    "Started at: $startedAt",
                    "",
                    "A phone that restarts by itself shows a short",
                    "time since start. Compare with the customer's",
                    "description of the fault."
                ).joinToString("\n")
            }

            "device-info" -> {

                val activityManager =
                    getSystemService(Context.ACTIVITY_SERVICE)
                        as ActivityManager

                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)

                val sensorManager =
                    getSystemService(Context.SENSOR_SERVICE)
                        as SensorManager

                val sensors = sensorManager
                    .getSensorList(android.hardware.Sensor.TYPE_ALL)
                    .sortedBy { it.name }
                    .joinToString("\n") {
                        "${it.name} | ${it.vendor} | Type: ${it.type}"
                    }
                    .ifBlank { "Unavailable" }

                val features = packageManager
                    .systemAvailableFeatures
                    .mapNotNull { it.name }
                    .sorted()
                    .joinToString("\n")
                    .ifBlank { "Unavailable" }

               fun readProperty(name: String): String {
                    var process: Process? = null

                    return try {
                        process = Runtime.getRuntime().exec(
                            arrayOf("getprop", name)
                        )

                        val value = process.inputStream
                            .bufferedReader()
                            .use { it.readText().trim() }

                        val exitCode = process.waitFor()

                        if (exitCode == 0 && value.isNotBlank()) {
                            value
                        } else {
                            "Unavailable"
                        }
                    } catch (e: Exception) {
                        "Unavailable"
                    } finally {
                        process?.destroy()
                    }
                }

              fun readPartition(path: String): String {
                    return try {
                        val file = java.io.File(path)

                        if (file.exists() && file.isDirectory) {
                            val stat = android.os.StatFs(path)

                            val total = stat.totalBytes /
                                (1024.0 * 1024.0 * 1024.0)

                            "%.2f GB".format(total)
                        } else {
                            "Not exposed or unavailable"
                        }
                    } catch (e: Exception) {
                        "Unavailable"
                    }
                }

                val totalRAM =
                    memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

                val availableRAM =
                    memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0)

                val usedRAM = totalRAM - availableRAM

                val uptimeSeconds =
                    android.os.SystemClock.elapsedRealtime() / 1000

                val selinuxStatus = try {
                    val file = java.io.File("/sys/fs/selinux/enforce")
                    if (file.canRead()) {
                        when (file.readText().trim()) {
                            "1" -> "Enforcing"
                            "0" -> "Permissive"
                            else -> "Unknown"
                        }
                    } else {
                        "Unavailable to application"
                    }
                } catch (e: Exception) {
                    "Unavailable to application"
                }

                val abUpdate = readProperty("ro.build.ab_update")
                val virtualAb = readProperty("ro.virtual_ab.enabled")
                val dynamicPartitions =
                    readProperty("ro.boot.dynamic_partitions")

                val verifiedBootState =
                    readProperty("ro.boot.verifiedbootstate")

                val flashLocked =
                    readProperty("ro.boot.flash.locked")

                val vbmetaState =
                    readProperty("ro.boot.vbmeta.device_state")

                val partitionNames = listOf(
                    "system",
                    "vendor",
                    "product",
                    "odm",
                    "system_ext"
                )

                val partitionReport = partitionNames.joinToString("\n") {
                    val path = "/$it"
                    "$it: ${readPartition(path)}"
                }

                               val assessment = mutableListOf<String>()

                if (memoryInfo.lowMemory) {
                    assessment.add(
                        "Android reports low memory conditions."
                    )
                }

                if (selinuxStatus == "Permissive") {
                    assessment.add(
                        "WARNING: SELinux is permissive. Investigate device security."
                    )
                }

                when (verifiedBootState.lowercase()) {
                    "orange" -> assessment.add(
                        "WARNING: Verified Boot reports an unlocked bootloader."
                    )

                    "red" -> assessment.add(
                        "CRITICAL: Verified Boot reports a verification failure."
                    )

                    "yellow" -> assessment.add(
                        "NOTICE: Verified Boot reports a custom trust key."
                    )
                }

                when (flashLocked) {
                    "0" -> assessment.add(
                        "WARNING: Bootloader lock property reports unlocked."
                    )

                    "1" -> {
                        // Bootloader lock property reports locked.
                    }
                }

                when (vbmetaState.lowercase()) {
                    "unlocked" -> assessment.add(
                        "WARNING: VBMeta reports an unlocked device state."
                    )

                    "locked" -> {
                        // VBMeta reports a locked device state.
                    }
                }

                if (assessment.isEmpty()) {
                    assessment.add(
                        "No immediate abnormal condition detected from available readings. Some security properties may be restricted or unavailable."
                    )
                }
                """
                ADVANCED DEVICE, SOFTWARE AND FIRMWARE REPORT

                ==========================================
                1. DEVICE IDENTITY
                ==========================================

                Manufacturer: ${Build.MANUFACTURER}
                Brand: ${Build.BRAND}
                Model: ${Build.MODEL}
                Device Codename: ${Build.DEVICE}
                Product: ${Build.PRODUCT}
                Board: ${Build.BOARD}
                Hardware: ${Build.HARDWARE}
                Bootloader: ${Build.BOOTLOADER}
                Radio/Baseband: ${Build.getRadioVersion() ?: "Unavailable"}

                ==========================================
                2. ANDROID SOFTWARE
                ==========================================

                Android Version: ${Build.VERSION.RELEASE}
                SDK Version: ${Build.VERSION.SDK_INT}
                Security Patch: ${Build.VERSION.SECURITY_PATCH}
                Base OS: ${Build.VERSION.BASE_OS}
                Build ID: ${Build.ID}
                Build Display: ${Build.DISPLAY}
                Build Incremental: ${Build.VERSION.INCREMENTAL}
                Build Type: ${Build.TYPE}
                Build Tags: ${Build.TAGS}
                Build Fingerprint: ${Build.FINGERPRINT}

                ==========================================
                3. FIRMWARE IDENTIFIERS
                ==========================================

                ROM Version: ${readProperty("ro.build.display.id")}
                Firmware Fingerprint: ${readProperty("ro.build.fingerprint")}
                Manufacturer Build: ${readProperty("ro.build.version.incremental")}
                Platform: ${readProperty("ro.board.platform")}
                Chipset Hardware: ${readProperty("ro.hardware")}
                Bootloader Version: ${readProperty("ro.bootloader")}
                Baseband Version: ${readProperty("gsm.version.baseband")}
                Vendor Build: ${readProperty("ro.vendor.build.fingerprint")}
                Product Build: ${readProperty("ro.product.build.fingerprint")}

                ==========================================
                4. PROCESSOR AND ARCHITECTURE
                ==========================================

                Available CPU Cores: ${
                    Runtime.getRuntime().availableProcessors()
                }
                Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}
                Kernel Architecture: ${System.getProperty("os.arch")}
                Kernel Version: ${System.getProperty("os.version")}

                ==========================================
                5. MEMORY
                ==========================================

                Total RAM: ${"%.2f".format(totalRAM)} GB
                Used RAM: ${"%.2f".format(usedRAM)} GB
                Available RAM: ${"%.2f".format(availableRAM)} GB
                Low Memory: ${memoryInfo.lowMemory}
                Memory Threshold: ${
                    memoryInfo.threshold / (1024.0 * 1024.0)
                } MB

                ==========================================
                6. DISPLAY AND HARDWARE SENSORS
                ==========================================

                Display Width: ${resources.displayMetrics.widthPixels} pixels
                Display Height: ${resources.displayMetrics.heightPixels} pixels
                Display Density: ${resources.displayMetrics.densityDpi} DPI

                Sensors:
                $sensors

                ==========================================
                7. HARDWARE FEATURES
                ==========================================

                $features

                ==========================================
                8. BOOT AND PARTITION CONFIGURATION
                ==========================================

                A/B Updates: $abUpdate
                Virtual A/B: $virtualAb
                Dynamic Partitions: $dynamicPartitions
                Verified Boot State: $verifiedBootState
                Bootloader Flash Lock: $flashLocked
                VBMeta Device State: $vbmetaState

                Partition Information:
                $partitionReport

                ==========================================
                9. SECURITY INFORMATION
                ==========================================

                SELinux: $selinuxStatus
                Build Type: ${Build.TYPE}
                Build Tags: ${Build.TAGS}
                Verified Boot: $verifiedBootState
                Security Patch: ${Build.VERSION.SECURITY_PATCH}

                ==========================================
                10. SYSTEM OPERATION
                ==========================================

                Uptime: ${uptimeSeconds / 86400} days
                Uptime Seconds: $uptimeSeconds

                ==========================================
                11. TECHNICIAN ASSESSMENT
                ==========================================

                ${assessment.joinToString("\n") { "- $it" }}

                ==========================================
                12. FIRMWARE MATCHING WARNING
                ==========================================

                Always match firmware using the exact model,
                device codename, hardware variant, region,
                build fingerprint and bootloader requirements.

                Never flash firmware based on model name alone.

                Some properties and partitions are protected
                and may require authorized ADB or manufacturer
                service tools. Unavailable does not mean absent.

                """.tidyReport()
            }

            
            "battery" -> {

                val batteryIntent = registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )

                if (batteryIntent == null) {
                    "Unable to retrieve battery information."
                } else {

                    val batteryManager =
                        getSystemService(Context.BATTERY_SERVICE)
                            as BatteryManager

                    val level = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_LEVEL,
                        -1
                    )

                    val scale = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_SCALE,
                        -1
                    )

                    val percentage =
                        if (level >= 0 && scale > 0) {
                            (level * 100) / scale
                        } else {
                            -1
                        }

                    val status = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_STATUS,
                        -1
                    )

                    val chargingStatus = when (status) {
                        BatteryManager.BATTERY_STATUS_CHARGING ->
                            "Charging"

                        BatteryManager.BATTERY_STATUS_FULL ->
                            "Fully charged"

                        BatteryManager.BATTERY_STATUS_DISCHARGING ->
                            "Discharging"

                        BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                            "Not charging"

                        BatteryManager.BATTERY_STATUS_UNKNOWN ->
                            "Unknown"

                        else -> "Unavailable"
                    }

                    val health = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_HEALTH,
                        -1
                    )

                    val batteryHealth = when (health) {
                        BatteryManager.BATTERY_HEALTH_GOOD ->
                            "Good"

                        BatteryManager.BATTERY_HEALTH_OVERHEAT ->
                            "Overheating"

                        BatteryManager.BATTERY_HEALTH_DEAD ->
                            "Dead"

                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ->
                            "Over voltage"

                        BatteryManager.BATTERY_HEALTH_COLD ->
                            "Too cold"

                        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
                            "Unspecified failure"

                        else -> "Unknown or unavailable"
                    }

                    // Tenths of a degree; can be below zero in the cold.
                    val temperature = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_TEMPERATURE,
                        Int.MIN_VALUE
                    )

                    val voltage = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_VOLTAGE,
                        -1
                    )

                    val plugged = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_PLUGGED,
                        0
                    )

                    val chargingSource = when {
                        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 ->
                            "AC charger"

                        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 ->
                            "USB charger"

                        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 ->
                            "Wireless charger"

                        else -> "Not connected or unknown"
                    }

                    val technology = batteryIntent.getStringExtra(
                        BatteryManager.EXTRA_TECHNOLOGY
                    ) ?: "Unavailable"

                    val currentNow = batteryManager.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
                    )

                    val currentAverage = batteryManager.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE
                    )

                    val chargeCounter = batteryManager.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
                    )

                    fun formatCurrent(value: Int): String {
                        return if (value == Int.MIN_VALUE) {
                            "Unavailable"
                        } else {
                            "%.2f mA".format(value / 1000.0)
                        }
                    }

                    val temperatureC =
                        if (temperature != Int.MIN_VALUE) {
                            temperature / 10.0
                        } else {
                            Double.NaN
                        }

                    val voltageV =
                        if (voltage > 0) {
                            voltage / 1000.0
                        } else {
                            -1.0
                        }

                    val assessment = mutableListOf<String>()

                    if (percentage in 0..15) {
                        assessment.add(
                            "Low battery level. Check charging and battery condition."
                        )
                    }

                    if (temperatureC >= 45.0) {
                        assessment.add(
                            "HIGH TEMPERATURE: Battery is overheating. Stop intensive use and inspect the device."
                        )
                    } else if (temperatureC >= 40.0) {
                        assessment.add(
                            "Battery temperature is elevated. Monitor during charging and operation."
                        )
                    }

                    if (
                        health == BatteryManager.BATTERY_HEALTH_OVERHEAT ||
                        health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ||
                        health == BatteryManager.BATTERY_HEALTH_DEAD ||
                        health == BatteryManager.BATTERY_HEALTH_COLD ||
                        health == BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE
                    ) {
                        assessment.add(
                            "Android reports an abnormal battery health condition."
                        )
                    }

                    if (assessment.isEmpty()) {
                        assessment.add(
                            "No abnormal condition detected from the available readings."
                        )
                    }

                    val chargeCounterText =
                        if (chargeCounter != Int.MIN_VALUE &&
                            chargeCounter >= 0
                        ) {
                            "%.0f mAh".format(
                                chargeCounter / 1000.0
                            )
                        } else {
                            "Unavailable"
                        }

                    """
                    BATTERY DIAGNOSTIC REPORT

                    [BATTERY CONDITION]
                    Battery Level: ${
                        if (percentage >= 0) "$percentage%" else "Unavailable"
                    }
                    Battery Health: $batteryHealth
                    Battery Technology: $technology
                    Charge Counter: $chargeCounterText

                    [CHARGING SYSTEM]
                    Charging Status: $chargingStatus
                    Charging Source: $chargingSource

                    [ELECTRICAL READINGS]
                    Battery Voltage: ${
                        if (voltageV >= 0) "%.3f V".format(voltageV)
                        else "Unavailable"
                    }
                    Instantaneous Current: ${formatCurrent(currentNow)}
                    Average Current: ${formatCurrent(currentAverage)}

                    [THERMAL READINGS]
                    Battery Temperature: ${
                        if (!temperatureC.isNaN()) "%.1f °C".format(temperatureC)
                        else "Unavailable"
                    }

                    [TECHNICIAN ASSESSMENT]
                    ${assessment.joinToString("\n") { "- $it" }}

                    [DATA LIMITATIONS]
                    Battery capacity, cycle count and detailed charging-controller diagnostics
                    are not exposed consistently by Android. Additional manufacturer-specific
                    or authorized ADB diagnostics may be required.
                    """.tidyReport()
                }
            }

            
            "storage" -> {

                fun formatBytes(bytes: Long): String {
                    if (bytes < 0) return "Unavailable"

                    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
                    val mb = bytes / (1024.0 * 1024.0)

                    return if (gb >= 1.0) {
                        "%.2f GB".format(gb)
                    } else {
                        "%.2f MB".format(mb)
                    }
                }

                fun inspectStorage(path: String): String {
                    return try {
                        val file = java.io.File(path)

                        if (!file.exists()) {
                            return "Path unavailable"
                        }

                        val stat = android.os.StatFs(path)

                        val total = stat.totalBytes
                        val free = stat.freeBytes
                        val available = stat.availableBytes
                        val used = total - free

                        val usage = if (total > 0) {
                            (used * 100.0) / total
                        } else {
                            0.0
                        }

                        """
                        Path: $path
                        Total Capacity: ${formatBytes(total)}
                        Used Space: ${formatBytes(used)}
                        Free Space: ${formatBytes(free)}
                        Available to Applications: ${formatBytes(available)}
                        Usage: ${"%.1f".format(usage)}%
                        Block Size: ${formatBytes(stat.blockSizeLong)}
                        Total Blocks: ${stat.blockCountLong}
                        Free Blocks: ${stat.freeBlocksLong}
                        """.tidyReport()

                    } catch (e: Exception) {
                        "Storage information unavailable: ${e.message}"
                    }
                }

                fun directorySize(directory: java.io.File): Long {
                    return try {
                        if (!directory.exists()) {
                            return 0L
                        }

                        if (directory.isFile) {
                            return directory.length()
                        }

                        directory.listFiles()?.sumOf {
                            directorySize(it)
                        } ?: 0L

                    } catch (e: Exception) {
                        0L
                    }
                }

                val internalStorage =
                    inspectStorage(filesDir.absolutePath)

                val sharedStorage =
                    inspectStorage(
                        android.os.Environment
                            .getExternalStorageDirectory()
                            .absolutePath
                    )

                val appFilesSize =
                    directorySize(filesDir)

                val appCacheSize =
                    directorySize(cacheDir)

                val externalDirectories =
                    getExternalFilesDirs(null)

                val externalReport =
                    externalDirectories.mapIndexed { index, directory ->

                        if (directory != null) {
                            """
                            Volume $index
                            ${inspectStorage(directory.absolutePath)}
                            """.tidyReport()
                        } else {
                            "Volume $index: Unavailable"
                        }

                    }.joinToString("\n\n")

                val storageState =
                    android.os.Environment.getExternalStorageState()

                val removable =
                    android.os.Environment.isExternalStorageRemovable()

                val assessment = mutableListOf<String>()

                try {
                    val stat = android.os.StatFs(filesDir.absolutePath)

                    val available = stat.availableBytes
                    val total = stat.totalBytes

                    if (total > 0 && available < total * 0.10) {
                        assessment.add(
                            "WARNING: Less than 10% of internal storage is available."
                        )
                    }

                    if (available < 1024L * 1024L * 500L) {
                        assessment.add(
                            "CRITICAL: Less than 500 MB of internal storage is available."
                        )
                    }

                } catch (e: Exception) {
                    assessment.add(
                        "Unable to assess internal storage capacity."
                    )
                }

                if (assessment.isEmpty()) {
                    assessment.add(
                        "No immediate storage capacity warning detected."
                    )
                }

                """
                ADVANCED STORAGE DIAGNOSTIC REPORT

                ==========================================
                1. INTERNAL STORAGE
                ==========================================

                $internalStorage

                ==========================================
                2. SHARED STORAGE
                ==========================================

                $sharedStorage

                ==========================================
                3. APPLICATION STORAGE
                ==========================================

                App Files Directory:
                ${filesDir.absolutePath}

                App Files Usage:
                ${formatBytes(appFilesSize)}

                App Cache Usage:
                ${formatBytes(appCacheSize)}

                ==========================================
                4. STORAGE VOLUMES
                ==========================================

                $externalReport

                ==========================================
                5. STORAGE STATUS
                ==========================================

                External Storage State: $storageState
                Removable Storage: $removable

                ==========================================
                6. TECHNICIAN ASSESSMENT
                ==========================================

                ${assessment.joinToString("\n") { "- $it" }}

                ==========================================
                7. STORAGE DIAGNOSTIC LIMITATIONS
                ==========================================

                Android may restrict access to some directories.
                Storage capacity readings do not measure flash
                memory health, bad blocks, write endurance or
                controller faults.

                Full eMMC/UFS health diagnostics may require
                authorized ADB, root or manufacturer service tools.

                Do not format or erase storage during diagnosis.
                Always back up important user data first.

                """.tidyReport()
            }

            
            "memory" -> {

                val activityManager =
                    getSystemService(Context.ACTIVITY_SERVICE)
                        as ActivityManager

                val systemMemory =
                    ActivityManager.MemoryInfo()

                activityManager.getMemoryInfo(systemMemory)

                val processMemory =
                    android.os.Debug.MemoryInfo()

                android.os.Debug.getMemoryInfo(processMemory)

                val runtime =
                    Runtime.getRuntime()

                val totalRAM =
                    systemMemory.totalMem

                val availableRAM =
                    systemMemory.availMem

                val usedRAM =
                    totalRAM - availableRAM

                val appHeapUsed =
                    runtime.totalMemory() - runtime.freeMemory()

                val appHeapMax =
                    runtime.maxMemory()

                fun formatMemory(bytes: Long): String {
                    return "%.2f MB".format(
                        bytes / (1024.0 * 1024.0)
                    )
                }

                val totalPss =
                    processMemory.totalPss * 1024L

                val dalvikPss =
                    processMemory.dalvikPss * 1024L

                val nativePss =
                    processMemory.nativePss * 1024L

                val otherPss =
                    processMemory.otherPss * 1024L

                val memoryUsagePercent =
                    if (totalRAM > 0) {
                        usedRAM * 100.0 / totalRAM
                    } else {
                        0.0
                    }

                val assessment =
                    mutableListOf<String>()

                if (systemMemory.lowMemory) {
                    assessment.add(
                        "WARNING: Android reports a low-memory condition."
                    )
                }

                if (memoryUsagePercent >= 90.0) {
                    assessment.add(
                        "HIGH: System RAM usage is at least 90%."
                    )
                } else if (memoryUsagePercent >= 80.0) {
                    assessment.add(
                        "NOTICE: System RAM usage is elevated."
                    )
                }

                if (
                    availableRAM < systemMemory.threshold
                ) {
                    assessment.add(
                        "WARNING: Available RAM is below Android's memory threshold."
                    )
                }

                if (assessment.isEmpty()) {
                    assessment.add(
                        "No immediate system memory pressure warning detected."
                    )
                }

                """
                ADVANCED RAM AND MEMORY DIAGNOSTIC REPORT

                ==========================================
                1. SYSTEM RAM
                ==========================================

                Total Physical RAM: ${formatMemory(totalRAM)}
                Used RAM Estimate: ${formatMemory(usedRAM)}
                Available RAM: ${formatMemory(availableRAM)}
                Memory Usage: ${"%.2f".format(memoryUsagePercent)}%
                Android Memory Threshold: ${
                    formatMemory(systemMemory.threshold)
                }
                Low Memory Condition: ${systemMemory.lowMemory}

                ==========================================
                2. APPLICATION MEMORY
                ==========================================

                Application Process ID: ${android.os.Process.myPid()}
                Application PSS: ${formatMemory(totalPss)}
                Dalvik PSS: ${formatMemory(dalvikPss)}
                Native PSS: ${formatMemory(nativePss)}
                Other PSS: ${formatMemory(otherPss)}

                ==========================================
                3. APPLICATION HEAP
                ==========================================

                Heap Currently Allocated: ${formatMemory(runtime.totalMemory())}
                Heap Currently Used: ${formatMemory(appHeapUsed)}
                Heap Maximum: ${formatMemory(appHeapMax)}
                Application Memory Class: ${
                    activityManager.memoryClass
                } MB
                Large Memory Class: ${
                    activityManager.largeMemoryClass
                } MB

                ==========================================
                4. NATIVE MEMORY
                ==========================================

                Native Heap Allocated: ${
                    formatMemory(
                        android.os.Debug.getNativeHeapAllocatedSize()
                    )
                }
                Native Heap Free: ${
                    formatMemory(
                        android.os.Debug.getNativeHeapFreeSize()
                    )
                }
                Native Heap Capacity: ${
                    formatMemory(
                        android.os.Debug.getNativeHeapSize()
                    )
                }

                ==========================================
                5. TECHNICIAN ASSESSMENT
                ==========================================

                ${assessment.joinToString("\n") { "- $it" }}

                ==========================================
                6. DIAGNOSTIC LIMITATIONS
                ==========================================

                Application PSS and heap readings describe this
                diagnostic application's memory consumption.

                Android restricts access to detailed memory
                information belonging to other applications.

                High RAM usage alone does not prove a fault.
                Android uses available RAM for caching and
                application performance.

                Do not terminate system processes based only
                on these readings.

                """.tidyReport()
            }

            
            "security" -> {

                fun readSecurityProperty(name: String): String {
                    return try {
                        val process = Runtime.getRuntime().exec(
                            arrayOf("getprop", name)
                        )

                        val value = process.inputStream
                            .bufferedReader()
                            .use { it.readText().trim() }

                        process.waitFor()

                        value.ifBlank { "Unavailable" }
                    } catch (e: Exception) {
                        "Unavailable"
                    }
                }

                val securityPatch =
                    Build.VERSION.SECURITY_PATCH

                val selinuxStatus = try {
                    val file =
                        java.io.File("/sys/fs/selinux/enforce")

                    if (file.canRead()) {
                        when (file.readText().trim()) {
                            "1" -> "Enforcing"
                            "0" -> "Permissive"
                            else -> "Unknown"
                        }
                    } else {
                        "Unavailable to application"
                    }
                } catch (e: Exception) {
                    "Unavailable to application"
                }

                val verifiedBoot =
                    readSecurityProperty(
                        "ro.boot.verifiedbootstate"
                    )

                val flashLocked =
                    readSecurityProperty(
                        "ro.boot.flash.locked"
                    )

                val vbmetaState =
                    readSecurityProperty(
                        "ro.boot.vbmeta.device_state"
                    )

                val vbmetaDigest =
                    readSecurityProperty(
                        "ro.boot.vbmeta.digest"
                    )

                val encryptionState =
                    readSecurityProperty(
                        "ro.crypto.state"
                    )

                val encryptionType =
                    readSecurityProperty(
                        "ro.crypto.type"
                    )

                val debuggable =
                    readSecurityProperty(
                        "ro.debuggable"
                    )

                val secureProperty =
                    readSecurityProperty(
                        "ro.secure"
                    )

                val adbEnabled =
                    readSecurityProperty(
                        "persist.sys.usb.config"
                    )

                val buildType = Build.TYPE
                val buildTags = Build.TAGS

                val assessment =
                    mutableListOf<String>()

                if (selinuxStatus == "Permissive") {
                    assessment.add(
                        "WARNING: SELinux is in permissive mode."
                    )
                }

                if (verifiedBoot == "orange") {
                    assessment.add(
                        "WARNING: Verified Boot reports an unlocked or modified boot state."
                    )
                } else if (verifiedBoot == "red") {
                    assessment.add(
                        "CRITICAL: Verified Boot reports a verification failure."
                    )
                } else if (verifiedBoot == "yellow") {
                    assessment.add(
                        "NOTICE: Verified Boot reports a user-set trust configuration."
                    )
                }

                if (flashLocked == "0") {
                    assessment.add(
                        "WARNING: Bootloader reports an unlocked state."
                    )
                }

                if (debuggable == "1") {
                    assessment.add(
                        "NOTICE: Build is marked debuggable."
                    )
                }

                if (buildType != "user") {
                    assessment.add(
                        "NOTICE: Build type is not the standard user release type."
                    )
                }

                if (assessment.isEmpty()) {
                    assessment.add(
                        "No immediate security warning detected from available indicators."
                    )
                }

                """
                ADVANCED ANDROID SECURITY REPORT

                ==========================================
                1. ANDROID SECURITY PATCH
                ==========================================

                Security Patch Level: ${
                    securityPatch.ifBlank { "Unavailable" }
                }
                Android Version: ${Build.VERSION.RELEASE}
                SDK Version: ${Build.VERSION.SDK_INT}

                ==========================================
                2. VERIFIED BOOT
                ==========================================

                Verified Boot State: $verifiedBoot
                Bootloader Flash Lock: $flashLocked
                VBMeta Device State: $vbmetaState
                VBMeta Digest: $vbmetaDigest

                ==========================================
                3. SYSTEM SECURITY
                ==========================================

                SELinux Status: $selinuxStatus
                Secure Property: $secureProperty
                Debuggable Property: $debuggable
                Build Type: $buildType
                Build Tags: $buildTags

                ==========================================
                4. DATA ENCRYPTION
                ==========================================

                Encryption State: $encryptionState
                Encryption Type: $encryptionType

                ==========================================
                5. DEBUGGING CONFIGURATION
                ==========================================

                USB Configuration: $adbEnabled

                Note: This property does not prove that
                USB debugging is currently authorized.

                ==========================================
                6. TECHNICIAN ASSESSMENT
                ==========================================

                ${assessment.joinToString("\n") { "- $it" }}

                ==========================================
                7. SECURITY DIAGNOSTIC LIMITATIONS
                ==========================================

                Some boot and encryption properties are
                restricted by Android or manufacturer policy.

                Unavailable values do not prove that a
                security feature is disabled.

                This report does not perform a malware scan,
                exploit test, certificate validation or
                complete hardware-backed attestation.

                Do not unlock the bootloader or disable
                security protections as a diagnostic shortcut.

                """.tidyReport()
            }

            
            "network" -> {

                val connectivityManager =
                    getSystemService(Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager

                val activeNetwork =
                    connectivityManager.activeNetwork

                val networkCapabilities =
                    activeNetwork?.let {
                        connectivityManager.getNetworkCapabilities(it)
                    }

                val connectionType = when {
                    networkCapabilities?.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_WIFI
                    ) == true -> "Wi-Fi"

                    networkCapabilities?.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_CELLULAR
                    ) == true -> "Mobile Data"

                    networkCapabilities?.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_ETHERNET
                    ) == true -> "Ethernet"

                    networkCapabilities?.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_VPN
                    ) == true -> "VPN"

                    else -> "Disconnected or unavailable"
                }

                val internetCapability =
                    networkCapabilities?.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
                    ) == true

                val validatedInternet =
                    networkCapabilities?.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    ) == true

                val metered =
                    connectivityManager.isActiveNetworkMetered

                val vpnActive =
                    networkCapabilities?.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_VPN
                    ) == true

                val wifiManager =
                    applicationContext.getSystemService(
                        Context.WIFI_SERVICE
                    ) as android.net.wifi.WifiManager

                val wifiEnabled = wifiManager.isWifiEnabled

                val wifiInfo = try {
                    wifiManager.connectionInfo
                } catch (e: Exception) {
                    null
                }

                val wifiSSID =
                    wifiInfo?.ssid
                        ?.takeIf {
                            it != "<unknown ssid>" && it.isNotBlank()
                        } ?: "Unavailable or permission restricted"

                val wifiBSSID =
                    wifiInfo?.bssid
                        ?.takeIf {
                            it.isNotBlank()
                        } ?: "Unavailable or permission restricted"

                val wifiSignal =
                    wifiInfo?.rssi ?: -127

                val wifiLinkSpeed =
                    wifiInfo?.linkSpeed ?: -1

                val wifiFrequency =
                    wifiInfo?.frequency ?: -1

                val wifiSignalQuality = when {
                    wifiSignal >= -50 -> "Excellent"
                    wifiSignal >= -60 -> "Very good"
                    wifiSignal >= -67 -> "Good"
                    wifiSignal >= -70 -> "Fair"
                    wifiSignal >= -80 -> "Weak"
                    wifiSignal > -127 -> "Very weak"
                    else -> "Unavailable"
                }

                val networkInterfaces = try {
                    java.net.NetworkInterface
                        .getNetworkInterfaces()
                        ?.toList()
                        ?.filter { it.isUp }
                        ?.joinToString("\n") { networkInterface ->

                            val addresses =
                                networkInterface.inetAddresses
                                    .toList()
                                    .joinToString(", ") {
                                        it.hostAddress ?: "Unknown"
                                    }

                            """
                            Interface: ${networkInterface.name}
                            Display Name: ${networkInterface.displayName}
                            Addresses: $addresses
                            Loopback: ${networkInterface.isLoopback}
                            """.tidyReport()
                        } ?: "Unavailable"
                } catch (e: Exception) {
                    "Network interface information unavailable."
                }

                val dnsServers = try {
                    java.net.InetAddress
                        .getAllByName("localhost")
                        .joinToString("\n") {
                            it.hostAddress ?: "Unknown"
                        }
                } catch (e: Exception) {
                    "Unavailable"
                }

                 
                """
                ADVANCED NETWORK DIAGNOSTIC REPORT

                ==========================================
                1. CONNECTION STATUS
                ==========================================

                Connection Type: $connectionType
                Active Network: ${activeNetwork != null}
                Internet Capability: $internetCapability
                Internet Validated: $validatedInternet
                Metered Connection: $metered
                VPN Active: $vpnActive

                ==========================================
                2. WI-FI DIAGNOSTICS
                ==========================================

                Wi-Fi Enabled: $wifiEnabled
                Network Name: $wifiSSID
                Access Point BSSID: $wifiBSSID
                Signal Strength: $wifiSignal dBm
                Signal Quality: $wifiSignalQuality
                Link Speed: ${
                    if (wifiLinkSpeed >= 0) "$wifiLinkSpeed Mbps"
                    else "Unavailable"
                }
                Frequency: ${
                    if (wifiFrequency > 0) "$wifiFrequency MHz"
                    else "Unavailable"
                }

                ==========================================
                3. IP AND NETWORK INTERFACES
                ==========================================

                $networkInterfaces

                ==========================================
                4. DNS INFORMATION
                ==========================================

                Localhost Resolution:
                $dnsServers

                DNS server configuration may be restricted
                by Android and is not fully exposed here.

                ==========================================
                5. TECHNICIAN ASSESSMENT
                ==========================================

                No technician assessment is available in this network report.

                ==========================================
                6. DIAGNOSTIC LIMITATIONS
                ==========================================

                Android may restrict Wi-Fi SSID, BSSID and
                detailed network information without location
                or nearby Wi-Fi permissions.

                Internet validation does not guarantee that
                every website or application is reachable.

                This report does not modify network settings,
                reset connections or disconnect the VPN.

                """.tidyReport()
            }

            
            "technician-suite" -> {

                fun readProperty(name: String): String {
                    return try {
                        val process = Runtime.getRuntime().exec(
                            arrayOf("getprop", name)
                        )
                        val value = process.inputStream
                            .bufferedReader()
                            .use { it.readText().trim() }
                        process.waitFor()
                        value.ifBlank { "Unavailable" }
                    } catch (e: Exception) {
                        "Unavailable"
                    }
                }

                fun readFile(path: String): String {
                    return try {
                        val file = java.io.File(path)
                        if (file.canRead()) {
                            file.readText().trim()
                                .ifBlank { "Unavailable" }
                        } else {
                            "Restricted or unavailable"
                        }
                    } catch (e: Exception) {
                        "Unavailable"
                    }
                }

                // CPU AND PERFORMANCE

                val cpuCount =
                    Runtime.getRuntime().availableProcessors()

                val cpuOnline =
                    readFile("/sys/devices/system/cpu/online")

                val cpuLoad =
                    readFile("/proc/loadavg")

                val cpuFrequencies = (0 until cpuCount.coerceAtMost(16))
                    .map { index ->
                        val path =
                            "/sys/devices/system/cpu/cpu$index/cpufreq/scaling_cur_freq"

                        val value = readFile(path)

                        if (value != "Unavailable" &&
                            value != "Restricted or unavailable"
                        ) {
                            "CPU $index: ${
                                try {
                                    "%.2f MHz".format(
                                        value.toLong() / 1000.0
                                    )
                                } catch (e: Exception) {
                                    value
                                }
                            }"
                        } else {
                            "CPU $index: Frequency unavailable"
                        }
                    }.joinToString("\n")

                // THERMAL INFORMATION

                val sensorManager =
                    getSystemService(Context.SENSOR_SERVICE)
                        as? android.hardware.SensorManager

                val temperatureSensors =
                    try {
                        if (sensorManager == null) {
                            "Temperature sensor service unavailable."
                        } else {
                            sensorManager.getSensorList(
                                android.hardware.Sensor.TYPE_ALL
                            ).filter {
                                it.type ==
                                    android.hardware.Sensor.TYPE_AMBIENT_TEMPERATURE
                            }.joinToString("\n") {
                                "${it.name} | ${it.vendor}"
                            }.ifBlank {
                                "No ambient temperature sensors exposed."
                            }
                        }
                    } catch (e: Exception) {
                        "Temperature sensor information unavailable: ${e.message}"
                    }

                val powerManager =
                    getSystemService(Context.POWER_SERVICE)
                        as? android.os.PowerManager

                val thermalStatus =
                    try {
                        if (Build.VERSION.SDK_INT < 29) {
                            "Not exposed on this Android version"
                        } else if (powerManager == null) {
                            "Power management service unavailable."
                        } else {
                            when (powerManager.currentThermalStatus) {
                                android.os.PowerManager.THERMAL_STATUS_NONE ->
                                    "No thermal throttling reported"

                                android.os.PowerManager.THERMAL_STATUS_LIGHT ->
                                    "Light thermal condition"

                                android.os.PowerManager.THERMAL_STATUS_MODERATE ->
                                    "Moderate thermal condition"

                                android.os.PowerManager.THERMAL_STATUS_SEVERE ->
                                    "Severe thermal condition"

                                android.os.PowerManager.THERMAL_STATUS_CRITICAL ->
                                    "Critical thermal condition"

                                android.os.PowerManager.THERMAL_STATUS_EMERGENCY ->
                                    "Emergency thermal condition"

                                android.os.PowerManager.THERMAL_STATUS_SHUTDOWN ->
                                    "Thermal shutdown status"

                                else -> "Unknown"
                            }
                        }
                    } catch (e: Exception) {
                        "Thermal status unavailable: ${e.message}"
                    }

                // APPLICATION INVENTORY

                val installedApps =
                    packageManager.getInstalledApplications(0)

                                val enabledApps =
                    installedApps.count { app ->
                        try {
                            val state =
                                packageManager.getApplicationEnabledSetting(
                                    app.packageName
                                )

                            app.enabled &&
                                state != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                                state != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        } catch (e: Exception) {
                            app.enabled
                        }
                    }

                val systemApps =
                    installedApps.count {
                        (it.flags and
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    }

                val userApps =
                    installedApps.size - systemApps

                val disabledApps =
                    installedApps.count {
                        !it.enabled
                    }

                // RUNNING PROCESSES

                val activityManager =
                    getSystemService(Context.ACTIVITY_SERVICE)
                        as ActivityManager

                val runningProcesses =
                    try {
                        activityManager.runningAppProcesses
                    } catch (e: Exception) {
                        null
                    }

                val processReport =
                    runningProcesses
                        ?.take(30)
                        ?.joinToString("\n") {
                            "${it.processName} | PID: ${it.pid} | Importance: ${it.importance}"
                        } ?: "Process information restricted."

                // USB HARDWARE

                val usbManager =
                    getSystemService(Context.USB_SERVICE)
                        as android.hardware.usb.UsbManager

               val usbDevices = try {
                    usbManager.deviceList.values.toList()
                } catch (e: Exception) {
                    emptyList<android.hardware.usb.UsbDevice>()
                }

                val usbReport =
                    usbDevices.joinToString("\n") { device ->
                        """
                        Device: ${device.deviceName}
                        Manufacturer ID: ${device.vendorId}
                        Product ID: ${device.productId}
                        Interfaces: ${device.interfaceCount}
                        """.tidyReport()
                    }.ifBlank {
                        "No external USB devices detected."
                    }

                val usbHostSupported =
                    packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_USB_HOST
                    )

                   // AUDIO HARDWARE

                val audioManager =
                    getSystemService(Context.AUDIO_SERVICE)
                        as? android.media.AudioManager

                val audioDevices =
                    try {
                        if (audioManager == null) {
                            "Audio service unavailable."
                        } else if (Build.VERSION.SDK_INT >= 23) {
                            audioManager.getDevices(
                                android.media.AudioManager.GET_DEVICES_ALL
                            ).joinToString("\n") {
                                "${it.productName} | Type: ${it.type} | Source: ${it.isSource} | Sink: ${it.isSink}"
                            }.ifBlank {
                                "No audio devices exposed."
                            }
                        } else {
                            "Audio device enumeration unsupported."
                        }
                    } catch (e: Exception) {
                        "Audio device information unavailable: ${e.message}"
                    }
                    
                // DISPLAY AND TOUCH

                val displayMetrics =
                    resources.displayMetrics

                val display =
                    windowManager.defaultDisplay

                val refreshRate =
                    try {
                        val rate = display.refreshRate

                        if (rate > 0f) {
                            "%.2f".format(rate) + " Hz"
                        } else {
                            "Unavailable"
                        }
                    } catch (e: Exception) {
                        "Unavailable: ${e.message}"
                    }

                val touchSupported =
                    packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_TOUCHSCREEN
                    )

                val multitouchSupported =
                    packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH
                    )

                // CAMERA HARDWARE

                val cameraManager =
                    getSystemService(Context.CAMERA_SERVICE)
                        as? android.hardware.camera2.CameraManager

                val cameraReport = try {
                    val manager = cameraManager

                    if (manager == null) {
                        "Camera service unavailable."
                    } else {
                        manager.cameraIdList.joinToString("\n") { id ->

                            val characteristics =
                                manager.getCameraCharacteristics(id)

                            val facing =
                                characteristics.get(
                                    android.hardware.camera2.CameraCharacteristics.LENS_FACING
                                )

                            val facingName = when (facing) {
                                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT ->
                                    "Front"

                                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK ->
                                    "Back"

                                android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL ->
                                    "External"

                                else -> "Unknown"
                            }

                            val hardwareLevel =
                                characteristics.get(
                                    android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
                                )

                            "Camera ID: $id | Facing: $facingName | Hardware Level: $hardwareLevel"
                        }.ifBlank {
                            "No camera devices exposed."
                        }
                    }
                } catch (e: Exception) {
                    "Camera information unavailable: ${e.message}"
                }

                // BOOT AND SOFTWARE UPDATE INFORMATION

                val bootCompleted =
                    readProperty("sys.boot_completed")

                val bootSlot =
                    readProperty("ro.boot.slot_suffix")

                val bootMode =
                    readProperty("ro.bootmode")

                val otaVersion =
                    readProperty("ro.build.version.ota")

                val buildDate =
                    readProperty("ro.build.date")

                val buildDateUtc =
                    readProperty("ro.build.date.utc")

                val bootReason =
                    readProperty("ro.boot.bootreason")

                val updateEngine =
                    readProperty("ro.build.version.incremental")

                // TECHNICIAN ASSESSMENT

                val assessment =
                    mutableListOf<String>()

                if (thermalStatus.contains("Severe") ||
                    thermalStatus.contains("Critical") ||
                    thermalStatus.contains("Emergency") ||
                    thermalStatus.contains("shutdown")
                ) {
                    assessment.add(
                        "WARNING: Android reports a serious thermal condition."
                    )
                }

                if (disabledApps > 0) {
                    assessment.add(
                        "$disabledApps installed applications are disabled. Review before re-enabling."
                    )
                }

                if (usbDevices.isEmpty()) {
                    assessment.add(
                        "No external USB device is currently detected. This does not prove USB hardware failure."
                    )
                }

                if (assessment.isEmpty()) {
                    assessment.add(
                        "No immediate abnormal condition detected from available indicators."
                    )
                }

                """
                ULTIMATE DEBUGGING TOOL
                ADVANCED TECHNICIAN DIAGNOSTIC SUITE

                ==========================================
                1. CPU AND PERFORMANCE
                ==========================================

                Available CPU Cores: $cpuCount
                Online CPU Cores: $cpuOnline

                CPU Load Average:
                $cpuLoad

                CPU Frequency Information:
                $cpuFrequencies

                ==========================================
                2. THERMAL DIAGNOSTICS
                ==========================================

                Android Thermal Status:
                $thermalStatus

                Exposed Temperature Sensors:
                $temperatureSensors

                ==========================================
                3. APPLICATION DIAGNOSTICS
                ==========================================

                Total Installed Applications: ${installedApps.size}
                System Applications: $systemApps
                User Applications: $userApps
                Enabled Applications: $enabledApps
                Disabled Applications: $disabledApps

                ==========================================
                4. PROCESS DIAGNOSTICS
                ==========================================

                Running Processes Visible to Application:
                ${runningProcesses?.size ?: "Restricted"}

                Process Information:
                $processReport

                ==========================================
                5. USB AND HARDWARE
                ==========================================

                USB Host Supported: $usbHostSupported
                Connected USB Devices: ${usbDevices.size}

                USB Device Information:
                $usbReport

                ==========================================
                6. AUDIO HARDWARE
                ==========================================

                Audio Mode: ${
                    try {
                        audioManager?.mode?.toString()
                            ?: "Unavailable"
                    } catch (e: Exception) {
                        "Unavailable"
                    }
                }
                Microphone Mute: ${
                    try {
                        audioManager?.isMicrophoneMute?.toString()
                            ?: "Unavailable"
                    } catch (e: Exception) {
                        "Unavailable"
                    }
                }
                Music Volume: ${
                    try {
                        audioManager?.getStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC
                        )?.toString() ?: "Unavailable"
                    } catch (e: Exception) {
                        "Unavailable"
                    }
                }
                Maximum Music Volume: ${
                    try {
                        audioManager?.getStreamMaxVolume(
                            android.media.AudioManager.STREAM_MUSIC
                        )?.toString() ?: "Unavailable"
                    } catch (e: Exception) {
                        "Unavailable"
                    }
                }

                Audio Devices:
                $audioDevices

                ==========================================
                7. DISPLAY AND TOUCH
                ==========================================

                Width: ${displayMetrics.widthPixels} pixels
                Height: ${displayMetrics.heightPixels} pixels
                Density: ${displayMetrics.densityDpi} DPI
                Refresh Rate: $refreshRate
                Touchscreen Supported: $touchSupported
                Multitouch Supported: $multitouchSupported

                ==========================================
                8. CAMERA DIAGNOSTICS
                ==========================================

                $cameraReport

                ==========================================
                9. BOOT AND UPDATE INFORMATION
                ==========================================

                Boot Completed: $bootCompleted
                Boot Slot: $bootSlot
                Boot Mode: $bootMode
                Boot Reason: $bootReason
                OTA Version: $otaVersion
                Build Date: $buildDate
                Build Date UTC: $buildDateUtc
                Incremental Build: $updateEngine

                ==========================================
                10. LOG AND CRASH DIAGNOSTICS
                ==========================================

                Application PID: ${android.os.Process.myPid()}
                Application Package: $packageName

                System-wide logcat and protected crash
                reports require authorized ADB access.
                This application-only report does not claim
                to have read protected system logs.

                ==========================================
                11. TECHNICIAN ASSESSMENT
                ==========================================

                ${assessment.joinToString("\n") { "- $it" }}

                ==========================================
                12. IMPORTANT DIAGNOSTIC LIMITATIONS
                ==========================================

                CPU frequency, temperature sensors, USB
                devices, camera capabilities and process
                details depend on manufacturer exposure
                and Android permissions.

                Hardware presence does not prove that
                hardware is functioning correctly.

                Actual microphone, speaker, camera,
                touchscreen and USB tests require
                controlled functional testing.

                No repairs, resets, application disabling,
                firmware flashing or data deletion are
                performed by this diagnostic suite.

                """.tidyReport()
            }



            else -> {
                "Unknown command: $command\nType 'help' to see available commands."
            }
        }
    }

}
