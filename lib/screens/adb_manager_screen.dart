
import 'dart:async';
import 'package:flutter/material.dart';
import '../services/usb_service.dart';

class AdbManagerScreen extends StatefulWidget {
  const AdbManagerScreen({super.key});

  @override
  State<AdbManagerScreen> createState() => _AdbManagerScreenState();
}

class _AdbManagerScreenState extends State<AdbManagerScreen> {
  final UsbService _usbService = UsbService();

  bool _loading = true;
  bool _hostSupported = false;
  bool _connecting = false;
  bool _disconnecting = false;

  String? _error;
  String _connectionStatus = 'DISCONNECTED';
  String _keyFingerprint = '';

  AdbConnectionInfo? _connectionInfo;

  List<AdbInterfaceInfo> _interfaces = [];

  bool get _isConnected =>
      _connectionStatus.toUpperCase() == 'CONNECTED';

  @override
  void initState() {
    super.initState();
    _refreshInterfaces();
    _loadConnectionStatus();
    _loadKeyFingerprint();
  }

  Future<void> _refreshInterfaces() async {
    if (mounted) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }

    try {
      final supported = await _usbService.isUsbHostSupported();
      final interfaces = await _usbService.getAdbInterfaces();

      if (!mounted) return;

      setState(() {
        _hostSupported = supported;
        _interfaces = interfaces;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;

      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  Future<void> _loadConnectionStatus() async {
    try {
      final result = await _usbService.getAdbConnectionStatus();

      if (!mounted) return;

      setState(() {
        _connectionStatus = result.status;
      });
    } catch (_) {
      // Keep the last displayed status if retrieval fails.
    }
  }

  Future<void> _loadKeyFingerprint() async {
    try {
      final fingerprint =
          await _usbService.getAdbKeyFingerprint();

      if (!mounted) return;

      setState(() {
        _keyFingerprint = fingerprint;
      });
    } catch (_) {
      // Fingerprint can be loaded again later.
    }
  }

  Future<void> _requestPermission(
    AdbInterfaceInfo device,
  ) async {
    try {
      final granted =
          await _usbService.requestPermission(device.deviceName);

      if (!mounted) return;

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            granted
                ? 'USB permission granted.'
                : 'USB permission request sent. Approve it on the device if prompted, then refresh.',
          ),
        ),
      );

      await _refreshInterfaces();
    } catch (e) {
      if (!mounted) return;

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Permission request failed: $e'),
        ),
      );
    }
  }

  Future<void> _connectAdb(
    AdbInterfaceInfo device,
  ) async {
    if (_connecting || _disconnecting || _isConnected) return;

    if (!device.hasPermission) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Grant USB permission before connecting.',
          ),
        ),
      );
      return;
    }

    if (!device.hasAdbEndpoints ||
        !device.hasBulkIn ||
        !device.hasBulkOut) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'This interface does not have the required ADB endpoints.',
          ),
        ),
      );
      return;
    }

    setState(() {
      _connecting = true;
      _connectionStatus = 'CONNECTING';
      _connectionInfo = null;
      _error = null;
    });

    try {
      final result = await _usbService.connectAdb(
        deviceName: device.deviceName,
        interfaceId: device.interfaceId,
      );

      if (!mounted) return;

      setState(() {
        _connectionInfo = result;
        _connectionStatus = result.status;
        _connecting = false;
      });

      await _loadConnectionStatus();

      if (!mounted) return;

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            _isConnected
                ? 'ADB connection established.'
                : 'ADB returned status: ${result.status}',
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;

      setState(() {
        _connecting = false;
        _connectionStatus = 'FAILED';
        _error = e.toString();
      });

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('ADB connection failed: $e'),
        ),
      );
    }
  }

  Future<void> _disconnectAdb() async {
    if (_disconnecting) return;

    setState(() {
      _disconnecting = true;
    });

    try {
      await _usbService.disconnectAdb();

      if (!mounted) return;

      setState(() {
        _connectionStatus = 'DISCONNECTED';
        _connectionInfo = null;
        _connecting = false;
        _disconnecting = false;
      });

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('ADB disconnection requested.'),
        ),
      );
    } catch (e) {
      if (!mounted) return;

      setState(() {
        _disconnecting = false;
        _error = e.toString();
      });

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Disconnect failed: $e'),
        ),
      );
    }
  }

  Color _connectionColor() {
    switch (_connectionStatus.toUpperCase()) {
      case 'CONNECTED':
        return Colors.greenAccent;
      case 'CONNECTING':
      case 'AUTHENTICATION_REQUIRED':
        return Colors.orangeAccent;
      case 'FAILED':
        return Colors.redAccent;
      default:
        return Colors.white60;
    }
  }

  Widget _buildConnectionCard() {
    return Card(
      color: const Color(0xFF172638),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(
                  Icons.cable,
                  color: Colors.lightBlueAccent,
                  size: 28,
                ),
                SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'ADB CONNECTION',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 15,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 18),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text(
                  'Connection Status',
                  style: TextStyle(color: Colors.white70),
                ),
                Text(
                  _connectionStatus.replaceAll('_', ' '),
                  style: TextStyle(
                    color: _connectionColor(),
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            if (_connecting) ...[
              const SizedBox(height: 14),
              const LinearProgressIndicator(),
              const SizedBox(height: 8),
              const Text(
                'Waiting for the ADB handshake. '
                'If prompted, approve the USB debugging RSA '
                'authorization on the target phone.',
                style: TextStyle(
                  color: Colors.orangeAccent,
                  fontSize: 12,
                  height: 1.5,
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: _disconnecting ? null : _disconnectAdb,
                icon: const Icon(Icons.cancel),
                label: const Text('CANCEL CONNECTION'),
              ),
            ],
            if (_isConnected) ...[
              const Divider(
                height: 28,
                color: Colors.white24,
              ),
              _infoRow(
                'ADB Version',
                '0x${(_connectionInfo?.version ?? 0).toRadixString(16)}',
              ),
              _infoRow(
                'Maximum Data',
                '${_connectionInfo?.maxDataSize ?? 0} bytes',
              ),
              _infoRow(
                'Device Banner',
                _connectionInfo?.deviceBanner ?? '',
              ),
              const SizedBox(height: 10),
              const Text(
                'Supported Features',
                style: TextStyle(
                  color: Colors.white70,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                (_connectionInfo?.features ?? []).isEmpty
                    ? 'No features reported'
                    : _connectionInfo!.features.join(', '),
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                ),
              ),
              const SizedBox(height: 14),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed:
                      _disconnecting ? null : _disconnectAdb,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.red.shade700,
                    foregroundColor: Colors.white,
                  ),
                  icon: const Icon(Icons.link_off),
                  label: const Text('DISCONNECT ADB'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildStatusCard() {
    final detected = _interfaces.length;

    return Card(
      color: const Color(0xFF172638),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(
                  Icons.usb,
                  color: Colors.lightBlueAccent,
                  size: 30,
                ),
                SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'ADB USB INTERFACE DETECTION',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 15,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 18),
            _statusRow(
              'USB Host Support',
              _hostSupported ? 'Supported' : 'Not supported',
              _hostSupported
                  ? Colors.greenAccent
                  : Colors.redAccent,
            ),
            const SizedBox(height: 10),
            _statusRow(
              'ADB Interfaces Found',
              detected.toString(),
              detected > 0
                  ? Colors.greenAccent
                  : Colors.orangeAccent,
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusRow(
    String label,
    String value,
    Color color,
  ) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: const TextStyle(color: Colors.white70),
        ),
        Text(
          value,
          style: TextStyle(
            color: color,
            fontWeight: FontWeight.bold,
          ),
        ),
      ],
    );
  }

  Widget _buildEndpointStatus(AdbInterfaceInfo device) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        _statusChip('Bulk IN', device.hasBulkIn),
        _statusChip('Bulk OUT', device.hasBulkOut),
        _statusChip('ADB Endpoints', device.hasAdbEndpoints),
      ],
    );
  }

  Widget _statusChip(String label, bool available) {
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: 10,
        vertical: 7,
      ),
      decoration: BoxDecoration(
        color: available
            ? Colors.green.withOpacity(0.15)
            : Colors.red.withOpacity(0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: available ? Colors.green : Colors.redAccent,
        ),
      ),
      child: Text(
        '$label: ${available ? "YES" : "NO"}',
        style: TextStyle(
          color: available
              ? Colors.greenAccent
              : Colors.redAccent,
          fontSize: 11,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }

  Widget _buildDeviceCard(AdbInterfaceInfo device) {
    final canConnect = device.hasPermission &&
        device.hasAdbEndpoints &&
        device.hasBulkIn &&
        device.hasBulkOut &&
        !_connecting &&
        !_disconnecting &&
        !_isConnected;

    return Card(
      margin: const EdgeInsets.only(bottom: 14),
      color: const Color(0xFF172638),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(
                  Icons.phone_android,
                  color: Colors.lightBlueAccent,
                  size: 32,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        device.productName.isNotEmpty
                            ? device.productName
                            : 'Unknown USB Device',
                        style: const TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        device.manufacturerName.isNotEmpty
                            ? device.manufacturerName
                            : 'Unknown manufacturer',
                        style: const TextStyle(
                          color: Colors.white60,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                const Icon(
                  Icons.usb,
                  color: Colors.lightBlueAccent,
                ),
              ],
            ),
            const Divider(
              height: 28,
              color: Colors.white24,
            ),
            _infoRow('Device name', device.deviceName),
            _infoRow('Device ID', device.deviceId.toString()),
            _infoRow(
              'Vendor ID',
              '0x${device.vendorId.toRadixString(16).toUpperCase()}',
            ),
            _infoRow(
              'Product ID',
              '0x${device.productId.toRadixString(16).toUpperCase()}',
            ),
            _infoRow(
              'Interface ID',
              device.interfaceId.toString(),
            ),
            _infoRow(
              'Interface Class',
              '0x${device.interfaceClass.toRadixString(16).toUpperCase()}',
            ),
            _infoRow(
              'Interface Subclass',
              '0x${device.interfaceSubclass.toRadixString(16).toUpperCase()}',
            ),
            _infoRow(
              'Interface Protocol',
              '0x${device.interfaceProtocol.toRadixString(16).toUpperCase()}',
            ),
            const SizedBox(height: 16),
            const Text(
              'ADB Endpoint Status',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 10),
            _buildEndpointStatus(device),
            const SizedBox(height: 16),
            Row(
              children: [
                const Text(
                  'USB Permission',
                  style: TextStyle(color: Colors.white70),
                ),
                const Spacer(),
                Text(
                  device.hasPermission
                      ? 'GRANTED'
                      : 'NOT GRANTED',
                  style: TextStyle(
                    color: device.hasPermission
                        ? Colors.greenAccent
                        : Colors.orangeAccent,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            if (!device.hasPermission) ...[
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: _loading
                      ? null
                      : () => _requestPermission(device),
                  icon: const Icon(Icons.lock_open),
                  label: const Text('REQUEST USB PERMISSION'),
                ),
              ),
            ],
            if (device.hasPermission) ...[
              const SizedBox(height: 14),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: canConnect
                      ? () => _connectAdb(device)
                      : null,
                  icon: const Icon(Icons.link),
                  label: Text(
                    _isConnected
                        ? 'ADB ALREADY CONNECTED'
                        : _connecting
                            ? 'CONNECTING...'
                            : 'CONNECT ADB',
                  ),
                ),
              ),
            ],
            const SizedBox(height: 12),
            if (device.endpoints.isNotEmpty) ...[
              const Text(
                'USB Endpoints',
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              ...device.endpoints.map(
                (endpoint) => Container(
                  width: double.infinity,
                  margin: const EdgeInsets.only(bottom: 6),
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.black26,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    'Address: ${endpoint.address} | '
                    'Number: ${endpoint.endpointNumber}\n'
                    'Direction: ${endpoint.direction} | '
                    'Type: ${endpoint.type}\n'
                    'Max Packet Size: ${endpoint.maxPacketSize}',
                    style: const TextStyle(
                      color: Colors.white70,
                      fontSize: 11,
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildFingerprintCard() {
    return Card(
      color: const Color(0xFF172638),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'WORKSTATION ADB KEY FINGERPRINT',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                fontSize: 13,
              ),
            ),
            const SizedBox(height: 10),
            SelectableText(
              _keyFingerprint.isEmpty
                  ? 'Fingerprint not available'
                  : _keyFingerprint,
              style: const TextStyle(
                color: Colors.lightBlueAccent,
                fontSize: 12,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'This identifies the ADB public key generated by this workstation.',
              style: TextStyle(
                color: Colors.white54,
                fontSize: 11,
                height: 1.4,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 9),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 125,
            child: Text(
              label,
              style: const TextStyle(
                color: Colors.white54,
                fontSize: 12,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 12,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEmptyState() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      margin: const EdgeInsets.only(top: 14),
      decoration: BoxDecoration(
        color: const Color(0xFF172638),
        borderRadius: BorderRadius.circular(12),
      ),
      child: const Column(
        children: [
          Icon(
            Icons.usb_off,
            color: Colors.orangeAccent,
            size: 48,
          ),
          SizedBox(height: 14),
          Text(
            'No ADB Interface Detected',
            style: TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.bold,
              fontSize: 16,
            ),
          ),
          SizedBox(height: 10),
          Text(
            'Connect a compatible Android device using a USB OTG cable. '
            'The target device must expose a standard ADB USB interface. '
            'USB debugging should be enabled on the target device.',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: Colors.white70,
              height: 1.5,
              fontSize: 12,
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0B1522),
      appBar: AppBar(
        backgroundColor: const Color(0xFF122235),
        foregroundColor: Colors.white,
        title: const Text(
          'ADB Manager',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            onPressed: _loading
                ? null
                : () async {
                    await _refreshInterfaces();
                    await _loadConnectionStatus();
                    await _loadKeyFingerprint();
                  },
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh ADB interfaces',
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          await _refreshInterfaces();
          await _loadConnectionStatus();
          await _loadKeyFingerprint();
        },
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          children: [
            _buildConnectionCard(),
            const SizedBox(height: 12),
            _buildStatusCard(),
            const SizedBox(height: 12),
            _buildFingerprintCard(),
            const SizedBox(height: 18),
            if (_error != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.red.withOpacity(0.12),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: Colors.redAccent),
                ),
                child: Text(
                  _error!,
                  style: const TextStyle(
                    color: Colors.redAccent,
                    fontSize: 12,
                  ),
                ),
              ),
              const SizedBox(height: 14),
            ],
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text(
                  'Detected ADB Devices',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 17,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Text(
                  '${_interfaces.length} found',
                  style: const TextStyle(
                    color: Colors.lightBlueAccent,
                  ),
                ),
              ],
            ),
            if (_loading) ...[
              const SizedBox(height: 35),
              const Center(
                child: CircularProgressIndicator(),
              ),
              const SizedBox(height: 15),
              const Center(
                child: Text(
                  'Scanning USB interfaces...',
                  style: TextStyle(color: Colors.white70),
                ),
              ),
            ] else if (_error != null && _interfaces.isEmpty) ...[
              const SizedBox(height: 18),
              ElevatedButton.icon(
                onPressed: _refreshInterfaces,
                icon: const Icon(Icons.refresh),
                label: const Text('TRY AGAIN'),
              ),
            ] else if (!_hostSupported) ...[
              const SizedBox(height: 18),
              const Text(
                'This phone does not report USB Host support.',
                style: TextStyle(color: Colors.orangeAccent),
              ),
            ] else if (_interfaces.isEmpty) ...[
              _buildEmptyState(),
            ] else ...[
              const SizedBox(height: 12),
              ..._interfaces.map(_buildDeviceCard),
            ],
            const SizedBox(height: 25),
          ],
        ),
      ),
    );
  }
}
