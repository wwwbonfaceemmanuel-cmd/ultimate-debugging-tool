
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
  String? _error;
  List<AdbInterfaceInfo> _interfaces = [];

  @override
  void initState() {
    super.initState();
    _refreshInterfaces();
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

  Future<void> _requestPermission(AdbInterfaceInfo device) async {
    try {
      await _usbService.requestPermission(device.deviceName);

      if (!mounted) return;

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'USB permission request sent. Refresh after responding.',
          ),
        ),
      );

      await _refreshInterfaces();
    } catch (e) {
      if (!mounted) return;

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Permission request failed: $e')),
      );
    }
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
                Icon(Icons.usb, color: Colors.lightBlueAccent, size: 30),
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
              _hostSupported ? Colors.greenAccent : Colors.redAccent,
            ),
            const SizedBox(height: 10),
            _statusRow(
              'ADB Interfaces Found',
              detected.toString(),
              detected > 0 ? Colors.greenAccent : Colors.orangeAccent,
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusRow(String label, String value, Color color) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: const TextStyle(color: Colors.white70)),
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
        _statusChip(
          'Bulk IN',
          device.hasBulkIn,
        ),
        _statusChip(
          'Bulk OUT',
          device.hasBulkOut,
        ),
        _statusChip(
          'ADB Endpoints',
          device.hasAdbEndpoints,
        ),
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
          color: available ? Colors.greenAccent : Colors.redAccent,
          fontSize: 11,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }

  Widget _buildDeviceCard(AdbInterfaceInfo device, int index) {
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
                        device.productName?.isNotEmpty == true
                            ? device.productName!
                            : 'Unknown USB Device',
                        style: const TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        device.manufacturerName ?? 'Unknown manufacturer',
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

            const Divider(height: 28, color: Colors.white24),

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
            _infoRow('Interface ID', device.interfaceId.toString()),
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
                  device.hasPermission ? 'GRANTED' : 'NOT GRANTED',
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
            onPressed: _loading ? null : _refreshInterfaces,
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh ADB interfaces',
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refreshInterfaces,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          children: [
            _buildStatusCard(),

            const SizedBox(height: 12),

            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.orange.withOpacity(0.10),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: Colors.orangeAccent),
              ),
              child: const Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    Icons.info_outline,
                    color: Colors.orangeAccent,
                  ),
                  SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      'Current stage: Interface detection only. '
                      'ADB authentication, device communication, '
                      'and command execution are not yet implemented.',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 12,
                        height: 1.5,
                      ),
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 18),

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
              const Center(child: CircularProgressIndicator()),
              const SizedBox(height: 15),
              const Center(
                child: Text(
                  'Scanning USB interfaces...',
                  style: TextStyle(color: Colors.white70),
                ),
              ),
            ] else if (_error != null) ...[
              const SizedBox(height: 18),
              Text(
                'Error: $_error',
                style: const TextStyle(color: Colors.redAccent),
              ),
              const SizedBox(height: 12),
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
              ..._interfaces.asMap().entries.map(
                (entry) => _buildDeviceCard(
                  entry.value,
                  entry.key,
                ),
              ),
            ],

            const SizedBox(height: 25),
          ],
        ),
      ),
    );
  }
}
