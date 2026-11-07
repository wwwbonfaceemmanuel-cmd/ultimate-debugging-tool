
package com.ultimate.debugging.ultimate_debugging_tool

import android.app.PendingIntent
import android.app.ActivityManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

import android.hardware.SensorManager

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "UltimateDebuggingTool"

        private const val METHOD_CHANNEL =
            "com.ultimate.debugging/usb"

        private const val EVENT_CHANNEL =
            "com.ultimate.debugging/usb_events"

        private const val ACTION_USB_PERMISSION =
            "com.ultimate.debugging.USB_PERMISSION"

        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
    }

    private lateinit var usbManager: UsbManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val adbExecutor = Executors.newSingleThreadExecutor()
    private val wirelessExecutor = Executors.newSingleThreadExecutor()
    private val terminalExecutor = Executors.newSingleThreadExecutor()

    private val adbLifecycleLock = Any()

    private val pendingWirelessResult =
        AtomicReference<MethodChannel.Result?>(null)

    private val pendingWirelessResultSent =
        AtomicReference<AtomicBoolean?>(null)

    @Volatile
    private var adbConnection: AdbConnection? = null

    @Volatile
    private var adbTransport: AdbUsbTransport? = null

    @Volatile
    private var wirelessManager: AdbWirelessManager? = null

    @Volatile
    private var wirelessStatus = "DISCONNECTED"

    @Volatile
    private var wirelessHost: String? = null

    @Volatile
    private var wirelessPort: Int? = null

    @Volatile
    private var adbDeviceName: String? = null

    @Volatile
    private var adbStatus = "DISCONNECTED"

    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    @Volatile
    private var activityDestroyed = false

    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            when (intent.action) {

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    sendUsbEvent("connected")
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {

                    val detachedDevice =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE
                            )
                        }

                    if (
                        detachedDevice != null &&
                        adbDeviceName == detachedDevice.deviceName
                    ) {
                        disconnectAdbInternal()
                    }

                    sendUsbEvent("disconnected")
                }

                ACTION_USB_PERMISSION -> {

                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )

                    sendUsbEvent(
                        if (granted) {
                            "permission_granted"
                        } else {
                            "permission_denied"
                        }
                    )
                }
            }
        }
    }

    
override fun configureFlutterEngine(
    flutterEngine: FlutterEngine
) {
    super.configureFlutterEngine(flutterEngine)

    usbManager =
        getSystemService(Context.USB_SERVICE) as UsbManager

    MethodChannel(
        flutterEngine.dartExecutor.binaryMessenger,
        METHOD_CHANNEL
    ).setMethodCallHandler { call, result ->

        when (call.method) {

            "executeStandardDiagnostic" -> {
                val command = call.argument<String>("command")

                if (command.isNullOrBlank()) {
                    result.error(
                        "INVALID_COMMAND",
                        "A command is required.",
                        null
                    )
                } else {
                    try {
                        result.success(
                            executeStandardDiagnostic(command)
                        )
                    } catch (e: Exception) {
                        result.error(
                            "DIAGNOSTIC_ERROR",
                            e.message ?: "Diagnostic command failed.",
                            null
                        )
                    }
                }
            }

            "executeAdbCommand" -> {
                executeTerminalCommand(
                    call,
                    result,
                    requireRoot = false
                )
            }

            "executeRootCommand" -> {
                executeTerminalCommand(
                    call,
                    result,
                    requireRoot = true
                )
            }

            "executeRepairCommand" -> {
                executeRepairCommand(call, result)
            }

            "getUsbDevices" -> {
                try {
                    result.success(getConnectedUsbDevices())
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to retrieve USB devices", e)
                    result.error(
                        "USB_ERROR",
                        e.message ?: "Unable to retrieve USB devices.",
                        null
                    )
                }
            }

            "getAdbInterfaces" -> {
                try {
                    result.success(getAdbInterfaces())
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to inspect ADB interfaces", e)
                    result.error(
                        "ADB_INTERFACE_ERROR",
                        e.message ?: "Unable to retrieve ADB interfaces.",
                        null
                    )
                }
            }

            "requestUsbPermission" -> {
                val deviceName =
                    call.argument<String>("deviceName")

                if (deviceName.isNullOrBlank()) {
                    result.error(
                        "INVALID_DEVICE",
                        "USB device name is required.",
                        null
                    )
                    return@setMethodCallHandler
                }

                val device = usbManager.deviceList[deviceName]

                if (device == null) {
                    result.error(
                        "DEVICE_NOT_FOUND",
                        "USB device is no longer connected.",
                        null
                    )
                    return@setMethodCallHandler
                }

                try {
                    requestDevicePermission(device)
                    result.success(true)
                } catch (e: Exception) {
                    Log.e(TAG, "USB permission request failed", e)
                    result.error(
                        "PERMISSION_ERROR",
                        e.message ?: "USB permission request failed.",
                        null
                    )
                }
            }

            "connectAdb" -> {
                connectAdb(call, result)
            }

            "getAdbConnectionStatus" -> {
                result.success(
                    mapOf(
                        "status" to getCurrentAdbStatus(),
                        "connected" to (
                            adbConnection?.isConnected() == true
                        )
                    )
                )
            }

            "disconnectAdb" -> {
                disconnectAdbInternal()
                result.success(true)
            }

            "getAdbKeyFingerprint" -> {
                getAdbKeyFingerprint(result)
            }

            "isUsbHostSupported" -> {
                result.success(
                    packageManager.hasSystemFeature(
                        PackageManager.FEATURE_USB_HOST
                    )
                )
            }

            "exportApplicationApk" -> {
                val packageName =
                    call.argument<String>("packageName")

                if (packageName.isNullOrBlank()) {
                    result.error(
                        "INVALID_PACKAGE",
                        "A valid package name is required.",
                        null
                    )
                    return@setMethodCallHandler
                }

                exportApplicationApk(packageName, result)
            }

            "shareApplicationApkBackup" -> {
                val backupDirectory =
                    call.argument<String>("backupDirectory")

                val files =
                    call.argument<List<*>>("files")

                if (
                    backupDirectory.isNullOrBlank() ||
                    files.isNullOrEmpty()
                ) {
                    result.error(
                        "INVALID_BACKUP",
                        "A backup directory and verified APK file list are required.",
                        null
                    )
                    return@setMethodCallHandler
                }

                shareApplicationApkBackup(
                    backupDirectory,
                    files,
                    result
                )
            }

            "getInstalledApplications" -> {
                try {
                    val inventory = ApplicationInventory(this)

                    result.success(
                        inventory.getInstalledApplications()
                    )
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Unable to retrieve installed applications",
                        e
                    )
                    result.error(
                        "APP_INVENTORY_ERROR",
                        e.message
                            ?: "Unable to retrieve installed applications.",
                        null
                    )
                }
            }

            "getApplicationPermissions" -> {
                val packageName =
                    call.argument<String>("packageName")

                if (packageName.isNullOrBlank()) {
                    result.error(
                        "INVALID_PACKAGE",
                        "A valid package name is required.",
                        null
                    )
                    return@setMethodCallHandler
                }

                try {
                    result.success(
                        getApplicationPermissions(packageName)
                    )
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Unable to retrieve application permissions",
                        e
                    )
                    result.error(
                        "APP_PERMISSION_ERROR",
                        e.message
                            ?: "Unable to retrieve application permissions.",
                        null
                    )
                }
            }

            "connectWirelessAdb" -> {
                connectWirelessAdb(call, result)
            }

            "getWirelessAdbStatus" -> {
                result.success(getWirelessAdbStatus())
            }

                        "disconnectWirelessAdb" -> {
                disconnectWirelessAdb(result)
            }

            "saveRepairHistory" -> {
                try {
                    val operation =
                        call.argument<String>("operation") ?: ""

                    val category =
                        call.argument<String>("category") ?: "General"

                    val repairResult =
                        call.argument<String>("result") ?: "Unknown"

                    val details =
                        call.argument<String>("details") ?: ""

                    val saved = RepairHistoryStore.save(
                        this,
                        operation,
                        category,
                        repairResult,
                        details
                    )

                    result.success(saved)

                } catch (e: Exception) {
                    result.error(
                        "HISTORY_SAVE_ERROR",
                        e.message ?: "Unable to save repair history.",
                        null
                    )
                }
            }

            "getRepairHistory" -> {
                try {
                    result.success(
                        RepairHistoryStore.getAll(this)
                    )
                } catch (e: Exception) {
                    result.error(
                        "HISTORY_READ_ERROR",
                        e.message ?: "Unable to retrieve repair history.",
                        null
                    )
                }
            }

            "clearRepairHistory" -> {
                try {
                    result.success(
                        RepairHistoryStore.clear(this)
                    )
                } catch (e: Exception) {
                    result.error(
                        "HISTORY_CLEAR_ERROR",
                        e.message ?: "Unable to clear repair history.",
                        null
                    )
                }
            }

            else -> result.notImplemented()
        }
    }

    EventChannel(
        flutterEngine.dartExecutor.binaryMessenger,
        EVENT_CHANNEL
    ).setStreamHandler(
        object : EventChannel.StreamHandler {

            override fun onListen(
                arguments: Any?,
                events: EventChannel.EventSink?
            ) {
                eventSink = events
                sendUsbEvent("snapshot")
                sendAdbEvent("snapshot")
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        }
    )
}


    private fun executeStandardDiagnostic(
        command: String
    ): String {

        return when (command.trim().lowercase()) {

            "help" -> {
                """
                ULTIMATE DEBUGGING TOOL
                Standard Android Commands

                help         - Show available commands
                device-info  - Show device information
                battery      - Show battery information
                storage      - Show internal storage information
                memory       - Show RAM information
                uptime       - Show device uptime
                """.trimIndent()
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
                    .getSensorList(Sensor.TYPE_ALL)
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
                    assessment.add("Android reports low memory conditions.")
                }

                if (selinuxStatus == "Permissive") {
                    assessment.add(
                        "WARNING: SELinux is permissive. Investigate device security."
                    )
                }

                if (verifiedBootState == "orange") {
                    assessment.add(
                        "Verified Boot reports an unlocked or modified boot state."
                    )
                }

                if (assessment.isEmpty()) {
                    assessment.add(
                        "No immediate abnormal condition detected from available readings."
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

                Total RAM: %.2f GB
                Used RAM: %.2f GB
                Available RAM: %.2f GB
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

                """.trimIndent().format(
                    totalRAM,
                    usedRAM,
                    availableRAM
                )
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

                    val temperature = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_TEMPERATURE,
                        -1
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
                        if (temperature >= 0) {
                            temperature / 10.0
                        } else {
                            -1.0
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
                        if (temperatureC >= 0) "%.1f °C".format(temperatureC)
                        else "Unavailable"
                    }

                    [TECHNICIAN ASSESSMENT]
                    ${assessment.joinToString("\n") { "- $it" }}

                    [DATA LIMITATIONS]
                    Battery capacity, cycle count and detailed charging-controller diagnostics
                    are not exposed consistently by Android. Additional manufacturer-specific
                    or authorized ADB diagnostics may be required.
                    """.trimIndent()
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
                        """.trimIndent()

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
                            """.trimIndent()
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

                """.trimIndent()
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

                """.trimIndent()
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

                """.trimIndent()
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
                            """.trimIndent()
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

                val assessment =
                    mutableListOf<String>()

                if (activeNetwork == null) {
                    assessment.add(
                        "No active network connection detected."
                    )
                }

                if (activeNetwork != null && !validatedInternet) {
                    assessment.add(
                        "Network is connected but Android has not validated internet access."
                    )
                }

                if (connectionType == "Wi-Fi" && wifiSignal < -70) {
                    assessment.add(
                        "Wi-Fi signal is weak. Inspect distance, interference and router condition."
                    )
                }

                if (vpnActive) {
                    assessment.add(
                        "VPN transport is active. It may affect routing or connectivity."
                    )
                }

                if (assessment.isEmpty()) {
                    assessment.add(
                        "No immediate network warning detected from available indicators."
                    )
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

                ${assessment.joinToString("\n") { "- $it" }}

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

                """.trimIndent()
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

                val cpuFrequencies = (0..cpuCount.coerceAtMost(15))
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
                        """.trimIndent()
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

                """.trimIndent()
            }



            else -> {
                "Unknown command: $command\nType 'help' to see available commands."
            }
        }
    }

    
private fun executeTerminalCommand(
    call: MethodCall,
    result: MethodChannel.Result,
    requireRoot: Boolean
) {
    val command = call.argument<String>("command")
        ?.trim()
        ?.lowercase()

    if (command.isNullOrBlank()) {
        result.error(
            "INVALID_COMMAND",
            "Enter a command first.",
            null
        )
        return
    }

    data class DiagnosticCommand(
        val shellCommand: String,
        val description: String
    )

    val commands = linkedMapOf(
        "id" to DiagnosticCommand(
            "id",
            "Show the current shell user and permissions."
        ),
        "whoami" to DiagnosticCommand(
            "whoami",
            "Show the current username."
        ),
        "uname -a" to DiagnosticCommand(
            "uname -a",
            "Show kernel and system information."
        ),
        "getprop" to DiagnosticCommand(
            "getprop",
            "List Android system properties."
        ),
        "df -h" to DiagnosticCommand(
            "df -h",
            "Show filesystem storage usage."
        ),
        "cat /proc/meminfo" to DiagnosticCommand(
            "cat /proc/meminfo",
            "Show detailed memory information."
        ),
        "dumpsys battery" to DiagnosticCommand(
            "dumpsys battery",
            "Inspect battery status and properties."
        ),
        "pm list packages" to DiagnosticCommand(
            "pm list packages",
            "List installed application packages."
        ),
        "device-model" to DiagnosticCommand(
            "getprop ro.product.model",
            "Show the device model."
        ),
        "manufacturer" to DiagnosticCommand(
            "getprop ro.product.manufacturer",
            "Show the manufacturer."
        ),
        "device-brand" to DiagnosticCommand(
            "getprop ro.product.brand",
            "Show the device brand."
        ),
        "android-version" to DiagnosticCommand(
            "getprop ro.build.version.release",
            "Show the Android version."
        ),
        "sdk-version" to DiagnosticCommand(
            "getprop ro.build.version.sdk",
            "Show the Android SDK version."
        ),
        "security-patch" to DiagnosticCommand(
            "getprop ro.build.version.security_patch",
            "Show the Android security patch date."
        ),
        "build-fingerprint" to DiagnosticCommand(
            "getprop ro.build.fingerprint",
            "Show the device build fingerprint."
        ),
        "cpu-architecture" to DiagnosticCommand(
            "getprop ro.product.cpu.abi",
            "Show the primary CPU architecture."
        ),
        "kernel-version" to DiagnosticCommand(
            "cat /proc/version",
            "Show detailed Linux kernel version."
        ),
        "cpu-info" to DiagnosticCommand(
            "cat /proc/cpuinfo",
            "Inspect available CPU information."
        ),
        "partitions" to DiagnosticCommand(
            "cat /proc/partitions",
            "List detected storage partitions."
        ),
        "uptime" to DiagnosticCommand(
            "cat /proc/uptime",
            "Show time since the device booted."
        ),
        "processes" to DiagnosticCommand(
            "ps -A",
            "List running processes."
        ),
        "ip-addresses" to DiagnosticCommand(
            "ip addr show",
            "Inspect network interfaces and IP addresses."
        ),
        "connectivity" to DiagnosticCommand(
            "dumpsys connectivity",
            "Inspect Android connectivity services."
        ),
        "wifi-status" to DiagnosticCommand(
            "dumpsys wifi",
            "Inspect Wi-Fi service information."
        ),
        "bluetooth-status" to DiagnosticCommand(
            "dumpsys bluetooth_manager",
            "Inspect Bluetooth service information."
        ),
        "usb-status" to DiagnosticCommand(
            "dumpsys usb",
            "Inspect USB service information."
        ),
        "display-info" to DiagnosticCommand(
            "dumpsys display",
            "Inspect display configuration."
        ),
        "thermal-status" to DiagnosticCommand(
            "dumpsys thermalservice",
            "Inspect thermal service information."
        ),
        "power-info" to DiagnosticCommand(
            "dumpsys power",
            "Inspect Android power management."
        ),
        "sensor-info" to DiagnosticCommand(
            "dumpsys sensorservice",
            "Inspect available sensor service information."
        ),
        "system-packages" to DiagnosticCommand(
            "pm list packages -s",
            "List system packages."
        ),
        "third-party-packages" to DiagnosticCommand(
            "pm list packages -3",
            "List third-party applications."
        ),
        "disabled-packages" to DiagnosticCommand(
            "pm list packages -d",
            "List disabled packages."
        ),
        "enabled-packages" to DiagnosticCommand(
            "pm list packages -e",
            "List enabled packages."
        ),
        "boot-reason" to DiagnosticCommand(
            "getprop sys.boot.reason",
            "Show the reported boot reason."
        ),
        "selinux-status" to DiagnosticCommand(
            "getenforce",
            "Show SELinux enforcement status."
        ),
        "activity-processes" to DiagnosticCommand(
            "dumpsys activity processes",
            "Inspect Android activity processes."
        )
    )

    if (command == "help") {
        val help = buildString {
            appendLine("ULTIMATE DEBUGGING TOOL")
            appendLine(
                if (requireRoot) "ROOT MODE" else "ADB MODE"
            )
            appendLine()
            appendLine("AVAILABLE READ-ONLY DIAGNOSTICS")
            appendLine()

            commands.forEach { (name, details) ->
                appendLine(
                    "${name.padEnd(23)} - ${details.description}"
                )
            }

            appendLine()
            appendLine(
                "These commands inspect device information."
            )
            appendLine(
                "They do not perform repairs or modify settings."
            )
            appendLine(
                "Some results depend on Android version and manufacturer."
            )
        }

        result.success(help)
        return
    }

    val selectedCommand = commands[command]

    if (selectedCommand == null) {
        result.error(
            "COMMAND_NOT_ALLOWED",
            "Command not supported. Type help to see available commands.",
            null
        )
        return
    }

    try {
        terminalExecutor.execute {

            try {
                val usbConnected =
                    adbConnection?.isConnected() == true

                val wirelessConnected =
                    wirelessStatus == "CONNECTED" &&
                        wirelessManager?.getConnectionInfo()?.state ==
                        AdbConnection.State.CONNECTED

                if (usbConnected && wirelessConnected) {
                    throw IOException(
                        "Both USB and Wireless ADB are connected. Disconnect one connection first."
                    )
                }

                if (!usbConnected && !wirelessConnected) {
                    throw IOException(
                        "No authenticated ADB connection. Connect a device using USB or Wireless ADB first."
                    )
                }

                fun executeAdbShell(
                    shellCommand: String
                ): AdbShellSession.CommandResult {
                    return if (usbConnected) {
                        adbConnection!!
                            .createShellSession()
                            .execute(shellCommand, 30000L)
                    } else {
                        wirelessManager!!
                            .executeCommand(shellCommand, 30000L)
                    }
                }

                fun shellQuote(value: String): String {
                    return "'" + value.replace(
                        "'",
                        "'\\''"
                    ) + "'"
                }

                if (requireRoot) {
                    val rootCheck = executeAdbShell(
                        "su -c ${shellQuote("id")}"
                    )

                    if (
                        rootCheck.exitCode != 0 ||
                        !rootCheck.output.contains("uid=0")
                    ) {
                        throw IOException(
                            "Root access was not granted. This device may not be rooted, or the root permission request was denied."
                        )
                    }
                }

                val shellCommand = if (requireRoot) {
                    "su -c ${shellQuote(selectedCommand.shellCommand)}"
                } else {
                    selectedCommand.shellCommand
                }

                val commandResult =
                    executeAdbShell(shellCommand)

                val output = buildString {
                    append(commandResult.output)

                    if (commandResult.errorOutput.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("ERROR:\n")
                        append(commandResult.errorOutput)
                    }

                    if (commandResult.exitCode != null) {
                        if (isNotEmpty()) append("\n\n")
                        append("Exit code: ")
                        append(commandResult.exitCode)
                    }
                }.ifBlank {
                    "Command completed without output."
                }

                mainHandler.post {
                    result.success(output)
                }

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Terminal command execution failed",
                    e
                )

                mainHandler.post {
                    result.error(
                        "TERMINAL_EXECUTION_ERROR",
                        e.message ?: "Terminal command failed.",
                        null
                    )
                }
            }
        }
    } catch (e: Exception) {
        result.error(
            "TERMINAL_EXECUTION_ERROR",
            e.message ?: "Unable to start terminal command.",
            null
        )
    }
}



private fun executeRepairCommand(
    call: MethodCall,
    result: MethodChannel.Result
) {
    val operation =
        call.argument<String>("operation")?.trim()

    val packageName =
        call.argument<String>("packageName")?.trim()

    val confirmed =
        call.argument<Boolean>("confirmed") == true

    if (operation != "force-stop-app") {
        result.error(
            "REPAIR_NOT_ALLOWED",
            "This repair operation is not approved.",
            null
        )
        return
    }

    if (!confirmed) {
        result.error(
            "CONFIRMATION_REQUIRED",
            "Explicit confirmation is required before repair.",
            null
        )
        return
    }

    if (
        packageName.isNullOrBlank() ||
        !packageName.matches(
            Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        )
    ) {
        result.error(
            "INVALID_PACKAGE",
            "Enter a valid application package name.",
            null
        )
        return
    }

    try {
        terminalExecutor.execute {
            try {
                val usbConnected =
                    adbConnection?.isConnected() == true

                val wirelessConnected =
                    wirelessStatus == "CONNECTED" &&
                        wirelessManager?.getConnectionInfo()?.state ==
                        AdbConnection.State.CONNECTED

                if (usbConnected && wirelessConnected) {
                    throw IOException(
                        "Disconnect either USB or Wireless ADB before repairing."
                    )
                }

                if (!usbConnected && !wirelessConnected) {
                    throw IOException(
                        "No authenticated ADB connection. Connect the target device first."
                    )
                }

                fun executeShell(
                    command: String
                ): AdbShellSession.CommandResult {
                    return if (usbConnected) {
                        adbConnection!!
                            .createShellSession()
                            .execute(command, 30000L)
                    } else {
                        wirelessManager!!
                            .executeCommand(command, 30000L)
                    }
                }

                fun shellQuote(value: String): String {
                    return "'" + value.replace(
                        "'",
                        "'\\''"
                    ) + "'"
                }

                val quotedPackage =
                    shellQuote(packageName)

                val packageCheck = executeShell(
                    "pm path $quotedPackage"
                )

                if (
                    packageCheck.exitCode != 0 ||
                    !packageCheck.output.contains("package:")
                ) {
                    throw IOException(
                        "The application was not found on the connected device."
                    )
                }

                val systemCheck = executeShell(
                    "pm list packages -s $quotedPackage"
                )

                val isSystemPackage =
                    systemCheck.output
                        .lineSequence()
                        .any {
                            it.trim() == "package:$packageName"
                        }

                if (isSystemPackage) {
                    throw SecurityException(
                        "System applications cannot be force-stopped through this repair operation."
                    )
                }

                val repairResult = executeShell(
                    "am force-stop --user 0 $quotedPackage"
                )

                if (repairResult.exitCode != 0) {
                    throw IOException(
                        repairResult.errorOutput.ifBlank {
                            "The application force-stop operation failed."
                        }
                    )
                }

                mainHandler.post {
                    if (!activityDestroyed) {
                        result.success(
                            mapOf(
                                "success" to true,
                                "operation" to "force-stop-app",
                                "packageName" to packageName,
                                "risk" to "LOW",
                                "dataPreserved" to true,
                                "message" to "The application was force-stopped successfully.",
                                "verification" to "ADB reported successful execution. Reopen the application to test it."
                            )
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Repair operation failed",
                    e
                )

                mainHandler.post {
                    if (!activityDestroyed) {
                        result.error(
                            "REPAIR_EXECUTION_ERROR",
                            e.message ?: "Repair operation failed.",
                            null
                        )
                    }
                }
            }
        }
    } catch (e: Exception) {
        result.error(
            "REPAIR_EXECUTION_ERROR",
            e.message ?: "Unable to start repair operation.",
            null
        )
    }
}



    private fun connectAdb(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val deviceName =
            call.argument<String>("deviceName")

        val interfaceId =
            call.argument<Number>("interfaceId")?.toInt()

        if (deviceName.isNullOrBlank() || interfaceId == null) {
            result.error(
                "INVALID_ARGUMENTS",
                "Device name and interface ID are required.",
                null
            )
            return
        }

        synchronized(adbLifecycleLock) {

            val wirelessConnectionState =
                wirelessManager?.getConnectionInfo()?.state

            if (
                adbStatus in setOf(
                    "CONNECTING",
                    "AUTHENTICATION_REQUIRED",
                    "CONNECTED"
                ) ||
                wirelessStatus in setOf(
                    "CONNECTING",
                    "AUTHENTICATION_REQUIRED",
                    "CONNECTED"
                ) ||
                wirelessConnectionState in setOf(
                    AdbConnection.State.CONNECTING,
                    AdbConnection.State.AUTHENTICATION_REQUIRED,
                    AdbConnection.State.CONNECTED
                )
            ) {
                result.error(
                    "ADB_BUSY",
                    "USB ADB cannot connect while Wireless ADB is active or connecting. Disconnect Wireless ADB first.",
                    null
                )
                return
            }

            val device = usbManager.deviceList[deviceName]

            if (device == null) {
                result.error(
                    "DEVICE_NOT_FOUND",
                    "The selected USB device is no longer connected.",
                    null
                )
                return
            }

            if (!usbManager.hasPermission(device)) {
                result.error(
                    "USB_PERMISSION_REQUIRED",
                    "Grant USB permission before connecting to ADB.",
                    null
                )
                return
            }

            val adbInterfaceExists =
                (0 until device.interfaceCount)
                    .map { device.getInterface(it) }
                    .any {
                        it.id == interfaceId &&
                            it.interfaceClass == ADB_CLASS &&
                            it.interfaceSubclass == ADB_SUBCLASS &&
                            it.interfaceProtocol == ADB_PROTOCOL
                    }

            if (!adbInterfaceExists) {
                result.error(
                    "ADB_INTERFACE_NOT_FOUND",
                    "The selected ADB interface was not found.",
                    null
                )
                return
            }

            adbStatus = "CONNECTING"
            adbDeviceName = device.deviceName
        }

        sendAdbEvent("connecting")

        try {
            adbExecutor.execute {

                var transport: AdbUsbTransport? = null
                var connection: AdbConnection? = null

                try {
                    transport = AdbUsbTransport(
                        usbManager,
                        usbManager.deviceList[deviceName]
                            ?: throw IOException(
                                "The USB device was disconnected."
                            ),
                        interfaceId
                    )

                    lateinit var createdConnection: AdbConnection

                    createdConnection = AdbConnection(transport) { newState ->

                        if (adbConnection === createdConnection) {
                            adbStatus = newState.name

                            val event = when (newState) {
                                AdbConnection.State.CONNECTING ->
                                    "connecting"

                                AdbConnection.State.AUTHENTICATION_REQUIRED ->
                                    "authentication_required"

                                AdbConnection.State.CONNECTED ->
                                    "connected"

                                AdbConnection.State.FAILED ->
                                    "failed"

                                AdbConnection.State.CLOSED ->
                                    "disconnected"

                                AdbConnection.State.DISCONNECTED ->
                                    "disconnected"
                            }

                            sendAdbEvent(event)
                        }
                    }

                    connection = createdConnection

                    synchronized(adbLifecycleLock) {
                        if (adbStatus != "CONNECTING") {
                            throw AdbConnectionCancelledException(
                                "USB ADB connection was cancelled."
                            )
                        }

                        adbTransport = transport
                        adbConnection = createdConnection
                    }

                    transport.open()

                    val authentication = AdbAuthentication()

                    val handshake =
                        createdConnection.connect(authentication)

                    if (adbConnection !== createdConnection) {
                        throw AdbConnectionCancelledException(
                            "ADB connection was replaced or cancelled."
                        )
                    }

                    when (handshake) {

                        is AdbConnection.HandshakeResult.Connected -> {

                            adbStatus = "CONNECTED"

                            mainHandler.post {
                                result.success(
                                    mapOf(
                                        "status" to "CONNECTED",
                                        "version" to handshake.version,
                                        "maxDataSize" to handshake.maxDataSize,
                                        "deviceBanner" to handshake.deviceBanner,
                                        "features" to handshake.features,
                                        "keyFingerprint" to
                                            authentication.getPublicKeyFingerprint()
                                    )
                                )
                            }
                        }

                        is AdbConnection.HandshakeResult.AuthenticationRequired -> {

                            adbStatus = "AUTHENTICATION_REQUIRED"

                            mainHandler.post {
                                result.success(
                                    mapOf(
                                        "status" to "AUTHENTICATION_REQUIRED",
                                        "message" to
                                            "The device requires ADB authentication."
                                    )
                                )
                            }
                        }

                        is AdbConnection.HandshakeResult.Rejected -> {

                            if (adbConnection === createdConnection) {
                                adbConnection = null
                                adbTransport = null
                                adbDeviceName = null
                                adbStatus = "FAILED"
                            }

                            createdConnection.close()

                            mainHandler.post {
                                sendAdbEvent("failed")

                                result.error(
                                    "ADB_REJECTED",
                                    handshake.reason,
                                    null
                                )
                            }
                        }
                    }

                } catch (e: Exception) {

                    val wasCancelled =
                        e is AdbConnectionCancelledException ||
                            connection?.isCancelled() == true ||
                            (
                                connection != null &&
                                    adbConnection !== connection
                            )

                    if (wasCancelled) {
                        Log.i(TAG, "ADB connection was cancelled.")

                        if (adbConnection === connection) {
                            adbConnection = null
                            adbTransport = null
                            adbDeviceName = null
                            adbStatus = "DISCONNECTED"
                        }

                        try {
                            connection?.close()
                        } catch (_: Exception) {
                            // Continue cancellation reporting.
                        }

                        mainHandler.post {
                            result.error(
                                "ADB_CANCELLED",
                                "ADB connection was cancelled.",
                                null
                            )
                        }

                    } else {
                        Log.e(TAG, "ADB connection failed", e)

                        if (adbConnection === connection) {
                            adbConnection = null
                            adbTransport = null
                            adbDeviceName = null
                            adbStatus = "FAILED"
                        }

                        try {
                            connection?.close()
                            if (connection == null) {
                                transport?.close()
                            }
                        } catch (_: Exception) {
                            // Continue failure reporting.
                        }

                        mainHandler.post {
                            sendAdbEvent("failed")

                            result.error(
                                "ADB_CONNECTION_ERROR",
                                e.message ?: "ADB connection failed.",
                                null
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            adbStatus = "FAILED"
            adbDeviceName = null

            sendAdbEvent("failed")

            result.error(
                "ADB_EXECUTOR_ERROR",
                e.message ?: "Unable to start USB ADB connection.",
                null
            )
        }
    }

    private fun connectWirelessAdb(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val host = call.argument<String>("host")?.trim()
        val port = call.argument<Number>("port")?.toInt()

        if (
            host.isNullOrBlank() ||
            port == null ||
            port !in 1..65535
        ) {
            result.error(
                "INVALID_ARGUMENTS",
                "A valid device IP address and TCP port are required.",
                null
            )
            return
        }

        synchronized(adbLifecycleLock) {

            val currentUsbStatus = getCurrentAdbStatus()

            if (
                wirelessStatus in setOf(
                    "CONNECTED",
                    "AUTHENTICATION_REQUIRED",
                    "CONNECTING"
                ) ||
                currentUsbStatus in setOf(
                    "CONNECTED",
                    "AUTHENTICATION_REQUIRED",
                    "CONNECTING"
                ) ||
                adbStatus in setOf(
                    "CONNECTED",
                    "AUTHENTICATION_REQUIRED",
                    "CONNECTING"
                )
            ) {
                result.error(
                    "WIRELESS_BUSY",
                    "Wireless ADB cannot connect while USB ADB is active or connecting. Disconnect USB ADB first.",
                    null
                )
                return
            }

            wirelessHost = host
            wirelessPort = port
            wirelessStatus = "CONNECTING"
        }

        val resultSent = AtomicBoolean(false)

        if (!pendingWirelessResult.compareAndSet(null, result)) {
            wirelessStatus = "DISCONNECTED"

            result.error(
                "WIRELESS_REQUEST_PENDING",
                "Another Wireless ADB connection request is still pending.",
                null
            )
            return
        }

        pendingWirelessResultSent.set(resultSent)

        val manager = try {
            wirelessManager ?: AdbWirelessManager(
                authentication = AdbAuthentication(),
                onStateChanged = { state ->
                    wirelessStatus = state.name
                }
            ).also {
                wirelessManager = it
            }
        } catch (e: Exception) {
            pendingWirelessResult.compareAndSet(result, null)
            pendingWirelessResultSent.compareAndSet(resultSent, null)
            wirelessStatus = "FAILED"

            result.error(
                "WIRELESS_INITIALIZATION_ERROR",
                e.message ?: "Unable to initialize Wireless ADB.",
                null
            )
            return
        }

        sendAdbEvent("wireless_connecting")

        try {
            wirelessExecutor.execute {

                try {
                    manager.connect(host, port) { info ->

                        wirelessStatus = info.state.name

                        val isFinalState = when (info.state) {
                            AdbConnection.State.CONNECTED,
                            AdbConnection.State.FAILED,
                            AdbConnection.State.CLOSED,
                            AdbConnection.State.DISCONNECTED -> true

                            else -> false
                        }

                        if (
                            isFinalState &&
                            resultSent.compareAndSet(false, true)
                        ) {
                            pendingWirelessResult.compareAndSet(
                                result,
                                null
                            )

                            pendingWirelessResultSent.compareAndSet(
                                resultSent,
                                null
                            )

                            mainHandler.post {

                                if (
                                    info.state == AdbConnection.State.FAILED ||
                                    info.state == AdbConnection.State.CLOSED ||
                                    info.state == AdbConnection.State.DISCONNECTED
                                ) {
                                    result.error(
                                        "WIRELESS_CONNECTION_FAILED",
                                        info.error
                                            ?: "Wireless ADB connection ended before it was established.",
                                        null
                                    )
                                } else {
                                    result.success(
                                        mapOf(
                                            "status" to info.state.name,
                                            "host" to info.host,
                                            "port" to info.port,
                                            "deviceBanner" to info.deviceBanner,
                                            "features" to info.features,
                                            "error" to info.error
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Unable to start Wireless ADB connection",
                        e
                    )

                    if (resultSent.compareAndSet(false, true)) {
                        pendingWirelessResult.compareAndSet(
                            result,
                            null
                        )

                        pendingWirelessResultSent.compareAndSet(
                            resultSent,
                            null
                        )

                        wirelessStatus = "FAILED"

                        mainHandler.post {
                            result.error(
                                "WIRELESS_CONNECTION_ERROR",
                                e.message
                                    ?: "Unable to start Wireless ADB connection.",
                                null
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            pendingWirelessResult.compareAndSet(result, null)
            pendingWirelessResultSent.compareAndSet(resultSent, null)

            wirelessStatus = "FAILED"

            result.error(
                "WIRELESS_EXECUTOR_ERROR",
                e.message ?: "Unable to schedule Wireless ADB connection.",
                null
            )
        }
    }

    private fun getWirelessAdbStatus(): Map<String, Any?> {
        val info = wirelessManager?.getConnectionInfo()

        return mapOf(
            "status" to wirelessStatus,
            "host" to wirelessHost,
            "port" to wirelessPort,
            "connected" to (wirelessStatus == "CONNECTED"),
            "deviceBanner" to info?.deviceBanner,
            "features" to info?.features,
            "error" to info?.error
        )
    }

    private fun disconnectWirelessAdb(
        result: MethodChannel.Result
    ) {
        try {
            wirelessManager?.disconnect()

            wirelessStatus = "DISCONNECTED"
            wirelessHost = null
            wirelessPort = null

            val pendingResult = pendingWirelessResult.getAndSet(null)
            val pendingSent = pendingWirelessResultSent.getAndSet(null)

            if (
                pendingResult != null &&
                pendingSent?.compareAndSet(false, true) == true
            ) {
                mainHandler.post {
                    pendingResult.error(
                        "WIRELESS_CANCELLED",
                        "Wireless ADB connection was cancelled.",
                        null
                    )
                }
            }

            result.success(
                mapOf(
                    "status" to "DISCONNECTED",
                    "connected" to false
                )
            )

        } catch (error: Exception) {
            Log.e(TAG, "Wireless ADB disconnect failed", error)

            result.error(
                "WIRELESS_DISCONNECT_ERROR",
                error.message ?: "Unable to disconnect Wireless ADB.",
                null
            )
        }
    }

    private fun getAdbKeyFingerprint(
        result: MethodChannel.Result
    ) {
        try {
            adbExecutor.execute {

                try {
                    val authentication = AdbAuthentication()

                    authentication.initialize()

                    val fingerprint =
                        authentication.getPublicKeyFingerprint()

                    mainHandler.post {
                        result.success(fingerprint)
                    }

                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Unable to retrieve ADB fingerprint",
                        e
                    )

                    mainHandler.post {
                        result.error(
                            "ADB_KEY_ERROR",
                            e.message
                                ?: "Unable to retrieve ADB fingerprint.",
                            null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            result.error(
                "ADB_EXECUTOR_ERROR",
                e.message ?: "Unable to schedule ADB fingerprint request.",
                null
            )
        }
    }

    private fun getCurrentAdbStatus(): String {
        val connection = adbConnection

        if (connection != null) {
            return connection.state.name
        }

        return adbStatus
    }

    private fun disconnectAdbInternal() {

        val connection: AdbConnection?

        synchronized(adbLifecycleLock) {
            connection = adbConnection

            adbConnection = null
            adbTransport = null
            adbDeviceName = null
            adbStatus = "DISCONNECTED"
        }

        try {
            connection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "ADB disconnect error", e)
        }

        sendAdbEvent("disconnected")
    }

    private fun sendAdbEvent(event: String) {

        mainHandler.post {

            try {
                eventSink?.success(
                    mapOf(
                        "event" to event,
                        "status" to getCurrentAdbStatus()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to send ADB event", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()

        if (!receiverRegistered) {

            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    usbReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(usbReceiver, filter)
            }

            receiverRegistered = true
        }
    }

    override fun onStop() {

        if (receiverRegistered) {
            unregisterReceiver(usbReceiver)
            receiverRegistered = false
        }

        super.onStop()
    }

    override fun onDestroy() {

        activityDestroyed = true

        disconnectAdbInternal()

        try {
            wirelessManager?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Wireless ADB shutdown failed", e)
        }

        wirelessManager = null

        val pendingResult = pendingWirelessResult.getAndSet(null)
        val pendingSent = pendingWirelessResultSent.getAndSet(null)

        if (
            pendingResult != null &&
            pendingSent?.compareAndSet(false, true) == true
        ) {
            mainHandler.post {
                pendingResult.error(
                    "ACTIVITY_DESTROYED",
                    "The activity was destroyed before Wireless ADB completed.",
                    null
                )
            }
        }

        wirelessExecutor.shutdownNow()
        adbExecutor.shutdownNow()
        terminalExecutor.shutdownNow()

        eventSink = null

        super.onDestroy()
    }

    private fun sendUsbEvent(event: String) {

        mainHandler.post {
            try {
                eventSink?.success(
                    mapOf(
                        "event" to event,
                        "devices" to getConnectedUsbDevices()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to send USB event: $event", e)

                eventSink?.error(
                    "USB_EVENT_ERROR",
                    e.message
                        ?: "Unable to retrieve USB device information.",
                    null
                )
            }
        }
    }

    private fun getConnectedUsbDevices(): List<Map<String, Any>> {

        return usbManager.deviceList.values.map { device ->

            mapOf(
                "deviceName" to device.deviceName,
                "deviceId" to device.deviceId,
                "vendorId" to device.vendorId,
                "productId" to device.productId,
                "manufacturerName" to
                    (device.manufacturerName ?: "Unknown"),
                "productName" to
                    (device.productName ?: "Unknown"),
                "deviceClass" to device.deviceClass,
                "deviceSubclass" to device.deviceSubclass,
                "interfaceCount" to device.interfaceCount,
                "hasPermission" to usbManager.hasPermission(device)
            )
        }
    }

    private fun getAdbInterfaces(): List<Map<String, Any>> {

        val results = mutableListOf<Map<String, Any>>()

        for (device in usbManager.deviceList.values) {

            for (index in 0 until device.interfaceCount) {

                val usbInterface = device.getInterface(index)

                val isAdbInterface =
                    usbInterface.interfaceClass == ADB_CLASS &&
                        usbInterface.interfaceSubclass == ADB_SUBCLASS &&
                        usbInterface.interfaceProtocol == ADB_PROTOCOL

                if (!isAdbInterface) continue

                val endpoints = mutableListOf<Map<String, Any>>()

                for (endpointIndex in 0 until usbInterface.endpointCount) {

                    val endpoint =
                        usbInterface.getEndpoint(endpointIndex)

                    endpoints.add(
                        mapOf(
                            "address" to endpoint.address,
                            "endpointNumber" to endpoint.endpointNumber,
                            "direction" to getEndpointDirection(endpoint),
                            "type" to getEndpointType(endpoint),
                            "maxPacketSize" to endpoint.maxPacketSize
                        )
                    )
                }

                val hasBulkIn = endpoints.any {
                    it["direction"] == "IN" &&
                        it["type"] == "BULK"
                }

                val hasBulkOut = endpoints.any {
                    it["direction"] == "OUT" &&
                        it["type"] == "BULK"
                }

                results.add(
                    mapOf(
                        "deviceName" to device.deviceName,
                        "deviceId" to device.deviceId,
                        "vendorId" to device.vendorId,
                        "productId" to device.productId,
                        "manufacturerName" to
                            (device.manufacturerName ?: "Unknown"),
                        "productName" to
                            (device.productName ?: "Unknown"),
                        "interfaceId" to usbInterface.id,
                        "interfaceClass" to usbInterface.interfaceClass,
                        "interfaceSubclass" to usbInterface.interfaceSubclass,
                        "interfaceProtocol" to usbInterface.interfaceProtocol,
                        "hasBulkIn" to hasBulkIn,
                        "hasBulkOut" to hasBulkOut,
                        "hasAdbEndpoints" to (hasBulkIn && hasBulkOut),
                        "hasPermission" to usbManager.hasPermission(device),
                        "endpoints" to endpoints
                    )
                )
            }
        }

        return results
    }

    private fun getEndpointDirection(
        endpoint: UsbEndpoint
    ): String {
        return when (endpoint.direction) {
            UsbConstants.USB_DIR_IN -> "IN"
            UsbConstants.USB_DIR_OUT -> "OUT"
            else -> "UNKNOWN"
        }
    }

    private fun getEndpointType(
        endpoint: UsbEndpoint
    ): String {
        return when (endpoint.type) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
            else -> "UNKNOWN"
        }
    }

    private fun getApplicationPermissions(
        packageName: String
    ): List<Map<String, String>> {

        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_PERMISSIONS.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_PERMISSIONS
                )
            }

        val requestedPermissions =
            packageInfo.requestedPermissions ?: return emptyList()

        val permissionFlags =
            packageInfo.requestedPermissionsFlags

        return requestedPermissions.mapIndexed { index, permission ->

            val granted =
                permissionFlags != null &&
                    index < permissionFlags.size &&
                    (permissionFlags[index] and
                        android.content.pm.PackageInfo
                            .REQUESTED_PERMISSION_GRANTED) != 0

            val special = isSpecialPermission(permission)

            val status = when {
                special -> "unknown"
                granted -> "granted"
                else -> "denied"
            }

            val sensitivity = getPermissionSensitivity(permission)

            val explanation = when {
                special ->
                    "This permission may require special access in Android settings. Its actual access state has not been verified."

                sensitivity == "critical" ->
                    "This permission may provide access to highly sensitive messages, calls or communication information."

                sensitivity == "sensitive" ->
                    "This permission may provide access to personal information or sensitive device features."

                else ->
                    "This permission is declared by the application. Review its purpose and whether it is necessary."
            }

            val recommendation = when {
                special ->
                    "Inspect the relevant Android special-access settings before making a decision."

                status == "denied" ->
                    "Check whether the application requires this permission to function correctly."

                sensitivity == "critical" ->
                    "Verify that this permission is necessary and that the application is trusted."

                sensitivity == "sensitive" ->
                    "Confirm that this access is appropriate for the application's purpose."

                else ->
                    "No immediate action is indicated. Review if investigating an application problem."
            }

            mapOf(
                "permissionName" to permission,
                "status" to status,
                "sensitivity" to sensitivity,
                "explanation" to explanation,
                "recommendation" to recommendation
            )
        }
    }

    private fun isSpecialPermission(
        permission: String
    ): Boolean {

        val specialPermissions = setOf(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.WRITE_SETTINGS",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.SCHEDULE_EXACT_ALARM"
        )

        return permission in specialPermissions
    }

    private fun getPermissionSensitivity(
        permission: String
    ): String {

        val criticalPermissions = setOf(
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS"
        )

        if (permission in criticalPermissions) {
            return "critical"
        }

        if (isSpecialPermission(permission)) {
            return "special"
        }

        val sensitivePermissions = setOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.BODY_SENSORS",
            "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE"
        )

        if (
            permission in sensitivePermissions ||
            permission.startsWith("android.permission.READ_MEDIA_")
        ) {
            return "sensitive"
        }

        return "normal"
    }

    private fun requestDevicePermission(
        device: UsbDevice
    ) {

        val intent = Intent(ACTION_USB_PERMISSION)
            .setPackage(packageName)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

        val permissionIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            intent,
            flags
        )

        usbManager.requestPermission(
            device,
            permissionIntent
        )
    }

    private fun calculateSha256(
        file: File
    ): String {

        val digest = MessageDigest.getInstance("SHA-256")

        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)

            while (true) {
                val bytesRead = input.read(buffer)

                if (bytesRead == -1) {
                    break
                }

                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().joinToString("") {
            "%02X".format(it)
        }
    }

    private fun exportApplicationApk(
        packageName: String,
        result: MethodChannel.Result
    ) {

        Thread({

            var backupDirectory: File? = null

            try {
                val appInfo = packageManager.getApplicationInfo(
                    packageName,
                    0
                )

                val sourceFiles = mutableListOf<File>()

                sourceFiles.add(File(appInfo.sourceDir))

                appInfo.splitSourceDirs?.forEach { path ->
                    sourceFiles.add(File(path))
                }

                val sourcePaths = sourceFiles.map {
                    it.canonicalPath
                }

                if (sourcePaths.toSet().size != sourcePaths.size) {
                    throw IllegalStateException(
                        "Duplicate APK source files were detected."
                    )
                }

                if (
                    sourceFiles.isEmpty() ||
                    sourceFiles.any {
                        !it.exists() || !it.isFile || !it.canRead()
                    }
                ) {
                    throw IllegalStateException(
                        "One or more APK source files are unavailable."
                    )
                }

                val safePackageName = packageName.replace(
                    Regex("[^A-Za-z0-9._-]"),
                    "_"
                )

                val directory = File(
                    filesDir,
                    "apk_exports/$safePackageName-${System.currentTimeMillis()}"
                )

                if (!directory.mkdirs() && !directory.isDirectory) {
                    throw IllegalStateException(
                        "Unable to create the APK backup directory."
                    )
                }

                backupDirectory = directory

                val backupFiles = mutableListOf<Map<String, Any>>()

                sourceFiles.forEachIndexed { index, sourceFile ->

                    val fileName = if (index == 0) {
                        "base.apk"
                    } else {
                        "split_${index}.apk"
                    }

                    val destinationFile = File(
                        directory,
                        fileName
                    )

                    val originalHash = calculateSha256(sourceFile)

                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }

                    if (sourceFile.length() != destinationFile.length()) {
                        throw IllegalStateException(
                            "File size verification failed for $fileName"
                        )
                    }

                    val backupHash = calculateSha256(destinationFile)

                    if (originalHash != backupHash) {
                        throw IllegalStateException(
                            "SHA-256 verification failed for $fileName"
                        )
                    }

                    backupFiles.add(
                        mapOf(
                            "fileName" to fileName,
                            "sizeBytes" to destinationFile.length(),
                            "sha256" to backupHash,
                            "verified" to true
                        )
                    )
                }

                mainHandler.post {

                    if (activityDestroyed) {
                        return@post
                    }

                    result.success(
                        mapOf(
                            "success" to true,
                            "packageName" to packageName,
                            "backupDirectory" to directory.absolutePath,
                            "fileCount" to backupFiles.size,
                            "files" to backupFiles,
                            "integrityVerified" to true
                        )
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "APK backup failed for $packageName",
                    e
                )

                backupDirectory?.let { directory ->
                    try {
                        directory.deleteRecursively()
                    } catch (cleanupError: Exception) {
                        Log.e(
                            TAG,
                            "Unable to clean up incomplete APK backup",
                            cleanupError
                        )
                    }
                }

                mainHandler.post {
                    if (!activityDestroyed) {
                        result.error(
                            "APK_EXPORT_ERROR",
                            e.message
                                ?: "Unable to export application APK.",
                            null
                        )
                    }
                }
            }

        }, "APK-Backup-Worker").start()
    }

    private fun shareApplicationApkBackup(
        backupDirectoryPath: String,
        fileMetadata: List<*>,
        result: MethodChannel.Result
    ) {

        Thread({

            try {
                val backupRoot = File(
                    filesDir,
                    "apk_exports"
                ).canonicalFile

                val backupDirectory =
                    File(backupDirectoryPath).canonicalFile

                if (
                    backupDirectory.parentFile != backupRoot ||
                    !backupDirectory.isDirectory
                ) {
                    throw SecurityException(
                        "The requested backup directory is not permitted."
                    )
                }

                val validatedFiles = mutableListOf<File>()
                val seenNames = mutableSetOf<String>()

                fileMetadata.forEach { item ->

                    val metadata = item as? Map<*, *>
                        ?: throw IllegalArgumentException(
                            "Invalid APK backup metadata."
                        )

                    val fileName = metadata["fileName"] as? String
                        ?: throw IllegalArgumentException(
                            "APK filename is missing."
                        )

                    val expectedHash = metadata["sha256"] as? String
                        ?: throw IllegalArgumentException(
                            "APK SHA-256 fingerprint is missing."
                        )

                    val verified =
                        metadata["verified"] as? Boolean ?: false

                    val expectedSize =
                        (metadata["sizeBytes"] as? Number)?.toLong()
                            ?: throw IllegalArgumentException(
                                "APK file size is missing."
                            )

                    if (!verified) {
                        throw SecurityException(
                            "An APK file has not passed backup verification."
                        )
                    }

                    if (
                        !fileName.matches(
                            Regex("""(?:base|split_[0-9]+)\.apk""")
                        )
                    ) {
                        throw SecurityException(
                            "An invalid APK filename was supplied."
                        )
                    }

                    if (!seenNames.add(fileName)) {
                        throw SecurityException(
                            "Duplicate APK filename detected."
                        )
                    }

                    if (!expectedHash.matches(Regex("[A-Fa-f0-9]{64}"))) {
                        throw SecurityException(
                            "Invalid SHA-256 fingerprint."
                        )
                    }

                    val apkFile = File(
                        backupDirectory,
                        fileName
                    ).canonicalFile

                    if (
                        apkFile.parentFile != backupDirectory ||
                        !apkFile.exists() ||
                        !apkFile.isFile ||
                        !apkFile.canRead()
                    ) {
                        throw SecurityException(
                            "An APK backup file is unavailable."
                        )
                    }

                    if (apkFile.length() != expectedSize) {
                        throw SecurityException(
                            "APK size verification failed for $fileName."
                        )
                    }

                    val actualHash = calculateSha256(apkFile)

                    if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                        throw SecurityException(
                            "APK integrity verification failed for $fileName."
                        )
                    }

                    validatedFiles.add(apkFile)
                }

                if (
                    validatedFiles.isEmpty() ||
                    "base.apk" !in seenNames
                ) {
                    throw IllegalStateException(
                        "The backup must contain a verified base.apk file."
                    )
                }

                val splitIndexes = seenNames
                    .filter { it.startsWith("split_") }
                    .mapNotNull {
                        Regex("""^split_([0-9]+)\.apk$""")
                            .matchEntire(it)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                    }
                    .sorted()

                for (index in splitIndexes.indices) {
                    if (splitIndexes[index] != index + 1) {
                        throw IllegalStateException(
                            "The APK backup is missing a split file."
                        )
                    }
                }

                val apkUris = ArrayList<Uri>()

                validatedFiles.forEach { apkFile ->

                    val uri = FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
                        apkFile
                    )

                    apkUris.add(uri)
                }

                mainHandler.post {

                    if (activityDestroyed) {
                        return@post
                    }

                    try {
                        val shareIntent = Intent(
                            Intent.ACTION_SEND_MULTIPLE
                        ).apply {

                            type =
                                "application/vnd.android.package-archive"

                            putParcelableArrayListExtra(
                                Intent.EXTRA_STREAM,
                                apkUris
                            )

                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )

                            clipData = ClipData.newUri(
                                contentResolver,
                                "Verified APK backup",
                                apkUris.first()
                            ).apply {

                                apkUris.drop(1).forEach { uri ->
                                    addItem(ClipData.Item(uri))
                                }
                            }
                        }

                        startActivity(
                            Intent.createChooser(
                                shareIntent,
                                "Share verified APK backup"
                            )
                        )

                        result.success(
                            mapOf(
                                "success" to true,
                                "fileCount" to apkUris.size,
                                "integrityVerified" to true
                            )
                        )

                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Unable to open APK sharing interface",
                            e
                        )

                        result.error(
                            "APK_SHARE_ERROR",
                            e.message
                                ?: "Unable to share APK backup.",
                            null
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "APK backup sharing verification failed",
                    e
                )

                mainHandler.post {
                    if (!activityDestroyed) {
                        result.error(
                            "APK_SHARE_VERIFICATION_ERROR",
                            e.message
                                ?: "APK backup verification failed.",
                            null
                        )
                    }
                }
            }

        }, "APK-Share-Worker").start()
    }
}
