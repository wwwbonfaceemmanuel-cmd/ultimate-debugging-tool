
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

  // Application diagnostic information.
  final int? uid;
  final int? targetSdkVersion;
  final int? firstInstallTime;
  final int? lastUpdateTime;
  final String? installSource;
  final bool packageInfoAvailable;

  // APK signing information.
  final bool signingInfoAvailable;
  final bool hasMultipleSigners;
  final int signerCount;
  final List<String> certificateSha256;

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
    this.uid,
    this.targetSdkVersion,
    this.firstInstallTime,
    this.lastUpdateTime,
    this.installSource,
    this.packageInfoAvailable = false,
    this.signingInfoAvailable = false,
    this.hasMultipleSigners = false,
    this.signerCount = 0,
    this.certificateSha256 = const [],
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

    final fingerprints = (
      map['certificateSha256'] as List? ?? []
    ).map((item) => item.toString()).toList();

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

      uid: (map['uid'] as num?)?.toInt(),
      targetSdkVersion:
          (map['targetSdkVersion'] as num?)?.toInt(),
      firstInstallTime:
          (map['firstInstallTime'] as num?)?.toInt(),
      lastUpdateTime:
          (map['lastUpdateTime'] as num?)?.toInt(),
      installSource: map['installSource']?.toString(),
      packageInfoAvailable:
          map['packageInfoAvailable'] as bool? ?? false,

      signingInfoAvailable:
          map['signingInfoAvailable'] as bool? ?? false,
      hasMultipleSigners:
          map['hasMultipleSigners'] as bool? ?? false,
      signerCount:
          (map['signerCount'] as num?)?.toInt() ??
              fingerprints.length,
      certificateSha256: fingerprints,

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

  
  Future<Map<String, dynamic>> exportApplicationApk(
    String packageName,
  ) async {
    if (packageName.trim().isEmpty) {
      throw ArgumentError('A valid package name is required.');
    }

    final dynamic response = await _channel.invokeMethod(
      'exportApplicationApk',
      {
        'packageName': packageName,
      },
    );

    if (response is! Map) {
      throw Exception('Invalid APK backup response.');
    }

    final data = Map<dynamic, dynamic>.from(response);

    if (data['success'] != true ||
        data['integrityVerified'] != true) {
      throw Exception('APK backup was not verified.');
    }

    final directory = data['backupDirectory'];

    if (directory is! String || directory.isEmpty) {
      throw Exception('APK backup directory is missing.');
    }

    final rawFiles = data['files'];

    if (rawFiles is! List || rawFiles.isEmpty) {
      throw Exception('No APK backup files were returned.');
    }

    final files = <Map<String, dynamic>>[];

    for (final item in rawFiles) {
      if (item is! Map) {
        throw Exception('Invalid APK backup metadata.');
      }

      final file = Map<dynamic, dynamic>.from(item);

      final name = file['fileName'];
      final hash = file['sha256'];
      final size = file['sizeBytes'];
      final verified = file['verified'];

      if (name is! String ||
          hash is! String ||
          size is! num ||
          verified != true) {
        throw Exception('Incomplete APK verification metadata.');
      }

      if (!RegExp(r'^(base|split_[0-9]+)\.apk$')
          .hasMatch(name)) {
        throw Exception('Invalid APK backup filename.');
      }

      if (!RegExp(r'^[A-Fa-f0-9]{64}$').hasMatch(hash)) {
        throw Exception('Invalid APK SHA-256 fingerprint.');
      }

      if (size <= 0) {
        throw Exception('Invalid APK file size.');
      }

      files.add({
        'fileName': name,
        'sha256': hash,
        'sizeBytes': size,
        'verified': true,
      });
    }

    if (data['fileCount'] != files.length ||
        !files.any((file) => file['fileName'] == 'base.apk')) {
      throw Exception('APK backup file list is incomplete.');
    }

    return {
      'success': true,
      'packageName': data['packageName'],
      'backupDirectory': directory,
      'fileCount': files.length,
      'files': files,
      'integrityVerified': true,
    };
  }

  Future<Map<String, dynamic>> shareApplicationApkBackup({
    required String backupDirectory,
    required List<Map<String, dynamic>> files,
  }) async {
    if (backupDirectory.trim().isEmpty || files.isEmpty) {
      throw ArgumentError('A valid APK backup is required.');
    }

    final dynamic response = await _channel.invokeMethod(
      'shareApplicationApkBackup',
      {
        'backupDirectory': backupDirectory,
        'files': files,
      },
    );

        if (response is! Map) {
      throw Exception('Invalid APK sharing response.');
    }

    final data = Map<dynamic, dynamic>.from(response);

    if (data['success'] != true ||
        data['integrityVerified'] != true) {
      throw Exception(
        'APK backup verification failed before sharing.',
      );
    }

    return Map<String, dynamic>.from(data);
