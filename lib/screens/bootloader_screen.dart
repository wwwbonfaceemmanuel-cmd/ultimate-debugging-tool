import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';

/// The part a flashing tool is known for: talking to a phone that is in
/// its bootloader, and writing an image to one partition.
///
/// It refuses to guess: the partition list is fixed, the image is hashed
/// before and after the choice, the partition size the phone reports is
/// checked, and the technician must type the partition name.
class BootloaderScreen extends StatefulWidget {
  const BootloaderScreen({super.key});

  @override
  State<BootloaderScreen> createState() => _BootloaderScreenState();
}

class _BootloaderScreenState extends State<BootloaderScreen> {
  static const List<String> _allowedPartitions = <String>[
    'boot',
    'init_boot',
    'recovery',
    'dtbo',
    'vbmeta',
    'vendor_boot',
  ];

  final DeviceBridge _bridge = DeviceBridge.instance;
  final TextEditingController _confirm = TextEditingController();

  List<FastbootDevice> _devices = <FastbootDevice>[];
  bool _loadingDevices = false;
  String? _error;

  FastbootInfo? _info;
  bool _connecting = false;

  Map<String, dynamic>? _image;
  String _partition = 'boot';
  bool _flashing = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _findDevices());
  }

  @override
  void dispose() {
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _findDevices() async {
    if (_loadingDevices) return;
    setState(() {
      _loadingDevices = true;
      _error = null;
    });

    try {
      final devices = await _bridge.getFastbootDevices();
      if (!mounted) return;
      setState(() {
        _devices = devices;
        _loadingDevices = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = describeError(error);
        _loadingDevices = false;
      });
    }
  }

  Future<void> _connect(FastbootDevice device) async {
    setState(() {
      _connecting = true;
      _error = null;
    });

    try {
      final info = await _bridge.connectFastboot(device.deviceName);
      if (!mounted) return;
      setState(() {
        _info = info;
        _connecting = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _connecting = false;
        _error = describeError(error);
      });
    }
  }

  Future<void> _disconnect() async {
    try {
      await _bridge.disconnectFastboot();
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    }

    if (!mounted) return;
    setState(() {
      _info = null;
      _image = null;
      _confirm.clear();
    });
  }

  Future<void> _reboot(String target) async {
    final agreed = await confirmAction(
      context,
      title: 'Restart the phone?',
      message: 'The phone leaves the bootloader and restarts. The '
          'bootloader link closes, so connect again afterwards if you '
          'still have work to do.',
      confirmLabel: 'Restart',
    );

    if (!agreed || !mounted) return;

    try {
      await _bridge.rebootFastboot(target);
      if (!mounted) return;
      setState(() => _info = null);
      showMessage(context, 'The phone is restarting.');
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    }
  }

  Future<void> _pickImage() async {
    try {
      final image = await _bridge.pickBootImage();
      if (!mounted) return;

      if (image['cancelled'] == true) return;

      setState(() {
        _image = image;
        _confirm.clear();
      });
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    }
  }

  bool get _mayFlash {
    final image = _image;
    if (image == null || _info == null || _flashing) return false;
    if (_confirm.text.trim() != _partition) return false;

    final size = asInt(image['sizeBytes']);
    final limit = _info!.sizeOf(_partition);

    if (limit > 0 && size > limit) return false;

    return true;
  }

  Future<void> _flash() async {
    final image = _image;
    if (image == null) return;

    final agreed = await confirmAction(
      context,
      title: 'Write to the $_partition partition?',
      message: 'The image is written to $_partition on the connected '
          'phone. If it does not match this exact model and firmware, the '
          'phone may not start again.\n\nThis cannot be undone.',
      confirmLabel: 'Write it',
      dangerous: true,
    );

    if (!agreed || !mounted) return;

    // Backup-first guard: show report and record note before actual flash
    try {
      final guard = await _bridge.prepareFlashGuard(_partition, asText(image['path']));
      if (!mounted) return;
      final ok = await confirmAction(
        context,
        title: 'Backup check before flash',
        message: guard,
        confirmLabel: 'Flash anyway',
      );
      if (!ok || !mounted) return;
      await _bridge.recordFlashBackupNote(_partition, 'User confirmed flash of $_partition via bootloader');
    } catch (e) {
      debugPrint('Flash guard skipped: $e');
    }

    setState(() => _flashing = true);

    try {
      final result = await _bridge.flashFastbootImage(
        partition: _partition,
        path: asText(image['path']),
        sha256: asText(image['sha256']),
      );
      if (!mounted) return;
      setState(() {
        _flashing = false;
        _image = null;
        _confirm.clear();
      });

      await _bridge.saveHistory(
        operation: 'Flash $_partition',
        category: 'Bootloader',
        result: 'Completed',
        details: '${asText(image['fileName'])} '
            '(${formatBytes(asInt(image['sizeBytes']))}) written to '
            '$_partition. SHA-256 ${asText(image['sha256'])}.',
        onTarget: true,
      );

      if (!mounted) return;
      showMessage(
        context,
        asText(result['message'], 'The $_partition partition was written.'),
      );
      await _refresh();
    } catch (error) {
      if (!mounted) return;
      setState(() => _flashing = false);
      showMessage(context, describeError(error), error: true);
    }
  }

  Future<void> _refresh() async {
    if (_info == null) return;

    try {
      final info = await _bridge.getFastbootInfo();
      if (!mounted) return;
      setState(() => _info = info);
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = describeError(error));
    }
  }

  Widget _buildDeviceList() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionTitle('Phones in bootloader mode'),
        if (_loadingDevices)
          const Padding(
            padding: EdgeInsets.all(20),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (_devices.isEmpty)
          const Notice(
            icon: Icons.usb_off,
            title: 'No phone is in bootloader mode',
            message: 'Connect the phone by cable, restart it into the '
                'bootloader (Repair Tools, then "Restart into '
                'bootloader"), then read the list again.',
            actionLabel: 'Read again',
          )
        else
          for (final device in _devices)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Panel(
                child: Row(
                  children: [
                    const Icon(Icons.memory, color: AppColors.info),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            device.title,
                            style: const TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            device.deviceName,
                            style: const TextStyle(
                              color: AppColors.textMuted,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    FilledButton.tonal(
                      onPressed: _connecting ? null : () => _connect(device),
                      child: Text(_connecting ? '…' : 'Connect'),
                    ),
                  ],
                ),
              ),
            ),
        const SizedBox(height: 8),
        TextButton.icon(
          onPressed: _findDevices,
          icon: const Icon(Icons.refresh, size: 18),
          label: const Text('Read the list again'),
        ),
      ],
    );
  }

  Widget _buildInfo(FastbootInfo info) {
    final unlocked = info.unlocked == true;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionTitle('The phone in the bootloader'),
        Panel(
          borderColor: unlocked
              ? AppColors.warning.withAlpha(160)
              : AppColors.border,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.memory, color: AppColors.info),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      info.product,
                      style: const TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        fontSize: 16,
                      ),
                    ),
                  ),
                  Tag(
                    info.unlockText.toUpperCase(),
                    color: unlocked ? AppColors.warning : AppColors.ok,
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                'Serial ${info.serial.isEmpty ? "not reported" : info.serial}'
                '${info.currentSlot.isEmpty ? "" : " - slot ${info.currentSlot}"}'
                '${info.slots > 0 ? " of ${info.slots}" : ""}',
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 12.5,
                ),
              ),
              if (!unlocked) ...[
                const SizedBox(height: 10),
                const Text(
                  'The bootloader is locked, so the phone will refuse every '
                  'write. Unlocking is done from the phone itself, wipes the '
                  'phone, and is the owner\'s decision. This tool does not '
                  'unlock anything.',
                  style: TextStyle(color: Colors.white70, height: 1.35),
                ),
              ],
            ],
          ),
        ),
        if (info.partitions.isNotEmpty) ...[
          const SectionTitle('Partitions the phone reports'),
          Panel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final partition in info.partitions)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Row(
                      children: [
                        Expanded(
                          child: Text(
                            partition.name,
                            style: TextStyle(
                              color: _allowedPartitions.contains(partition.name)
                                  ? Colors.white
                                  : Colors.white54,
                              fontSize: 13,
                            ),
                          ),
                        ),
                        Text(
                          partition.sizeText.isEmpty
                              ? 'size not reported'
                              : partition.sizeText,
                          style: const TextStyle(
                            color: AppColors.textMuted,
                            fontSize: 12.5,
                          ),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ],
        const SectionTitle('Restart'),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            OutlinedButton(
              key: const Key('boot-to-system'),
              onPressed: () => _reboot(''),
              child: const Text('Restart normally'),
            ),
            OutlinedButton(
              onPressed: () => _reboot('bootloader'),
              child: const Text('Back to bootloader'),
            ),
            OutlinedButton(
              onPressed: () => _reboot('recovery'),
              child: const Text('Into recovery'),
            ),
          ],
        ),
        const SectionTitle('Write an image'),
        _buildFlashPanel(info),
        const SizedBox(height: 12),
        TextButton.icon(
          onPressed: _disconnect,
          icon: const Icon(Icons.link_off, size: 18),
          label: const Text('Close the bootloader link'),
        ),
      ],
    );
  }

  Widget _buildFlashPanel(FastbootInfo info) {
    final image = _image;
    final size = image == null ? 0 : asInt(image['sizeBytes']);
    final limit = info.sizeOf(_partition);
    final tooBig = limit > 0 && size > limit;

    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Only boot, init_boot, recovery, dtbo, vbmeta and vendor_boot '
            'can be written here. The phone checks everything else itself.',
            style: TextStyle(color: Colors.white70, height: 1.35, fontSize: 13),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              FilledButton.tonalIcon(
                key: const Key('boot-pick-image'),
                onPressed: _pickImage,
                icon: const Icon(Icons.folder_open, size: 18),
                label: Text(image == null ? 'Choose an image' : 'Choose another'),
              ),
              DropdownButton<String>(
                value: _partition,
                dropdownColor: AppColors.surface,
                onChanged: (value) {
                  if (value == null) return;
                  setState(() {
                    _partition = value;
                    _confirm.clear();
                  });
                },
                items: [
                  for (final name in _allowedPartitions)
                    DropdownMenuItem<String>(
                      value: name,
                      child: Text(name),
                    ),
                ],
              ),
            ],
          ),
          if (image != null) ...[
            const SizedBox(height: 12),
            Text(
              asText(image['fileName'], 'image'),
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              '${formatBytes(size)}'
              '${limit > 0 ? " of ${formatBytes(limit)} in $_partition" : ""}',
              style: TextStyle(
                color: tooBig ? AppColors.danger : AppColors.textMuted,
                fontSize: 12.5,
              ),
            ),
            const SizedBox(height: 4),
            SelectableText(
              'SHA-256 ${asText(image['sha256'])}',
              style: const TextStyle(
                color: AppColors.textMuted,
                fontSize: 11,
                fontFamily: 'monospace',
              ),
            ),
            if (tooBig)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'This image is larger than the partition. Choose the right '
                  'image for this phone.',
                  style: TextStyle(color: AppColors.danger, height: 1.35),
                ),
              ),
            const SizedBox(height: 12),
            TextField(
              controller: _confirm,
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(
                labelText: 'Type "$_partition" to allow writing',
                prefixIcon: const Icon(Icons.keyboard),
              ),
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              key: const Key('boot-flash'),
              onPressed: _mayFlash ? _flash : null,
              style: FilledButton.styleFrom(
                backgroundColor: Colors.red.shade700,
              ),
              icon: const Icon(Icons.flash_on),
              label: Text(_flashing ? 'Writing…' : 'Write to $_partition'),
            ),
          ],
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final info = _info;

    return Scaffold(
      appBar: AppBar(title: const Text('Bootloader')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
        children: [
          const Notice(
            icon: Icons.warning_amber,
            color: AppColors.warning,
            title: 'Writing firmware can leave a phone unusable',
            message: 'An image must match the exact model, device code and '
                'build of the phone. Read the partition table first, keep the '
                'phone on a charger, and never use an image from an unknown '
                'source.',
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Notice(
              icon: Icons.error_outline,
              color: AppColors.danger,
              title: 'The bootloader answered with a problem',
              message: _error!,
              actionLabel: 'Read the list again',
              onAction: _findDevices,
            ),
          ],
          const SizedBox(height: 12),
          if (info == null) _buildDeviceList() else _buildInfo(info),
        ],
      ),
    );
  }
}
