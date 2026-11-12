import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';

/// Everything the phone can report about its own hardware, plus the
/// checks only a person can make (screen, speaker, buttons, charging).
class HardwareCheckScreen extends StatefulWidget {
  const HardwareCheckScreen({super.key});

  @override
  State<HardwareCheckScreen> createState() => _HardwareCheckScreenState();
}

class _HardwareCheckScreenState extends State<HardwareCheckScreen> {
  final DeviceBridge _bridge = DeviceBridge.instance;

  HardwareCheckup? _checkup;
  String? _error;
  bool _running = false;
  bool _started = false;

  /// What the technician decided for each physical check.
  final Map<String, String> _verdicts = <String, String>{};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (_bridge.status.value.connected) {
        _run();
      }
    });
  }

  Future<void> _run() async {
    if (_running) return;
    _started = true;

    setState(() {
      _running = true;
      _error = null;
    });

    try {
      final checkup = await _bridge.runHardwareCheckup();
      if (!mounted) return;
      setState(() {
        _checkup = checkup;
        _running = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = describeError(error);
        _running = false;
      });
    }
  }

  Future<void> _record(ManualCheck check, String verdict) async {
    setState(() => _verdicts[check.id] = verdict);

    await _bridge.saveHistory(
      operation: 'Hardware check: ${check.title}',
      category: 'Hardware check',
      result: verdict,
      details: verdict == 'Pass'
          ? 'Checked by hand on the phone.'
          : 'The check failed on the phone.',
      onTarget: true,
    );

    if (!mounted) return;
    showMessage(context, '${check.title}: ${verdict.toLowerCase()} recorded.');
  }

  Future<void> _copyReport() async {
    final checkup = _checkup;
    if (checkup == null) return;

    final buffer = StringBuffer(checkup.report);

    if (_verdicts.isNotEmpty) {
      buffer.writeln();
      buffer.writeln('PHYSICAL CHECKS RECORDED');
      for (final check in checkup.manualChecks) {
        final verdict = _verdicts[check.id];
        if (verdict != null) {
          buffer.writeln('${check.title}: $verdict');
        }
      }
    }

    await copyText(context, buffer.toString());
  }

  Color _statusColor(String status) {
    switch (status) {
      case 'FAIL':
        return AppColors.danger;
      case 'ATTENTION':
        return AppColors.warning;
      case 'OK':
        return AppColors.ok;
      default:
        return AppColors.info;
    }
  }

  Widget _buildHeader(HardwareCheckup? checkup) {
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                checkup == null
                    ? Icons.build
                    : (checkup.attentionCount == 0
                        ? Icons.verified
                        : Icons.report_problem),
                color: checkup == null
                    ? AppColors.info
                    : (checkup.attentionCount == 0
                        ? AppColors.ok
                        : AppColors.warning),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  checkup?.summary ?? 'Reading the phone…',
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    height: 1.3,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          // A Wrap, not a Row: on a narrow phone the two buttons do not
          // fit side by side and must fall onto a second line.
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton.tonalIcon(
                key: const Key('hardware-read'),
                onPressed: _running ? null : _run,
                icon: const Icon(Icons.refresh, size: 18),
                label: Text(checkup == null ? 'Read the phone' : 'Read again'),
              ),
              TextButton.icon(
                key: const Key('hardware-copy'),
                onPressed: checkup == null ? null : _copyReport,
                icon: const Icon(Icons.copy, size: 18),
                label: const Text('Copy report'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildSection(HardwareSection section) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    section.title,
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 15,
                    ),
                  ),
                ),
                if (section.attentionCount > 0)
                  Tag(
                    '${section.attentionCount} to check',
                    color: AppColors.warning,
                  ),
              ],
            ),
            const SizedBox(height: 8),
            for (final item in section.items) _buildItem(item),
          ],
        ),
      ),
    );
  }

  Widget _buildItem(HardwareItem item) {
    final color = _statusColor(item.status);

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  item.name,
                  style: const TextStyle(color: Colors.white70, fontSize: 13),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  item.value,
                  textAlign: TextAlign.right,
                  style: TextStyle(
                    color: color,
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
          if (item.advice.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text(
                item.advice,
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 12,
                  height: 1.3,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildCheck(ManualCheck check) {
    final verdict = _verdicts[check.id];

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    check.title,
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 15,
                    ),
                  ),
                ),
                if (verdict != null)
                  Tag(
                    verdict == 'Pass' ? 'PASSED' : 'FAILED',
                    color: verdict == 'Pass'
                        ? AppColors.ok
                        : AppColors.danger,
                  ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              check.instruction,
              style: const TextStyle(
                color: Colors.white70,
                fontSize: 12.5,
                height: 1.35,
              ),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton(
                  key: Key('hardware-pass-${check.id}'),
                  onPressed: () => _record(check, 'Pass'),
                  child: const Text('Pass'),
                ),
                OutlinedButton(
                  key: Key('hardware-fail-${check.id}'),
                  onPressed: () => _record(check, 'Fail'),
                  child: const Text('Fail'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    final checkup = _checkup;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        const TargetBanner(),
        const SizedBox(height: 12),
        if (_running) ...[
          const LinearProgressIndicator(),
          const SizedBox(height: 12),
        ],
        if (_error != null) ...[
          Notice(
            icon: Icons.error_outline,
            color: AppColors.danger,
            title: 'The check did not finish',
            message: _error!,
            actionLabel: 'Try again',
            onAction: _run,
          ),
          const SizedBox(height: 12),
        ],
        _buildHeader(checkup),
        if (checkup != null) ...[
          for (final section in checkup.sections) ...[
            SectionTitle(section.title),
            _buildSection(section),
          ],
          const SectionTitle('Physical checks'),
          const Padding(
            padding: EdgeInsets.only(bottom: 10),
            child: Text(
              'A phone cannot see or hear another phone. Do these by hand, '
              'then record the result. Each result is written into the '
              'service history with the phone it was made on.',
              style: TextStyle(
                color: AppColors.textMuted,
                fontSize: 12.5,
                height: 1.35,
              ),
            ),
          ),
          for (final check in checkup.manualChecks) _buildCheck(check),
        ] else if (!_running && !_started) ...[
          const SectionTitle('What this checks'),
          const Notice(
            icon: Icons.info_outline,
            title: 'Ready when the phone is',
            message: 'Battery, screen, touch, sensors, cameras, radios, '
                'storage and boot state, plus eight checks done by hand.',
          ),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Hardware Check')),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          if (!status.connected) {
            return ListView(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
              children: const [
                TargetBanner(),
                SizedBox(height: 12),
                NeedsPhone(what: 'The hardware check'),
              ],
            );
          }

          return _buildBody();
        },
      ),
    );
  }
}
