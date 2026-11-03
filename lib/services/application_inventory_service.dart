
import 'package:flutter/services.dart';

enum ApplicationRisk {
  low,
  medium,
  high,
  critical,
  unknown,
}

enum ApplicationCategory {
  criticalSystem,
  system,
  rootManagement,
  user,
  disabled,
  unknown,
}

class InstalledApplication {
  final String appName;
  final String packageName;
  final String? versionName;
  final int? versionCode;
  final String appType;
  final bool enabled;
  final bool isSystemApp;
  final bool hasLaunchIntent;

  final ApplicationCategory category;
  final ApplicationRisk risk;
  final String classificationReason;
  final String recommendedAction;

  const InstalledApplication({
    required this.appName,
    required this.packageName,
    this.versionName,
    this.versionCode,
    required this.appType,
    required this.enabled,
    required this.isSystemApp,
    required this.hasLaunchIntent,
    required this.category,
    required this.risk,
    required this.classificationReason,
    required this.recommendedAction,
  });

  factory InstalledApplication.fromMap(
    Map<dynamic, dynamic> map,
  ) {
    final appName =
        map['appName']?.toString() ?? 'Unknown';

    final packageName =
        map['packageName']?.toString() ?? '';

    final isSystemApp =
        map['isSystemApp'] as bool? ?? false;

    final enabled =
        map['enabled'] as bool? ?? false;

    final appType =
        map['appType']?.toString() ?? 'UNKNOWN';

    final classification = _classifyApplication(
      packageName: packageName,
      isSystemApp: isSystemApp,
      enabled: enabled,
    );

    return InstalledApplication(
      appName: appName,
      packageName: packageName,
      versionName: map['versionName']?.toString(),
      versionCode:
          (map['versionCode'] as num?)?.toInt(),
      appType: appType,
      enabled: enabled,
      isSystemApp: isSystemApp,
      hasLaunchIntent:
          map['hasLaunchIntent'] as bool? ?? false,
      category: classification.category,
      risk: classification.risk,
      classificationReason: classification.reason,
      recommendedAction: classification.recommendation,
    );
  }

  static _ApplicationClassification _classifyApplication({
    required String packageName,
    required bool isSystemApp,
    required bool enabled,
  }) {
    final package = packageName.toLowerCase();

    // Known critical Android components.
    const criticalPackages = {
      'android',
      'com.android.systemui',
      'com.android.settings',
      'com.android.packageinstaller',
      'com.google.android.packageinstaller',
      'com.android.permissioncontroller',
      'com.google.android.permissioncontroller',
      'com.android.phone',
      'com.android.server.telecom',
      'com.android.providers.settings',
    };

    if (criticalPackages.contains(package)) {
      return const _ApplicationClassification(
        category: ApplicationCategory.criticalSystem,
        risk: ApplicationRisk.critical,
        reason: 'Recognized critical Android component.',
        recommendation:
            'Protect this component. Investigate logs, '
            'dependencies and official system updates before repair.',
      );
    }

    // Known root-management applications.
    const rootPackages = {
      'com.topjohnwu.magisk',
      'eu.chainfire.supersu',
      'com.noshufou.android.su',
      'me.weishu.kernelsu',
      'me.bmax.apatch',
    };

    if (rootPackages.contains(package)) {
      return const _ApplicationClassification(
        category: ApplicationCategory.rootManagement,
        risk: ApplicationRisk.high,
        reason: 'Recognized root-management application.',
        recommendation:
            'Inspect root configuration, modules and logs. '
            'Check compatibility before changing anything.',
      );
    }

    if (!enabled) {
      return const _ApplicationClassification(
        category: ApplicationCategory.disabled,
        risk: ApplicationRisk.medium,
        reason: 'Application is currently disabled.',
        recommendation:
            'Investigate why it was disabled. Verify its purpose '
            'and dependencies before considering re-enabling it.',
      );
    }

    if (isSystemApp) {
      return const _ApplicationClassification(
        category: ApplicationCategory.system,
        risk: ApplicationRisk.medium,
        reason: 'Android or manufacturer system application.',
        recommendation:
            'Check official updates, compatibility and crash logs. '
            'Avoid disabling or removing without investigation.',
      );
    }

    if (package.isEmpty) {
      return const _ApplicationClassification(
        category: ApplicationCategory.unknown,
        risk: ApplicationRisk.unknown,
        reason: 'Package identifier is unavailable.',
        recommendation:
            'Collect more application information before taking action.',
      );
    }

    return const _ApplicationClassification(
      category: ApplicationCategory.user,
      risk: ApplicationRisk.low,
      reason: 'Regular user application.',
      recommendation:
          'Investigate crashes and permissions. Use supported '
          'troubleshooting actions when necessary.',
    );
  }
}

class _ApplicationClassification {
  final ApplicationCategory category;
  final ApplicationRisk risk;
  final String reason;
  final String recommendation;

  const _ApplicationClassification({
    required this.category,
    required this.risk,
    required this.reason,
    required this.recommendation,
  });
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
