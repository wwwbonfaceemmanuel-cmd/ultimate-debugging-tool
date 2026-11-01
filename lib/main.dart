
import 'package:flutter/material.dart';

void main() {
  runApp(const UltimateDebuggingTool());
}

class UltimateDebuggingTool extends StatelessWidget {
  const UltimateDebuggingTool({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Ultimate Debugging Tool',
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF080F1D),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF38BDF8),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const DashboardScreen(),
    );
  }
}

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color(0xFF080F1D),
        title: const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'ULTIMATE',
              style: TextStyle(
                fontSize: 19,
                fontWeight: FontWeight.bold,
                letterSpacing: 2,
              ),
            ),
            Text(
              'DEBUGGING TOOL',
              style: TextStyle(
                color: Color(0xFF38BDF8),
                fontSize: 12,
                letterSpacing: 2,
              ),
            ),
          ],
        ),
        actions: const [
          Padding(
            padding: EdgeInsets.only(right: 16),
            child: Icon(
              Icons.shield_outlined,
              color: Color(0xFF38BDF8),
              size: 29,
            ),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'ANDROID SERVICING WORKSTATION',
              style: TextStyle(
                color: Colors.blueGrey,
                fontSize: 10,
                letterSpacing: 1.5,
              ),
            ),
            const SizedBox(height: 22),

            _connectionCard(),

            const SizedBox(height: 28),

            const Text(
              'DIAGNOSTIC TOOLS',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 15,
                letterSpacing: 1,
              ),
            ),

            const SizedBox(height: 15),

            GridView.count(
              crossAxisCount: 2,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              crossAxisSpacing: 12,
              mainAxisSpacing: 12,
              childAspectRatio: 1.35,
              children: const [
                ToolCard(
                  icon: Icons.usb,
                  title: 'USB Manager',
                  subtitle: 'Cable connection',
                ),
                ToolCard(
                  icon: Icons.wifi,
                  title: 'Wireless ADB',
                  subtitle: 'Wi-Fi debugging',
                ),
                ToolCard(
                  icon: Icons.health_and_safety,
                  title: 'Diagnostics',
                  subtitle: 'Device inspection',
                ),
                ToolCard(
                  icon: Icons.apps,
                  title: 'APK Manager',
                  subtitle: 'App management',
                ),
                ToolCard(
                  icon: Icons.terminal,
                  title: 'Command Terminal',
                  subtitle: 'ADB commands',
                ),
                ToolCard(
                  icon: Icons.build,
                  title: 'Repair Tools',
                  subtitle: 'Repair solutions',
                ),
                ToolCard(
                  icon: Icons.folder,
                  title: 'File Manager',
                  subtitle: 'Device files',
                ),
                ToolCard(
                  icon: Icons.security,
                  title: 'Security Center',
                  subtitle: 'Security checks',
                ),
              ],
            ),

            const SizedBox(height: 25),

            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(18),
              decoration: BoxDecoration(
                color: const Color(0xFF142238),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(
                  color: const Color(0xFF263449),
                ),
              ),
              child: const Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        Icons.monitor_heart,
                        color: Color(0xFF38BDF8),
                      ),
                      SizedBox(width: 10),
                      Text(
                        'SYSTEM STATUS',
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                  SizedBox(height: 15),
                  Text(
                    'Technician device: Ready',
                    style: TextStyle(color: Colors.greenAccent),
                  ),
                  SizedBox(height: 7),
                  Text(
                    'Target device: Not connected',
                    style: TextStyle(color: Colors.orangeAccent),
                  ),
                  SizedBox(height: 7),
                  Text(
                    'Debugging engine: Initializing',
                    style: TextStyle(color: Colors.blueGrey),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 25),

            const Center(
              child: Text(
                'ULTIMATE DEBUGGING TOOL • ADVANCED EDITION',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.blueGrey,
                  fontSize: 9,
                  letterSpacing: 1,
                ),
              ),
            ),

            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }

  Widget _connectionCard() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: const Color(0xFF142238),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: const Color(0xFF1E4261),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'DEVICE CONNECTION',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                ),
              ),
              Icon(
                Icons.bluetooth_connected,
                color: Color(0xFF38BDF8),
              ),
            ],
          ),
          const SizedBox(height: 22),
          const Center(
            child: Icon(
              Icons.phonelink,
              size: 65,
              color: Color(0xFF38BDF8),
            ),
          ),
          const SizedBox(height: 15),
          const Center(
            child: Text(
              'No device connected',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          const SizedBox(height: 8),
          const Text(
            'Connect another smartphone using USB OTG '
            'or Wireless Debugging.',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: Colors.blueGrey,
              fontSize: 12,
            ),
          ),
          const SizedBox(height: 20),
          SizedBox(
            width: double.infinity,
            height: 48,
            child: ElevatedButton.icon(
              onPressed: () {},
              icon: const Icon(Icons.add_link),
              label: const Text('CONNECT DEVICE'),
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF147DB6),
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class ToolCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;

  const ToolCard({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFF142238),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: const Color(0xFF263449),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            icon,
            color: const Color(0xFF38BDF8),
            size: 27,
          ),
          const SizedBox(height: 10),
          Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            subtitle,
            maxLines: 1,
            style: const TextStyle(
              color: Colors.blueGrey,
              fontSize: 10,
            ),
          ),
        ],
      ),
    );
  }
}
