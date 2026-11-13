import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';
import 'connection_screen.dart';

/// The technician's full service bench: diagnose, fix, verify.
///
/// It runs [ServiceDiagnosis] on the connected phone, shows every fault
/// with a one-tap fix, and checks the phone again after each fix so the
/// technician can prove the job is done. Every fix is confirmed first
/// and written to the service history, exactly like Repair Tools.
class DebuggingServiceScreen extends StatefulWidget {
  const DebuggingServiceScreen({super.key});

  @override
  State<DebuggingServiceScreen> createState() => _DebuggingServiceScreenState();
}

class _DebuggingServiceScreenState extends State<DebuggingServiceScreen> {
  final DeviceBridge _bridge = DeviceBridge.instance;

  ServiceDiagnosisResult? _result;
  String? _error;
  bool _running = false;
  bool _started = false;

  /// Which fixes are currently running, by check id.
  final Set<String> _fixing = <String>{};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (_bridge.status.value.connected) _run();
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
      final result = await _bridge.runServiceDiagnosis();
      if (!mounted) return;
      setState(() {
        _result = result;
        _running = false;
      });
      // Save a history entry for the diagnosis itself.
      await _bridge.saveHistory(
        operation: 'Service diagnosis: ${result.verdict}',
        category: 'Service diagnosis',
        result: result.attentionCount == 0 ? 'Healthy' : 'Needs attention',
        details: result.report,
        onTarget: true,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = describeError(e);
        _running = false;
      });
    }
  }

  Future<void> _fix(ServiceCheck check) async {
    final fixId = check.fixId;
    if (fixId == null || _fixing.contains(check.id)) return;

    // Find the repair service to show its real title/warning.
    List<RepairServiceInfo> catalog = const <RepairServiceInfo>[];
    try {
      catalog = await _bridge.getRepairCatalog();
    } catch (_) {}
    final service = catalog.where((s) => s.id == fixId).firstOrNull;

    final title = service?.title ?? check.fixTitle;
    final description = service?.description ?? check.detail;
    final warning = service?.warning ?? '';

    if (!mounted) return;
    final confirmed = await confirmAction(
      context,
      title: 'Run fix: $title?',
      message: [
        description,
        if (warning.isNotEmpty) '\n$warning',
        '\nThis runs on the CONNECTED phone, not this one.',
      ].join('\n'),
      confirmLabel: 'Fix now',
      dangerous: (service?.risk ?? 'LOW') == 'HIGH',
    );
    if (!confirmed) return;

    setState(() => _fixing.add(check.id));

    try {
      final outcome = await _bridge.runRepair(fixId, packageName: null);
      if (!mounted) return;
      showMessage(context, outcome.message);
      // Re-read the phone to prove the fix worked.
      await _run();
    } catch (e) {
      if (!mounted) return;
      showMessage(context, describeError(e), error: true);
    } finally {
      if (mounted) setState(() => _fixing.remove(check.id));
    }
  }

  Future<void> _fixAll() async {
    final result = _result;
    if (result == null) return;
    final fixable = result.problems.where((c) => c.autoFixable).toList();
    if (fixable.isEmpty) return;

    final confirmed = await confirmAction(
      context,
      title: 'Fix all ${fixable.length} issues?',
      message:
          'Each fix will run one after the other. You will be asked to confirm only once here — '
          'every fix is still written to the service history.\n\n'
          'The phone will be checked again when they are done.',
      confirmLabel: 'Fix all',
    );
    if (!confirmed) return;

    for (final check in fixable) {
      if (!mounted) break;
      // Small pause so the phone can settle between radios/storage jobs.
      await _fixWithoutReconfirm(check);
    }
    if (mounted) await _run();
  }

  Future<void> _fixWithoutReconfirm(ServiceCheck check) async {
    final fixId = check.fixId;
    if (fixId == null) return;
    setState(() => _fixing.add(check.id));
    try {
      await _bridge.runRepair(fixId);
    } catch (e) {
      if (mounted) showMessage(context, describeError(e), error: true);
    } finally {
      if (mounted) setState(() => _fixing.remove(check.id));
    }
  }

  Color _statusColor(String status) {
    switch (status) {
      case 'CRITICAL':
      case 'FAULT':
        return AppColors.danger;
      case 'WARNING':
        return AppColors.warning;
      case 'INFO':
        return AppColors.info;
      default:
        return AppColors.ok;
    }
  }

  IconData _statusIcon(String status) {
    switch (status) {
      case 'CRITICAL':
      case 'FAULT':
        return Icons.error;
      case 'WARNING':
        return Icons.warning_amber;
      case 'INFO':
        return Icons.info_outline;
      default:
        return Icons.check_circle;
    }
  }

  Widget _buildScore(ServiceDiagnosisResult result) {
    final color = result.score >= 90
        ? AppColors.ok
        : result.score >= 70
            ? AppColors.warning
            : AppColors.danger;

    return Panel(
      borderColor: color.withAlpha(140),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 64,
                height: 64,
                decoration: BoxDecoration(
                  color: color.withAlpha(30),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: color.withAlpha(120)),
                ),
                child: Center(
                  child: Text(
                    '${result.score}',
                    style: TextStyle(
                      color: color,
                      fontSize: 26,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      result.verdict,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      result.summary,
                      style: const TextStyle(color: Colors.white70, height: 1.3),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton.tonalIcon(
                key: const Key('service-rerun'),
                onPressed: _running ? null : _run,
                icon: const Icon(Icons.refresh, size: 18),
                label: Text(_result == null ? 'Diagnose' : 'Diagnose again'),
              ),
              if (result.fixableCount > 0)
                FilledButton.icon(
                  key: const Key('service-fix-all'),
                  onPressed: _running || _fixing.isNotEmpty ? null : _fixAll,
                  icon: const Icon(Icons.auto_fix_high, size: 18),
                  label: Text('Fix all (${result.fixableCount})'),
                ),
              OutlinedButton.icon(
                key: const Key('service-copy'),
                onPressed: () => copyText(context, result.report),
                icon: const Icon(Icons.copy, size: 18),
                label: const Text('Copy report'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildCheck(ServiceCheck check) {
    final color = _statusColor(check.status);
    final busy = _fixing.contains(check.id);

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Panel(
        borderColor: check.isProblem ? color.withAlpha(100) : AppColors.border,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(_statusIcon(check.status), color: color, size: 20),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    check.title,
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 14,
                    ),
                  ),
                ),
                Tag(check.status, color: color),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              check.detail,
              style: const TextStyle(color: Colors.white70, height: 1.35),
            ),
            if (check.evidence.isNotEmpty) ...[
              const SizedBox(height: 6),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                decoration: BoxDecoration(
                  color: AppColors.console,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  check.evidence,
                  style: const TextStyle(
                    color: AppColors.consoleText,
                    fontFamily: 'monospace',
                    fontSize: 11.5,
                  ),
                ),
              ),
            ],
            if (check.autoFixable) ...[
              const SizedBox(height: 10),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton.tonalIcon(
                  key: Key('service-fix-${check.id}'),
                  onPressed: busy ? null : () => _fix(check),
                  icon: busy
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.build, size: 16),
                  label: Text(busy ? 'Fixing…' : 'Fix: ${check.fixTitle}'),
                ),
              ),
            ] else if (check.isProblem) ...[
              const SizedBox(height: 8),
              const Text(
                'This cannot be fixed from here. Explain it to the owner.',
                style: TextStyle(color: AppColors.textMuted, fontSize: 12),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    final result = _result;

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
            title: 'Diagnosis did not finish',
            message: _error!,
            actionLabel: 'Try again',
            onAction: _run,
          ),
          const SizedBox(height: 12),
        ],
        if (result != null) ...[
          _buildScore(result),
          if (result.problems.isNotEmpty) ...[
            SectionTitle('Needs attention (${result.problems.length})'),
            for (final check in result.problems) _buildCheck(check),
          ] else ...[
            const SectionTitle('Result'),
            const Notice(
              icon: Icons.verified,
              color: AppColors.ok,
              title: 'No faults found',
              message:
                  'Every check passed. If the owner still reports a problem, '
                  'check the specific app in App Workbench or run Hardware Check.',
            ),
          ],
          if (result.passed.isNotEmpty) ...[
            SectionTitle('Passed (${result.passed.length})'),
            Panel(
              child: Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final c in result.passed)
                    Tag(c.title, color: AppColors.ok),
                ],
              ),
            ),
            const SizedBox(height: 10),
            ExpansionTile(
              title: const Text('Full report',
                  style: TextStyle(color: Colors.white70)),
              children: [ReportView(result.report)],
            ),
          ],
        ] else if (!_running && !_started) ...[
          const SectionTitle('What this does'),
          const Notice(
            icon: Icons.health_and_safety,
            title: 'Diagnose, fix, verify',
            message:
                'Reads storage, memory, network, display and security from the '
                'connected phone, grades every check, and offers a one-tap fix '
                'where a repair is safe. Run Diagnose, fix what you can, then '
                'Diagnose again to prove it.',
          ),
          const SizedBox(height: 12),
          Panel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Fixes include: private DNS, proxy, date/time, Wi-Fi/data/Bluetooth, '
                  'storage and cache clean-up, log clear, background apps, display and '
                  'animation resets, screen timeout, brightness, font size, Battery Saver, '
                  'and more. Every fix is confirmed and logged.',
                  style: TextStyle(color: Colors.white70, height: 1.4),
                ),
                SizedBox(height: 12),
                FilledButton.icon(
                  key: Key('service-start'),
                  onPressed: _run,
                  icon: Icon(Icons.play_arrow),
                  label: Text('Diagnose now'),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Debugging Service')),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          if (!status.connected) {
            return ListView(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
              children: [
                const TargetBanner(),
                const SizedBox(height: 12),
                NeedsPhone(
                  what: 'Debugging Service',
                  onConnect: () => openConnectionScreen(context),
                ),
              ],
            );
          }
          return _buildBody();
        },
      ),
    );
  }
}
