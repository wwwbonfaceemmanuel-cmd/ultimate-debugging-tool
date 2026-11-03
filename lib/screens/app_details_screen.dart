
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/application_inventory_service.dart';

class AppDetailsScreen extends StatelessWidget {
  final InstalledApplication app;

  const AppDetailsScreen({
    super.key,
    required this.app,
  });

  void _copyPackageName(BuildContext context) {
    Clipboard.setData(
      ClipboardData(text: app.packageName),
    );

    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Package name copied.'),
      ),
    );
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
