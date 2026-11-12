import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import '../services/device_bridge.dart';

/// The colours of the app, in one place.
class AppColors {
  AppColors._();

  static const Color background = Color(0xFF0B1522);
  static const Color surface = Color(0xFF172638);
  static const Color surfaceDeep = Color(0xFF122235);
  static const Color console = Color(0xFF0B1424);
  static const Color border = Color(0xFF26384F);
  static const Color textMuted = Color(0xFF9FB0C3);

  static const Color ok = Colors.greenAccent;
  static const Color warning = Colors.orangeAccent;
  static const Color danger = Colors.redAccent;
  static const Color info = Colors.lightBlueAccent;

  static Color forRisk(String risk) {
    switch (risk.toUpperCase()) {
      case 'HIGH':
      case 'CRITICAL':
        return danger;
      case 'MEDIUM':
      case 'WARNING':
        return warning;
      case 'OK':
        return ok;
      case 'INFO':
        return info;
      default:
        return ok;
    }
  }
}

ThemeData buildAppTheme() {
  return ThemeData(
    brightness: Brightness.dark,
    scaffoldBackgroundColor: AppColors.background,
    colorScheme: ColorScheme.fromSeed(
      seedColor: Colors.blue,
      brightness: Brightness.dark,
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: AppColors.background,
      foregroundColor: Colors.white,
    ),
    useMaterial3: true,
  );
}

void showMessage(BuildContext context, String message, {bool error = false}) {
  final messenger = ScaffoldMessenger.maybeOf(context);
  if (messenger == null) return;

  messenger
    ..hideCurrentSnackBar()
    ..showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: error ? const Color(0xFF7A2630) : null,
        duration: Duration(seconds: error ? 6 : 3),
      ),
    );
}

Future<void> copyText(BuildContext context, String text) async {
  await Clipboard.setData(ClipboardData(text: text));
  if (!context.mounted) return;
  showMessage(context, 'Copied.');
}

/// Asks the technician to confirm. Returns true only for "yes".
Future<bool> confirmAction(
  BuildContext context, {
  required String title,
  required String message,
  String confirmLabel = 'Continue',
  bool dangerous = false,
}) async {
  final answer = await showDialog<bool>(
    context: context,
    builder: (dialogContext) {
      return AlertDialog(
        backgroundColor: AppColors.surface,
        title: Text(title, style: const TextStyle(color: Colors.white)),
        content: SingleChildScrollView(
          child: Text(
            message,
            style: const TextStyle(color: Colors.white70, height: 1.4),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: dangerous
                ? FilledButton.styleFrom(backgroundColor: Colors.red.shade700)
                : null,
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(confirmLabel),
          ),
        ],
      );
    },
  );

  return answer == true;
}

/// A rounded card used for every block of content.
class Panel extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry padding;
  final Color color;
  final Color? borderColor;

  const Panel({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
    this.color = AppColors.surface,
    this.borderColor,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: padding,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: borderColor ?? AppColors.border),
      ),
      child: child,
    );
  }
}

class SectionTitle extends StatelessWidget {
  final String text;

  const SectionTitle(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 18, bottom: 8),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
          color: AppColors.textMuted,
          fontSize: 12,
          fontWeight: FontWeight.bold,
          letterSpacing: 1.1,
        ),
      ),
    );
  }
}

class Tag extends StatelessWidget {
  final String text;
  final Color color;

  const Tag(this.text, {super.key, this.color = AppColors.info});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withAlpha(36),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color.withAlpha(140)),
      ),
      child: Text(
        text,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }
}

/// A message with an icon: used for empty lists, errors and hints.
class Notice extends StatelessWidget {
  final IconData icon;
  final String title;
  final String message;
  final Color color;
  final String? actionLabel;
  final VoidCallback? onAction;

  const Notice({
    super.key,
    required this.icon,
    required this.title,
    this.message = '',
    this.color = AppColors.info,
    this.actionLabel,
    this.onAction,
  });

  @override
  Widget build(BuildContext context) {
    final label = actionLabel;

    return Panel(
      borderColor: color.withAlpha(120),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, color: color),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        fontSize: 15,
                      ),
                    ),
                    if (message.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        message,
                        style: const TextStyle(
                          color: Colors.white70,
                          height: 1.35,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
          if (label != null && onAction != null) ...[
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.tonal(
                onPressed: onAction,
                child: Text(label),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// Monospace text block for reports and command output.
class ReportView extends StatelessWidget {
  final String text;

  const ReportView(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Panel(
      color: AppColors.console,
      padding: const EdgeInsets.all(12),
      child: SelectableText(
        text,
        style: const TextStyle(
          color: Color(0xFFD6E2F0),
          fontFamily: 'monospace',
          fontSize: 12.5,
          height: 1.35,
        ),
      ),
    );
  }
}

/// Shows which phone a screen is working on, and updates by itself when
/// the phone is connected or unplugged.
class TargetBanner extends StatelessWidget {
  final VoidCallback? onConnect;

  const TargetBanner({super.key, this.onConnect});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<AdbStatus>(
      valueListenable: DeviceBridge.instance.status,
      builder: (context, status, _) {
        final Color color;
        final IconData icon;

        if (status.connected) {
          color = AppColors.ok;
          icon = status.isWireless ? Icons.wifi : Icons.usb;
        } else if (status.connecting) {
          color = AppColors.warning;
          icon = Icons.hourglass_top;
        } else {
          color = AppColors.danger;
          icon = Icons.link_off;
        }

        return Panel(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          borderColor: color.withAlpha(140),
          child: Row(
            children: [
              Icon(icon, color: color),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      status.title,
                      style: const TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      status.subtitle,
                      style: const TextStyle(
                        color: Colors.white70,
                        fontSize: 12.5,
                      ),
                    ),
                  ],
                ),
              ),
              if (!status.connected && !status.connecting && onConnect != null)
                TextButton(onPressed: onConnect, child: const Text('Connect')),
            ],
          ),
        );
      },
    );
  }
}

/// Shown in place of a tool when it needs a connected phone.
class NeedsPhone extends StatelessWidget {
  final String what;
  final VoidCallback? onConnect;

  const NeedsPhone({super.key, required this.what, this.onConnect});

  @override
  Widget build(BuildContext context) {
    return Notice(
      icon: Icons.phonelink_off,
      color: AppColors.warning,
      title: 'Connect a phone first',
      message: '$what works on the phone being serviced. '
          'Connect it by USB cable or Wi-Fi, then come back.',
      actionLabel: onConnect == null ? null : 'Open Connection',
      onAction: onConnect,
    );
  }
}
