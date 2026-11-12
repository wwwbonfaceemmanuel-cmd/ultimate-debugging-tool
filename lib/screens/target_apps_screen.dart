import 'package:flutter/material.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';
import 'connection_screen.dart';
import 'repair_flow.dart';

/// The apps installed on the connected phone.
class TargetAppsScreen extends StatefulWidget {
  const TargetAppsScreen({super.key});

  @override
  State<TargetAppsScreen> createState() => _TargetAppsScreenState();
}

class _TargetAppsScreenState extends State<TargetAppsScreen> {
  static const List<String> _filters = ['USER', 'SYSTEM', 'DISABLED', 'ALL'];

  static const Map<String, String> _filterLabels = {
    'USER': 'User apps',
    'SYSTEM': 'System',
    'DISABLED': 'Disabled',
    'ALL': 'All',
  };

  final DeviceBridge _bridge = DeviceBridge.instance;
  final TextEditingController _search = TextEditingController();

  List<TargetApp> _apps = const <TargetApp>[];
  bool _loading = false;
  bool _loadedOnce = false;
  String _error = '';
  String _filter = 'USER';
  String _loadedFor = '';

  @override
  void initState() {
    super.initState();
    _bridge.start();
    _bridge.status.addListener(_onStatusChanged);
    if (_bridge.status.value.connected) _load();
  }

  @override
  void dispose() {
    _bridge.status.removeListener(_onStatusChanged);
    _search.dispose();
    super.dispose();
  }

  String _identityOf(AdbStatus status) {
    return '${status.link}|${status.target}|${status.device['serial'] ?? ''}';
  }

  void _onStatusChanged() {
    final status = _bridge.status.value;

    if (!status.connected) {
      if (mounted && _apps.isNotEmpty) {
        setState(() {
          _apps = const <TargetApp>[];
          _loadedOnce = false;
          _loadedFor = '';
        });
      }
      return;
    }

    // A different phone was connected: its list replaces the old one.
    if (_identityOf(status) != _loadedFor && !_loading) _load();
  }

  Future<void> _load() async {
    if (_loading) return;

    final identity = _identityOf(_bridge.status.value);

    setState(() {
      _loading = true;
      _error = '';
    });

    try {
      final apps = await _bridge.getTargetApplications();
      apps.sort((a, b) => a.packageName.compareTo(b.packageName));

      if (!mounted) return;
      setState(() {
        _apps = apps;
        _loading = false;
        _loadedOnce = true;
        _loadedFor = identity;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = describeError(error);
        _loading = false;
      });
    }
  }

  List<TargetApp> get _visible {
    final query = _search.text.trim().toLowerCase();

    return _apps.where((app) {
      final matchesFilter = switch (_filter) {
        'USER' => !app.isSystemApp,
        'SYSTEM' => app.isSystemApp,
        'DISABLED' => !app.enabled,
        _ => true,
      };

      return matchesFilter &&
          (query.isEmpty || app.packageName.toLowerCase().contains(query));
    }).toList();
  }

  Future<void> _open(TargetApp app) async {
    await Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (context) => TargetAppDetailsScreen(app: app),
      ),
    );

    // The app may have been removed, disabled or enabled.
    if (mounted && _bridge.status.value.connected) await _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Apps on Connected Phone'),
        actions: [
          IconButton(
            tooltip: 'Refresh',
            onPressed: _loading ? null : _load,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          final visible = _visible;

          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                child: TargetBanner(
                  onConnect: () => openConnectionScreen(context),
                ),
              ),
              if (!status.connected)
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: NeedsPhone(
                    what: 'The app list',
                    onConnect: () => openConnectionScreen(context),
                  ),
                )
              else ...[
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                  child: TextField(
                    controller: _search,
                    autocorrect: false,
                    decoration: const InputDecoration(
                      prefixIcon: Icon(Icons.search),
                      hintText: 'Search by package name',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    onChanged: (_) => setState(() {}),
                  ),
                ),
                SizedBox(
                  height: 52,
                  child: ListView(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
                    children: [
                      for (final filter in _filters)
                        Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: ChoiceChip(
                            label: Text(_filterLabels[filter] ?? filter),
                            selected: _filter == filter,
                            onSelected: (_) =>
                                setState(() => _filter = filter),
                          ),
                        ),
                    ],
                  ),
                ),
                if (_loadedOnce)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        '${visible.length} shown of ${_apps.length} installed',
                        style: const TextStyle(
                          color: AppColors.textMuted,
                          fontSize: 12.5,
                        ),
                      ),
                    ),
                  ),
                Expanded(
                  child: _loading && !_loadedOnce
                      ? const Center(child: CircularProgressIndicator())
                      : _error.isNotEmpty
                          ? Padding(
                              padding: const EdgeInsets.all(16),
                              child: Notice(
                                icon: Icons.error_outline,
                                color: AppColors.danger,
                                title: 'The app list could not be read',
                                message: _error,
                                actionLabel: 'Try again',
                                onAction: _load,
                              ),
                            )
                          : visible.isEmpty
                              ? const Center(
                                  child: Text(
                                    'No app matches.',
                                    style: TextStyle(color: Colors.white60),
                                  ),
                                )
                              : ListView.builder(
                                  padding:
                                      const EdgeInsets.fromLTRB(8, 4, 8, 24),
                                  itemCount: visible.length,
                                  itemBuilder: (context, index) {
                                    final app = visible[index];

                                    return ListTile(
                                      leading: Icon(
                                        app.isSystemApp
                                            ? Icons.settings_applications
                                            : Icons.android,
                                        color: app.isSystemApp
                                            ? AppColors.warning
                                            : AppColors.ok,
                                      ),
                                      title: Text(
                                        app.packageName,
                                        style: const TextStyle(
                                          color: Colors.white,
                                          fontSize: 14,
                                        ),
                                      ),
                                      subtitle: Text(
                                        [
                                          app.isSystemApp
                                              ? 'System app'
                                              : 'User app',
                                          if (!app.enabled) 'disabled',
                                        ].join(', '),
                                        style: const TextStyle(
                                          color: Colors.white60,
                                        ),
                                      ),
                                      trailing: const Icon(
                                        Icons.chevron_right,
                                        color: Colors.white38,
                                      ),
                                      onTap: () => _open(app),
                                    );
                                  },
                                ),
                ),
              ],
            ],
          );
        },
      ),
    );
  }
}

/// Details of one app on the connected phone, with the actions that are
/// allowed on it.
class TargetAppDetailsScreen extends StatefulWidget {
  final TargetApp app;

  const TargetAppDetailsScreen({super.key, required this.app});

  @override
  State<TargetAppDetailsScreen> createState() => _TargetAppDetailsScreenState();
}

class _TargetAppDetailsScreenState extends State<TargetAppDetailsScreen> {
  /// Catalog services offered here, in this order.
  static const List<String> _actionIds = [
    'force-stop-app',
    'restart-app',
    'reset-app-permissions',
    'disable-app',
    'enable-app',
    'clear-app-data',
    'uninstall-app',
  ];

  final DeviceBridge _bridge = DeviceBridge.instance;

  Map<String, dynamic> _details = <String, dynamic>{};
  List<RepairServiceInfo> _services = const <RepairServiceInfo>[];
  bool _loading = true;
  bool _backingUp = false;
  bool _removed = false;
  String _error = '';

  String get _packageName => widget.app.packageName;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = '';
    });

    try {
      final catalog = await _bridge.getRepairCatalog();
      final details = await _bridge.getTargetApplicationDetails(_packageName);

      final services = <RepairServiceInfo>[];
      for (final id in _actionIds) {
        for (final service in catalog) {
          if (service.id == id) services.add(service);
        }
      }

      if (!mounted) return;
      setState(() {
        _details = details;
        _services = services;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = describeError(error);
        _loading = false;
      });
    }
  }

  bool get _isSystem =>
      _details.containsKey('system') ? asBool(_details['system']) : widget.app.isSystemApp;

  bool get _isEnabled =>
      _details.containsKey('enabled') ? _details['enabled'] != false : widget.app.enabled;

  /// Hides the actions that make no sense for this app, and the ones the
  /// native side refuses for system apps.
  bool _applies(RepairServiceInfo service) {
    switch (service.id) {
      case 'disable-app':
        return _isEnabled;
      case 'enable-app':
        return !_isEnabled;
      case 'force-stop-app':
      case 'restart-app':
      case 'clear-app-data':
      case 'uninstall-app':
        return !_isSystem;
      default:
        return true;
    }
  }

  Future<void> _runService(RepairServiceInfo service) async {
    final outcome = await runRepairFlow(
      context,
      service,
      packageName: _packageName,
    );

    if (outcome == null || !mounted) return;

    if (service.id == 'uninstall-app') {
      setState(() => _removed = true);
      return;
    }

    await _load();
  }

  Future<void> _backup() async {
    if (_backingUp) return;
    setState(() => _backingUp = true);

    try {
      final backup = await _bridge.backupTargetApplicationApk(_packageName);
      final files = asMapList(backup['files']);
      final directory = asText(backup['backupDirectory']);
      final totalBytes = asInt(backup['totalSizeBytes']);

      final summary = StringBuffer()
        ..writeln('${files.length} file(s), ${formatBytes(totalBytes)}');
      for (final file in files) {
        summary.writeln(
          '${asText(file['fileName'])}  ${formatBytes(asInt(file['sizeBytes']))}  '
          '${asText(file['verification'])}',
        );
        summary.writeln('  SHA-256 ${asText(file['sha256'])}');
      }

      await _bridge.saveHistory(
        operation: 'APK backup: $_packageName',
        category: 'Applications',
        result: 'Completed',
        details: summary.toString(),
        onTarget: true,
      );

      if (!mounted) return;
      setState(() => _backingUp = false);

      final share = await showDialog<bool>(
        context: context,
        builder: (dialogContext) {
          return AlertDialog(
            backgroundColor: AppColors.surface,
            title: const Row(
              children: [
                Icon(Icons.verified, color: AppColors.ok),
                SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Backup verified',
                    style: TextStyle(color: Colors.white),
                  ),
                ),
              ],
            ),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _packageName,
                    style: const TextStyle(
                      color: Colors.white,
                      fontFamily: 'monospace',
                    ),
                  ),
                  const SizedBox(height: 10),
                  ReportView(summary.toString().trimRight()),
                  const SizedBox(height: 10),
                  const Text(
                    'The copy is kept privately inside this app for 24 '
                    'hours. Share it now to save it somewhere permanent.',
                    style: TextStyle(color: Colors.white70, height: 1.4),
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: const Text('Close'),
              ),
              FilledButton.icon(
                onPressed: () => Navigator.pop(dialogContext, true),
                icon: const Icon(Icons.share),
                label: const Text('Share'),
              ),
            ],
          );
        },
      );

      if (share == true) {
        await _bridge.shareApkBackup(directory, files);
      }
    } catch (error) {
      if (!mounted) return;
      setState(() => _backingUp = false);
      showMessage(context, describeError(error), error: true);
    }
  }

  Widget _row(String label, String value) {
    if (value.isEmpty) return const SizedBox.shrink();

    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 120,
            child: Text(
              label,
              style: const TextStyle(color: AppColors.textMuted),
            ),
          ),
          Expanded(
            child: SelectableText(
              value,
              style: const TextStyle(color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }

  String _shortPermission(String permission) {
    const prefix = 'android.permission.';
    return permission.startsWith(prefix)
        ? permission.substring(prefix.length)
        : permission;
  }

  Widget _buildPermissions() {
    final requested = asStringList(_details['requestedPermissions']);
    final granted = asStringList(_details['grantedPermissions']).toSet();

    if (requested.isEmpty && granted.isEmpty) {
      return const Panel(
        child: Text(
          'The phone reported no permissions for this app.',
          style: TextStyle(color: Colors.white60),
        ),
      );
    }

    final all = <String>{...requested, ...granted}.toList()..sort();

    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${granted.length} granted of ${all.length} requested',
            style: const TextStyle(color: Colors.white70),
          ),
          const SizedBox(height: 8),
          for (final permission in all)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Row(
                children: [
                  Icon(
                    granted.contains(permission)
                        ? Icons.check_circle
                        : Icons.remove_circle_outline,
                    size: 16,
                    color: granted.contains(permission)
                        ? AppColors.ok
                        : Colors.white38,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _shortPermission(permission),
                      style: const TextStyle(
                        color: Colors.white70,
                        fontSize: 12.5,
                      ),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('App Details')),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          if (_removed) {
            return Padding(
              padding: const EdgeInsets.all(16),
              child: Notice(
                icon: Icons.check_circle,
                color: AppColors.ok,
                title: 'App removed',
                message: '$_packageName is no longer on the phone.',
                actionLabel: 'Back to the list',
                onAction: () => Navigator.pop(context),
              ),
            );
          }

          if (_loading) {
            return const Center(child: CircularProgressIndicator());
          }

          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              TargetBanner(onConnect: () => openConnectionScreen(context)),
              const SizedBox(height: 12),
              if (_error.isNotEmpty)
                Notice(
                  icon: Icons.error_outline,
                  color: AppColors.danger,
                  title: 'The details could not be read',
                  message: _error,
                  actionLabel: 'Try again',
                  onAction: _load,
                )
              else ...[
                Panel(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SelectableText(
                        _packageName,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 6,
                        runSpacing: 6,
                        children: [
                          Tag(
                            _isSystem ? 'SYSTEM APP' : 'USER APP',
                            color:
                                _isSystem ? AppColors.warning : AppColors.ok,
                          ),
                          if (!_isEnabled)
                            const Tag('DISABLED', color: AppColors.danger),
                          if (asBool(_details['debuggable']))
                            const Tag('DEBUGGABLE', color: AppColors.warning),
                        ],
                      ),
                      const SizedBox(height: 12),
                      _row('Version', asText(_details['versionName'])),
                      _row('Version code', asText(_details['versionCode'])),
                      _row('Target SDK', asText(_details['targetSdk'])),
                      _row('Minimum SDK', asText(_details['minSdk'])),
                      _row('Installed by', asText(_details['installer'])),
                      _row('First installed', asText(_details['firstInstalled'])),
                      _row('Last updated', asText(_details['lastUpdated'])),
                      _row('Data folder', asText(_details['dataDir'])),
                    ],
                  ),
                ),
                const SectionTitle('Backup'),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: status.connected && !_backingUp ? _backup : null,
                    icon: _backingUp
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2.5),
                          )
                        : const Icon(Icons.download),
                    label: Text(
                      _backingUp
                          ? 'Copying from the phone...'
                          : 'Back up the APK to this phone',
                    ),
                  ),
                ),
                const SizedBox(height: 6),
                const Text(
                  'Copies the installer file only, not the app\'s data or '
                  'account.',
                  style: TextStyle(color: AppColors.textMuted, fontSize: 12.5),
                ),
                const SectionTitle('Actions'),
                for (final service in _services)
                  if (_applies(service))
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Material(
                        color: AppColors.surface,
                        borderRadius: BorderRadius.circular(12),
                        child: ListTile(
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                          title: Text(
                            service.title,
                            style: const TextStyle(color: Colors.white),
                          ),
                          subtitle: Text(
                            service.description,
                            style: const TextStyle(color: Colors.white60),
                          ),
                          trailing: Tag(
                            service.risk,
                            color: AppColors.forRisk(service.risk),
                          ),
                          enabled: status.connected,
                          onTap: () => _runService(service),
                        ),
                      ),
                    ),
                const SectionTitle('Permissions'),
                _buildPermissions(),
              ],
            ],
          );
        },
      ),
    );
  }
}
