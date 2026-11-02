
import 'package:flutter/material.dart';
import '../services/wireless_adb_service.dart';

class WirelessAdbScreen extends StatefulWidget {
  const WirelessAdbScreen({super.key});

  @override
  State<WirelessAdbScreen> createState() => _WirelessAdbScreenState();
}

class _WirelessAdbScreenState extends State<WirelessAdbScreen> {
  final TextEditingController _hostController = TextEditingController();
  final TextEditingController _portController =
      TextEditingController(text: '5555');

  WirelessAdbConnectionInfo? _connectionInfo;

  bool _loading = false;
  String? _message;

  bool get _connected =>
      _connectionInfo?.status == 'CONNECTED';

  bool get _authenticationRequired =>
      _connectionInfo?.status == 'AUTHENTICATION_REQUIRED';

  Future<void> _connect() async {
    final host = _hostController.text.trim();
    final port = int.tryParse(_portController.text.trim());

    if (host.isEmpty || port == null || port < 1 || port > 65535) {
      setState(() {
        _message = 'Enter a valid IP address and TCP port.';
      });
      return;
    }

    FocusScope.of(context).unfocus();

    setState(() {
      _loading = true;
      _message = null;
      _connectionInfo = null;
    });

    try {
      final info = await WirelessAdbService.connect(
        host: host,
        port: port,
      );

      if (!mounted) return;

      setState(() {
        _connectionInfo = info;
        _message = info.error;
      });
    } catch (error) {
      if (!mounted) return;

      setState(() {
        _message = error.toString();
      });
    } finally {
      if (mounted) {
        setState(() {
          _loading = false;
        });
      }
    }
  }

  Future<void> _refreshStatus() async {
    setState(() {
      _loading = true;
      _message = null;
    });

    try {
      final info = await WirelessAdbService.getStatus();

      if (!mounted) return;

      setState(() {
        _connectionInfo = info;
        _message = info.error;
      });
    } catch (error) {
      if (!mounted) return;

      setState(() {
        _message = error.toString();
      });
    } finally {
      if (mounted) {
        setState(() {
          _loading = false;
        });
      }
    }
  }

  Future<void> _disconnect() async {
    setState(() {
      _loading = true;
      _message = null;
    });

    try {
      final info = await WirelessAdbService.disconnect();

      if (!mounted) return;

      setState(() {
        _connectionInfo = info;
        _message = 'Wireless ADB disconnected.';
      });
    } catch (error) {
      if (!mounted) return;

      setState(() {
        _message = error.toString();
      });
    } finally {
      if (mounted) {
        setState(() {
          _loading = false;
        });
      }
    }
  }

  Color get _statusColor {
    if (_connected) return Colors.green;
    if (_authenticationRequired) return Colors.orange;
    if (_connectionInfo?.status == 'FAILED') return Colors.red;
    if (_connectionInfo?.status == 'CONNECTING') return Colors.blue;
    return Colors.grey;
  }

  String get _statusText {
    if (_connectionInfo == null) return 'DISCONNECTED';

    switch (_connectionInfo!.status) {
      case 'CONNECTED':
        return 'CONNECTED';
      case 'AUTHENTICATION_REQUIRED':
        return 'AUTHORIZATION REQUIRED';
      case 'CONNECTING':
        return 'CONNECTING';
      case 'FAILED':
        return 'CONNECTION FAILED';
      case 'CLOSED':
        return 'CLOSED';
      default:
        return _connectionInfo!.status;
    }
  }

  @override
  void dispose() {
    _hostController.dispose();
    _portController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF101827),
      appBar: AppBar(
        title: const Text('Wireless ADB'),
        backgroundColor: const Color(0xFF17243A),
        foregroundColor: Colors.white,
        actions: [
          IconButton(
            onPressed: _loading ? null : _refreshStatus,
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh status',
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(18),
          children: [
            Container(
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: const Color(0xFF1B2B43),
                borderRadius: BorderRadius.circular(18),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(
                    Icons.wifi_tethering,
                    color: Colors.lightBlueAccent,
                    size: 42,
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'Wireless Android Debug Bridge',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Connect to another Android device over a local Wi-Fi '
                    'network using its wireless ADB TCP address.',
                    style: TextStyle(
                      color: Colors.white70,
                      height: 1.5,
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 22),

            _sectionTitle('DEVICE CONNECTION'),

            const SizedBox(height: 12),

            _inputField(
              controller: _hostController,
              label: 'Device IP address',
              hint: 'Example: 192.168.1.100',
              icon: Icons.language,
              keyboardType: TextInputType.url,
            ),

            const SizedBox(height: 14),

            _inputField(
              controller: _portController,
              label: 'ADB TCP port',
              hint: '5555',
              icon: Icons.settings_ethernet,
              keyboardType: TextInputType.number,
            ),

            const SizedBox(height: 20),

            SizedBox(
              height: 52,
              child: ElevatedButton.icon(
                onPressed: _loading || _connected
                    ? null
                    : _connect,
                icon: const Icon(Icons.link),
                label: const Text('CONNECT DEVICE'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.blueAccent,
                  foregroundColor: Colors.white,
                  disabledBackgroundColor: Colors.blueGrey,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ),

            const SizedBox(height: 12),

            SizedBox(
              height: 50,
              child: OutlinedButton.icon(
                onPressed: _loading || !_connected
                    ? null
                    : _disconnect,
                icon: const Icon(Icons.link_off),
                label: const Text('DISCONNECT DEVICE'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.redAccent,
                  side: const BorderSide(color: Colors.redAccent),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ),

            const SizedBox(height: 24),

            _sectionTitle('CONNECTION STATUS'),

            const SizedBox(height: 12),

            Container(
              padding: const EdgeInsets.all(18),
              decoration: BoxDecoration(
                color: const Color(0xFF1B2B43),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(
                  color: _statusColor.withValues(alpha: 0.6),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        _connected
                            ? Icons.check_circle
                            : _authenticationRequired
                                ? Icons.lock
                                : Icons.circle,
                        color: _statusColor,
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          _statusText,
                          style: TextStyle(
                            color: _statusColor,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                      if (_loading)
                        const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                          ),
                        ),
                    ],
                  ),

                  if (_connectionInfo?.host != null) ...[
                    const SizedBox(height: 14),
                    _infoRow('IP address', _connectionInfo!.host!),
                    _infoRow(
                      'TCP port',
                      _connectionInfo!.port?.toString() ?? '-',
                    ),
                  ],

                  if (_connectionInfo?.deviceBanner != null) ...[
                    _infoRow(
                      'Device',
                      _connectionInfo!.deviceBanner!,
                    ),
                  ],

                  if (_connectionInfo?.features.isNotEmpty == true) ...[
                    const SizedBox(height: 10),
                    const Text(
                      'ADB Features',
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      _connectionInfo!.features.join(', '),
                      style: const TextStyle(color: Colors.white70),
                    ),
                  ],

                  if (_authenticationRequired) ...[
                    const SizedBox(height: 12),
                    const Text(
                      'Check the target phone and approve the ADB '
                      'authorization request if one is displayed.',
                      style: TextStyle(
                        color: Colors.orangeAccent,
                        height: 1.5,
                      ),
                    ),
                  ],
                ],
              ),
            ),

            if (_message != null) ...[
              const SizedBox(height: 14),
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: Colors.red.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  _message!,
                  style: const TextStyle(
                    color: Colors.redAccent,
                    height: 1.4,
                  ),
                ),
              ),
            ],

            const SizedBox(height: 24),

            _sectionTitle('IMPORTANT INFORMATION'),

            const SizedBox(height: 10),

            const Text(
              '• Both phones must be connected to the same reachable '
              'network.\n'
              '• The target phone must already have ADB TCP enabled.\n'
              '• Port 5555 is a common legacy ADB TCP port, but the '
              'target may use another port.\n'
              '• Some Android versions require USB or an existing '
              'ADB session to enable legacy TCP mode.\n'
              '• This screen does not yet implement Android 11+ '
              'Wireless Debugging pairing codes.',
              style: TextStyle(
                color: Colors.white70,
                height: 1.7,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionTitle(String text) {
    return Text(
      text,
      style: const TextStyle(
        color: Colors.lightBlueAccent,
        fontWeight: FontWeight.bold,
        letterSpacing: 1.1,
      ),
    );
  }

  Widget _inputField({
    required TextEditingController controller,
    required String label,
    required String hint,
    required IconData icon,
    required TextInputType keyboardType,
  }) {
    return TextField(
      controller: controller,
      keyboardType: keyboardType,
      style: const TextStyle(color: Colors.white),
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        prefixIcon: Icon(icon, color: Colors.lightBlueAccent),
        labelStyle: const TextStyle(color: Colors.white70),
        hintStyle: const TextStyle(color: Colors.white38),
        filled: true,
        fillColor: const Color(0xFF1B2B43),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide.none,
        ),
      ),
    );
  }

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(top: 9),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 105,
            child: Text(
              label,
              style: const TextStyle(color: Colors.white54),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }
}
