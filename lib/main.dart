
import 'package:flutter/material.dart';

import 'screens/usb_manager_screen.dart';
import 'screens/adb_manager_screen.dart';

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
        scaffoldBackgroundColor: const Color(0xFF0B1522),
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blue,
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

  void _openScreen(BuildContext context, Widget screen) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => screen),
    );
  }

  @override
  Widget build(BuildContext context) {
    final tools = [
      ToolItem(
        title: 'USB Manager',
        description: 'Manage connected USB devices and permissions.',
        icon: Icons.usb,
        color: Colors.blue,
        onTap: () => _openScreen(
          context,
          const UsbManagerScreen(),
        ),
      ),
      ToolItem(
        title: 'ADB Manager',
        description: 'Detect ADB interfaces and inspect USB endpoints.',
        icon: Icons.phonelink,
        color: Colors.green,
        onTap: () => _openScreen(
          context,
          const AdbManagerScreen(),
        ),
      ),
      ToolItem(
        title: 'Wireless ADB',
        description: 'Wireless Android debugging tools.',
        icon: Icons.wifi,
        color: Colors.cyan,
      ),
      ToolItem(
        title: 'Diagnostics',
        description: 'Inspect device information and system status.',
        icon: Icons.monitor_heart,
        color: Colors.orange,
      ),
      ToolItem(
        title: 'APK Manager',
        description: 'Android application management tools.',
        icon: Icons.android,
        color: Colors.lightGreen,
      ),
      ToolItem(
        title: 'Command Terminal',
        description: 'Command execution interface.',
        icon: Icons.terminal,
        color: Colors.purple,
      ),
      ToolItem(
        title: 'Repair Tools',
        description: 'Android troubleshooting and repair utilities.',
        icon: Icons.build,
        color: Colors.redAccent,
      ),
      ToolItem(
        title: 'File Manager',
        description: 'Browse and manage accessible files.',
        icon: Icons.folder,
        color: Colors.amber,
      ),
      ToolItem(
        title: 'Security Center',
        description: 'Device security and protection tools.',
        icon: Icons.security,
        color: Colors.teal,
      ),
    ];

    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color(0xFF122235),
        title: const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'ULTIMATE DEBUGGING TOOL',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 17,
              ),
            ),
            Text(
              'ANDROID SERVICING WORKSTATION',
              style: TextStyle(
                fontSize: 10,
                color: Colors.white60,
                letterSpacing: 1.2,
              ),
            ),
          ],
        ),
      ),

      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildConnectionCard(),

              const SizedBox(height: 24),

              const Text(
                'SERVICING TOOLS',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 1,
                ),
              ),

              const SizedBox(height: 14),

              GridView.builder(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: tools.length,
                gridDelegate:
                    const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 2,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 12,
                  childAspectRatio: 0.95,
                ),
                itemBuilder: (context, index) {
                  final tool = tools[index];

                  return ToolCard(
                    title: tool.title,
                    description: tool.description,
                    icon: tool.icon,
                    color: tool.color,
                    onTap: tool.onTap,
                  );
                },
              ),

              const SizedBox(height: 24),

              _buildSystemStatus(),

              const SizedBox(height: 20),

              const Center(
                child: Text(
                  'ULTIMATE DEBUGGING TOOL',
                  style: TextStyle(
                    color: Colors.white38,
                    fontSize: 11,
                    letterSpacing: 1.5,
                  ),
                ),
              ),

              const SizedBox(height: 8),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildConnectionCard() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [
            Color(0xFF173B5B),
            Color(0xFF10243A),
          ],
        ),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: Colors.blueAccent.withOpacity(0.35),
        ),
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.devices,
                color: Colors.lightBlueAccent,
                size: 30,
              ),
              SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Device Connection',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),

          SizedBox(height: 12),

          Text(
            'Connect an Android device using USB OTG or wireless '
            'debugging to begin servicing.',
            style: TextStyle(
              color: Colors.white70,
              height: 1.5,
              fontSize: 13,
            ),
          ),

          SizedBox(height: 14),

          Row(
            children: [
              Icon(
                Icons.circle,
                color: Colors.orangeAccent,
                size: 10,
              ),
              SizedBox(width: 8),
              Text(
                'Waiting for device connection',
                style: TextStyle(
                  color: Colors.orangeAccent,
                  fontSize: 12,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildSystemStatus() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF172638),
        borderRadius: BorderRadius.circular(14),
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'SYSTEM STATUS',
            style: TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.bold,
            ),
          ),

          SizedBox(height: 16),

          StatusItem(
            title: 'USB Manager',
            status: 'Interface available',
            color: Colors.greenAccent,
          ),

          SizedBox(height: 12),

          StatusItem(
            title: 'ADB Manager',
            status: 'Interface detection module',
            color: Colors.orangeAccent,
          ),

          SizedBox(height: 12),

          StatusItem(
            title: 'Wireless ADB',
            status: 'Not yet implemented',
            color: Colors.orangeAccent,
          ),

          SizedBox(height: 12),

          StatusItem(
            title: 'Device Connection',
            status: 'Waiting for device',
            color: Colors.orangeAccent,
          ),
        ],
      ),
    );
  }
}

class ToolItem {
  final String title;
  final String description;
  final IconData icon;
  final Color color;
  final VoidCallback? onTap;

  const ToolItem({
    required this.title,
    required this.description,
    required this.icon,
    required this.color,
    this.onTap,
  });
}

class ToolCard extends StatelessWidget {
  final String title;
  final String description;
  final IconData icon;
  final Color color;
  final VoidCallback? onTap;

  const ToolCard({
    super.key,
    required this.title,
    required this.description,
    required this.icon,
    required this.color,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: const Color(0xFF172638),
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: Colors.white10,
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                icon,
                color: color,
                size: 32,
              ),

              const Spacer(),

              Text(
                title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                ),
              ),

              const SizedBox(height: 6),

              Text(
                description,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: Colors.white60,
                  fontSize: 10,
                  height: 1.4,
                ),
              ),

              const SizedBox(height: 8),

              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  Icon(
                    onTap == null
                        ? Icons.lock_outline
                        : Icons.arrow_forward,
                    size: 15,
                    color: onTap == null
                        ? Colors.white30
                        : color,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class StatusItem extends StatelessWidget {
  final String title;
  final String status;
  final Color color;

  const StatusItem({
    super.key,
    required this.title,
    required this.status,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(
          Icons.circle,
          size: 9,
          color: color,
        ),

        const SizedBox(width: 10),

        Expanded(
          child: Text(
            title,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 12,
            ),
          ),
        ),

        Text(
          status,
          style: TextStyle(
            color: color,
            fontSize: 11,
          ),
        ),
      ],
    );
  }
}
