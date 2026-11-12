import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';

/// Lets the technician choose an app that is installed on the connected
/// phone. Returns its package name, or null when nothing was chosen.
Future<String?> pickTargetPackage(BuildContext context) {
  return showModalBottomSheet<String>(
    context: context,
    backgroundColor: AppColors.surfaceDeep,
    isScrollControlled: true,
    builder: (context) => const _PackagePicker(),
  );
}

class _PackagePicker extends StatefulWidget {
  const _PackagePicker();

  @override
  State<_PackagePicker> createState() => _PackagePickerState();
}

class _PackagePickerState extends State<_PackagePicker> {
  final TextEditingController _search = TextEditingController();

  List<TargetApp> _apps = const <TargetApp>[];
  bool _loading = true;
  bool _showSystem = false;
  String _error = '';

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final apps = await DeviceBridge.instance.getTargetApplications();
      apps.sort((a, b) => a.packageName.compareTo(b.packageName));
      if (!mounted) return;
      setState(() {
        _apps = apps;
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

  /// A removed system app is not in the list, so a typed package name
  /// can be used as it is (the native side validates it again).
  Widget _buildNoMatch(String query) {
    final typed = _search.text.trim();
    final looksLikePackage =
        RegExp(r'^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$').hasMatch(typed);

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'No installed app matches.',
              style: TextStyle(color: Colors.white60),
            ),
            if (looksLikePackage) ...[
              const SizedBox(height: 12),
              FilledButton.tonal(
                onPressed: () => Navigator.pop(context, typed),
                child: Text('Use "$typed"'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final query = _search.text.trim().toLowerCase();

    final visible = _apps.where((app) {
      if (!_showSystem && app.isSystemApp) return false;
      return query.isEmpty || app.packageName.toLowerCase().contains(query);
    }).toList();

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SizedBox(
        height: MediaQuery.of(context).size.height * 0.8,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Choose an app on the connected phone',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 17,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 10),
                  TextField(
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
                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          'Show system apps',
                          style: TextStyle(color: Colors.white70),
                        ),
                      ),
                      Switch(
                        value: _showSystem,
                        onChanged: (value) =>
                            setState(() => _showSystem = value),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _error.isNotEmpty
                      ? Padding(
                          padding: const EdgeInsets.all(16),
                          child: Notice(
                            icon: Icons.error_outline,
                            color: AppColors.danger,
                            title: 'The app list could not be read',
                            message: _error,
                          ),
                        )
                      : visible.isEmpty
                          ? _buildNoMatch(query)
                          : ListView.builder(
                              itemCount: visible.length,
                              itemBuilder: (context, index) {
                                final app = visible[index];
                                return ListTile(
                                  dense: true,
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
                                    style:
                                        const TextStyle(color: Colors.white),
                                  ),
                                  subtitle: Text(
                                    [
                                      app.isSystemApp ? 'System app' : 'User app',
                                      if (!app.enabled) 'disabled',
                                    ].join(', '),
                                    style:
                                        const TextStyle(color: Colors.white60),
                                  ),
                                  onTap: () =>
                                      Navigator.pop(context, app.packageName),
                                );
                              },
                            ),
            ),
          ],
        ),
      ),
    );
  }
}

Future<bool> _confirmRepair(
  BuildContext context,
  RepairServiceInfo service,
  String? packageName,
) async {
  final destructive = !service.dataPreserved;
  bool acknowledged = !destructive;

  final answer = await showDialog<bool>(
    context: context,
    builder: (dialogContext) {
      return StatefulBuilder(
        builder: (context, setDialogState) {
          final riskColor = AppColors.forRisk(service.risk);

          return AlertDialog(
            backgroundColor: AppColors.surface,
            title: Text(
              service.title,
              style: const TextStyle(color: Colors.white),
            ),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      Tag('${service.risk} RISK', color: riskColor),
                      Tag(
                        destructive ? 'ERASES DATA' : 'KEEPS DATA',
                        color: destructive ? AppColors.danger : AppColors.ok,
                      ),
                    ],
                  ),
                  if (packageName != null && packageName.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    const Text(
                      'App',
                      style: TextStyle(
                        color: AppColors.textMuted,
                        fontSize: 12,
                      ),
                    ),
                    Text(
                      packageName,
                      style: const TextStyle(
                        color: Colors.white,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ],
                  const SizedBox(height: 12),
                  Text(
                    service.description,
                    style: const TextStyle(color: Colors.white70, height: 1.4),
                  ),
                  if (service.warning.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    Text(
                      service.warning,
                      style: TextStyle(color: riskColor, height: 1.4),
                    ),
                  ],
                  if (service.endsSession) ...[
                    const SizedBox(height: 12),
                    const Text(
                      'The connection to the phone will end.',
                      style: TextStyle(color: AppColors.warning),
                    ),
                  ],
                  if (destructive) ...[
                    const SizedBox(height: 8),
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      controlAffinity: ListTileControlAffinity.leading,
                      value: acknowledged,
                      onChanged: (value) {
                        setDialogState(() => acknowledged = value == true);
                      },
                      title: const Text(
                        'The owner understands that data will be lost '
                        'and agrees.',
                        style: TextStyle(color: Colors.white, fontSize: 14),
                      ),
                    ),
                  ],
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: const Text('Cancel'),
              ),
              FilledButton(
                style: destructive
                    ? FilledButton.styleFrom(
                        backgroundColor: Colors.red.shade700,
                      )
                    : null,
                onPressed: acknowledged
                    ? () => Navigator.pop(dialogContext, true)
                    : null,
                child: const Text('Run'),
              ),
            ],
          );
        },
      );
    },
  );

  return answer == true;
}

Future<void> _showOutcome(
  BuildContext context,
  RepairServiceInfo service,
  RepairOutcome? outcome,
  Object? failure,
) {
  final succeeded = outcome != null;
  final color = succeeded ? AppColors.ok : AppColors.danger;

  final lines = <String>[];
  if (outcome != null) {
    lines.add(outcome.message);
    if (outcome.verification.isNotEmpty) {
      lines.add('Check: ${outcome.verification}');
    }
  } else if (failure != null) {
    lines.add(describeError(failure));
  }

  final output = outcome?.output.trim() ?? '';

  return showDialog<void>(
    context: context,
    builder: (dialogContext) {
      return AlertDialog(
        backgroundColor: AppColors.surface,
        title: Row(
          children: [
            Icon(succeeded ? Icons.check_circle : Icons.error, color: color),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                succeeded ? 'Done' : 'Not completed',
                style: const TextStyle(color: Colors.white),
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
                service.title,
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              for (final line in lines)
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text(
                    line,
                    style: const TextStyle(color: Colors.white70, height: 1.4),
                  ),
                ),
              if (output.isNotEmpty) ...[
                const SizedBox(height: 6),
                ReportView(
                  output.length > 3000
                      ? '${output.substring(0, 3000)}\n...'
                      : output,
                ),
              ],
              const SizedBox(height: 6),
              Text(
                succeeded
                    ? 'Saved to Repair History.'
                    : 'Nothing further was attempted.',
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 12.5,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Close'),
          ),
        ],
      );
    },
  );
}

/// The whole repair conversation: choose the app when one is needed,
/// explain and confirm, run, then show what happened.
///
/// Returns the outcome, or null when it was cancelled or failed.
Future<RepairOutcome?> runRepairFlow(
  BuildContext context,
  RepairServiceInfo service, {
  String? packageName,
}) async {
  String? target = packageName;

  if (service.needsPackage && (target == null || target.isEmpty)) {
    target = await pickTargetPackage(context);
    if (target == null || !context.mounted) return null;
  }

  final confirmed = await _confirmRepair(context, service, target);
  if (!confirmed || !context.mounted) return null;

  final navigator = Navigator.of(context, rootNavigator: true);

  unawaited(
    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) {
        return PopScope(
          canPop: false,
          child: AlertDialog(
            backgroundColor: AppColors.surface,
            content: Row(
              children: [
                const SizedBox(
                  width: 24,
                  height: 24,
                  child: CircularProgressIndicator(strokeWidth: 3),
                ),
                const SizedBox(width: 18),
                Expanded(
                  child: Text(
                    'Running: ${service.title}\nKeep the phone connected.',
                    style: const TextStyle(color: Colors.white, height: 1.4),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    ),
  );

  RepairOutcome? outcome;
  Object? failure;

  try {
    outcome = await DeviceBridge.instance.runRepair(
      service.id,
      packageName: service.needsPackage ? target : null,
    );
  } catch (error) {
    failure = error;
  }

  navigator.pop();

  if (!context.mounted) return outcome;
  await _showOutcome(context, service, outcome, failure);
  return outcome;
}
