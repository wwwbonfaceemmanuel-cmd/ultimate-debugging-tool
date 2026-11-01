
package com.ultimate.debugging.ultimate_debugging_tool

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.ultimate.debugging/usb"
    private val ACTION_USB_PERMISSION =
        "com.ultimate.debugging.USB_PERMISSION"

    private lateinit var usbManager: UsbManager

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                "getUsbDevices" -> {
                    try {
                        result.success(getConnectedUsbDevices())
                    } catch (e: Exception) {
                        result.error(
                            "USB_ERROR",
                            e.message,
                            null
                        )
                    }
                }

                "requestUsbPermission" -> {
                    val deviceName =
                        call.argument<String>("deviceName")

                    if (deviceName == null) {
                        result.error(
                            "INVALID_DEVICE",
                            "USB device name is required",
                            null
                        )
                        return@setMethodCallHandler
                    }

                    val device =
                        usbManager.deviceList[deviceName]

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
                        result.error(
                            "PERMISSION_ERROR",
                            e.message,
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

        val intent = android.content.Intent(
            ACTION_USB_PERMISSION
        ).setPackage(packageName)

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
