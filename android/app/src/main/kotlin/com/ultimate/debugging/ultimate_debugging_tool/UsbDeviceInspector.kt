package com.ultimate.debugging.ultimate_debugging_tool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build

/** Describes attached USB devices and finds their ADB interfaces. */
class UsbDeviceInspector(private val usbManager: UsbManager) {

    fun getConnectedUsbDevices(): List<Map<String, Any>> {

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

    fun getAdbInterfaces(): List<Map<String, Any>> {

        val results = mutableListOf<Map<String, Any>>()

        for (device in usbManager.deviceList.values) {

            for (index in 0 until device.interfaceCount) {

                val usbInterface = device.getInterface(index)

                val isAdbInterface =
                    usbInterface.interfaceClass == AdbUsbTransport.ADB_CLASS &&
                        usbInterface.interfaceSubclass == AdbUsbTransport.ADB_SUBCLASS &&
                        usbInterface.interfaceProtocol == AdbUsbTransport.ADB_PROTOCOL

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

}
