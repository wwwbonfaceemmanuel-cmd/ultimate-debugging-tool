
import 'package:flutter/services.dart';

class UsbDeviceInfo {
  final String deviceName;
  final int deviceId;
  final int vendorId;
  final int productId;
  final String manufacturerName;
  final String productName;
  final int deviceClass;
  final int deviceSubclass;
  final int interfaceCount;
  final bool hasPermission;

  const UsbDeviceInfo({
    required this.deviceName,
    required this.deviceId,
    required this.vendorId,
    required this.productId,
    required this.manufacturerName,
    required this.productName,
    required this.deviceClass,
    required this.deviceSubclass,
    required this.interfaceCount,
    required this.hasPermission,
  });

  factory UsbDeviceInfo.fromMap(Map<dynamic, dynamic> map) {
    return UsbDeviceInfo(
      deviceName: map['deviceName']?.toString() ?? '',
      deviceId: map['deviceId'] as int? ?? -1,
      vendorId: map['vendorId'] as int? ?? 0,
      productId: map['productId'] as int? ?? 0,
      manufacturerName:
          map['manufacturerName']?.toString() ?? 'Unknown',
      productName: map['productName']?.toString() ?? 'Unknown',
      deviceClass: map['deviceClass'] as int? ?? 0,
      deviceSubclass: map['deviceSubclass'] as int? ?? 0,
      interfaceCount: map['interfaceCount'] as int? ?? 0,
      hasPermission: map['hasPermission'] as bool? ?? false,
    );
  }
}

class UsbService {
  static const MethodChannel _channel =
      MethodChannel('com.ultimate.debugging/usb');

  Future<bool> isUsbHostSupported() async {
    try {
      return await _channel.invokeMethod<bool>(
            'isUsbHostSupported',
          ) ??
          false;
    } on PlatformException {
      return false;
    }
  }

  Future<List<UsbDeviceInfo>> getConnectedDevices() async {
    try {
      final List<dynamic> devices =
          await _channel.invokeMethod<List<dynamic>>(
                'getUsbDevices',
              ) ??
              [];

      return devices
          .map(
            (device) => UsbDeviceInfo.fromMap(
              Map<dynamic, dynamic>.from(device as Map),
            ),
          )
          .toList();
    } on PlatformException {
      return [];
    }
  }

  Future<bool> requestPermission(String deviceName) async {
    try {
      return await _channel.invokeMethod<bool>(
            'requestUsbPermission',
            {'deviceName': deviceName},
          ) ??
          false;
    } on PlatformException {
      return false;
    }
  }
}
