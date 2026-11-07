
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class DiagnosticsScreen extends StatefulWidget {
  const DiagnosticsScreen({super.key});

  @override
  State<DiagnosticsScreen> createState() =>
      _DiagnosticsScreenState();
}

class _DiagnosticsScreenState extends State<DiagnosticsScreen> {
  static const MethodChannel _channel =
      MethodChannel('com.ultimate.debugging/usb');

  final Map<String, String> _results = {};
  final Set<String> _running = {};

    final Map<String, String> _checks = {
    'device-info': 'Device, Software & Firmware',
    'battery': 'Battery Diagnostics',
    'storage': 'Advanced Storage Diagnostics',
    'memory': 'RAM & Memory Diagnostics',
    'security': 'Security Diagnostics',
    'network': 'Network Diagnostics',
    'technician-suite': 'Advanced Technician Suite',
    'uptime': 'Device Uptime',
  };

  bool _runningAll = false;

  Future<void> _saveHistory({
    required String operation,
    required String result,
    required String details,
  }) async {
    try {
      await _channel.invokeMethod(
        'saveRepairHistory',
        {
          'operation': operation,
          'category': 'Device diagnostics',
          'result': result,
          'details': details,
        },
      );
    } catch (error) {
      debugPrint('Diagnostic history save failed: $error');
    }
  }

  Future<void> _runCheck(String command) async {
    if (_running.contains(command)) return;

    setState(() {
      _running.add(command);
      _results.remove(command);
    });

    try {
      final result = await _channel.invokeMethod<String>(
        'executeStandardDiagnostic',
        {'command': command},
      );

      if (!mounted) return;

      final output = result?.trim().isNotEmpty == true
          ? result!.trim()
          : 'The diagnostic returned no information.';

      setState(() {
        _results[command] = output;
      });

      await _saveHistory(
        operation: _checks[command] ?? command,
        result: result?.trim().isNotEmpty == true
            ? 'Completed'
            : 'Warning',
        details: output,
      );
    } on PlatformException catch (error) {
      if (!mounted) return;

      final message =
          'Diagnostic failed: ${error.message ?? error.code}';

      setState(() {
        _results[command] = message;
      });

      await _saveHistory(
        operation: _checks[command] ?? command,
        result: 'Failed',
        details: message,
      );
    } catch (error) {
      if (!mounted) return;

      final message = 'Unexpected diagnostic error: $error';

      setState(() {
        _results[command] = message;
      });

      await _saveHistory(
        operation: _checks[command] ?? command,
        result: 'Failed',
        details: message,
      );
    } finally {
      if (mounted) {
        setState(() {
          _running.remove(command);
        });
      }
    }
  }

  Future<void> _runAllChecks() async {
    if (_runningAll) return;

    setState(() {
      _runningAll = true;
    });

    try {
      for (final command in _checks.keys) {
        if (!mounted) return;
        await _runCheck(command);
      }
    } finally {
      if (mounted) {
        setState(() {
          _runningAll = false;
        });
      }
    }
  }

  Widget _buildDiagnosticCard(String command, String title) {
    final isRunning = _running.contains(command);
    final output = _results[command];

    return Card(
      color: const Color(0xFF172638),
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                if (isRunning)
                  const SizedBox(
                    width: 22,
                    height: 22,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                    ),
                  )
                else
                  IconButton(
                    onPressed: _runningAll
                        ? null
                        : () => _runCheck(command),
                    icon: const Icon(
                      Icons.refresh,
                      color: Colors.lightBlueAccent,
                    ),
                    tooltip: 'Run diagnostic',
                  ),
              ],
            ),
            if (output != null) ...[
              const Divider(color: Colors.white24),
              SelectableText(
                output,
                style: const TextStyle(
                  color: Colors.white70,
                  height: 1.5,
                  fontFamily: 'monospace',
                ),
              ),
            ] else if (!isRunning) ...[
              const Text(
                'Not yet checked.',
                style: TextStyle(color: Colors.white54),
              ),
            ],
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF080F1D),
      appBar: AppBar(
        title: const Text('Device Diagnostics'),
        backgroundColor: const Color(0xFF122235),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text(
              'ANDROID DEVICE HEALTH',
              style: TextStyle(
                color: Colors.white,
                fontSize: 20,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Run real diagnostic checks on the phone '
              'running Ultimate Debugging Tool.',
              style: TextStyle(
                color: Colors.white70,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: _runningAll
                    ? null
                    : _runAllChecks,
                icon: const Icon(Icons.health_and_safety),
                label: Text(
                  _runningAll
                      ? 'Running Diagnostics...'
                      : 'Run All Checks',
                ),
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF0284C7),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(
                    vertical: 16,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 20),
            ..._checks.entries.map(
              (entry) => _buildDiagnosticCard(
                entry.key,
                entry.value,
              ),
            ),
            const SizedBox(height: 12),
            const Text(
              'Note: These checks inspect the phone running '
              'this application. Connected-device diagnostics '
              'will be handled separately through ADB.',
              style: TextStyle(
                color: Colors.white54,
                height: 1.5,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
