
package com.ultimate.debugging.ultimate_debugging_tool

import android.app.PendingIntent
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

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

    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

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

                "getUsbDevices" -> {
                    try {
                        result.success(getConnectedUsbDevices())
                    } catch (e: Exception) {
                        Log.e(TAG, "Unable to retrieve USB devices", e)

                        result.error(
                            "USB_ERROR",
                            e.message ?: "Unable to retrieve USB devices",
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
                            e.message ?: "Unable to inspect ADB interfaces",
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
                            "USB device name is required",
                            null
                        )
                        return@setMethodCallHandler
                    }

                    val device = usbManager.deviceList[deviceName]

                    if (device == null) {
                        result.error(
                            "DEVICE_NOT_FOUND",
                            "USB device is no longer connected",
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
                            e.message ?: "USB permission request failed",
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

    if (backupDirectory.isNullOrBlank() || files.isNullOrEmpty()) {
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
                                ?: "Unable to retrieve installed applications",
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
                                ?: "Unable to retrieve application permissions",
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

    private fun connectAdb(
        call: io.flutter.plugin.common.MethodCall,
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

        if (
            adbStatus == "CONNECTING" ||
            adbStatus == "AUTHENTICATION_REQUIRED" ||
            adbStatus == "CONNECTED"
        ) {
            result.error(
                "ADB_BUSY",
                "An ADB connection is already active.",
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

        sendAdbEvent("connecting")

        adbExecutor.execute {

            var transport: AdbUsbTransport? = null
            var connection: AdbConnection? = null

            try {

                transport = AdbUsbTransport(
                    usbManager,
                    device,
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

                adbTransport = transport
                adbConnection = createdConnection

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
    }

    private fun connectWirelessAdb(
        call: io.flutter.plugin.common.MethodCall,
        result: MethodChannel.Result
    ) {
        val host = call.argument<String>("host")?.trim()
        val port = call.argument<Number>("port")?.toInt()

        if (host.isNullOrBlank() || port == null || port !in 1..65535) {
            result.error(
                "INVALID_ARGUMENTS",
                "A valid device IP address and TCP port are required.",
                null
            )
            return
        }

        if (
            wirelessStatus == "CONNECTED" ||
            wirelessStatus == "AUTHENTICATION_REQUIRED" ||
            wirelessStatus == "CONNECTING"
        ) {
            result.error(
                "WIRELESS_BUSY",
                "A wireless ADB session is already active.",
                null
            )
            return
        }

        val manager = wirelessManager ?: AdbWirelessManager(
            authentication = AdbAuthentication(),
            onStateChanged = { state ->
                wirelessStatus = state.name
            }
        ).also {
            wirelessManager = it
        }

        wirelessHost = host
        wirelessPort = port
        wirelessStatus = "CONNECTING"

        val resultSent =
            java.util.concurrent.atomic.AtomicBoolean(false)

        wirelessExecutor.execute {
            manager.connect(host, port) { info ->

                wirelessStatus = info.state.name

                val isFinalState = when (info.state) {
                    AdbConnection.State.CONNECTED,
                    AdbConnection.State.AUTHENTICATION_REQUIRED,
                    AdbConnection.State.FAILED,
                    AdbConnection.State.CLOSED,
                    AdbConnection.State.DISCONNECTED -> true

                    else -> false
                }

                if (isFinalState && resultSent.compareAndSet(false, true)) {
                    mainHandler.post {
                        if (info.state == AdbConnection.State.FAILED) {
                            result.error(
                                "WIRELESS_CONNECTION_FAILED",
                                info.error ?: "Wireless ADB connection failed.",
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
                error.message ?: "Unable to disconnect wireless ADB.",
                null
            )
        }
    }

    private fun getAdbKeyFingerprint(
        result: MethodChannel.Result
    ) {

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

                Log.e(TAG, "Unable to retrieve ADB fingerprint", e)

                mainHandler.post {
                    result.error(
                        "ADB_KEY_ERROR",
                        e.message ?: "Unable to retrieve ADB fingerprint.",
                        null
                    )
                }
            }
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

        val connection = adbConnection

        adbConnection = null
        adbTransport = null
        adbDeviceName = null
        adbStatus = "DISCONNECTED"

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

        disconnectAdbInternal()

        wirelessManager?.shutdown()
        wirelessManager = null

        wirelessExecutor.shutdownNow()
        adbExecutor.shutdownNow()

        super.onDestroy()
    }

    private fun sendUsbEvent(event: String) {

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
                e.message ?: "Unable to retrieve USB device information",
                null
            )
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
                    android.content.pm.PackageManager.PackageInfoFlags.of(
                        android.content.pm.PackageManager.GET_PERMISSIONS.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.GET_PERMISSIONS
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

    private fun requestDevicePermission(device: UsbDevice) {

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

    private fun calculateSha256(file: File): String {

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

                // Retrieve the installed application's APK files.
                val appInfo = packageManager.getApplicationInfo(
                    packageName,
                    0
                )

                val sourceFiles = mutableListOf<File>()

                sourceFiles.add(File(appInfo.sourceDir))

                appInfo.splitSourceDirs?.forEach { path ->
                    sourceFiles.add(File(path))
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

                // Create a private backup directory.
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

                // Register the directory for cleanup only after creation.
                backupDirectory = directory

                val backupFiles = mutableListOf<Map<String, Any>>()

                // Copy and verify every APK file.
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

                    // Calculate the original hash before copying.
                    val originalHash = calculateSha256(sourceFile)

                    // Copy without modifying the original file.
                    FileInputStream(sourceFile).use { input ->

                        FileOutputStream(destinationFile).use { output ->

                            input.copyTo(output)

                            // Ensure copied data is flushed to storage.
                            output.fd.sync()
                        }
                    }

                    // Check file size.
                    if (sourceFile.length() != destinationFile.length()) {
                        throw IllegalStateException(
                            "File size verification failed for $fileName"
                        )
                    }

                    // Verify the copied file's SHA-256 fingerprint.
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

                // Return success only after every file passes verification.
                runOnUiThread {

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

                // Remove incomplete backup files.
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

                // Return the error on Android's main thread.
                runOnUiThread {

                    result.error(
                        "APK_EXPORT_ERROR",
                        e.message ?: "Unable to export application APK.",
                        null
                    )
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
            // Only allow direct child directories of apk_exports.
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

            // Verify every APK before sharing.
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

                val verified = metadata["verified"] as? Boolean
                    ?: false

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

            // Create secure FileProvider content URIs.
            val apkUris = ArrayList<Uri>()

            validatedFiles.forEach { apkFile ->

                val uri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    apkFile
                )

                apkUris.add(uri)
            }

            // Open Android's sharing interface on the main thread.
            mainHandler.post {

                try {

                    val shareIntent = Intent(
                        Intent.ACTION_SEND_MULTIPLE
                    ).apply {

                        type = "application/vnd.android.package-archive"

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
                                addItem(
                                    ClipData.Item(uri)
                                )
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
                        e.message ?: "Unable to share APK backup.",
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

                result.error(
                    "APK_SHARE_VERIFICATION_ERROR",
                    e.message ?: "APK backup verification failed.",
                    null
                )
            }
        }

     }, "APK-Share-Worker").start()
    
  }
    
}
