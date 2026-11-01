
package com.ultimate.debugging.ultimate_debugging_tool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "UltimateDebuggingTool"
        private const val METHOD_CHANNEL =
            "com.ultimate.debugging/usb"
        private const val EVENT_CHANNEL =
            "com.ultimate.debugging/usb_events"
        private const val ACTION_USB_PERMISSION =
            "com.ultimate.debugging.USB_PERMISSION"
    }

    private lateinit var usbManager: UsbManager

    private var eventSink: EventChannel.EventSink? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

            when (intent.action) {

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    sendUsbEvent("connected")
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
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

                "isUsbHostSupported" -> {
                    result.success(
                        packageManager.hasSystemFeature(
                            android.content.pm.PackageManager
                                .FEATURE_USB_HOST
                        )
                    )
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

                    // Send the current USB device state immediately.
                    sendUsbEvent("snapshot")
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            }
        )
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

    private fun sendUsbEvent(event: String) {

        try {
            val devices = getConnectedUsbDevices()

            eventSink?.success(
                mapOf(
                    "event" to event,
                    "devices" to devices
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
