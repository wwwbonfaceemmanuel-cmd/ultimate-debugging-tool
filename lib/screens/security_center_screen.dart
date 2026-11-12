import 'package:flutter/material.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';
import 'connection_screen.dart';

/// A read-only security check of the connected phone.
class SecurityCenterScreen extends StatefulWidget {
  const SecurityCenterScreen({super.key});

  @override
  State<SecurityCenterScreen> createState() => _SecurityCenterScreenState();
}

class _SecurityCenterScreenState extends State<SecurityCenterScreen> {
  final DeviceBridge _bridge = DeviceBridge.instance;

  bool _running = false;
  SecurityAuditResult? _result;
  String _error = '';

  @override
  void initState() {
    super.initState();
    _bridge.start();
  }

  Future<void> _runAudit() async {
    if (_running) return;

    setState(() {
      _running = true;
      _error = '';
    });

    try {
      final result = await _bridge.runSecurityAudit();

      await _bridge.saveHistory(
        operation: 'Security audit',
        category: 'Security',
        result: 'Completed',
        details: result.report,
        onTarget: true,
      );

      if (!mounted) return;
      setState(() {
        _result = result;
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

  Color _ratingColor(SecurityAuditResult result) {
    if (result.rating == 'GOOD') return AppColors.ok;
    if (result.rating == 'AT RISK') return AppColors.danger;
    return AppColors.warning;
  }

  IconData _levelIcon(String level) {
    switch (level) {
      case 'CRITICAL':
        return Icons.dangerous;
      case 'WARNING':
        return Icons.warning_amber;
      case 'OK':
        return Icons.check_circle;
      default:
        return Icons.info;
    }
  }

  int _levelOrder(String level) {
    switch (level) {
      case 'CRITICAL':
        return 0;
      case 'WARNING':
        return 1;
      case 'INFO':
        return 2;
      default:
        return 3;
    }
  }

  Widget _buildScore(SecurityAuditResult result) {
    final color = _ratingColor(result);
    final critical =
        result.findings.where((item) => item.level == 'CRITICAL').length;
    final warnings =
        result.findings.where((item) => item.level == 'WARNING').length;

    return Panel(
      borderColor: color.withAlpha(150),
      child: Row(
        children: [
          SizedBox(
            width: 76,
            height: 76,
            child: Stack(
              alignment: Alignment.center,
              children: [
                SizedBox(
                  width: 76,
                  height: 76,
                  child: CircularProgressIndicator(
                    value: result.score.clamp(0, 100) / 100,
                    strokeWidth: 7,
                    color: color,
                    backgroundColor: Colors.white12,
                  ),
                ),
                Text(
                  '${result.score}',
                  style: TextStyle(
                    color: color,
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 18),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  result.rating,
                  style: TextStyle(
                    color: color,
                    fontSize: 20,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  '$critical critical, $warnings to review',
                  style: const TextStyle(color: Colors.white70),
                ),
                const SizedBox(height: 4),
                const Text(
                  'Score out of 100. Nothing on the phone was changed.',
                  style: TextStyle(color: AppColors.textMuted, fontSize: 12.5),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFinding(SecurityFinding finding) {
    final color = AppColors.forRisk(finding.level);

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Panel(
        padding: const EdgeInsets.all(14),
        borderColor: color.withAlpha(110),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(_levelIcon(finding.level), color: color),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    finding.title,
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  if (finding.detail.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      finding.detail,
                      style: const TextStyle(color: Colors.white70, height: 1.35),
                    ),
                  ],
                  if (finding.advice.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text(
                      'What to do: ${finding.advice}',
                      style: TextStyle(color: color, height: 1.35),
                    ),
                  ],
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
    final result = _result;

    final findings = result == null
        ? <SecurityFinding>[]
        : (List<SecurityFinding>.of(result.findings)
          ..sort((a, b) => _levelOrder(a.level) - _levelOrder(b.level)));

    return Scaffold(
      appBar: AppBar(
        title: const Text('Security Center'),
        actions: [
          if (result != null)
            IconButton(
              tooltip: 'Copy report',
              onPressed: () => copyText(context, result.report),
              icon: const Icon(Icons.copy),
            ),
        ],
      ),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              TargetBanner(onConnect: () => openConnectionScreen(context)),
              const SizedBox(height: 12),
              if (!status.connected && result == null)
                NeedsPhone(
                  what: 'The security audit',
                  onConnect: () => openConnectionScreen(context),
                )
              else ...[
                const Panel(
                  child: Text(
                    'Checks the security patch age, root, bootloader and '
                    'verified boot state, encryption, screen lock, risky '
                    'settings, device administrators, accessibility services '
                    'and apps installed from outside the store.\n\n'
                    'The audit only reads. It changes nothing on the phone.',
                    style: TextStyle(color: Colors.white70, height: 1.4),
                  ),
                ),
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed:
                        _running || !status.connected ? null : _runAudit,
                    icon: _running
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2.5),
                          )
                        : const Icon(Icons.shield),
                    label: Text(
                      _running
                          ? 'Checking the phone...'
                          : (result == null
                              ? 'Run security audit'
                              : 'Run again'),
                    ),
                  ),
                ),
              ],
              if (_error.isNotEmpty) ...[
                const SizedBox(height: 12),
                Notice(
                  icon: Icons.error_outline,
                  color: AppColors.danger,
                  title: 'The audit could not be completed',
                  message: _error,
                ),
              ],
              if (result != null) ...[
                const SizedBox(height: 16),
                _buildScore(result),
                const SectionTitle('Findings'),
                for (final finding in findings) _buildFinding(finding),
              ],
            ],
          );
        },
      ),
    );
  }
}
