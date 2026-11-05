
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/application_inventory_service.dart';
import 'application_permissions_screen.dart';

class AppDetailsScreen extends StatelessWidget {
  final InstalledApplication app;

  const AppDetailsScreen({
    super.key,
    required this.app,
  });

  void _copyText(BuildContext context, String value, String label) {
    Clipboard.setData(ClipboardData(text: value));

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('$label copied.'),
      ),
    );
  }

  void _openPermissions(BuildContext context) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) =>
            ApplicationPermissionsScreen(app: app),
      ),
    );
  }

  String _formatTimestamp(int? timestamp) {
    if (timestamp == null || timestamp <= 0) {
      return 'Unavailable';
    }

    final date = DateTime.fromMillisecondsSinceEpoch(
      timestamp,
    ).toLocal();

    String twoDigits(int value) =>
        value.toString().padLeft(2, '0');

    return '${date.year}-'
        '${twoDigits(date.month)}-'
        '${twoDigits(date.day)} '
        '${twoDigits(date.hour)}:'
        '${twoDigits(date.minute)}';
  }

  Widget _detailItem(
    String title,
    String value, {
    IconData? icon,
  }) {
    return Card(
      color: const Color(0xFF172638),
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        leading: icon == null
            ? null
            : Icon(
                icon,
                color: Colors.lightBlueAccent,
              ),
        title: Text(
          title,
          style: const TextStyle(
            color: Colors.white60,
            fontSize: 12,
          ),
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 5),
          child: Text(
            value,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }

  String _categoryLabel(ApplicationCategory category) {
    switch (category) {
      case ApplicationCategory.criticalSystem:
        return 'CRITICAL SYSTEM';
      case ApplicationCategory.system:
        return 'SYSTEM APPLICATION';
      case ApplicationCategory.rootManagement:
        return 'ROOT MANAGEMENT';
      case ApplicationCategory.user:
        return 'USER APPLICATION';
      case ApplicationCategory.disabled:
        return 'DISABLED APPLICATION';
      case ApplicationCategory.unknown:
        return 'UNKNOWN';
    }
  }

  String _riskLabel(ApplicationRisk risk) {
    switch (risk) {
      case ApplicationRisk.low:
        return 'LOW';
      case ApplicationRisk.medium:
        return 'MEDIUM';
      case ApplicationRisk.high:
        return 'HIGH';
      case ApplicationRisk.critical:
        return 'CRITICAL';
      case ApplicationRisk.unknown:
        return 'UNKNOWN';
    }
  }

  Widget _buildPermissionsButton(BuildContext context) {
    return Card(
      color: const Color(0xFF172638),
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        leading: const Icon(
          Icons.security,
          color: Colors.lightBlueAccent,
          size: 30,
        ),
        title: const Text(
          'Application Permissions Analysis',
          style: TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.bold,
          ),
        ),
        subtitle: const Padding(
          padding: EdgeInsets.only(top: 5),
          child: Text(
            'Inspect requested permissions, grant status, '
            'sensitivity and technician recommendations.',
            style: TextStyle(
              color: Colors.white60,
              fontSize: 12,
            ),
          ),
        ),
        trailing: const Icon(
          Icons.arrow_forward_ios,
          color: Colors.lightBlueAccent,
          size: 18,
        ),
        onTap: () => _openPermissions(context),
      ),
    );
  }

  Widget _buildSigningSection(BuildContext context) {
    final fingerprints = app.certificateSha256;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'APK SIGNING CERTIFICATE INSPECTION',
          style: TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.bold,
            letterSpacing: 1,
          ),
        ),

        const SizedBox(height: 12),

        _detailItem(
          'Signing Information',
          app.signingInfoAvailable
              ? 'AVAILABLE'
              : 'UNAVAILABLE',
          icon: Icons.verified_user_outlined,
        ),

        _detailItem(
          'Multiple Simultaneous Signers',
          app.hasMultipleSigners ? 'YES' : 'NO',
          icon: Icons.security,
        ),

        _detailItem(
          'Certificate Fingerprints Found',
          app.signerCount.toString(),
          icon: Icons.fingerprint,
        ),

        if (fingerprints.isNotEmpty) ...[
          const SizedBox(height: 8),

          const Text(
            'SHA-256 CERTIFICATE FINGERPRINTS',
            style: TextStyle(
              color: Colors.white70,
              fontWeight: FontWeight.bold,
              fontSize: 12,
            ),
          ),

          const SizedBox(height: 10),

          ...fingerprints.asMap().entries.map((entry) {
            final index = entry.key;
            final fingerprint = entry.value;

            return Card(
              color: const Color(0xFF172638),
              margin: const EdgeInsets.only(bottom: 10),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Certificate ${index + 1}',
                      style: const TextStyle(
                        color: Colors.white70,
                        fontWeight: FontWeight.bold,
                        fontSize: 12,
                      ),
                    ),

                    const SizedBox(height: 8),

                    SelectableText(
                      fingerprint,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 11,
                        height: 1.5,
                      ),
                    ),

                    const SizedBox(height: 8),

                    Align(
                      alignment: Alignment.centerRight,
                      child: TextButton.icon(
                        onPressed: () {
                          _copyText(
                            context,
                            fingerprint,
                            'Certificate fingerprint',
                          );
                        },
                        icon: const Icon(
                          Icons.copy,
                          size: 16,
                        ),
                        label: const Text('Copy fingerprint'),
                        style: TextButton.styleFrom(
                          foregroundColor:
                              Colors.lightBlueAccent,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          }),
        ] else
          _detailItem(
            'Certificate Fingerprints',
            'No fingerprints were provided by Android.',
            icon: Icons.info_outline,
          ),

        Card(
          color: const Color(0xFF172638),
          margin: const EdgeInsets.only(bottom: 10),
          child: const Padding(
            padding: EdgeInsets.all(14),
            child: Text(
              'Technician note: A certificate fingerprint identifies '
              'a signing certificate. It does not independently prove '
              'that the publisher is trusted or that the application '
              'is safe. Compare fingerprints with a trusted reference '
              'when available. Certificate history may include older '
              'certificates after signing-key rotation. Unavailable '
              'signing information is not proof of corruption.',
              style: TextStyle(
                color: Colors.white70,
                fontSize: 12,
                height: 1.5,
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildIntegritySection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'APPLICATION INTEGRITY & INSTALLATION DETAILS',
          style: TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.bold,
            letterSpacing: 1,
          ),
        ),

        const SizedBox(height: 12),

        _detailItem(
          'Package Information',
          app.packageInfoAvailable
              ? 'AVAILABLE'
              : 'UNAVAILABLE',
          icon: Icons.fact_check_outlined,
        ),

        _detailItem(
          'Application UID',
          app.uid?.toString() ?? 'Unavailable',
          icon: Icons.fingerprint,
        ),

        _detailItem(
          'Target SDK Version',
          app.targetSdkVersion?.toString() ?? 'Unavailable',
          icon: Icons.developer_mode,
        ),

        _detailItem(
          'First Installation',
          _formatTimestamp(app.firstInstallTime),
          icon: Icons.install_mobile,
        ),

        _detailItem(
          'Last Updated',
          _formatTimestamp(app.lastUpdateTime),
          icon: Icons.update,
        ),

        _detailItem(
          'Reported Installation Source',
          app.installSource == null ||
                  app.installSource!.isEmpty
              ? 'Unavailable or not reported'
              : app.installSource!,
          icon: Icons.source,
        ),

        Card(
          color: const Color(0xFF172638),
          margin: const EdgeInsets.only(bottom: 10),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  app.packageInfoAvailable
                      ? Icons.verified_outlined
                      : Icons.warning_amber,
                  color: app.packageInfoAvailable
                      ? Colors.greenAccent
                      : Colors.orangeAccent,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    app.packageInfoAvailable
                        ? 'Package information was successfully '
                            'retrieved from Android.'
                        : 'Android did not provide complete package '
                            'information. Investigate further before '
                            'making repair decisions.',
                    style: const TextStyle(
                      color: Colors.white70,
                      fontSize: 12,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),

        const Text(
          'Installation source information may be unavailable '
          'for manually installed applications, restored apps, '
          'or applications whose installer information is not '
          'reported by Android. Missing information is not proof '
          'of application corruption.',
          style: TextStyle(
            color: Colors.white54,
            fontSize: 11,
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final appType = app.isSystemApp
        ? 'SYSTEM APPLICATION'
        : 'USER APPLICATION';

    return Scaffold(
      backgroundColor: const Color(0xFF0B1424),
      appBar: AppBar(
        backgroundColor: const Color(0xFF101D32),
        foregroundColor: Colors.white,
        title: const Text('Application Details'),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Card(
              color: const Color(0xFF172638),
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  children: [
                    CircleAvatar(
                      radius: 35,
                      backgroundColor:
                          Colors.lightBlueAccent.withOpacity(0.15),
                      child: Icon(
                        app.isSystemApp
                            ? Icons.settings
                            : Icons.android,
                        size: 38,
                        color: Colors.lightBlueAccent,
                      ),
                    ),
                    const SizedBox(height: 14),
                    Text(
                      app.appName,
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 20,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      appType,
                      style: TextStyle(
                        color: app.isSystemApp
                            ? Colors.orangeAccent
                            : Colors.greenAccent,
                        fontWeight: FontWeight.bold,
                        fontSize: 11,
                      ),
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 20),

            const Text(
              'PACKAGE INFORMATION',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
              ),
            ),

            const SizedBox(height: 12),

            _detailItem(
              'Application Name',
              app.appName,
              icon: Icons.apps,
            ),

            Card(
              color: const Color(0xFF172638),
              child: ListTile(
                leading: const Icon(
                  Icons.inventory_2,
                  color: Colors.lightBlueAccent,
                ),
                title: const Text(
                  'Package Name',
                  style: TextStyle(
                    color: Colors.white60,
                    fontSize: 12,
                  ),
                ),
                subtitle: Padding(
                  padding: const EdgeInsets.only(top: 5),
                  child: Text(
                    app.packageName,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                    ),
                  ),
                ),
                trailing: IconButton(
                  icon: const Icon(
                    Icons.copy,
                    color: Colors.lightBlueAccent,
                  ),
                  onPressed: () {
                    _copyText(
                      context,
                      app.packageName,
                      'Package name',
                    );
                  },
                ),
              ),
            ),

            _detailItem(
              'Version Name',
              app.versionName ?? 'Unknown',
              icon: Icons.new_releases,
            ),

            _detailItem(
              'Version Code',
              app.versionCode?.toString() ?? 'Unknown',
              icon: Icons.numbers,
            ),

            const SizedBox(height: 20),

            const Text(
              'APPLICATION STATUS',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
              ),
            ),

            const SizedBox(height: 12),

            _detailItem(
              'Application Type',
              appType,
              icon: Icons.category,
            ),

            _detailItem(
              'Enabled Status',
              app.enabled ? 'ENABLED' : 'DISABLED',
              icon: Icons.power_settings_new,
            ),

            _detailItem(
              'Launch Support',
              app.hasLaunchIntent
                  ? 'Launchable application'
                  : 'No standard launch activity detected',
              icon: Icons.launch,
            ),

            const SizedBox(height: 20),

            _buildIntegritySection(),

            const SizedBox(height: 20),

            _buildSigningSection(context),

            const SizedBox(height: 20),

            const Text(
              'APPLICATION SECURITY & PERMISSIONS',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
              ),
            ),

            const SizedBox(height: 12),

            _buildPermissionsButton(context),

            const SizedBox(height: 20),

            const Text(
              'TECHNICIAN DIAGNOSTIC ASSESSMENT',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
              ),
            ),

            const SizedBox(height: 12),

            _detailItem(
              'Application Classification',
              _categoryLabel(app.category),
              icon: Icons.category,
            ),

            _detailItem(
              'Risk Assessment',
              _riskLabel(app.risk),
              icon: Icons.warning_amber,
            ),

            _detailItem(
              'Classification Reason',
              app.classificationReason,
              icon: Icons.info_outline,
            ),

            _detailItem(
              'Recommended Technician Action',
              app.recommendedAction,
              icon: Icons.build_circle_outlined,
            ),

            const SizedBox(height: 16),

            const Text(
              'Important: This is an automated preliminary assessment, '
              'not proof that an application is faulty or dangerous. '
              'Investigate before performing repairs.',
              style: TextStyle(
                color: Colors.white54,
                fontSize: 11,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
leading: const Icon(
                  Icons.inventory_2,
                  color: Colors.lightBlueAccent,
                ),
                title: const Text(
                  'Package Name',
                  style: TextStyle(
                    color: Colors.white60,
                    fontSize: 12,
                  ),
                ),
                subtitle: Padding(
                  padding: const EdgeInsets.only(top: 5),
                  child: Text(
                    app.packageName,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                    ),
                  ),
                ),
                trailing: IconButton(
                  icon: const Icon(
                    Icons.copy,
                    color: Colors.lightBlueAccent,
                  ),
                  onPressed: () {
                    _copyPackageName(context);
                  },
                ),
              ),
            ),

            _detailItem(
              'Version Name',
              app.versionName ?? 'Unknown',
              icon: Icons.new_releases,
            ),

            _detailItem(
              'Version Code',
              app.versionCode?.toString() ?? 'Unknown',
              icon: Icons.numbers,
            ),

            const SizedBox(height: 20),

            const Text(
              'APPLICATION STATUS',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
              ),
            ),

            const SizedBox(height: 12),

            _detailItem(
              'Application Type',
              appType,
              icon: Icons.category,
            ),

            _detailItem(
              'Enabled Status',
              app.enabled ? 'ENABLED' : 'DISABLED',
              icon: Icons.power_settings_new,
            ),

            _detailItem(
              'Launch Support',
              app.hasLaunchIntent
                  ? 'Launchable application'
                  : 'No standard launch activity detected',
              icon: Icons.launch,
            ),

            const SizedBox(height: 20),

            _buildIntegritySection(),

            const SizedBox(height: 20),

            const Text(
              'APPLICATION SECURITY & PERMISSIONS',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
              ),
            ),

            const SizedBox(height: 12),

            _buildPermissionsButton(context),

            const SizedBox(height: 20),

            const Text(
              'TECHNICIAN DIAGNOSTIC ASSESSMENT',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
              ),
            ),

            const SizedBox(height: 12),

            _detailItem(
              'Application Classification',
              _categoryLabel(app.category),
              icon: Icons.category,
            ),

            _detailItem(
              'Risk Assessment',
              _riskLabel(app.risk),
              icon: Icons.warning_amber,
            ),

            _detailItem(
              'Classification Reason',
              app.classificationReason,
              icon: Icons.info_outline,
            ),

            _detailItem(
              'Recommended Technician Action',
              app.recommendedAction,
              icon: Icons.build_circle_outlined,
            ),

            const SizedBox(height: 16),

            const Text(
              'Important: This is an automated preliminary assessment, '
              'not proof that an application is faulty or dangerous. '
              'Investigate before performing repairs.',
              style: TextStyle(
                color: Colors.white54,
                fontSize: 11,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
eliminary assessment, '
              'not proof that an application is faulty or dangerous. '
              'Investigate before performing repairs.',
              style: TextStyle(
                color: Colors.white54,
                fontSize: 11,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
