
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
  final String? status;
  final List<UsbDeviceInfo> devices;

  const UsbEvent({
    required this.event,
    this.status,
    required this.devices,
  });

  factory UsbEvent.fromMap(Map<dynamic, dynamic> map) {
    final rawDevices = map['devices'] as List<dynamic>? ?? [];

    return UsbEvent(
      event: map['event']?.toString() ?? 'unknown',
      status: map['status']?.toString(),
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

/// Information returned after a successful ADB handshake.
class AdbConnectionInfo {
  final String status;
  final int version;
  final int maxDataSize;
  final String deviceBanner;
  final List<String> features;
  final String keyFingerprint;

  const AdbConnectionInfo({
    required this.status,
    required this.version,
    required this.maxDataSize,
    required this.deviceBanner,
    required this.features,
    required this.keyFingerprint,
  });

  factory AdbConnectionInfo.fromMap(Map<dynamic, dynamic> map) {
    final rawFeatures = map['features'] as List<dynamic>? ?? [];

    return AdbConnectionInfo(
      status: map['status']?.toString() ?? 'UNKNOWN',
      version: map['version'] as int? ?? 0,
      maxDataSize: map['maxDataSize'] as int? ?? 0,
      deviceBanner: map['deviceBanner']?.toString() ?? '',
      features: rawFeatures.map((item) => item.toString()).toList(),
      keyFingerprint: map['keyFingerprint']?.toString() ?? '',
    );
  }
}

/// Current native ADB connection status.
class AdbConnectionStatus {
  final String status;
  final bool connected;

  const AdbConnectionStatus({
    required this.status,
    required this.connected,
  });

  factory AdbConnectionStatus.fromMap(Map<dynamic, dynamic> map) {
    return AdbConnectionStatus(
      status: map['status']?.toString() ?? 'UNKNOWN',
      connected: map['connected'] as bool? ?? false,
    );
  }
}

class UsbService {
  static const MethodChannel _channel =
      MethodChannel('com.ultimate.debugging/usb');

  static const EventChannel _eventChannel =
      EventChannel('com.ultimate.debugging/usb_events');

  // --------------------------------------------------
  // USB HOST SUPPORT
  // --------------------------------------------------

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

  // --------------------------------------------------
  // USB DEVICES
  // --------------------------------------------------

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

  // --------------------------------------------------
  // ADB CONNECTION
  // --------------------------------------------------

  /// Starts an ADB connection to the selected USB device.
  ///
  /// The Android side performs the ADB handshake and
  /// authentication process.
  Future<AdbConnectionInfo> connectAdb({
    required String deviceName,
    required int interfaceId,
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'connectAdb',
      {
        'deviceName': deviceName,
        'interfaceId': interfaceId,
      },
    );

    if (result == null) {
      throw PlatformException(
        code: 'ADB_EMPTY_RESPONSE',
        message: 'The Android ADB service returned no response.',
      );
    }

    return AdbConnectionInfo.fromMap(result);
  }

  /// Reads the current ADB connection status.
  Future<AdbConnectionStatus> getAdbConnectionStatus() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getAdbConnectionStatus',
    );

    if (result == null) {
      return const AdbConnectionStatus(
        status: 'UNKNOWN',
        connected: false,
      );
    }

    return AdbConnectionStatus.fromMap(result);
  }

  /// Disconnects the current ADB session.
  Future<bool> disconnectAdb() async {
    return await _channel.invokeMethod<bool>(
          'disconnectAdb',
        ) ??
        false;
  }

  /// Gets the fingerprint of this workstation's ADB public key.
  Future<String> getAdbKeyFingerprint() async {
    final fingerprint = await _channel.invokeMethod<String>(
      'getAdbKeyFingerprint',
    );

    return fingerprint ?? '';
  }

  // --------------------------------------------------
  // USB EVENTS
  // --------------------------------------------------

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
