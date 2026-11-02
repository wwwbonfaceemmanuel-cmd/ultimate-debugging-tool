
import 'package:flutter/services.dart';

class WirelessAdbConnectionInfo {
  final String status;
  final String? host;
  final int? port;
  final bool connected;
  final String? deviceBanner;
  final List<String> features;
  final String? error;

  const WirelessAdbConnectionInfo({
    required this.status,
    this.host,
    this.port,
    required this.connected,
    this.deviceBanner,
    this.features = const [],
    this.error,
  });

  factory WirelessAdbConnectionInfo.fromMap(
    Map<dynamic, dynamic> map,
  ) {
    return WirelessAdbConnectionInfo(
      status: map['status']?.toString() ?? 'DISCONNECTED',
      host: map['host']?.toString(),
      port: (map['port'] as num?)?.toInt(),
      connected: map['connected'] == true,
      deviceBanner: map['deviceBanner']?.toString(),
      features: (map['features'] as List<dynamic>?)
              ?.map((item) => item.toString())
              .toList() ??
          const [],
      error: map['error']?.toString(),
    );
  }
}

class WirelessAdbService {
  static const MethodChannel _channel =
      MethodChannel('com.ultimate.debugging/usb');

  /// Connects to a device using legacy wireless ADB TCP.
  ///
  /// The target device must already have ADB TCP enabled.
  static Future<WirelessAdbConnectionInfo> connect({
    required String host,
    required int port,
  }) async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'connectWirelessAdb',
        {
          'host': host.trim(),
          'port': port,
        },
      );

      if (result == null) {
        throw PlatformException(
          code: 'EMPTY_RESPONSE',
          message: 'The Android service returned an empty response.',
        );
      }

      return WirelessAdbConnectionInfo.fromMap(result);
    } on PlatformException {
      rethrow;
    } catch (error) {
      throw PlatformException(
        code: 'WIRELESS_CONNECTION_ERROR',
        message: error.toString(),
      );
    }
  }

  /// Retrieves the latest wireless ADB connection status.
  static Future<WirelessAdbConnectionInfo> getStatus() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'getWirelessAdbStatus',
      );

      if (result == null) {
        throw PlatformException(
          code: 'EMPTY_RESPONSE',
          message: 'Unable to retrieve wireless ADB status.',
        );
      }

      return WirelessAdbConnectionInfo.fromMap(result);
    } on PlatformException {
      rethrow;
    }
  }

  /// Disconnects the current wireless ADB session.
  static Future<WirelessAdbConnectionInfo> disconnect() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'disconnectWirelessAdb',
      );

      if (result == null) {
        throw PlatformException(
          code: 'EMPTY_RESPONSE',
          message: 'Unable to disconnect wireless ADB.',
        );
      }

      return WirelessAdbConnectionInfo.fromMap(result);
    } on PlatformException {
      rethrow;
    }
  }
}
