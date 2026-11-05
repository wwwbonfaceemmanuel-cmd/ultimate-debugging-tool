
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/application_inventory_service.dart';
import '../services/application_permissions_service.dart';

class ApplicationPermissionsScreen extends StatefulWidget {
  final InstalledApplication app;

  const ApplicationPermissionsScreen({
    super.key,
    required this.app,
  });

  @override
  State<ApplicationPermissionsScreen> createState() =>
      _ApplicationPermissionsScreenState();
}

class _ApplicationPermissionsScreenState
    extends State<ApplicationPermissionsScreen> {
  final ApplicationPermissionsService _service =
      ApplicationPermissionsService();

  List<ApplicationPermission> _permissions = [];

  bool _isLoading = true;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _loadPermissions();
  }

  Future<void> _loadPermissions() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final permissions =
          await _service.getApplicationPermissions(
        widget.app.packageName,
      );

      if (!mounted) return;

      setState(() {
        _permissions = permissions;
        _isLoading = false;
      });
    } on PlatformException catch (error) {
      if (!mounted) return;

      setState(() {
        _errorMessage =
            error.message ?? 'Unable to retrieve permissions.';
        _isLoading = false;
      });
    } catch (error) {
      if (!mounted) return;

      setState(() {
        _errorMessage =
            'An unexpected error occurred: $error';
        _isLoading = false;
      });
    }
  }

  Color _statusColor(PermissionStatus status) {
    switch (status) {
      case PermissionStatus.granted:
        return Colors.green;
      case PermissionStatus.denied:
        return Colors.orange;
      case PermissionStatus.restricted:
        return Colors.red;
      case PermissionStatus.unknown:
        return Colors.grey;
    }
  }

  Color _sensitivityColor(
    PermissionSensitivity sensitivity,
  ) {
    switch (sensitivity) {
      case PermissionSensitivity.normal:
        return Colors.green;
      case PermissionSensitivity.sensitive:
        return Colors.orange;
      case PermissionSensitivity.critical:
        return Colors.red;
      case PermissionSensitivity.special:
        return Colors.deepPurple;
    }
  }

  String _statusLabel(PermissionStatus status) {
    switch (status) {
      case PermissionStatus.granted:
        return 'GRANTED';
      case PermissionStatus.denied:
        return 'DENIED';
      case PermissionStatus.restricted:
        return 'RESTRICTED';
      case PermissionStatus.unknown:
        return 'UNKNOWN';
    }
  }

  String _sensitivityLabel(
    PermissionSensitivity sensitivity,
  ) {
    switch (sensitivity) {
      case PermissionSensitivity.normal:
        return 'NORMAL';
      case PermissionSensitivity.sensitive:
        return 'SENSITIVE';
      case PermissionSensitivity.critical:
        return 'CRITICAL';
      case PermissionSensitivity.special:
        return 'SPECIAL ACCESS';
    }
  }

  Widget _buildSummaryCard() {
    final granted = _permissions
        .where((p) => p.status == PermissionStatus.granted)
        .length;

    final denied = _permissions
        .where((p) => p.status == PermissionStatus.denied)
        .length;

    final special = _permissions
        .where((p) => p.sensitivity == PermissionSensitivity.special)
        .length;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Permission Summary',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            _summaryRow('Total requested', _permissions.length),
            _summaryRow('Granted', granted),
            _summaryRow('Denied', denied),
            _summaryRow('Special access permissions', special),
          ],
        ),
      ),
    );
  }

  Widget _summaryRow(String label, int count) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label),
          Text(
            count.toString(),
            style: const TextStyle(
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPermissionCard(
    ApplicationPermission permission,
  ) {
    final statusColor = _statusColor(permission.status);
    final sensitivityColor =
        _sensitivityColor(permission.sensitivity);

    return Card(
      margin: const EdgeInsets.symmetric(
        horizontal: 12,
        vertical: 6,
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SelectableText(
              permission.permissionName,
              style: const TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 14,
              ),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _buildBadge(
                  _statusLabel(permission.status),
                  statusColor,
                ),
                _buildBadge(
                  _sensitivityLabel(permission.sensitivity),
                  sensitivityColor,
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'What it means',
              style: TextStyle(
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 4),
            Text(permission.explanation),
            const SizedBox(height: 12),
            const Text(
              'Technician recommendation',
              style: TextStyle(
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 4),
            Text(permission.recommendation),
          ],
        ),
      ),
    );
  }

  Widget _buildBadge(String label, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: 10,
        vertical: 5,
      ),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }

  Widget _buildContent() {
    if (_isLoading) {
      return const Center(
        child: CircularProgressIndicator(),
      );
    }

    if (_errorMessage != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.error_outline,
                color: Colors.red,
                size: 42,
              ),
              const SizedBox(height: 12),
              Text(
                _errorMessage!,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              ElevatedButton.icon(
                onPressed: _loadPermissions,
                icon: const Icon(Icons.refresh),
                label: const Text('Try Again'),
              ),
            ],
          ),
        ),
      );
    }

    if (_permissions.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Text(
            'No requested permissions were found for this application.',
            textAlign: TextAlign.center,
          ),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _loadPermissions,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: _buildSummaryCard(),
          ),
          const Padding(
            padding: EdgeInsets.symmetric(
              horizontal: 16,
              vertical: 8,
            ),
            child: Text(
              'Requested Permissions',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          ..._permissions.map(_buildPermissionCard),
          const Padding(
            padding: EdgeInsets.all(16),
            child: Text(
              'Important: Permission status is based on information '
              'reported by Android. Special app access, temporary '
              'permissions, background restrictions and device '
              'manufacturer policies may require separate checks. '
              'This assessment does not prove that an application '
              'is harmful or malfunctioning.',
              style: TextStyle(
                fontSize: 12,
                color: Colors.grey,
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Application Permissions'),
        actions: [
          IconButton(
            onPressed: _loadPermissions,
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh permissions',
          ),
        ],
      ),
      body: Column(
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            color: Theme.of(context)
                .colorScheme
                .surfaceContainerHighest,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  widget.app.appName,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                SelectableText(
                  widget.app.packageName,
                  style: const TextStyle(fontSize: 12),
                ),
              ],
            ),
          ),
          Expanded(child: _buildContent()),
        ],
      ),
    );
  }
}
