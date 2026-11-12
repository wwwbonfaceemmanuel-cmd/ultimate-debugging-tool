import 'package:material_ui/material_ui.dart';

import 'app/app_theme.dart';
import 'screens/apk_manager_screen.dart';
import 'screens/command_terminal_screen.dart';
import 'screens/connection_screen.dart';
import 'screens/diagnostics_screen.dart';
import 'screens/file_manager_screen.dart';
import 'screens/repair_history_screen.dart';
import 'screens/repair_tools_screen.dart';
import 'screens/security_center_screen.dart';
import 'screens/target_apps_screen.dart';
import 'services/device_bridge.dart';

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
      theme: buildAppTheme(),
      home: const DashboardScreen(),
    );
  }
}

class ToolItem {
  final String title;
  final String description;
  final IconData icon;
  final Color color;
  final WidgetBuilder builder;

  /// True when the tool works on the connected phone.
  final bool needsPhone;

  const ToolItem({
    required this.title,
    required this.description,
    required this.icon,
    required this.color,
    required this.builder,
    this.needsPhone = true,
  });
}

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  static final List<ToolItem> _phoneTools = [
    ToolItem(
      title: 'Diagnostics',
      description: 'Battery, storage, memory, network and more.',
      icon: Icons.monitor_heart,
      color: Colors.teal,
      builder: (context) => const DiagnosticsScreen(),
    ),
    ToolItem(
      title: 'Repair Tools',
      description: 'Guided fixes that ask before they run.',
      icon: Icons.build_circle,
      color: Colors.orange,
      builder: (context) => const RepairToolsScreen(),
    ),
    ToolItem(
      title: 'Apps',
      description: 'Inspect, back up, stop or remove apps.',
      icon: Icons.apps,
      color: Colors.green,
      builder: (context) => const TargetAppsScreen(),
    ),
    ToolItem(
      title: 'File Manager',
      description: 'Browse storage and copy files.',
      icon: Icons.folder_copy,
      color: Colors.amber,
      builder: (context) => const FileManagerScreen(),
    ),
    ToolItem(
      title: 'Security Center',
      description: 'Read-only security audit with a score.',
      icon: Icons.shield,
      color: Colors.redAccent,
      builder: (context) => const SecurityCenterScreen(),
    ),
    ToolItem(
      title: 'Command Terminal',
      description: 'Read-only inspection commands.',
      icon: Icons.terminal,
      color: Colors.blueGrey,
      builder: (context) => const CommandTerminalScreen(),
    ),
  ];

  static final List<ToolItem> _workstationTools = [
    ToolItem(
      title: 'Repair History',
      description: 'The service record, with export.',
      icon: Icons.history,
      color: Colors.purple,
      needsPhone: false,
      builder: (context) => const RepairHistoryScreen(),
    ),
    ToolItem(
      title: 'Apps on This Phone',
      description: 'Apps and APK backups of this phone.',
      icon: Icons.smartphone,
      color: Colors.indigo,
      needsPhone: false,
      builder: (context) => const ApkManagerScreen(),
    ),
  ];

  final DeviceBridge _bridge = DeviceBridge.instance;

  @override
  void initState() {
    super.initState();
    _bridge.start();
  }

  void _open(WidgetBuilder builder) {
    Navigator.push(context, MaterialPageRoute<void>(builder: builder));
  }

  Widget _buildConnectionCard(AdbStatus status) {
    final Color color;
    final IconData icon;

    if (status.connected) {
      color = AppColors.ok;
      icon = status.isWireless ? Icons.wifi : Icons.usb;
    } else if (status.connecting) {
      color = AppColors.warning;
      icon = Icons.hourglass_top;
    } else {
      color = AppColors.info;
      icon = Icons.phonelink_off;
    }

    final device = status.device;
    final facts = <String>[
      if ((device['android'] ?? '').isNotEmpty) 'Android ${device['android']}',
      if ((device['securityPatch'] ?? '').isNotEmpty)
        'Patch ${device['securityPatch']}',
      if ((device['serial'] ?? '').isNotEmpty) 'Serial ${device['serial']}',
    ];

    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => openConnectionScreen(context),
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: color.withAlpha(150)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: color.withAlpha(36),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Icon(icon, color: color, size: 28),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          status.title,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 17,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 3),
                        Text(
                          status.subtitle,
                          style: const TextStyle(
                            color: Colors.white70,
                            height: 1.3,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              if (status.connected && facts.isNotEmpty) ...[
                const SizedBox(height: 12),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final fact in facts)
                      Tag(fact, color: AppColors.textMuted),
                  ],
                ),
              ],
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton.tonalIcon(
                  onPressed: () => openConnectionScreen(context),
                  icon: Icon(status.connected ? Icons.settings_ethernet : Icons.link),
                  label: Text(
                    status.connected
                        ? 'Manage connection'
                        : (status.connecting ? 'View progress' : 'Connect a phone'),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildGrid(List<ToolItem> tools, AdbStatus status) {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 240,
        mainAxisExtent: 136,
        crossAxisSpacing: 10,
        mainAxisSpacing: 10,
      ),
      itemCount: tools.length,
      itemBuilder: (context, index) {
        final tool = tools[index];
        final waiting = tool.needsPhone && !status.connected;

        return Material(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          child: InkWell(
            borderRadius: BorderRadius.circular(14),
            onTap: () => _open(tool.builder),
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        tool.icon,
                        color: waiting ? tool.color.withAlpha(120) : tool.color,
                        size: 28,
                      ),
                      const Spacer(),
                      if (waiting)
                        const Icon(
                          Icons.phonelink_off,
                          size: 16,
                          color: Colors.white30,
                        ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text(
                    tool.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: waiting ? Colors.white70 : Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 15,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Expanded(
                    child: Text(
                      tool.description,
                      maxLines: 3,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white60,
                        fontSize: 12.5,
                        height: 1.25,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Ultimate Debugging Tool'),
      ),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              _buildConnectionCard(status),
              const SectionTitle('Connected phone'),
              _buildGrid(_phoneTools, status),
              const SectionTitle('This workstation'),
              _buildGrid(_workstationTools, status),
              const SizedBox(height: 20),
              const Text(
                'Use this tool only on phones you own or have been asked '
                'to service. It does not remove screen locks or account '
                'locks.',
                textAlign: TextAlign.center,
                style: TextStyle(color: AppColors.textMuted, fontSize: 12.5),
              ),
            ],
          );
        },
      ),
    );
  }
}
