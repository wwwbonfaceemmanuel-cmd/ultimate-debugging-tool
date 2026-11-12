import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';
import 'connection_screen.dart';
import 'repair_flow.dart';

/// A symptom, what to check, and which reports and services help.
class RepairGuide {
  final String title;
  final String description;
  final IconData icon;
  final List<String> symptoms;
  final List<String> checks;
  final List<String> causes;
  final List<String> actions;

  /// Report names understood by the Diagnostics screen.
  final List<String> reports;

  /// Service ids from the native repair catalog.
  final List<String> services;

  const RepairGuide({
    required this.title,
    required this.description,
    required this.icon,
    required this.symptoms,
    required this.checks,
    required this.causes,
    required this.actions,
    required this.reports,
    required this.services,
  });
}

const List<RepairGuide> kRepairGuides = [
  RepairGuide(
    title: 'Phone running slowly',
    description: 'Slow response, freezing or lagging.',
    icon: Icons.speed,
    symptoms: [
      'Applications take too long to open.',
      'The screen freezes or responds slowly.',
      'The phone restarts unexpectedly.',
    ],
    checks: [
      'Check available internal storage.',
      'Check RAM and memory usage.',
      'Check whether the problem started after installing an app.',
      'Check for pending Android or application updates.',
    ],
    causes: [
      'Insufficient available storage.',
      'Heavy background applications.',
      'An application causing excessive resource usage.',
      'An outdated operating system or application.',
    ],
    actions: [
      'Back up important files.',
      'Close unnecessary running applications.',
      'Update applications that are causing problems.',
      'Review recently installed applications.',
    ],
    reports: ['memory', 'storage', 'uptime'],
    services: ['force-stop-app', 'trim-caches', 'optimize-apps', 'reboot'],
  ),
  RepairGuide(
    title: 'Battery draining quickly',
    description: 'Unusually fast battery consumption.',
    icon: Icons.battery_alert,
    symptoms: [
      'Battery percentage drops rapidly.',
      'Phone loses charge while idle.',
      'Battery drains faster after installing an app.',
    ],
    checks: [
      'Inspect battery usage by application.',
      'Check whether the phone is overheating.',
      'Check screen brightness and display timeout.',
      'Compare battery drain during idle periods.',
    ],
    causes: [
      'Applications running continuously in the background.',
      'High screen brightness.',
      'Weak network signal.',
      'Battery ageing or hardware problems.',
    ],
    actions: [
      'Back up important data.',
      'Update applications with unusually high battery usage.',
      'Restrict unnecessary background activity.',
      'Seek battery inspection if physical damage or swelling is suspected.',
    ],
    reports: ['battery', 'thermal'],
    services: ['force-stop-app', 'stay-awake-off', 'reset-battery-stats'],
  ),
  RepairGuide(
    title: 'Storage problems',
    description: 'Low storage or insufficient space warnings.',
    icon: Icons.storage,
    symptoms: [
      'Phone reports insufficient storage.',
      'Applications cannot update.',
      'Files cannot be downloaded.',
    ],
    checks: [
      'Inspect total and available storage.',
      'Identify unusually large applications.',
      'Review downloads and large media files.',
      'Check whether application data is consuming excessive space.',
    ],
    causes: [
      'Large videos, photos and downloads.',
      'Application data accumulation.',
      'Duplicate or unnecessary files.',
    ],
    actions: [
      'Back up important files.',
      'Transfer personal media to secure storage.',
      'Remove only files confirmed to be unnecessary.',
      'Review application storage before clearing anything.',
    ],
    reports: ['storage', 'apps'],
    services: ['trim-caches', 'storage-maintenance', 'clear-logs'],
  ),
  RepairGuide(
    title: 'Applications crashing',
    description: 'Apps closing unexpectedly or refusing to open.',
    icon: Icons.error_outline,
    symptoms: [
      'An application closes immediately.',
      'An application repeatedly displays error messages.',
      'The problem affects only one application.',
    ],
    checks: [
      'Identify the affected application.',
      'Check its installed version.',
      'Check available device storage.',
      'Determine whether other applications have the same problem.',
    ],
    causes: [
      'Application bugs or outdated versions.',
      'Corrupted application cache.',
      'Insufficient storage or memory.',
      'Compatibility problems.',
    ],
    actions: [
      'Back up important application data where possible.',
      'Update the affected application.',
      'Restart the application.',
      'Clear its data only after explaining that it signs the user out.',
    ],
    reports: ['memory', 'storage'],
    services: [
      'restart-app',
      'force-stop-app',
      'reset-app-permissions',
      'clear-app-data',
    ],
  ),
  RepairGuide(
    title: 'Network connectivity',
    description: 'Wi-Fi or mobile network connection problems.',
    icon: Icons.wifi_off,
    symptoms: [
      'Wi-Fi refuses to connect.',
      'Mobile data is unavailable.',
      'Internet connection frequently disconnects.',
    ],
    checks: [
      'Check whether airplane mode is enabled.',
      'Inspect Wi-Fi or mobile network status.',
      'Check whether other devices can access the network.',
      'Check signal strength and network availability.',
    ],
    causes: [
      'Weak signal or network outage.',
      'Incorrect network settings.',
      'Router or SIM-related problems.',
      'A private DNS or proxy setting that no longer works.',
    ],
    actions: [
      'Confirm that the network is available.',
      'Restart the affected connection.',
      'Reset private DNS and proxy settings.',
      'Restart the phone if necessary.',
    ],
    reports: ['network'],
    services: [
      'restart-wifi',
      'restart-mobile-data',
      'airplane-mode-cycle',
      'reset-private-dns',
      'clear-proxy',
    ],
  ),
  RepairGuide(
    title: 'Boot and startup problems',
    description: 'Phone stuck during startup or repeatedly restarting.',
    icon: Icons.power_settings_new,
    symptoms: [
      'Phone remains stuck on the startup logo.',
      'Phone repeatedly restarts.',
      'Phone takes unusually long to start.',
    ],
    checks: [
      'Check battery level and charging behaviour.',
      'Inspect for signs of physical damage.',
      'Determine whether the problem started after an update.',
      'Check whether recovery mode is accessible.',
    ],
    causes: [
      'Failed system update.',
      'Damaged system files.',
      'Faulty applications or firmware.',
      'Battery or hardware failure.',
    ],
    actions: [
      'Do not attempt a factory reset before discussing data loss.',
      'Preserve important data whenever recovery is possible.',
      'Record the exact startup error or behaviour.',
      'Escalate firmware and hardware problems for specialist assessment.',
    ],
    reports: ['device-info', 'uptime'],
    services: ['reboot', 'reboot-recovery'],
  ),
];

class RepairToolsScreen extends StatefulWidget {
  const RepairToolsScreen({super.key});

  @override
  State<RepairToolsScreen> createState() => _RepairToolsScreenState();
}

class _RepairToolsScreenState extends State<RepairToolsScreen> {
  final DeviceBridge _bridge = DeviceBridge.instance;

  List<RepairServiceInfo> _catalog = const <RepairServiceInfo>[];
  bool _loading = true;
  String _error = '';

  @override
  void initState() {
    super.initState();
    _bridge.start();
    _loadCatalog();
  }

  Future<void> _loadCatalog() async {
    try {
      final catalog = await _bridge.getRepairCatalog();
      if (!mounted) return;
      setState(() {
        _catalog = catalog;
        _loading = false;
        _error = '';
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = describeError(error);
        _loading = false;
      });
    }
  }

  RepairServiceInfo? _find(String id) {
    for (final service in _catalog) {
      if (service.id == id) return service;
    }
    return null;
  }

  /// Null when the service can run; otherwise why it cannot.
  String? _unavailableReason(RepairServiceInfo service, AdbStatus status) {
    if (!status.connected) return 'Connect a phone first';

    final sdk = int.tryParse(status.device['sdk'] ?? '') ?? 0;
    if (service.minSdk > 0 && sdk > 0 && sdk < service.minSdk) {
      return 'Needs a newer Android version than this phone has';
    }

    return null;
  }

  Future<void> _runService(RepairServiceInfo service) async {
    await runRepairFlow(context, service);
  }

  Widget _buildServiceTile(RepairServiceInfo service, AdbStatus status) {
    final reason = _unavailableReason(service, status);
    final riskColor = AppColors.forRisk(service.risk);

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: reason == null ? () => _runService(service) : null,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        service.title,
                        style: TextStyle(
                          color: reason == null ? Colors.white : Colors.white54,
                          fontWeight: FontWeight.bold,
                          fontSize: 15,
                        ),
                      ),
                    ),
                    Tag(service.risk, color: riskColor),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  service.description,
                  style: const TextStyle(color: Colors.white60, height: 1.35),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    if (service.needsPackage)
                      const Tag('CHOOSE AN APP', color: AppColors.info),
                    if (!service.dataPreserved)
                      const Tag('ERASES DATA', color: AppColors.danger),
                    if (service.endsSession)
                      const Tag('ENDS CONNECTION', color: AppColors.warning),
                    if (reason != null && status.connected)
                      Tag(reason.toUpperCase(), color: AppColors.textMuted),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildServicesTab(AdbStatus status) {
    if (_loading) return const Center(child: CircularProgressIndicator());

    final groups = <String, List<RepairServiceInfo>>{};
    for (final service in _catalog) {
      groups.putIfAbsent(service.category, () => <RepairServiceInfo>[]).add(service);
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
            title: 'The service list could not be loaded',
            message: _error,
            actionLabel: 'Try again',
            onAction: _loadCatalog,
          ),
        const Panel(
          child: Text(
            'Every service explains what it does and asks before running. '
            'Each one is checked afterwards and saved to Repair History.\n\n'
            'Nothing here removes a screen lock, bypasses an account lock '
            'or flashes firmware.',
            style: TextStyle(color: Colors.white70, height: 1.4),
          ),
        ),
        for (final group in groups.entries) ...[
          SectionTitle(group.key),
          for (final service in group.value) _buildServiceTile(service, status),
        ],
      ],
    );
  }

  Widget _buildGuidesTab() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
      children: [
        const Panel(
          child: Text(
            'Start from what the owner reports. Each guide lists what to '
            'check, the likely causes, and the reports and services that '
            'help.',
            style: TextStyle(color: Colors.white70, height: 1.4),
          ),
        ),
        const SizedBox(height: 12),
        for (final guide in kRepairGuides)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Material(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(12),
              child: ListTile(
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                leading: Icon(guide.icon, color: AppColors.info),
                title: Text(
                  guide.title,
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                subtitle: Text(
                  guide.description,
                  style: const TextStyle(color: Colors.white60),
                ),
                trailing: const Icon(Icons.chevron_right, color: Colors.white54),
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute<void>(
                      builder: (context) => RepairGuideScreen(
                        guide: guide,
                        services: [
                          for (final id in guide.services)
                            if (_find(id) != null) _find(id)!,
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Repair Tools'),
          bottom: const TabBar(
            tabs: [
              Tab(text: 'Services'),
              Tab(text: 'Symptom guides'),
            ],
          ),
        ),
        body: ValueListenableBuilder<AdbStatus>(
          valueListenable: _bridge.status,
          builder: (context, status, _) {
            return TabBarView(
              children: [
                _buildServicesTab(status),
                _buildGuidesTab(),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// One guide: symptoms, checks, causes, then the reports and services
/// that can be run on the connected phone.
class RepairGuideScreen extends StatefulWidget {
  final RepairGuide guide;
  final List<RepairServiceInfo> services;

  const RepairGuideScreen({
    super.key,
    required this.guide,
    required this.services,
  });

  @override
  State<RepairGuideScreen> createState() => _RepairGuideScreenState();
}

class _RepairGuideScreenState extends State<RepairGuideScreen> {
  final DeviceBridge _bridge = DeviceBridge.instance;

  bool _running = false;
  String _report = '';
  bool _reportFailed = false;

  Future<void> _runReports() async {
    if (_running) return;

    setState(() {
      _running = true;
      _report = '';
      _reportFailed = false;
    });

    final buffer = StringBuffer();
    bool failed = false;

    for (final name in widget.guide.reports) {
      try {
        final text = await _bridge.runDiagnostic(name, onTarget: true);
        buffer.writeln(text.trimRight());
        buffer.writeln();
      } catch (error) {
        failed = true;
        buffer.writeln('$name: ${describeError(error)}');
        buffer.writeln();
        // A lost connection fails every remaining report too.
        if (!_bridge.status.value.connected) break;
      }
    }

    final text = buffer.toString().trimRight();

    await _bridge.saveHistory(
      operation: 'Guided checks: ${widget.guide.title}',
      category: 'Diagnostics',
      result: failed ? 'Failed' : 'Completed',
      details: text,
      onTarget: true,
    );

    if (!mounted) return;
    setState(() {
      _running = false;
      _report = text;
      _reportFailed = failed;
    });
  }

  Widget _buildList(String title, List<String> items, IconData icon) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: AppColors.info, size: 20),
                const SizedBox(width: 8),
                Text(
                  title,
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            for (final item in items)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('•  ', style: TextStyle(color: Colors.white54)),
                    Expanded(
                      child: Text(
                        item,
                        style: const TextStyle(
                          color: Colors.white70,
                          height: 1.35,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final guide = widget.guide;

    return Scaffold(
      appBar: AppBar(title: Text(guide.title)),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              TargetBanner(onConnect: () => openConnectionScreen(context)),
              const SizedBox(height: 12),
              _buildList('Reported symptoms', guide.symptoms, Icons.report_problem),
              _buildList('What to check', guide.checks, Icons.checklist),
              _buildList('Likely causes', guide.causes, Icons.psychology),
              _buildList('Recommended actions', guide.actions, Icons.build),
              const SectionTitle('Checks on the connected phone'),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: status.connected && !_running ? _runReports : null,
                  icon: _running
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2.5),
                        )
                      : const Icon(Icons.play_arrow),
                  label: Text(
                    _running
                        ? 'Running...'
                        : 'Run reports: ${guide.reports.join(', ')}',
                  ),
                ),
              ),
              if (_report.isNotEmpty) ...[
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _reportFailed
                            ? 'Some reports could not be completed'
                            : 'Reports',
                        style: TextStyle(
                          color:
                              _reportFailed ? AppColors.danger : Colors.white,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: 'Copy',
                      onPressed: () => copyText(context, _report),
                      icon: const Icon(Icons.copy, size: 20),
                    ),
                  ],
                ),
                ReportView(_report),
              ],
              if (widget.services.isNotEmpty) ...[
                const SectionTitle('Services that may help'),
                for (final service in widget.services)
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
                        onTap: () => runRepairFlow(context, service),
                      ),
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
