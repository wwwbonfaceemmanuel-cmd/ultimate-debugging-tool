import 'dart:io';
import 'package:flutter/material.dart';

class DiagnosticsScreen extends StatelessWidget {
  const DiagnosticsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final details = {
      'Platform': Platform.operatingSystem,
      'OS version': Platform.operatingSystemVersion,
      'Hostname': Platform.localHostname,
      'Architecture': Platform.version.split(' ').first,
    };

    return Scaffold(
      appBar: AppBar(
        title: const Text('Diagnostics'),
        backgroundColor: const Color(0xFF122235),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text(
              'DEVICE DIAGNOSTICS',
              style: TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Basic information about the phone running this application.',
              style: TextStyle(color: Colors.white70),
            ),
            const SizedBox(height: 20),
            ...details.entries.map(
              (entry) => Card(
                color: const Color(0xFF172638),
                child: ListTile(
                  title: Text(
                    entry.key,
                    style: const TextStyle(color: Colors.white60),
                  ),
                  subtitle: SelectableText(
                    entry.value,
                    style: const TextStyle(color: Colors.white),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),
            const Card(
              color: Color(0xFF172638),
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  'Connected-device diagnostics, battery, memory, '
                  'storage and network checks will be implemented '
                  'in subsequent development.',
                  style: TextStyle(
                    color: Colors.white70,
                    height: 1.5,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
