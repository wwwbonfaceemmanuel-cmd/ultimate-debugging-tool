import 'package:flutter/services.dart';

enum PermissionStatus {
  granted,
  denied,
  restricted,
  unknown,
}

enum PermissionSensitivity {
  normal,
  sensitive,
  critical,
  special,
}

class ApplicationPermission {
  final String permissionName;
  final PermissionStatus status;
  final PermissionSensitivity sensitivity;
  final String explanation;
  final String recommendation;

  const ApplicationPermission({
    required this.permissionName,
    required this.status,
    required this.sensitivity,
    required this.explanation,
    required this.recommendation,
  });

  factory ApplicationPermission.fromMap(
    Map<dynamic, dynamic> map,
  ) {
    return ApplicationPermission(
      permissionName:
          map['permissionName']?.toString() ?? 'Unknown',
      status: _parseStatus(map['status']?.toString()),
      sensitivity:
          _parseSensitivity(map['sensitivity']?.toString()),
      explanation:
          map['explanation']?.toString() ??
              'Permission information is unavailable.',
      recommendation:
          map['recommendation']?.toString() ??
              'Review this permission before taking action.',
    );
  }

  static PermissionStatus _parseStatus(String? value) {
    return PermissionStatus.values.firstWhere(
      (status) => status.name == value?.toLowerCase(),
      orElse: () => PermissionStatus.unknown,
    );
  }

  static PermissionSensitivity _parseSensitivity(
    String? value,
  ) {
    return PermissionSensitivity.values.firstWhere(
      (sensitivity) =>
          sensitivity.name == value?.toLowerCase(),
      orElse: () => PermissionSensitivity.normal,
    );
  }
}

class ApplicationPermissionsService {
  static const MethodChannel _channel =
      MethodChannel('com.ultimate.debugging/usb');

  Future<List<ApplicationPermission>>
      getApplicationPermissions(String packageName) async {
    if (packageName.trim().isEmpty) {
      throw ArgumentError('Package name cannot be empty.');
    }

    final List<dynamic> permissions =
        await _channel.invokeMethod<List<dynamic>>(
              'getApplicationPermissions',
              {'packageName': packageName},
            ) ??
            [];

    return permissions.map((permission) {
      return ApplicationPermission.fromMap(
        Map<dynamic, dynamic>.from(permission as Map),
      );
    }).toList();
  }
}
