
import 'dart:async';
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

class UsbEvent {
  final String event;
  final List<UsbDeviceInfo> devices;

  const UsbEvent({
    required this.event,
    required this.devices,
  });

  factory UsbEvent.fromMap(Map<dynamic, dynamic> map) {
    final rawDevices = map['devices'] as List<dynamic>? ?? [];

    return UsbEvent(
      event: map['event']?.toString() ?? 'unknown',
      devices: rawDevices
          .map(
            (device) => UsbDeviceInfo.fromMap(
              Map<dynamic, dynamic>.from(device as Map),
            ),
          )
          .toList(),
    );
  }
}

class AdbEndpointInfo {
  final int address;
  final int endpointNumber;
  final String direction;
  final String type;
  final int maxPacketSize;

  const AdbEndpointInfo({
    required this.address,
    required this.endpointNumber,
    required this.direction,
    required this.type,
    required this.maxPacketSize,
  });

  factory AdbEndpointInfo.fromMap(Map<dynamic, dynamic> map) {
    return AdbEndpointInfo(
      address: map['address'] as int? ?? 0,
      endpointNumber: map['endpointNumber'] as int? ?? 0,
      direction: map['direction']?.toString() ?? 'UNKNOWN',
      type: map['type']?.toString() ?? 'UNKNOWN',
      maxPacketSize: map['maxPacketSize'] as int? ?? 0,
    );
  }
}

class AdbInterfaceInfo {
  final String deviceName;
  final int deviceId;
  final int vendorId;
  final int productId;
  final String manufacturerName;
  final String productName;
  final int interfaceId;
  final int interfaceClass;
  final int interfaceSubclass;
  final int interfaceProtocol;
  final bool hasBulkIn;
  final bool hasBulkOut;
  final bool hasAdbEndpoints;
  final bool hasPermission;
  final List<AdbEndpointInfo> endpoints;

  const AdbInterfaceInfo({
    required this.deviceName,
    required this.deviceId,
    required this.vendorId,
    required this.productId,
    required this.manufacturerName,
    required this.productName,
    required this.interfaceId,
    required this.interfaceClass,
    required this.interfaceSubclass,
    required this.interfaceProtocol,
    required this.hasBulkIn,
    required this.hasBulkOut,
    required this.hasAdbEndpoints,
    required this.hasPermission,
    required this.endpoints,
  });

  factory AdbInterfaceInfo.fromMap(Map<dynamic, dynamic> map) {
    final rawEndpoints = map['endpoints'] as List<dynamic>? ?? [];

    return AdbInterfaceInfo(
      deviceName: map['deviceName']?.toString() ?? '',
      deviceId: map['deviceId'] as int? ?? -1,
      vendorId: map['vendorId'] as int? ?? 0,
      productId: map['productId'] as int? ?? 0,
      manufacturerName:
          map['manufacturerName']?.toString() ?? 'Unknown',
      productName: map['productName']?.toString() ?? 'Unknown',
      interfaceId: map['interfaceId'] as int? ?? -1,
      interfaceClass: map['interfaceClass'] as int? ?? 0,
      interfaceSubclass: map['interfaceSubclass'] as int? ?? 0,
      interfaceProtocol: map['interfaceProtocol'] as int? ?? 0,
      hasBulkIn: map['hasBulkIn'] as bool? ?? false,
      hasBulkOut: map['hasBulkOut'] as bool? ?? false,
      hasAdbEndpoints: map['hasAdbEndpoints'] as bool? ?? false,
      hasPermission: map['hasPermission'] as bool? ?? false,
      endpoints: rawEndpoints
          .map(
            (endpoint) => AdbEndpointInfo.fromMap(
              Map<dynamic, dynamic>.from(endpoint as Map),
            ),
          )
          .toList(),
    );
  }
}

class UsbService {
  static const MethodChannel _channel =
      MethodChannel('com.ultimate.debugging/usb');

  static const EventChannel _eventChannel =
      EventChannel('com.ultimate.debugging/usb_events');

  Future<bool> isUsbHostSupported() async {
    try {
      return await _channel.invokeMethod<bool>(
            'isUsbHostSupported',
          ) ??
          false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
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
    } on MissingPluginException {
      return [];
    }
  }

  Future<List<AdbInterfaceInfo>> getAdbInterfaces() async {
    try {
      final List<dynamic> interfaces =
          await _channel.invokeMethod<List<dynamic>>(
                'getAdbInterfaces',
              ) ??
              [];

      return interfaces
          .map(
            (item) => AdbInterfaceInfo.fromMap(
              Map<dynamic, dynamic>.from(item as Map),
            ),
          )
          .toList();
    } on PlatformException {
      rethrow;
    } on MissingPluginException {
      rethrow;
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
    } on MissingPluginException {
      return false;
    }
  }

  Stream<UsbEvent> get usbEvents {
    return _eventChannel
        .receiveBroadcastStream()
        .map(
          (event) => UsbEvent.fromMap(
            Map<dynamic, dynamic>.from(event as Map),
          ),
        );
  }
}
