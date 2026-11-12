import 'dart:async';

import 'package:flutter/material.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';

/// Opens the Connection screen from any other screen.
void openConnectionScreen(BuildContext context) {
  Navigator.push(
    context,
    MaterialPageRoute<void>(builder: (context) => const ConnectionScreen()),
  );
}

/// Everything about linking the phone being serviced: USB cable, Wi-Fi,
/// approval on the phone, and this workstation's ADB key.
class ConnectionScreen extends StatefulWidget {
  const ConnectionScreen({super.key});

  @override
  State<ConnectionScreen> createState() => _ConnectionScreenState();
}

class _ConnectionScreenState extends State<ConnectionScreen> {
  final DeviceBridge _bridge = DeviceBridge.instance;

  final TextEditingController _hostController = TextEditingController();
  final TextEditingController _portController =
      TextEditingController(text: '5555');

  StreamSubscription<UsbEvent>? _usbSubscription;

  bool _usbHostSupported = true;
  bool _loading = true;
  bool _working = false;
  Set<String> _adbCapable = <String>{};
  String _pendingPermissionDevice = '';
  String _fingerprint = '';
  String _keyStorage = '';
  String _keyError = '';

  @override
  void initState() {
    super.initState();
    _bridge.start();
    _usbSubscription = _bridge.usbEvents.listen(_onUsbEvent);
    _load();
    _loadKey();
  }

  @override
  void dispose() {
    _usbSubscription?.cancel();
    _hostController.dispose();
    _portController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final supported = await _bridge.isUsbHostSupported();
      await _bridge.getUsbDevices();
      final capable = await _readAdbCapable();
      await _bridge.refreshStatus();

      if (!mounted) return;
      setState(() {
        _usbHostSupported = supported;
        _adbCapable = capable;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _loading = false);
      showMessage(context, describeError(error), error: true);
    }
  }

  Future<Set<String>> _readAdbCapable() async {
    final names = <String>{};
    for (final item in await _bridge.getAdbInterfaces()) {
      if (item.hasAdbEndpoints) names.add(item.deviceName);
    }
    return names;
  }

  Future<void> _loadKey() async {
    try {
      final key = await _bridge.getKeyFingerprint();
      if (!mounted) return;
      setState(() {
        _fingerprint = asText(key['fingerprint']);
        _keyStorage = asText(key['storage']);
        _keyError = '';
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _keyError = describeError(error));
    }
  }

  Future<void> _onUsbEvent(UsbEvent event) async {
    try {
      final capable = await _readAdbCapable();
      if (!mounted) return;
      setState(() => _adbCapable = capable);
    } catch (_) {
      // The list is refreshed again on the next event.
    }

    if (!mounted) return;

    final waitingFor = _pendingPermissionDevice;
    if (waitingFor.isEmpty || event.deviceName != waitingFor) return;

    if (event.event == 'permission_granted') {
      setState(() => _pendingPermissionDevice = '');
      await _startUsbSession(waitingFor);
    } else if (event.event == 'permission_denied') {
      setState(() => _pendingPermissionDevice = '');
      showMessage(
        context,
        'USB access was not allowed. Tap Connect again and choose OK.',
        error: true,
      );
    }
  }

  Future<void> _connectUsb(UsbDeviceInfo device) async {
    if (_working) return;

    try {
      // Ask Android first; the answer may arrive later as an event.
      final granted = await _bridge.requestUsbPermission(device.deviceName);

      if (!granted) {
        if (!mounted) return;
        setState(() => _pendingPermissionDevice = device.deviceName);
        showMessage(context, 'Tap OK on the USB access prompt.');
        return;
      }
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
      return;
    }

    await _startUsbSession(device.deviceName);
  }

  Future<void> _startUsbSession(String deviceName) async {
    if (_working) return;
    setState(() => _working = true);

    try {
      final status = await _bridge.connectUsb(deviceName);
      if (!mounted) return;
      showMessage(context, 'Connected to ${status.title}.');
    } catch (error) {
      if (!mounted) return;
      _reportConnectError(error);
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  Future<void> _connectWireless() async {
    if (_working) return;

    final host = _hostController.text.trim();
    final port = int.tryParse(_portController.text.trim()) ?? 0;

    if (host.isEmpty) {
      showMessage(context, 'Enter the IP address of the phone.', error: true);
      return;
    }

    if (port < 1 || port > 65535) {
      showMessage(context, 'Enter a port from 1 to 65535.', error: true);
      return;
    }

    FocusScope.of(context).unfocus();
    setState(() => _working = true);

    try {
      final status = await _bridge.connectWireless(host, port);
      if (!mounted) return;
      showMessage(context, 'Connected to ${status.title}.');
    } catch (error) {
      if (!mounted) return;
      _reportConnectError(error);
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  void _reportConnectError(Object error) {
    if (errorCode(error) == 'ADB_CANCELLED') {
      showMessage(context, 'Connection cancelled.');
    } else {
      showMessage(context, describeError(error), error: true);
    }
  }

  Future<void> _disconnect() async {
    try {
      await _bridge.disconnect();
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    }
  }

  Future<void> _switchToWireless() async {
    final confirmed = await confirmAction(
      context,
      title: 'Continue over Wi-Fi?',
      message: 'The phone will start accepting ADB over Wi-Fi on port 5555 '
          'and this session will move to Wi-Fi, so the cable can be '
          'unplugged.\n\n'
          'Both phones must be on the same Wi-Fi network. Anyone on that '
          'network whose key the phone already trusts could also connect, '
          'so use "Turn off Wi-Fi debugging" when the job is finished. '
          'Restarting the phone also turns it off.',
      confirmLabel: 'Switch to Wi-Fi',
    );

    if (!confirmed || !mounted) return;
    setState(() => _working = true);

    try {
      final status = await _bridge.switchToWireless();
      if (!mounted) return;
      _hostController.text = status.host;
      showMessage(context, 'Now connected over Wi-Fi. The cable can be unplugged.');
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  Future<void> _stopWirelessDebugging() async {
    final confirmed = await confirmAction(
      context,
      title: 'Turn off Wi-Fi debugging?',
      message: 'The phone will go back to accepting ADB over a USB cable '
          'only, and this session will end.',
      confirmLabel: 'Turn off',
    );

    if (!confirmed || !mounted) return;
    setState(() => _working = true);

    try {
      await _bridge.stopWirelessDebugging();
      if (!mounted) return;
      showMessage(context, 'Wi-Fi debugging is off on the phone.');
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  // -------------------------------------------------------------------
  // Interface
  // -------------------------------------------------------------------

  Widget _buildStatus(AdbStatus status) {
    if (status.connected) {
      final device = status.device;

      Widget row(String label, String? value) {
        if (value == null || value.isEmpty) return const SizedBox.shrink();
        return Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: 120,
                child: Text(
                  label,
                  style: const TextStyle(color: AppColors.textMuted),
                ),
              ),
              Expanded(
                child: Text(value, style: const TextStyle(color: Colors.white)),
              ),
            ],
          ),
        );
      }

      return Panel(
        borderColor: AppColors.ok.withAlpha(150),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  status.isWireless ? Icons.wifi : Icons.usb,
                  color: AppColors.ok,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    status.title,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 17,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                const Tag('CONNECTED', color: AppColors.ok),
              ],
            ),
            const SizedBox(height: 8),
            row('Link', status.linkLabel),
            row('Maker', device['manufacturer']),
            row('Model', device['model']),
            row('Android', device['android']),
            row('SDK level', device['sdk']),
            row('Security patch', device['securityPatch']),
            row('Serial', device['serial']),
            row('Build', device['buildId']),
            if (status.isWireless) row('Address', '${status.host}:${status.port}'),
            const SizedBox(height: 14),
            Wrap(
              spacing: 10,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: _working ? null : _disconnect,
                  icon: const Icon(Icons.link_off),
                  label: const Text('Disconnect'),
                ),
                if (status.isUsb)
                  FilledButton.tonalIcon(
                    onPressed: _working ? null : _switchToWireless,
                    icon: const Icon(Icons.wifi),
                    label: const Text('Continue over Wi-Fi'),
                  ),
                if (status.isWireless)
                  FilledButton.tonalIcon(
                    onPressed: _working ? null : _stopWirelessDebugging,
                    icon: const Icon(Icons.wifi_off),
                    label: const Text('Turn off Wi-Fi debugging'),
                  ),
              ],
            ),
          ],
        ),
      );
    }

    if (status.connecting) {
      return Panel(
        borderColor: AppColors.warning.withAlpha(150),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2.5),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    status.title,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              status.waitingForApproval
                  ? 'Look at the other phone. Unlock it and tap "Allow" on '
                      'the "Allow USB debugging?" prompt. Tick "Always allow '
                      'from this computer" so it is not asked again.'
                  : 'Starting the ADB session with the phone.',
              style: const TextStyle(color: Colors.white70, height: 1.4),
            ),
            if (status.waitingForApproval && _fingerprint.isNotEmpty) ...[
              const SizedBox(height: 10),
              const Text(
                'The prompt should show this fingerprint:',
                style: TextStyle(color: AppColors.textMuted, fontSize: 12.5),
              ),
              const SizedBox(height: 4),
              SelectableText(
                _fingerprint,
                style: const TextStyle(
                  color: Colors.white,
                  fontFamily: 'monospace',
                  fontSize: 12.5,
                ),
              ),
            ],
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: OutlinedButton.icon(
                onPressed: _disconnect,
                icon: const Icon(Icons.close),
                label: const Text('Cancel'),
              ),
            ),
          ],
        ),
      );
    }

    return Notice(
      icon: Icons.link_off,
      color: status.error.isEmpty ? AppColors.info : AppColors.danger,
      title: status.error.isEmpty ? 'No phone connected' : 'Last attempt failed',
      message: status.error.isEmpty
          ? 'Connect the phone to be serviced with a USB cable (through an '
              'OTG adapter), or over Wi-Fi.'
          : status.error,
    );
  }

  Widget _buildUsbDevice(UsbDeviceInfo device, AdbStatus status) {
    final offersAdb = _adbCapable.contains(device.deviceName);
    final isCurrent =
        status.isUsb && status.target == device.deviceName && status.busy;
    final waiting = _pendingPermissionDevice == device.deviceName;
    final canConnect = offersAdb && !status.busy && !_working;

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.usb, color: AppColors.info),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    device.displayName,
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 15,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                Tag('ID ${device.ids}', color: AppColors.textMuted),
                if (offersAdb)
                  const Tag('USB DEBUGGING ON', color: AppColors.ok)
                else
                  const Tag('NO ADB', color: AppColors.warning),
                if (device.hasPermission)
                  const Tag('USB ACCESS ALLOWED', color: AppColors.ok)
                else
                  const Tag('USB ACCESS NOT YET ALLOWED',
                      color: AppColors.warning),
                if (isCurrent && status.connected)
                  const Tag('IN USE', color: AppColors.info),
              ],
            ),
            if (!offersAdb) ...[
              const SizedBox(height: 10),
              const Text(
                'This device is not offering ADB. On that phone open '
                'Settings > Developer options and turn on USB debugging. '
                'If it asks for a USB mode, choose File transfer.',
                style: TextStyle(color: Colors.white70, height: 1.35),
              ),
            ],
            if (waiting) ...[
              const SizedBox(height: 10),
              const Text(
                'Waiting for you to tap OK on the USB access prompt...',
                style: TextStyle(color: AppColors.warning),
              ),
            ],
            if (offersAdb && !isCurrent) ...[
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton.icon(
                  onPressed: canConnect ? () => _connectUsb(device) : null,
                  icon: const Icon(Icons.link),
                  label: const Text('Connect'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildUsbSection(AdbStatus status, List<UsbDeviceInfo> devices) {
    if (!_usbHostSupported) {
      return const Notice(
        icon: Icons.usb_off,
        color: AppColors.warning,
        title: 'This phone cannot act as a USB host',
        message: 'It cannot talk to another phone through a cable. '
            'Use the Wi-Fi option below instead.',
      );
    }

    if (devices.isEmpty) {
      return const Notice(
        icon: Icons.cable,
        title: 'No USB device detected',
        message: '1. Plug an OTG adapter into THIS phone.\n'
            '2. Connect the other phone to the adapter with a data cable.\n'
            '3. On the other phone turn on Developer options > USB '
            'debugging.\n\n'
            'If nothing appears, try another cable: many cables only charge.',
      );
    }

    return Column(
      children: [
        for (final device in devices) _buildUsbDevice(device, status),
      ],
    );
  }

  Widget _buildWirelessSection(AdbStatus status) {
    final enabled = !status.busy && !_working;

    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Both phones must be on the same Wi-Fi network. The easiest '
            'way is to connect by cable first and then tap "Continue over '
            'Wi-Fi": the address is filled in for you.',
            style: TextStyle(color: Colors.white70, height: 1.4),
          ),
          const SizedBox(height: 14),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                flex: 3,
                child: TextField(
                  controller: _hostController,
                  enabled: enabled,
                  keyboardType: TextInputType.url,
                  autocorrect: false,
                  decoration: const InputDecoration(
                    labelText: 'Phone IP address',
                    hintText: '192.168.1.25',
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 2,
                child: TextField(
                  controller: _portController,
                  enabled: enabled,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'Port',
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton.icon(
              onPressed: enabled ? _connectWireless : null,
              icon: const Icon(Icons.wifi),
              label: const Text('Connect over Wi-Fi'),
            ),
          ),
          const SizedBox(height: 6),
          const Text(
            'Note: the "Wireless debugging" pairing code of Android 11 and '
            'newer is not supported yet. Use the cable once, then continue '
            'over Wi-Fi.',
            style: TextStyle(color: AppColors.textMuted, fontSize: 12.5),
          ),
        ],
      ),
    );
  }

  Widget _buildKeySection() {
    if (_keyError.isNotEmpty) {
      return Notice(
        icon: Icons.key_off,
        color: AppColors.danger,
        title: 'The ADB key could not be prepared',
        message: _keyError,
        actionLabel: 'Try again',
        onAction: _loadKey,
      );
    }

    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'This is the identity the other phone sees. When it asks '
            '"Allow USB debugging?", the fingerprint on its screen must '
            'match this one.',
            style: TextStyle(color: Colors.white70, height: 1.4),
          ),
          const SizedBox(height: 10),
          if (_fingerprint.isEmpty)
            const Text(
              'Preparing the key...',
              style: TextStyle(color: AppColors.textMuted),
            )
          else
            Row(
              children: [
                Expanded(
                  child: SelectableText(
                    _fingerprint,
                    style: const TextStyle(
                      color: Colors.white,
                      fontFamily: 'monospace',
                      fontSize: 12.5,
                    ),
                  ),
                ),
                IconButton(
                  tooltip: 'Copy',
                  onPressed: () => copyText(context, _fingerprint),
                  icon: const Icon(Icons.copy, size: 20),
                ),
              ],
            ),
          if (_keyStorage.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              'Key storage: $_keyStorage',
              style: const TextStyle(
                color: AppColors.textMuted,
                fontSize: 12.5,
              ),
            ),
          ],
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Connection'),
        actions: [
          IconButton(
            tooltip: 'Refresh',
            onPressed: _load,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ValueListenableBuilder<AdbStatus>(
              valueListenable: _bridge.status,
              builder: (context, status, _) {
                return ValueListenableBuilder<List<UsbDeviceInfo>>(
                  valueListenable: _bridge.usbDevices,
                  builder: (context, devices, _) {
                    return ListView(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
                      children: [
                        _buildStatus(status),
                        const SectionTitle('USB cable'),
                        _buildUsbSection(status, devices),
                        const SectionTitle('Wi-Fi'),
                        _buildWirelessSection(status),
                        const SectionTitle('This workstation\'s ADB key'),
                        _buildKeySection(),
                      ],
                    );
                  },
                );
              },
            ),
    );
  }
}
