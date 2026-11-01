
import 'dart:async';
import 'package:flutter/material.dart';
import '../services/usb_service.dart';

class UsbManagerScreen extends StatefulWidget {
  const UsbManagerScreen({super.key});

  @override
  State<UsbManagerScreen> createState() => _UsbManagerScreenState();
}

class _UsbManagerScreenState extends State<UsbManagerScreen> {
  final UsbService _usbService = UsbService();

  StreamSubscription<UsbEvent>? _usbSubscription;

  bool _loading = true;
  bool _hostSupported = false;
  String? _error;
  String? _eventMessage;

  List<UsbDeviceInfo> _devices = [];

  @override
  void initState() {
    super.initState();

    _listenForUsbEvents();
    _refreshDevices();
  }

  void _listenForUsbEvents() {
    _usbSubscription = _usbService.usbEvents.listen(
      (event) {
        if (!mounted) return;

        String message;

        switch (event.event) {
          case 'connected':
            message = 'USB device connected.';
            break;

          case 'disconnected':
            message = 'USB device disconnected.';
            break;

          case 'permission_granted':
            message = 'USB permission granted.';
            break;

          case 'permission_denied':
            message = 'USB permission denied.';
            break;

          default:
            message = 'USB status updated.';
        }

        setState(() {
          _devices = event.devices;
          _loading = false;
          _error = null;
          _eventMessage = message;
        });
      },
      onError: (error) {
        if (!mounted) return;

        setState(() {
          _eventMessage = 'USB event monitoring unavailable.';
        });
      },
    );
  }

  Future<void> _refreshDevices() async {
    if (mounted) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }

    try {
      final supported = await _usbService.isUsbHostSupported();

      final devices = supported
          ? await _usbService.getConnectedDevices()
          : <UsbDeviceInfo>[];

      if (!mounted) return;

      setState(() {
        _hostSupported = supported;
        _devices = devices;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;

      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _requestPermission(UsbDeviceInfo device) async {
    try {
      final requested =
          await _usbService.requestPermission(device.deviceName);

      if (!mounted) return;

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            requested
                ? 'USB permission request sent. Respond to the Android prompt.'
                : 'Could not send USB permission request.',
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('USB permission error: $e'),
        ),
      );
    }
  }

  Widget _buildDeviceCard(UsbDeviceInfo device) {
    return Card(
      color: const Color(0xFF17243A),
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              device.productName,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 17,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Manufacturer: ${device.manufacturerName}',
              style: const TextStyle(color: Colors.white70),
            ),
            Text(
              'Vendor ID: ${device.vendorId}',
              style: const TextStyle(color: Colors.white70),
            ),
            Text(
              'Product ID: ${device.productId}',
              style: const TextStyle(color: Colors.white70),
            ),
            Text(
              'USB interfaces: ${device.interfaceCount}',
              style: const TextStyle(color: Colors.white70),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Icon(
                  device.hasPermission
                      ? Icons.verified_user
                      : Icons.lock_outline,
                  color: device.hasPermission
                      ? Colors.greenAccent
                      : Colors.orangeAccent,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    device.hasPermission
                        ? 'USB permission granted'
                        : 'USB permission not granted',
                    style: const TextStyle(color: Colors.white),
                  ),
                ),
              ],
            ),
            if (!device.hasPermission) ...[
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: () => _requestPermission(device),
                  icon: const Icon(Icons.lock_open),
                  label: const Text('Request USB Permission'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    _usbSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0B1424),
      appBar: AppBar(
        backgroundColor: const Color(0xFF101D32),
        foregroundColor: Colors.white,
        title: const Text('USB Manager'),
        actions: [
          IconButton(
            onPressed: _loading ? null : _refreshDevices,
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh devices',
          ),
        ],
      ),
      body: _loading
          ? const Center(
              child: CircularProgressIndicator(),
            )
          : RefreshIndicator(
              onRefresh: _refreshDevices,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  Card(
                    color: const Color(0xFF17243A),
                    child: ListTile(
                      leading: Icon(
                        _hostSupported ? Icons.usb : Icons.usb_off,
                        color: _hostSupported
                            ? Colors.greenAccent
                            : Colors.redAccent,
                        size: 32,
                      ),
                      title: const Text(
                        'USB Host Support',
                        style: TextStyle(color: Colors.white),
                      ),
                      subtitle: Text(
                        _hostSupported
                            ? 'Supported on this phone'
                            : 'Not supported or unavailable',
                        style: const TextStyle(color: Colors.white70),
                      ),
                    ),
                  ),

                  if (_eventMessage != null) ...[
                    const SizedBox(height: 12),
                    Card(
                      color: const Color(0xFF20334D),
                      child: ListTile(
                        leading: const Icon(
                          Icons.sync,
                          color: Colors.lightBlueAccent,
                        ),
                        title: Text(
                          _eventMessage!,
                          style: const TextStyle(color: Colors.white),
                        ),
                      ),
                    ),
                  ],

                  const SizedBox(height: 20),

                  const Text(
                    'CONNECTED USB DEVICES',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      letterSpacing: 1.2,
                    ),
                  ),

                  const SizedBox(height: 12),

                  if (_error != null)
                    Text(
                      _error!,
                      style: const TextStyle(color: Colors.redAccent),
                    )
                  else if (!_hostSupported)
                    const Text(
                      'This phone does not report USB host support.',
                      style: TextStyle(color: Colors.white70),
                    )
                  else if (_devices.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 32),
                      child: Column(
                        children: [
                          Icon(
                            Icons.usb_off,
                            color: Colors.white38,
                            size: 48,
                          ),
                          SizedBox(height: 12),
                          Text(
                            'No USB devices detected.',
                            style: TextStyle(color: Colors.white70),
                          ),
                          SizedBox(height: 6),
                          Text(
                            'Connect a USB device using an OTG adapter. '
                            'The screen will update when Android reports '
                            'a connection.',
                            textAlign: TextAlign.center,
                            style: TextStyle(color: Colors.white54),
                          ),
                        ],
                      ),
                    )
                  else ...[
                    Text(
                      '${_devices.length} USB device(s) detected',
                      style: const TextStyle(color: Colors.greenAccent),
                    ),
                    const SizedBox(height: 12),
                    ..._devices.map(_buildDeviceCard),
                  ],
                ],
              ),
            ),
    );
  }
}
