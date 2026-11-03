
package com.ultimate.debugging.ultimate_debugging_tool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
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
                            android.content.pm.PackageManager
                                .FEATURE_USB_HOST
                        )
                    )
                }

               "getInstalledApplications" -> {
                    try {
                        val inventory = ApplicationInventory(this)
                        result.success(inventory.getInstalledApplications())
                    } catch (e: Exception) {
                        Log.e(TAG, "Unable to retrieve installed applications", e)

                        result.error(
                            "APP_INVENTORY_ERROR",
                            e.message ?: "Unable to retrieve installed applications",
                            null
                        )
                    }
                }

                "connectWirelessAdb" -> connectWirelessAdb(call, result)

            "getWirelessAdbStatus" -> {
            result.success(getWirelessAdbStatus())
          }

         "disconnectWirelessAdb" -> disconnectWirelessAdb(result)

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

    val resultSent = java.util.concurrent.atomic.AtomicBoolean(false)

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
}
