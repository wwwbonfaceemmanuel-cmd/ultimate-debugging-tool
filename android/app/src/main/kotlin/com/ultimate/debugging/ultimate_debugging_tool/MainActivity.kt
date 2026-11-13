package com.ultimate.debugging.ultimate_debugging_tool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the Flutter screens to the native engines.
 *
 * This class only routes requests. The work itself lives in:
 *  - AdbSessionManager      the single ADB session (USB or wireless)
 *  - TargetDiagnostics      reports about the connected phone
 *  - SecurityAudit          security check-up of the connected phone
 *  - HardwareInspector      hardware check-up of the connected phone
 *  - TerminalCommands       the read-only terminal command list
 *  - RepairServices         approved repair services
 *  - AppDiagnosis           why one app on the connected phone misbehaves
 *  - TargetApps/TargetFiles apps and files on the connected phone
 *  - AdbSideload            an update package for a phone in recovery
 *  - LocalDeviceReports     reports about this phone
 *
 * Nothing slow runs on the main thread.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "UltimateDebuggingTool"

        const val METHOD_CHANNEL = "com.ultimate.debugging/usb"
        const val EVENT_CHANNEL = "com.ultimate.debugging/usb_events"

        private const val ACTION_USB_PERMISSION =
            "com.ultimate.debugging.USB_PERMISSION"

        private const val REQUEST_PICK_FILE = 4201
        private const val REQUEST_PICK_IMAGE = 4202
        private const val REQUEST_PICK_UPDATE = 4203
        private const val WIRELESS_PORT = 5555
        private const val MAX_TRANSFER_AGE_MS = 24L * 60 * 60 * 1000
    }

    /** Answers one Flutter request exactly once, on the main thread. */
    private inner class Reply(private val result: MethodChannel.Result) {
        private val sent = AtomicBoolean(false)

        fun success(value: Any?) = send { result.success(value) }

        fun error(code: String, message: String) =
            send { result.error(code, message, null) }

        private fun send(action: () -> Unit) {
            if (!sent.compareAndSet(false, true)) return

            mainHandler.post {
                if (activityDestroyed) return@post

                try {
                    action()
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to deliver a result to Flutter", e)
                }
            }
        }
    }

    private class RequestError(val code: String, message: String) :
        Exception(message)

    private lateinit var usbManager: UsbManager
    private lateinit var usbInspector: UsbDeviceInspector
    private lateinit var session: AdbSessionManager
    private lateinit var localReports: LocalDeviceReports
    private lateinit var apkExporter: ApkExporter

    private val mainHandler = Handler(Looper.getMainLooper())

    private val worker: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "UDT-Worker").apply { isDaemon = true }
    }

    /** The phone in bootloader mode, when one is connected. */
    @Volatile
    private var fastboot: FastbootSession? = null

    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    @Volatile
    private var activityDestroyed = false

    private var receiverRegistered = false

    // Main thread only.
    private var pendingPush: Pair<Reply, String>? = null

    // Main thread only.
    private var pendingImage: Reply? = null

    // Main thread only.
    private var pendingUpdate: Reply? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> sendUsbEvent("connected")

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = usbDeviceFrom(intent)
                    val current = session.snapshot()

                    if (
                        detached != null &&
                        current.link == AdbSessionManager.Link.USB &&
                        current.target == detached.deviceName
                    ) {
                        worker.execute { session.disconnect() }
                    }

                    sendUsbEvent("disconnected")
                }

                ACTION_USB_PERMISSION -> {
                    // The system reports the real answer here.
                    val device = usbDeviceFrom(intent)

                    val granted = if (device != null) {
                        usbManager.hasPermission(device)
                    } else {
                        intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false
                        )
                    }

                    sendUsbEvent(
                        if (granted) "permission_granted" else "permission_denied",
                        device?.deviceName
                    )
                }
            }
        }
    }

    private fun usbDeviceFrom(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbInspector = UsbDeviceInspector(usbManager)
        localReports = LocalDeviceReports(this)
        apkExporter = ApkExporter(this) { activityDestroyed }

        val appContext = applicationContext

        session = AdbSessionManager(
            AdbAuthentication { AdbKeyStore.loadOrCreate(appContext) }
        ) { snapshot -> sendAdbEvent(snapshot) }

        registerUsbReceiver()

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        ).setMethodCallHandler { call, result ->
            val reply = Reply(result)

            try {
                if (!handle(call, reply)) result.notImplemented()
            } catch (e: Exception) {
                Log.e(TAG, "Request failed: ${call.method}", e)
                reply.error("REQUEST_ERROR", e.message ?: "The request failed.")
            }
        }

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        ).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    sendUsbEvent("snapshot")
                    sendAdbEvent(session.snapshot())
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Request routing
    // ------------------------------------------------------------------

    /** Returns false when the method name is unknown. */
    private fun handle(call: MethodCall, reply: Reply): Boolean {
        when (call.method) {

            // ----- Connection -----

            "isUsbHostSupported" -> reply.success(
                packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
            )

            "getUsbDevices" -> reply.success(usbInspector.getConnectedUsbDevices())

            "getAdbInterfaces" -> reply.success(usbInspector.getAdbInterfaces())

            "requestUsbPermission" -> requestUsbPermission(call, reply)

            "connectAdb" -> connectUsb(call, reply)

            "connectWirelessAdb" -> connectWireless(call, reply)

            "disconnectAdb", "disconnectWirelessAdb" -> async(reply, "ADB_DISCONNECT_ERROR") {
                session.disconnect()
                session.snapshot().toMap()
            }

            "getAdbConnectionStatus", "getWirelessAdbStatus", "getConnectionStatus" ->
                reply.success(session.snapshot().toMap())

            "getAdbKeyFingerprint" -> async(reply, "ADB_KEY_ERROR") {
                val authentication = AdbAuthentication {
                    AdbKeyStore.loadOrCreate(applicationContext)
                }
                authentication.initialize()

                mapOf(
                    "fingerprint" to authentication.getDeviceStyleFingerprint(),
                    "sha256" to authentication.getPublicKeyFingerprint(),
                    "storage" to authentication.getKeyStorage()
                )
            }

            "switchToWireless" -> async(reply, "WIRELESS_SWITCH_ERROR") {
                switchToWireless()
            }

            "stopWirelessDebugging" -> async(reply, "WIRELESS_SWITCH_ERROR") {
                // Puts the phone's ADB back into USB-only mode.
                try {
                    session.service("usb:", 5000L)
                } finally {
                    session.disconnect()
                }
                true
            }

            // ----- Bootloader (fastboot) -----

            "getFastbootDevices" -> reply.success(fastbootDevices())

            "connectFastboot" -> {
                val deviceName = requireText(
                    call,
                    "deviceName",
                    "USB device name is required."
                )

                async(reply, "FASTBOOT_ERROR") { connectFastboot(deviceName) }
            }

            "disconnectFastboot" -> {
                fastboot?.close()
                fastboot = null
                true
            }

            "pickBootImage" -> pickBootImage(reply)

            "flashFastbootImage" -> {
                val partition = requireText(call, "partition", "A partition is required.")
                val path = requireText(call, "path", "Choose an image first.")
                val sha256 = requireText(call, "sha256", "The image checksum is required.")

                async(reply, "FASTBOOT_FLASH_ERROR") {
                    flashFastbootImage(partition, path, sha256)
                }
            }

            // ----- Update from ADB (sideload) -----

            "pickUpdatePackage" -> pickUpdatePackage(reply)

            "sideloadUpdatePackage" -> {
                val path = requireText(call, "path", "Choose an update package first.")
                val sha256 = requireText(call, "sha256", "The package checksum is required.")

                async(reply, "SIDELOAD_ERROR") { sideloadUpdatePackage(path, sha256) }
            }

            "getFastbootInfo" -> async(reply, "FASTBOOT_ERROR") {
                requireFastboot().deviceInfo().toMap()
            }

            "rebootFastboot" -> {
                val target = call.argument<String>("target").orEmpty()

                async(reply, "FASTBOOT_ERROR") {
                    requireFastboot().reboot(target)
                    true
                }
            }

            // ----- Diagnostics -----

            "getDiagnosticReports" -> reply.success(
                TargetDiagnostics.REPORTS.map {
                    mapOf("name" to it.first, "description" to it.second)
                }
            )

            "executeStandardDiagnostic" -> {
                val command = requireText(call, "command", "A command is required.")
                val onTarget = call.argument<String>("scope") == "target"

                async(reply, "DIAGNOSTIC_ERROR") {
                    if (onTarget) {
                        TargetDiagnostics(session).run(command)
                    } else {
                        localReports.run(command)
                    }
                }
            }

            "runSecurityAudit" -> async(reply, "SECURITY_AUDIT_ERROR") {
                SecurityAudit(session).run().toMap()
            }

            "runHardwareCheckup" -> async(reply, "HARDWARE_CHECK_ERROR") {
                HardwareInspector(session).run().toMap()
            }

            "runServiceDiagnosis" -> async(reply, "SERVICE_DIAGNOSIS_ERROR") {
                ServiceDiagnosis(session).run().toMap()
            }
            "getNotificationReport" -> {
                val pkg = call.argument<String>("packageName")
                async(reply, "NOTIFICATION_REPORT_ERROR") { NotificationInspector(session).notificationReport(pkg) }
            }
            "getBatteryDrainReport" -> async(reply, "BATTERY_DRAIN_ERROR") { NotificationInspector(session).batteryDrainReport() }
            "getApnReport" -> async(reply, "APN_REPORT_ERROR") { NotificationInspector(session).apnReport() }
            "getGfxReport" -> {
                val pkg = call.argument<String>("packageName")
                async(reply, "GFX_REPORT_ERROR") { NotificationInspector(session).gfxReport(pkg) }
            }
            "collectLogBundle" -> async(reply, "LOG_BUNDLE_ERROR") { LogBundle(session, File(filesDir, "log_bundles")).collect() }
            "recordOwnershipAttestation" -> {
                val tech = call.argument<String>("technicianName") ?: ""
                val ownerPresent = call.argument<Boolean>("ownerPresent") ?: false
                val idChecked = call.argument<Boolean>("idChecked") ?: false
                val receiptChecked = call.argument<Boolean>("receiptChecked") ?: false
                val notes = call.argument<String>("notes") ?: ""
                async(reply, "OWNERSHIP_ERROR") {
                    OwnershipVerifiedReset(session, filesDir).recordAttestation(
                        OwnershipVerifiedReset.Attestation(tech, ownerPresent, idChecked, receiptChecked, notes)
                    )
                }
            }
            "getOwnershipLinks" -> reply.success(OwnershipVerifiedReset.OFFICIAL_LINKS)
            "getDeviceIdentityForConsent" -> async(reply, "DEVICE_IDENTITY_ERROR") { OwnershipVerifiedReset(session, filesDir).deviceIdentity() }

            // ----- Terminal -----

            "getTerminalCommands" -> reply.success(TerminalCommands.describe())

            "getTerminalFixCommands" -> reply.success(TerminalCommands.describeFixes())

            "executeAdbCommand" -> executeTerminalCommand(call, reply, rootMode = false)

            "executeRootCommand" -> executeTerminalCommand(call, reply, rootMode = true)

            "executeFixCommand" -> executeFixCommand(call, reply, rootMode = false)

            "executeRootFixCommand" -> executeFixCommand(call, reply, rootMode = true)

            // ----- Repair -----

            "getRepairCatalog" -> reply.success(
                RepairServices.CATALOG.map { it.toMap() }
            )

            "executeRepairCommand" -> {
                val operation = call.argument<String>("operation")
                val packageName = call.argument<String>("packageName")
                val confirmed = call.argument<Boolean>("confirmed") == true

                async(reply, "REPAIR_EXECUTION_ERROR") {
                    runRepair(operation, packageName, confirmed)
                }
            }

            // ----- Apps on the connected phone -----

            "getTargetApplications" -> async(reply, "TARGET_APPS_ERROR") {
                TargetApps(session).list()
            }

            "getTargetApplicationDetails" -> {
                val packageName = requireText(call, "packageName", "A package name is required.")

                async(reply, "TARGET_APPS_ERROR") {
                    TargetApps(session).details(packageName)
                }
            }

            "backupTargetApplicationApk" -> {
                val packageName = requireText(call, "packageName", "A package name is required.")

                async(reply, "APK_EXPORT_ERROR") {
                    val root = File(filesDir, "apk_exports")
                    removeOldFiles(root)
                    TargetApps(session).backupApk(packageName, root)
                }
            }

            "getAppDiagnosis" -> {
                val packageName = requireText(call, "packageName", "A package name is required.")

                async(reply, "APP_DIAGNOSIS_ERROR") {
                    AppDiagnosis(session).inspect(packageName).toMap()
                }
            }

            // ----- Files on the connected phone -----

            "getTargetFileShortcuts" -> reply.success(
                TargetFiles.SHORTCUTS.map {
                    mapOf("name" to it.first, "path" to it.second)
                }
            )

            "listTargetFiles" -> {
                val path = requireText(call, "path", "A folder path is required.")

                async(reply, "TARGET_FILES_ERROR") {
                    TargetFiles(session).list(path)
                }
            }

            "pullTargetFile" -> {
                val path = requireText(call, "path", "A file path is required.")

                async(reply, "TARGET_FILES_ERROR") {
                    val root = File(filesDir, "transfers")
                    removeOldFiles(root)
                    TargetFiles(session).pull(path, root)
                }
            }

            "shareTransferredFile" -> {
                val path = requireText(call, "path", "A file path is required.")
                shareFile(File(path), File(filesDir, "transfers"), "*/*", reply)
            }

            "pickAndPushFile" -> {
                val directory = requireText(call, "remoteDirectory", "A folder path is required.")
                pickFileToPush(directory, reply)
            }

            // ----- Apps on this phone -----

            "getInstalledApplications" -> async(reply, "APP_INVENTORY_ERROR") {
                ApplicationInventory(this).getInstalledApplications()
            }

            "getApplicationPermissions" -> {
                val packageName = requireText(call, "packageName", "A valid package name is required.")

                async(reply, "APP_PERMISSION_ERROR") {
                    ApplicationPermissionsReader(this).getApplicationPermissions(packageName)
                }
            }

            "exportApplicationApk" -> {
                val packageName = requireText(call, "packageName", "A valid package name is required.")
                apkExporter.exportApplicationApk(packageName, resultOf(reply))
            }

            "shareApplicationApkBackup" -> {
                val directory = call.argument<String>("backupDirectory")
                val files = call.argument<List<*>>("files")

                if (directory.isNullOrBlank() || files.isNullOrEmpty()) {
                    throw RequestError(
                        "INVALID_BACKUP",
                        "A backup directory and verified APK file list are required."
                    )
                }

                apkExporter.shareApplicationApkBackup(directory, files, resultOf(reply))
            }

            // ----- Service history -----

            "saveRepairHistory" -> {
                val operation = call.argument<String>("operation").orEmpty()
                val category = call.argument<String>("category") ?: "General"
                val outcome = call.argument<String>("result") ?: "Unknown"
                val details = call.argument<String>("details").orEmpty()
                val scope = call.argument<String>("scope") ?: "workstation"

                async(reply, "HISTORY_SAVE_ERROR") {
                    saveHistory(operation, category, outcome, details, scope)
                }
            }

            "getRepairHistory" -> async(reply, "HISTORY_READ_ERROR") {
                RepairHistoryStore.getAll(filesDir)
            }

            "clearRepairHistory" -> async(reply, "HISTORY_CLEAR_ERROR") {
                RepairHistoryStore.clear(filesDir)
            }

            "exportRepairHistory" -> async(reply, "HISTORY_EXPORT_ERROR") {
                val directory = File(filesDir, "reports")
                directory.mkdirs()
                removeOldFiles(directory)

                val file = File(directory, "service-history-${System.currentTimeMillis()}.txt")
                file.writeText(RepairHistoryStore.exportText(filesDir), Charsets.UTF_8)

                shareFile(file, directory, "text/plain", null)
                true
            }

            else -> return false
        }

        return true
    }

    private fun requireText(call: MethodCall, name: String, message: String): String {
        val value = call.argument<String>(name)?.trim()

        if (value.isNullOrEmpty()) {
            throw RequestError("INVALID_ARGUMENTS", message)
        }

        return value
    }

    /** Runs [block] off the main thread and answers with its result. */
    private fun async(reply: Reply, errorCode: String, block: () -> Any?) {
        try {
            worker.execute {
                try {
                    reply.success(block())
                } catch (e: Exception) {
                    Log.e(TAG, "$errorCode: ${e.message}", e)

                    val code = when (e) {
                        is RequestError -> e.code
                        is RepairServices.RepairException -> e.code
                        is TerminalCommands.Rejected -> "COMMAND_NOT_ALLOWED"
                        is AdbTimeoutException -> "ADB_TIMEOUT"
                        else -> errorCode
                    }

                    reply.error(code, e.message ?: "The operation failed.")
                }
            }
        } catch (e: Exception) {
            reply.error(errorCode, "The app is closing.")
        }
    }

    /** Adapts a Reply for helpers that expect a MethodChannel.Result. */
    private fun resultOf(reply: Reply) = object : MethodChannel.Result {
        override fun success(result: Any?) = reply.success(result)

        override fun error(code: String, message: String?, details: Any?) =
            reply.error(code, message ?: "The operation failed.")

        override fun notImplemented() =
            reply.error("NOT_IMPLEMENTED", "The operation is not available.")
    }

    // ------------------------------------------------------------------
    // Bootloader
    // ------------------------------------------------------------------

    /** USB devices that are sitting in their bootloader. */
    private fun fastbootDevices(): List<Map<String, Any?>> {
        val found = ArrayList<Map<String, Any?>>()

        for (device in usbManager.deviceList.values) {
            val face = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == FastbootUsbTransport.FASTBOOT_CLASS &&
                        it.interfaceSubclass == FastbootUsbTransport.FASTBOOT_SUBCLASS &&
                        it.interfaceProtocol == FastbootUsbTransport.FASTBOOT_PROTOCOL
                } ?: continue

            found.add(
                linkedMapOf(
                    "deviceName" to device.deviceName,
                    "deviceId" to device.deviceId,
                    "vendorId" to device.vendorId,
                    "productId" to device.productId,
                    "manufacturerName" to device.manufacturerName.orEmpty(),
                    "productName" to device.productName.orEmpty(),
                    "interfaceId" to face.id,
                    "hasPermission" to usbManager.hasPermission(device)
                )
            )
        }

        return found
    }

    private fun pickBootImage(reply: Reply) {
        pendingImage?.error("FILE_PICK_REPLACED", "Another image was requested.")
        pendingImage = reply

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQUEST_PICK_IMAGE
            )
        } catch (e: Exception) {
            pendingImage = null
            throw RequestError(
                "FILE_PICK_ERROR",
                "No file chooser is available on this phone."
            )
        }
    }

    private fun pickUpdatePackage(reply: Reply) {
        pendingUpdate?.error("FILE_PICK_REPLACED", "Another package was requested.")
        pendingUpdate = reply

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQUEST_PICK_UPDATE
            )
        } catch (e: Exception) {
            pendingUpdate = null
            throw RequestError(
                "FILE_PICK_ERROR",
                "No file chooser is available on this phone."
            )
        }
    }

    /** Copies the chosen update package in, hashes it, and reports it. */
    private fun handlePickedUpdate(resultCode: Int, data: Intent?) {
        val reply = pendingUpdate ?: return
        pendingUpdate = null

        val uri: Uri? = data?.data

        if (resultCode != RESULT_OK || uri == null) {
            reply.success(mapOf("cancelled" to true))
            return
        }

        async(reply, "UPDATE_PACKAGE_ERROR") {
            val directory = File(filesDir, "updates").apply { mkdirs() }
            removeOldFiles(directory)

            val name = displayName(uri)
            val safeName = name.replace('/', '_').replace('\\', '_')
            val target = File(directory, "${System.currentTimeMillis()}-$safeName")

            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("The chosen package could not be opened.")

            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }

            val head = AdbSideload.headOf(target)

            mapOf(
                "cancelled" to false,
                "path" to target.absolutePath,
                "fileName" to name,
                "sizeBytes" to target.length(),
                "sha256" to sha256Of(target),
                "looksLikePackage" to AdbSideload.looksLikePackage(head)
            )
        }
    }

    /**
     * Sends an update package to the phone in recovery.
     *
     * Progress is sent to the app as `sideload` events, because a large
     * package takes minutes.
     */
    private fun sideloadUpdatePackage(path: String, sha256: String): Map<String, Any?> {
        val directory = File(filesDir, "updates").canonicalFile
        val file = File(path).canonicalFile

        if (!file.path.startsWith(directory.path + File.separator)) {
            throw RequestError(
                "INVALID_PACKAGE",
                "Choose the update package with this tool first."
            )
        }

        if (!file.isFile) {
            throw RequestError(
                "INVALID_PACKAGE",
                "The update package is gone. Choose it again."
            )
        }

        val actual = sha256Of(file)

        if (!actual.equals(sha256, ignoreCase = true)) {
            throw RequestError(
                "PACKAGE_CHANGED",
                "The package changed since it was checked. Choose it again."
            )
        }

        if (!session.snapshot().connected) {
            throw RequestError(
                "ADB_NOT_CONNECTED",
                "Connect the phone in recovery first."
            )
        }

        val result = AdbSideload(session).send(file) { percent ->
            sendEvent { mapOf("event" to "sideload", "percent" to percent) }
        }

        return result.toMap() + mapOf(
            "sha256" to actual,
            "fileName" to file.name,
            "packageBytes" to file.length()
        )
    }

    /** Copies the chosen image into the app, hashes it, and reports it. */
    private fun handlePickedImage(resultCode: Int, data: Intent?) {
        val reply = pendingImage ?: return
        pendingImage = null

        val uri: Uri? = data?.data

        if (resultCode != RESULT_OK || uri == null) {
            reply.success(mapOf("cancelled" to true))
            return
        }

        async(reply, "BOOT_IMAGE_ERROR") {
            val directory = File(filesDir, "boot_images").apply { mkdirs() }
            removeOldFiles(directory)

            val name = displayName(uri)
            val safeName = name.replace('/', '_').replace('\\', '_')
            val target = File(directory, "${System.currentTimeMillis()}-$safeName")

            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("The chosen image could not be opened.")

            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }

            mapOf(
                "cancelled" to false,
                "path" to target.absolutePath,
                "fileName" to name,
                "sizeBytes" to target.length(),
                "sha256" to sha256Of(target)
            )
        }
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)

            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") {
            String.format(java.util.Locale.US, "%02x", it)
        }
    }

    /**
     * Writes one image to one partition, but only after checking that
     * the file still has the checksum the technician confirmed.
     */
    private fun flashFastbootImage(
        partition: String,
        path: String,
        sha256: String
    ): Map<String, Any?> {
        val session = requireFastboot()

        val directory = File(filesDir, "boot_images").canonicalFile
        val image = File(path).canonicalFile

        if (!image.path.startsWith(directory.path + File.separator)) {
            throw RequestError(
                "INVALID_IMAGE",
                "Choose the image with this tool first."
            )
        }

        if (!image.isFile) {
            throw RequestError("INVALID_IMAGE", "The image file is gone. Choose it again.")
        }

        val bytes = image.readBytes()
        val actual = sha256Of(image)

        if (!actual.equals(sha256, ignoreCase = true)) {
            throw RequestError(
                "IMAGE_CHANGED",
                "The image changed since it was checked. Choose it again."
            )
        }

        val message = session.flash(partition, bytes)

        return linkedMapOf(
            "partition" to partition,
            "sizeBytes" to bytes.size,
            "sha256" to actual,
            "message" to message
        )
    }

    private fun requireFastboot(): FastbootSession {
        return fastboot ?: throw RequestError(
            "FASTBOOT_NOT_CONNECTED",
            "Connect a phone in bootloader mode first."
        )
    }

    private fun connectFastboot(deviceName: String): Map<String, Any?> {
        val device = usbManager.deviceList[deviceName]
            ?: throw RequestError("DEVICE_NOT_FOUND", "USB device is no longer connected.")

        if (!usbManager.hasPermission(device)) {
            throw RequestError(
                "USB_PERMISSION_REQUIRED",
                "Allow USB access for this device first."
            )
        }

        val interfaceId = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == FastbootUsbTransport.FASTBOOT_CLASS &&
                    it.interfaceSubclass == FastbootUsbTransport.FASTBOOT_SUBCLASS &&
                    it.interfaceProtocol == FastbootUsbTransport.FASTBOOT_PROTOCOL
            }
            ?.id
            ?: throw RequestError(
                "FASTBOOT_INTERFACE_NOT_FOUND",
                "This phone is not in bootloader mode. Restart it into the bootloader first."
            )

        fastboot?.close()
        fastboot = null

        val transport = FastbootUsbTransport(usbManager, device, interfaceId)

        try {
            transport.open()
        } catch (e: IOException) {
            throw RequestError("FASTBOOT_ERROR", e.message ?: "The bootloader could not be opened.")
        }

        val session = FastbootSession(transport)

        try {
            val info = session.deviceInfo()
            fastboot = session
            return info.toMap()
        } catch (e: Exception) {
            session.close()
            throw e
        }
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    private fun requestUsbPermission(call: MethodCall, reply: Reply) {
        val deviceName = requireText(call, "deviceName", "USB device name is required.")

        val device = usbManager.deviceList[deviceName]
            ?: throw RequestError("DEVICE_NOT_FOUND", "USB device is no longer connected.")

        if (usbManager.hasPermission(device)) {
            reply.success(mapOf("granted" to true, "requested" to false))
            return
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

        usbManager.requestPermission(
            device,
            PendingIntent.getBroadcast(
                this,
                device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                flags
            )
        )

        // The answer arrives later as a permission_granted/denied event.
        reply.success(mapOf("granted" to false, "requested" to true))
    }

    private fun connectUsb(call: MethodCall, reply: Reply) {
        val deviceName = requireText(call, "deviceName", "USB device name is required.")
        val requestedInterface = call.argument<Number>("interfaceId")?.toInt()

        val device = usbManager.deviceList[deviceName]
            ?: throw RequestError("DEVICE_NOT_FOUND", "USB device is no longer connected.")

        if (!usbManager.hasPermission(device)) {
            throw RequestError(
                "USB_PERMISSION_REQUIRED",
                "Allow USB access for this device first."
            )
        }

        val interfaceId = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == AdbUsbTransport.ADB_CLASS &&
                    it.interfaceSubclass == AdbUsbTransport.ADB_SUBCLASS &&
                    it.interfaceProtocol == AdbUsbTransport.ADB_PROTOCOL &&
                    (requestedInterface == null || it.id == requestedInterface)
            }
            ?.id
            ?: throw RequestError(
                "ADB_INTERFACE_NOT_FOUND",
                "This device is not offering ADB. Turn on USB debugging in its Developer options."
            )

        session.connect(
            link = AdbSessionManager.Link.USB,
            target = deviceName,
            transportFactory = { AdbUsbTransport(usbManager, device, interfaceId) },
            callback = { answerConnect(reply, it) }
        )
    }

    private fun connectWireless(call: MethodCall, reply: Reply) {
        val host = call.argument<String>("host")?.trim().orEmpty()
        val port = call.argument<Number>("port")?.toInt() ?: WIRELESS_PORT

        if (!ShellSupport.HOST_NAME.matches(host) || port !in 1..65535) {
            throw RequestError(
                "INVALID_ARGUMENTS",
                "Enter a valid IP address and a port between 1 and 65535."
            )
        }

        session.connect(
            link = AdbSessionManager.Link.WIRELESS,
            target = "$host:$port",
            host = host,
            port = port,
            transportFactory = { AdbTcpTransport(host, port) },
            callback = { answerConnect(reply, it) }
        )
    }

    private fun answerConnect(reply: Reply, result: AdbSessionManager.ConnectResult) {
        when (result) {
            is AdbSessionManager.ConnectResult.Connected ->
                reply.success(result.snapshot.toMap())

            is AdbSessionManager.ConnectResult.Failed ->
                reply.error(result.code, result.message)

            AdbSessionManager.ConnectResult.Cancelled ->
                reply.error("ADB_CANCELLED", "The connection was cancelled.")
        }
    }

    /**
     * Moves a USB session to Wi-Fi: tells the phone to listen on the
     * network, then reconnects to it. Works on Android 11+ as well,
     * because the phone already trusts this app's key from USB.
     */
    private fun switchToWireless(): Map<String, Any?> {
        val current = session.snapshot()

        if (!current.connected || current.link != AdbSessionManager.Link.USB) {
            throw RequestError(
                "USB_REQUIRED",
                "Connect the phone by USB first, then switch it to wireless."
            )
        }

        val address = session.shell("ip -o -4 addr show wlan0").output
            .let { Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(it)?.groupValues?.get(1) }
            ?: throw RequestError(
                "NO_WIFI",
                "The phone is not connected to Wi-Fi. Join the same Wi-Fi network as this phone first."
            )

        session.service("tcpip:$WIRELESS_PORT", 5000L)
        session.disconnect()

        var lastError = "The phone did not answer on the network."

        repeat(4) { attempt ->
            Thread.sleep(if (attempt == 0) 2500L else 2000L)

            val done = java.util.concurrent.LinkedBlockingQueue<AdbSessionManager.ConnectResult>()

            session.connect(
                link = AdbSessionManager.Link.WIRELESS,
                target = "$address:$WIRELESS_PORT",
                host = address,
                port = WIRELESS_PORT,
                transportFactory = { AdbTcpTransport(address, WIRELESS_PORT, 5000) },
                callback = { done.put(it) }
            )

            when (val result = done.take()) {
                is AdbSessionManager.ConnectResult.Connected ->
                    return result.snapshot.toMap()

                is AdbSessionManager.ConnectResult.Failed ->
                    lastError = result.message

                AdbSessionManager.ConnectResult.Cancelled ->
                    throw RequestError("ADB_CANCELLED", "The connection was cancelled.")
            }
        }

        throw RequestError(
            "WIRELESS_SWITCH_ERROR",
            "The phone is now listening at $address:$WIRELESS_PORT but this phone could not reach it " +
                "($lastError). Check both phones are on the same Wi-Fi, then connect from Wireless ADB."
        )
    }

    // ------------------------------------------------------------------
    // Terminal, repair and history
    // ------------------------------------------------------------------

    private fun executeTerminalCommand(call: MethodCall, reply: Reply, rootMode: Boolean) {
        val input = call.argument<String>("command")?.trim().orEmpty()

        if (input.equals("help", ignoreCase = true)) {
            reply.success(TerminalCommands.helpText(rootMode))
            return
        }

        async(reply, "TERMINAL_EXECUTION_ERROR") {
            val resolved = TerminalCommands.resolve(input)

            if (rootMode) {
                val check = session.shell(TerminalCommands.asRoot("id"), 20000L)

                if (!check.output.contains("uid=0")) {
                    throw RequestError(
                        "ROOT_UNAVAILABLE",
                        "Root access was not granted. This device may not be rooted, " +
                            "or the root permission request was denied."
                    )
                }
            }

            val result = session.shell(
                if (rootMode) TerminalCommands.asRoot(resolved.shell) else resolved.shell,
                resolved.entry.timeoutMs,
                1024 * 1024
            )

            buildString {
                append(result.output.trimEnd())

                if (result.errorOutput.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("ERROR:\n").append(result.errorOutput.trimEnd())
                }

                if (result.truncated) {
                    append("\n\n(Output shortened: only the first 1 MB is shown.)")
                }

                if (result.exitCode != null && result.exitCode != 0) {
                    if (isNotEmpty()) append("\n\n")
                    append("Exit code: ").append(result.exitCode)
                }
            }.ifBlank { "Command completed without output." }
        }
    }

    private fun executeFixCommand(call: MethodCall, reply: Reply, rootMode: Boolean) {
        val input = call.argument<String>("command")?.trim().orEmpty()
        val confirmed = call.argument<Boolean>("confirmed") == true

        if (!confirmed) {
            reply.error(
                "CONFIRMATION_REQUIRED",
                "This command changes the connected phone. Confirm it first."
            )
            return
        }

        if (input.equals("help", ignoreCase = true)) {
            reply.success(TerminalCommands.helpText(rootMode))
            return
        }

        async(reply, "TERMINAL_EXECUTION_ERROR") {
            val resolved = TerminalCommands.resolveFix(input)

            if (rootMode) {
                val check = session.shell(TerminalCommands.asRoot("id"), 20000L)
                if (!check.output.contains("uid=0")) {
                    throw RequestError(
                        "ROOT_UNAVAILABLE",
                        "Root access was not granted. This device may not be rooted, " +
                            "or the root permission request was denied."
                    )
                }
            }

            val shellCommand = if (rootMode) TerminalCommands.asRoot(resolved.shell) else resolved.shell
            val result = session.shell(shellCommand, resolved.entry.timeoutMs, 1024 * 1024)

            // Record the fix in history — every change is auditable.
            if (session.snapshot().connected) {
                try {
                    recordRepair(
                        session.snapshot(),
                        "Terminal fix: ${resolved.entry.name}",
                        null,
                        if (result.exitCode == null || result.exitCode == 0) "Completed" else "Completed with warnings",
                        "${resolved.entry.description}\n${result.output.trim().take(800)}"
                    )
                } catch (_: Exception) {}
            }

            buildString {
                append(result.output.trimEnd())
                if (result.errorOutput.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("ERROR:\n").append(result.errorOutput.trimEnd())
                }
                if (result.truncated) {
                    append("\n\n(Output shortened: only the first 1 MB is shown.)")
                }
                if (result.exitCode != null && result.exitCode != 0) {
                    if (isNotEmpty()) append("\n\n")
                    append("Exit code: ").append(result.exitCode)
                }
            }.ifBlank { "Command completed without output." }
        }
    }

    private fun runRepair(
        operation: String?,
        packageName: String?,
        confirmed: Boolean
    ): Map<String, Any?> {
        val service = RepairServices.find(operation)
        val before = session.snapshot()
        val title = service?.title ?: (operation ?: "Unknown repair")
        val subject = packageName?.takeIf { service?.needsPackage == true && it.isNotBlank() }
        val name = if (subject != null) "$title: $subject" else title

        return try {
            val outcome = RepairServices(session, filesDir).run(operation, packageName, confirmed)

            recordRepair(
                before, name, service, "Completed",
                "${outcome.message}\n${outcome.verification}"
            )

            outcome.toMap()
        } catch (e: Exception) {
            // Refusals before anything was attempted are not service records.
            val attempted = e !is RepairServices.RepairException ||
                e.code == "REPAIR_EXECUTION_ERROR"

            if (attempted && before.connected) {
                recordRepair(before, name, service, "Failed", e.message.orEmpty())
            }

            throw e
        }
    }

    private fun recordRepair(
        snapshot: AdbSessionManager.Snapshot,
        operation: String,
        service: RepairServices.Service?,
        outcome: String,
        details: String
    ) {
        try {
            RepairHistoryStore.save(
                directory = filesDir,
                operation = operation,
                category = "Repair - ${service?.category ?: "General"}",
                result = outcome,
                details = details,
                scope = "target",
                deviceLabel = snapshot.deviceLabel,
                deviceSerial = snapshot.device["serial"].orEmpty(),
                link = snapshot.link.name
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to record the repair", e)
        }
    }

    private fun saveHistory(
        operation: String,
        category: String,
        outcome: String,
        details: String,
        scope: String
    ): Map<String, Any> {
        val snapshot = session.snapshot()
        val onTarget = scope == "target" && snapshot.connected

        return RepairHistoryStore.save(
            directory = filesDir,
            operation = operation,
            category = category,
            result = outcome,
            details = details,
            scope = if (scope == "target") "target" else "workstation",
            deviceLabel = if (onTarget) snapshot.deviceLabel else "",
            deviceSerial = if (onTarget) snapshot.device["serial"].orEmpty() else "",
            link = if (onTarget) snapshot.link.name else ""
        )
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private fun removeOldFiles(directory: File) {
        try {
            val cutoff = System.currentTimeMillis() - MAX_TRANSFER_AGE_MS

            directory.listFiles()
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove old files", e)
        }
    }

    /** Shares a file that lives directly inside [allowedDirectory]. */
    private fun shareFile(
        file: File,
        allowedDirectory: File,
        mimeType: String,
        reply: Reply?
    ) {
        val target = file.canonicalFile

        if (target.parentFile != allowedDirectory.canonicalFile || !target.isFile) {
            throw RequestError("FILE_NOT_ALLOWED", "This file cannot be shared.")
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)

        mainHandler.post {
            if (activityDestroyed) return@post

            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, target.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(send, "Share ${target.name}"))
                reply?.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to share the file", e)
                reply?.error("SHARE_ERROR", e.message ?: "Unable to share the file.")
            }
        }
    }

    private fun pickFileToPush(remoteDirectory: String, reply: Reply) {
        if (!session.snapshot().connected) {
            throw RequestError("ADB_NOT_CONNECTED", "Connect the target phone first.")
        }

        pendingPush?.first?.error("FILE_PICK_REPLACED", "Another file was requested.")
        pendingPush = reply to TargetFiles.normalize(remoteDirectory)

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQUEST_PICK_FILE
            )
        } catch (e: Exception) {
            pendingPush = null
            throw RequestError("FILE_PICK_ERROR", "No file chooser is available on this phone.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK_IMAGE) {
            handlePickedImage(resultCode, data)
            return
        }

        if (requestCode == REQUEST_PICK_UPDATE) {
            handlePickedUpdate(resultCode, data)
            return
        }

        if (requestCode != REQUEST_PICK_FILE) return

        val (reply, directory) = pendingPush ?: return
        pendingPush = null

        val uri: Uri? = data?.data

        if (resultCode != RESULT_OK || uri == null) {
            reply.success(mapOf("cancelled" to true))
            return
        }

        async(reply, "TARGET_FILES_ERROR") {
            val name = displayName(uri)

            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("The chosen file could not be opened.")

            input.use { TargetFiles(session).push(it, name, directory) } +
                mapOf("cancelled" to false)
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read the file name", e)
        }

        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file-${System.currentTimeMillis()}"
    }

    // ------------------------------------------------------------------
    // Events and lifecycle
    // ------------------------------------------------------------------

    private fun sendEvent(payload: () -> Map<String, Any?>) {
        mainHandler.post {
            if (activityDestroyed) return@post

            try {
                eventSink?.success(payload())
            } catch (e: Exception) {
                Log.e(TAG, "Unable to send an event to Flutter", e)
            }
        }
    }

    private fun sendUsbEvent(event: String, deviceName: String? = null) {
        sendEvent {
            mapOf(
                "event" to event,
                "deviceName" to deviceName,
                "devices" to usbInspector.getConnectedUsbDevices()
            )
        }
    }

    private fun sendAdbEvent(snapshot: AdbSessionManager.Snapshot) {
        sendEvent {
            mapOf("event" to "adb_state", "adb" to snapshot.toMap())
        }
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }

        // Not exported: only Android itself and this app can send these
        // events, so another app cannot fake a USB permission answer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        receiverRegistered = true
    }

    override fun onDestroy() {
        activityDestroyed = true
        fastboot?.close()
        fastboot = null

        if (receiverRegistered) {
            try {
                unregisterReceiver(usbReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "USB receiver was already unregistered", e)
            }
            receiverRegistered = false
        }

        if (::session.isInitialized) {
            try {
                session.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "ADB shutdown failed", e)
            }
        }

        worker.shutdownNow()
        eventSink = null
        pendingPush = null
        pendingImage = null
        pendingUpdate = null

        super.onDestroy()
    }
}
