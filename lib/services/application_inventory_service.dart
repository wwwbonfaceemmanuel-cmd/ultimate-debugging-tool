
import 'package:flutter/services.dart';

class InstalledApplication {
  final String appName;
  final String packageName;
  final String? versionName;
  final int? versionCode;
  final String appType;
  final bool enabled;
  final bool isSystemApp;
  final bool hasLaunchIntent;

  const InstalledApplication({
    required this.appName,
    required this.packageName,
    this.versionName,
    this.versionCode,
    required this.appType,
    required this.enabled,
    required this.isSystemApp,
    required this.hasLaunchIntent,
  });

  factory InstalledApplication.fromMap(
    Map<dynamic, dynamic> map,
  ) {
    return InstalledApplication(
      appName: map['appName']?.toString() ?? 'Unknown',
      packageName: map['packageName']?.toString() ?? '',
      versionName: map['versionName']?.toString(),
      versionCode: (map['versionCode'] as num?)?.toInt(),
      appType: map['appType']?.toString() ?? 'UNKNOWN',
      enabled: map['enabled'] as bool? ?? false,
      isSystemApp: map['isSystemApp'] as bool? ?? false,
      hasLaunchIntent: map['hasLaunchIntent'] as bool? ?? false,
    );
  }
}

class ApplicationInventoryService {
  static const MethodChannel _channel =
      MethodChannel('com.ultimate.debugging/usb');

  Future<List<InstalledApplication>>
      getInstalledApplications() async {
    final List<dynamic> applications =
        await _channel.invokeMethod<List<dynamic>>(
              'getInstalledApplications',
            ) ??
            [];

    return applications.map((application) {
      return InstalledApplication.fromMap(
        Map<dynamic, dynamic>.from(application as Map),
      );
    }).toList();
  }
}
