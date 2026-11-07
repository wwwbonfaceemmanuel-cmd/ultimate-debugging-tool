
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class RepairToolsScreen extends StatefulWidget {
  const RepairToolsScreen({super.key});

  @override
  State<RepairToolsScreen> createState() =>
      _RepairToolsScreenState();
}

class _RepairToolsScreenState extends State<RepairToolsScreen> {
  static const MethodChannel _diagnosticChannel =
      MethodChannel('com.ultimate.debugging/usb');

  int? _selectedIndex;

  final Set<int> _completedSteps = {};
  final Map<String, String> _diagnosticResults = {};

  bool _isRunningDiagnostics = false;

  static const List<RepairGuide> _guides = [
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
      diagnosticCommands: [
        'memory',
        'storage',
        'uptime',
      ],
      risk: 'LOW',
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
      diagnosticCommands: [
        'battery',
      ],
      risk: 'LOW',
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
      diagnosticCommands: [
        'storage',
      ],
      risk: 'MEDIUM',
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
        'Consider clearing cache only after explaining the consequences.',
      ],
      diagnosticCommands: [
        'memory',
        'storage',
      ],
      risk: 'MEDIUM',
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
        'Software or configuration issues.',
      ],
      actions: [
        'Confirm that the network is available.',
        'Restart the affected connection.',
        'Restart the phone if necessary.',
        'Back up network settings before considering a network reset.',
      ],
      diagnosticCommands: [],
      risk: 'LOW',
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
      diagnosticCommands: [
        'device-info',
        'uptime',
      ],
      risk: 'HIGH',
    ),
  ];

  static const Map<String, String> _commandLabels = {
    'device-info': 'Device information',
    'battery': 'Battery information',
    'storage': 'Internal storage',
    'memory': 'RAM and memory',
    'uptime': 'Device uptime',
  };

  Future<void> _runAvailableDiagnostics(
    RepairGuide guide,
  ) async {
    if (guide.diagnosticCommands.isEmpty) {
      return;
    }

    setState(() {
      _isRunningDiagnostics = true;
      _diagnosticResults.clear();
    });

    for (final command in guide.diagnosticCommands) {
      try {
        final result =
            await _diagnosticChannel.invokeMethod<String>(
          'executeStandardDiagnostic',
          {'command': command},
        );

        if (!mounted) return;

        setState(() {
          _diagnosticResults[command] =
              result?.trim().isNotEmpty == true
                  ? result!.trim()
                  : 'The diagnostic returned no information.';
        });
      } on PlatformException catch (error) {
        if (!mounted) return;

        setState(() {
          _diagnosticResults[command] =
              'Diagnostic error: ${error.message ?? error.code}';
        });
      } catch (error) {
        if (!mounted) return;

        setState(() {
          _diagnosticResults[command] =
              'Unable to run this check: $error';
        });
      }
    }

    if (mounted) {
      setState(() {
        _isRunningDiagnostics = false;
      });
    }
  }

  void _selectGuide(int index) {
    setState(() {
      _selectedIndex = index;
      _completedSteps.clear();
      _diagnosticResults.clear();
      _isRunningDiagnostics = false;
    });
  }

  Color _riskColor(String risk) {
    switch (risk) {
      case 'LOW':
        return Colors.greenAccent;
      case 'MEDIUM':
        return Colors.orangeAccent;
      default:
        return Colors.redAccent;
    }
  }

  Widget _buildBulletSection(
    String title,
    List<String> items,
    IconData icon,
  ) {
    return Card(
      color: const Color(0xFF172638),
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: Colors.lightBlueAccent),
                const SizedBox(width: 10),
                Text(
                  title,
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            ...items.map(
              (item) => Padding(
                padding: const EdgeInsets.only(bottom: 9),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '• ',
                      style: TextStyle(
                        color: Colors.lightBlueAccent,
                      ),
                    ),
                    Expanded(
                      child: Text(
                        item,
                        style: const TextStyle(
                          color: Colors.white70,
                          height: 1.4,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildChecklist(List<String> steps) {
    return Card(
      color: const Color(0xFF172638),
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(
                  Icons.fact_check,
                  color: Colors.lightBlueAccent,
                ),
                SizedBox(width: 10),
                Text(
                  'Diagnostic checklist',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            ...List.generate(
              steps.length,
              (index) => CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                dense: true,
                controlAffinity:
                    ListTileControlAffinity.leading,
                activeColor: Colors.lightBlueAccent,
                title: Text(
                  steps[index],
                  style: const TextStyle(
                    color: Colors.white70,
                    fontSize: 13,
                  ),
                ),
                value: _completedSteps.contains(index),
                onChanged: (value) {
                  setState(() {
                    if (value == true) {
                      _completedSteps.add(index);
                    } else {
                      _completedSteps.remove(index);
                    }
                  });
                },
              ),
            ),
            const Divider(color: Colors.white12),
            Text(
              '${_completedSteps.length} of ${steps.length} checks completed',
              style: const TextStyle(
                color: Colors.lightBlueAccent,
                fontSize: 12,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDeviceDiagnostics(RepairGuide guide) {
    return Card(
      color: const Color(0xFF172638),
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(
                  Icons.health_and_safety,
                  color: Colors.lightBlueAccent,
                ),
                SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Available automated checks',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 14,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            const Text(
              'These checks inspect the phone running UDT. '
              'They do not inspect a connected customer phone. '
              'Target-device checks require an authenticated '
              'ADB connection.',
              style: TextStyle(
                color: Colors.orangeAccent,
                fontSize: 12,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 12),
            if (guide.diagnosticCommands.isEmpty)
              const Text(
                'No automated network diagnostic is implemented '
                'yet. Use the manual checklist above.',
                style: TextStyle(
                  color: Colors.white70,
                  height: 1.5,
                ),
              )
            else ...[
              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: _isRunningDiagnostics
                      ? null
                      : () => _runAvailableDiagnostics(guide),
                  icon: _isRunningDiagnostics
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                          ),
                        )
                      : const Icon(Icons.play_arrow),
                  label: Text(
                    _isRunningDiagnostics
                        ? 'Running checks...'
                        : 'Run available checks on this phone',
                  ),
                ),
              ),
              const SizedBox(height: 12),
              ...guide.diagnosticCommands.map((command) {
                final result = _diagnosticResults[command];

                return Container(
                  width: double.infinity,
                  margin: const EdgeInsets.only(bottom: 10),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFF0D1928),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(
                      color: Colors.white12,
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _commandLabels[command] ?? command,
                        style: const TextStyle(
                          color: Colors.lightBlueAccent,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      if (result == null)
                        const Text(
                          'Not run yet.',
                          style: TextStyle(
                            color: Colors.white54,
                            fontSize: 12,
                          ),
                        )
                      else
                        SelectableText(
                          result,
                          style: const TextStyle(
                            color: Colors.white70,
                            fontSize: 12,
                            height: 1.5,
                          ),
                        ),
                    ],
                  ),
                );
              }),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildGuideDetails(RepairGuide guide) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Card(
          color: const Color(0xFF172638),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      guide.icon,
                      color: Colors.lightBlueAccent,
                      size: 30,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        guide.title,
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 17,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  guide.description,
                  style: const TextStyle(
                    color: Colors.white70,
                  ),
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    const Text('Repair risk: '),
                    Text(
                      guide.risk,
                      style: TextStyle(
                        color: _riskColor(guide.risk),
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
        _buildBulletSection(
          'Reported symptoms',
          guide.symptoms,
          Icons.report_problem_outlined,
        ),
        _buildDeviceDiagnostics(guide),
        _buildChecklist(guide.checks),
        _buildBulletSection(
          'Possible causes',
          guide.causes,
          Icons.search,
        ),
        _buildBulletSection(
          'Recommended actions',
          guide.actions,
          Icons.build_outlined,
        ),
        Card(
          color: const Color(0xFF3A2916),
          child: const Padding(
            padding: EdgeInsets.all(14),
            child: Text(
              'IMPORTANT: Diagnostic results provide '
              'information for technician review. They do not '
              'automatically identify every fault or perform '
              'repairs. Back up user data before any '
              'data-affecting operation.',
              style: TextStyle(
                color: Colors.orangeAccent,
                height: 1.5,
                fontSize: 12,
              ),
            ),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final selectedGuide = _selectedIndex == null
        ? null
        : _guides[_selectedIndex!];

    return Scaffold(
      appBar: AppBar(
        title: const Text('Guided Diagnosis & Repair'),
        backgroundColor: const Color(0xFF122235),
      ),
      body: SafeArea(
        child: selectedGuide == null
            ? ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  const Text(
                    'SELECT A DEVICE PROBLEM',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Choose the symptoms reported by the user '
                    'to begin a guided diagnostic procedure.',
                    style: TextStyle(
                      color: Colors.white70,
                      height: 1.5,
                    ),
                  ),
                  const SizedBox(height: 18),
                  ...List.generate(
                    _guides.length,
                    (index) {
                      final guide = _guides[index];

                      return Card(
                        color: const Color(0xFF172638),
                        margin: const EdgeInsets.only(bottom: 10),
                        child: ListTile(
                          leading: Icon(
                            guide.icon,
                            color: Colors.lightBlueAccent,
                            size: 28,
                          ),
                          title: Text(
                            guide.title,
                            style: const TextStyle(
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          subtitle: Text(
                            guide.description,
                            style: const TextStyle(
                              color: Colors.white60,
                            ),
                          ),
                          trailing: const Icon(
                            Icons.arrow_forward_ios,
                            size: 16,
                          ),
                          onTap: () => _selectGuide(index),
                        ),
                      );
                    },
                  ),
                ],
              )
            : Column(
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton.icon(
                      onPressed: _isRunningDiagnostics
                          ? null
                          : () {
                              setState(() {
                                _selectedIndex = null;
                                _completedSteps.clear();
                                _diagnosticResults.clear();
                              });
                            },
                      icon: const Icon(Icons.arrow_back),
                      label: const Text('All problems'),
                    ),
                  ),
                  Expanded(
                    child: ListView(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                      ),
                      children: [
                        _buildGuideDetails(selectedGuide),
                      ],
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

class RepairGuide {
  final String title;
  final String description;
  final IconData icon;
  final List<String> symptoms;
  final List<String> checks;
  final List<String> causes;
  final List<String> actions;
  final List<String> diagnosticCommands;
  final String risk;

  const RepairGuide({
    required this.title,
    required this.description,
    required this.icon,
    required this.symptoms,
    required this.checks,
    required this.causes,
    required this.actions,
    required this.diagnosticCommands,
    required this.risk,
  });
}
