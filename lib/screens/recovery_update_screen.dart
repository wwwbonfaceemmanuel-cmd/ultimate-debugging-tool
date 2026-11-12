import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';
import 'connection_screen.dart';

/// Sends a full OTA package to a phone that sits in recovery, the way
/// `adb sideload` does on a computer.
///
/// The phone must be connected over ADB while it shows "Apply update
/// from ADB". Recovery accepts only a package built for that exact
/// model, and the phone checks the signature itself.
class RecoveryUpdateScreen extends StatefulWidget {
  const RecoveryUpdateScreen({super.key});

  @override
  State<RecoveryUpdateScreen> createState() => _RecoveryUpdateScreenState();
}

class _RecoveryUpdateScreenState extends State<RecoveryUpdateScreen> {
  final DeviceBridge _bridge = DeviceBridge.instance;

  Map<String, dynamic>? _package;
  bool _sending = false;
  int _percent = 0;
  String? _result;

  StreamSubscription<int>? _progress;

  @override
  void initState() {
    super.initState();
    _progress = _bridge.sideloadProgress.listen((percent) {
      if (!mounted) return;
      setState(() => _percent = percent.clamp(0, 100));
    });
  }

  @override
  void dispose() {
    _progress?.cancel();
    super.dispose();
  }

  Future<void> _pickPackage() async {
    try {
      final package = await _bridge.pickUpdatePackage();
      if (!mounted) return;
      if (package['cancelled'] == true) return;

      setState(() {
        _package = package;
        _result = null;
        _percent = 0;
      });
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    }
  }

  Future<void> _rebootIntoRecovery() async {
    final agreed = await confirmAction(
      context,
      title: 'Restart into recovery?',
      message: 'The phone restarts into its recovery screen. Nothing is '
          'erased. Choose "Apply update from ADB" there, then come back '
          'to this screen.',
      confirmLabel: 'Restart',
    );

    if (!agreed || !mounted) return;

    try {
      final outcome = await _bridge.runRepair('reboot-recovery');
      if (!mounted) return;
      showMessage(
        context,
        outcome.message.isEmpty
            ? 'The phone is restarting into recovery.'
            : outcome.message,
      );
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    }
  }

  Future<void> _send() async {
    final package = _package;
    if (package == null) return;

    final agreed = await confirmAction(
      context,
      title: 'Send the update package?',
      message: 'The package is sent to the phone in recovery and installed '
          'there. It can take several minutes; do not unplug the cable.\n\n'
          'Recovery refuses a package that does not belong to this phone, '
          'so a wrong choice stops rather than damages anything.',
      confirmLabel: 'Send it',
      dangerous: true,
    );

    if (!agreed || !mounted) return;

    setState(() {
      _sending = true;
      _percent = 0;
      _result = null;
    });

    try {
      final result = await _bridge.sideloadUpdatePackage(
        path: asText(package['path']),
        sha256: asText(package['sha256']),
      );

      if (!mounted) return;

      setState(() {
        _sending = false;
        _percent = 100;
        _result = asText(result['message'], 'The package was sent.');
      });

      await _bridge.saveHistory(
        operation: 'Update from ADB',
        category: 'Firmware',
        result: 'Completed',
        details: '${asText(package['fileName'])} '
            '(${formatBytes(asInt(package['sizeBytes']))}) sent to recovery. '
            'SHA-256 ${asText(package['sha256'])}.',
        onTarget: true,
      );

      if (!mounted) return;
      showMessage(context, 'The package was sent.');
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _sending = false;
        _result = describeError(error);
      });
      showMessage(context, describeError(error), error: true);
    }
  }

  Widget _buildPackagePanel() {
    final package = _package;
    final looksLikePackage =
        package == null || package['looksLikePackage'] != false;

    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton.tonalIcon(
                key: const Key('update-pick'),
                onPressed: _sending ? null : _pickPackage,
                icon: const Icon(Icons.folder_open, size: 18),
                label: Text(
                  package == null ? 'Choose the package' : 'Choose another',
                ),
              ),
              if (package != null)
                FilledButton.icon(
                  key: const Key('update-send'),
                  onPressed: _sending || !looksLikePackage ? null : _send,
                  icon: const Icon(Icons.cloud_upload, size: 18),
                  label: Text(_sending ? 'Sending…' : 'Sideload'),
                ),
            ],
          ),
          if (package != null) ...[
            const SizedBox(height: 12),
            Text(
              asText(package['fileName'], 'package'),
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              formatBytes(asInt(package['sizeBytes'])),
              style:
                  const TextStyle(color: AppColors.textMuted, fontSize: 12.5),
            ),
            const SizedBox(height: 4),
            SelectableText(
              'SHA-256 ${asText(package['sha256'])}',
              style: const TextStyle(
                color: AppColors.textMuted,
                fontSize: 11,
                fontFamily: 'monospace',
              ),
            ),
            if (!looksLikePackage)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'That file is not a zip, so it is not an OTA package. '
                  'Choose the .zip file the manufacturer published.',
                  style: TextStyle(color: AppColors.danger, height: 1.35),
                ),
              ),
          ],
          if (_sending) ...[
            const SizedBox(height: 14),
            LinearProgressIndicator(value: _percent / 100),
            const SizedBox(height: 6),
            Text(
              'Sending: about $_percent %. The phone reads most of the '
              'package twice, so the figure can jump.',
              style: const TextStyle(color: AppColors.textMuted, fontSize: 12),
            ),
          ],
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Update from ADB')),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
            children: [
              const TargetBanner(),
              const SizedBox(height: 12),
              const Notice(
                icon: Icons.usb,
                color: AppColors.info,
                title: 'This works in recovery, not in Android',
                message: 'Recovery is the phone\'s own repair screen. It '
                    'accepts a package built for this exact model, and the '
                    'phone checks its signature before installing anything.',
              ),
              if (!status.connected) ...[
                const SizedBox(height: 12),
                Notice(
                  icon: Icons.phonelink_off,
                  color: AppColors.warning,
                  title: 'Connect the phone in recovery',
                  message: 'While the phone shows "Apply update from ADB", '
                      'connect it to this tool like any other phone.',
                  actionLabel: 'Open Connection',
                  onAction: () => openConnectionScreen(context),
                ),
              ],
              const SectionTitle('Step 1: get into recovery'),
              Panel(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'If the phone still starts normally, restart it into '
                      'recovery from here. On the phone choose '
                      '"Apply update from ADB"; it will say it is waiting '
                      'for a package.',
                      style: TextStyle(color: Colors.white70, height: 1.35),
                    ),
                    const SizedBox(height: 12),
                    FilledButton.tonalIcon(
                      key: const Key('update-recovery'),
                      onPressed: status.connected ? _rebootIntoRecovery : null,
                      icon: const Icon(Icons.restart_alt),
                      label: const Text('Restart into recovery'),
                    ),
                  ],
                ),
              ),
              const SectionTitle('Step 2: choose the package'),
              _buildPackagePanel(),
              if (_result != null) ...[
                const SectionTitle('What happened'),
                ReportView(_result!),
              ],
              const SectionTitle('Before you send'),
              const Notice(
                icon: Icons.info_outline,
                title: 'Match the package to the phone',
                message: 'Use the full OTA package published for this exact '
                    'model and build. Recovery refuses a package for another '
                    'phone, but a package for a different build of the same '
                    'model can still fail halfway.',
              ),
            ],
          );
        },
      ),
    );
  }
}
