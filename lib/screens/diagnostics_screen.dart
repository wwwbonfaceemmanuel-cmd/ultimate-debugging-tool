import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';
import 'connection_screen.dart';

/// Read-only reports, either about the connected phone (over ADB) or
/// about this phone itself.
class DiagnosticsScreen extends StatefulWidget {
  const DiagnosticsScreen({super.key});

  @override
  State<DiagnosticsScreen> createState() => _DiagnosticsScreenState();
}

class _DiagnosticsScreenState extends State<DiagnosticsScreen> {
  static const List<NamedItem> _localReports = [
    NamedItem('device-info', 'Device, build and hardware identity'),
    NamedItem('battery', 'Battery condition and charging'),
    NamedItem('storage', 'Storage usage'),
    NamedItem('memory', 'RAM usage'),
    NamedItem('network', 'Network status'),
    NamedItem('security', 'Security settings'),
    NamedItem('uptime', 'Time since the phone was started'),
    NamedItem('technician-suite', 'All reports in one'),
  ];

  static const Map<String, IconData> _icons = {
    'device-info': Icons.phone_android,
    'battery': Icons.battery_charging_full,
    'storage': Icons.storage,
    'memory': Icons.memory,
    'network': Icons.network_check,
    'thermal': Icons.thermostat,
    'uptime': Icons.timer,
    'apps': Icons.apps,
    'security': Icons.shield,
    'technician-suite': Icons.fact_check,
  };

  static const Map<String, String> _titles = {
    'device-info': 'Device information',
    'battery': 'Battery',
    'storage': 'Storage',
    'memory': 'Memory',
    'network': 'Network',
    'thermal': 'Temperature',
    'uptime': 'Uptime',
    'apps': 'Apps summary',
    'security': 'Security',
    'technician-suite': 'Full technician report',
  };

  final DeviceBridge _bridge = DeviceBridge.instance;

  bool _onTarget = true;
  List<NamedItem> _targetReports = const <NamedItem>[];
  String _running = '';
  String _resultTitle = '';
  String _resultText = '';
  bool _resultFailed = false;

  @override
  void initState() {
    super.initState();
    _bridge.start();
    _onTarget = _bridge.status.value.connected;
    _loadReports();
  }

  Future<void> _loadReports() async {
    try {
      final reports = await _bridge.getTargetReports();
      if (!mounted) return;
      setState(() => _targetReports = reports);
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    }
  }

  String _titleOf(String name) => _titles[name] ?? name;

  Future<void> _run(NamedItem report) async {
    if (_running.isNotEmpty) return;

    final onTarget = _onTarget;
    final title = _titleOf(report.name);

    setState(() {
      _running = report.name;
      _resultTitle = title;
      _resultText = '';
      _resultFailed = false;
    });

    String text;
    bool failed = false;

    try {
      text = await _bridge.runDiagnostic(report.name, onTarget: onTarget);
    } catch (error) {
      text = describeError(error);
      failed = true;
    }

    await _bridge.saveHistory(
      operation: 'Report: $title',
      category: 'Diagnostics',
      result: failed ? 'Failed' : 'Completed',
      details: text,
      onTarget: onTarget,
    );

    if (!mounted) return;
    setState(() {
      _running = '';
      _resultText = text;
      _resultFailed = failed;
    });
  }

  Widget _buildReportTile(NamedItem report) {
    final running = _running == report.name;

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        child: ListTile(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          leading: Icon(
            _icons[report.name] ?? Icons.description,
            color: AppColors.info,
          ),
          title: Text(
            _titleOf(report.name),
            style: const TextStyle(color: Colors.white),
          ),
          subtitle: Text(
            report.description,
            style: const TextStyle(color: Colors.white60),
          ),
          trailing: running
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2.5),
                )
              : const Icon(Icons.play_arrow, color: Colors.white54),
          enabled: _running.isEmpty,
          onTap: () => _run(report),
        ),
      ),
    );
  }

  Widget _buildResult() {
    if (_running.isNotEmpty) {
      return Notice(
        icon: Icons.hourglass_top,
        color: AppColors.warning,
        title: 'Running: $_resultTitle',
        message: 'The full report can take up to a minute.',
      );
    }

    if (_resultText.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                _resultTitle,
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                  fontSize: 16,
                ),
              ),
            ),
            if (_resultFailed) const Tag('FAILED', color: AppColors.danger),
            IconButton(
              tooltip: 'Copy report',
              onPressed: () => copyText(context, _resultText),
              icon: const Icon(Icons.copy, size: 20),
            ),
          ],
        ),
        const SizedBox(height: 6),
        ReportView(_resultText),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Diagnostics')),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          final reports = _onTarget ? _targetReports : _localReports;
          final blocked = _onTarget && !status.connected;

          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              SizedBox(
                width: double.infinity,
                child: SegmentedButton<bool>(
                  segments: const [
                    ButtonSegment<bool>(
                      value: true,
                      icon: Icon(Icons.phonelink),
                      label: Text('Connected phone'),
                    ),
                    ButtonSegment<bool>(
                      value: false,
                      icon: Icon(Icons.smartphone),
                      label: Text('This phone'),
                    ),
                  ],
                  selected: <bool>{_onTarget},
                  onSelectionChanged: _running.isNotEmpty
                      ? null
                      : (selection) {
                          setState(() {
                            _onTarget = selection.first;
                            _resultText = '';
                          });
                        },
                ),
              ),
              const SizedBox(height: 12),
              if (_onTarget)
                TargetBanner(onConnect: () => openConnectionScreen(context))
              else
                const Notice(
                  icon: Icons.smartphone,
                  title: 'Reports about this phone',
                  message: 'These describe the phone you are holding, not '
                      'the customer\'s phone.',
                ),
              const SizedBox(height: 12),
              _buildResult(),
              if (blocked)
                NeedsPhone(
                  what: 'This report',
                  onConnect: () => openConnectionScreen(context),
                )
              else ...[
                const SectionTitle('Reports'),
                for (final report in reports) _buildReportTile(report),
              ],
            ],
          );
        },
      ),
    );
  }
}
