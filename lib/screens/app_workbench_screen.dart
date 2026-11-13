import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';

/// One app on the connected phone, looked at the way a PC debloat or
/// logcat script would: what it is, what it keeps running, why it
/// crashed, and the repairs that fix each case.
class AppWorkbenchScreen extends StatefulWidget {
  const AppWorkbenchScreen({super.key});

  @override
  State<AppWorkbenchScreen> createState() => _AppWorkbenchScreenState();
}

/// A repair that acts on one app, as offered on this screen.
class _AppAction {
  final String id;
  final String label;
  final String question;
  final String detail;

  const _AppAction({
    required this.id,
    required this.label,
    required this.question,
    required this.detail,
  });
}

const List<_AppAction> _appActions = <_AppAction>[
  _AppAction(
    id: 'clear-app-cache',
    label: 'Clear cache',
    question: 'Clear only the cache?',
    detail: 'Temporary files are deleted. Accounts, settings and the '
        'app\'s own files are kept.',
  ),
  _AppAction(
    id: 'uninstall-app-updates',
    label: 'Remove updates',
    question: 'Remove the updates of this built-in app?',
    detail: 'The app goes back to the version that came with the phone. '
        'Its data is kept, but the store may update it again.',
  ),
  _AppAction(
    id: 'recompile-app',
    label: 'Recompile',
    question: 'Rebuild this app\'s code?',
    detail: 'The phone recompiles the app for its own processor. Useful '
        'after an update made the app slow or unstable.',
  ),
  _AppAction(
    id: 'reset-app-settings',
    label: 'Reset settings',
    question: 'Reset this app\'s settings?',
    detail: 'Permissions and background restrictions return to their '
        'defaults. The app\'s data is kept.',
  ),
  _AppAction(
    id: 'fix-app-notifications-allow',
    label: 'Allow notifications',
    question: 'Allow notifications for this app?',
    detail: 'Fixes missing notifications via AppOps POST_NOTIFICATION allow. Real write.',
  ),
  _AppAction(
    id: 'fix-system-disable-safe',
    label: 'Disable system app',
    question: 'Disable this system app? (reversible)',
    detail: 'System app is disabled for this user — no uninstall. Protected cores are refused. Re-enable with Enable button.',
  ),
  _AppAction(
    id: 'fix-system-enable',
    label: 'Enable system app',
    question: 'Re-enable this system app?',
    detail: 'Restores a disabled system app.',
  ),
  _AppAction(
    id: 'fix-system-clear-cache',
    label: 'Clear system cache',
    question: 'Clear only cache of this system app?',
    detail: 'Removes temporary files only — data kept. Safe for system apps that cause lag.',
  ),
  _AppAction(
    id: 'fix-system-reset-perms',
    label: 'Reset system perms',
    question: 'Reset permissions for this system app?',
    detail: 'AppOps reset — app will ask again. Useful when system app misbehaves after update.',
  ),
  _AppAction(
    id: 'fix-app-standby',
    label: 'Unrestrict',
    question: 'Remove battery restriction?',
    detail: 'Sets standby bucket to active — real fix, not demo.',
  ),
];

class _AppWorkbenchScreenState extends State<AppWorkbenchScreen> {
  final DeviceBridge _bridge = DeviceBridge.instance;
  final TextEditingController _search = TextEditingController();

  List<TargetApp> _apps = <TargetApp>[];
  bool _loadingApps = false;
  bool _showSystem = true;

  TargetApp? _selected;
  AppDiagnosisReport? _report;
  bool _loadingReport = false;
  String? _error;
  String? _runningAction;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (_bridge.status.value.connected) {
        _loadApps();
      }
    });
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _loadApps() async {
    if (_loadingApps) return;
    setState(() {
      _loadingApps = true;
      _error = null;
    });

    try {
      final apps = await _bridge.getTargetApplications();
      if (!mounted) return;
      setState(() {
        _apps = apps;
        _loadingApps = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = describeError(error);
        _loadingApps = false;
      });
    }
  }

  Future<void> _inspect(TargetApp app) async {
    setState(() {
      _selected = app;
      _report = null;
      _loadingReport = true;
      _error = null;
    });

    try {
      final report = await _bridge.getAppDiagnosis(app.packageName);
      if (!mounted) return;
      setState(() {
        _report = report;
        _loadingReport = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = describeError(error);
        _loadingReport = false;
      });
    }
  }

  Future<void> _run(_AppAction action) async {
    final report = _report;
    if (report == null) return;

    final agreed = await confirmAction(
      context,
      title: action.question,
      message: '${action.detail}\n\nApp: ${report.packageName}',
      confirmLabel: 'Do it',
    );

    if (!agreed || !mounted) return;

    setState(() => _runningAction = action.id);

    try {
      final outcome = await _bridge.runRepair(
        action.id,
        packageName: report.packageName,
      );
      if (!mounted) return;
      setState(() => _runningAction = null);
      showMessage(context, outcome.message);

      // Read the app again, so the screen shows what changed.
      final app = _selected;
      if (app != null) await _inspect(app);
    } catch (error) {
      if (!mounted) return;
      setState(() => _runningAction = null);
      showMessage(context, describeError(error), error: true);
    }
  }

  List<TargetApp> get _visibleApps {
    final query = _search.text.trim().toLowerCase();

    return _apps.where((app) {
      if (!_showSystem && app.isSystemApp) return false;
      if (query.isEmpty) return true;
      return app.packageName.toLowerCase().contains(query);
    }).toList();
  }

  Widget _buildActions(AppDiagnosisReport report) {
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Repairs for this app',
            style: TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.bold,
              fontSize: 15,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            report.system
                ? 'This is a built-in app. These four repairs are safe for '
                    'built-in apps: none of them deletes the app or its data.'
                : 'Use these when the app misbehaves but its data must stay.',
            style: const TextStyle(
              color: AppColors.textMuted,
              fontSize: 12.5,
              height: 1.35,
            ),
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final action in _appActions)
                OutlinedButton(
                  key: Key('app-action-${action.id}'),
                  onPressed: _runningAction == null
                      ? () => _run(action)
                      : null,
                  child: Text(
                    _runningAction == action.id
                        ? 'Working…'
                        : action.label,
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildReport(AppDiagnosisReport report) {
    final color = report.hasProblems ? AppColors.warning : AppColors.ok;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Panel(
          borderColor: color.withAlpha(140),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    report.hasProblems ? Icons.report_problem : Icons.check_circle,
                    color: color,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      report.verdict,
                      style: const TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        height: 1.3,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  Tag(
                    report.system ? 'BUILT-IN APP' : 'USER APP',
                    color: report.system ? AppColors.info : AppColors.ok,
                  ),
                  if (!report.enabled)
                    Tag('DISABLED', color: AppColors.danger),
                  if (report.running)
                    Tag('RUNNING', color: AppColors.ok),
                  if (report.versionName.isNotEmpty)
                    Tag('v${report.versionName}', color: AppColors.textMuted),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _buildActions(report),
        const SectionTitle('What the phone reports'),
        ReportView(report.report),
        if (report.crashes.isNotEmpty) ...[
          const SectionTitle('Recent crashes'),
          ReportView(report.crashes.join('\n')),
        ],
      ],
    );
  }

  Widget _buildPicker() {
    final apps = _visibleApps;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        const TargetBanner(),
        const SizedBox(height: 12),
        if (_error != null) ...[
          Notice(
            icon: Icons.error_outline,
            color: AppColors.danger,
            title: 'The list did not load',
            message: _error!,
            actionLabel: 'Try again',
            onAction: _loadApps,
          ),
          const SizedBox(height: 12),
        ],
        Panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: _search,
                onChanged: (_) => setState(() {}),
                decoration: const InputDecoration(
                  labelText: 'Find an app',
                  hintText: 'Search by package name',
                  prefixIcon: Icon(Icons.search),
                ),
              ),
              const SizedBox(height: 4),
              Material(
                type: MaterialType.transparency,
                child: SwitchListTile(
                  value: _showSystem,
                  onChanged: (value) => setState(() => _showSystem = value),
                  contentPadding: EdgeInsets.zero,
                  title: const Text(
                    'Include built-in apps',
                    style: TextStyle(color: Colors.white70, fontSize: 13),
                  ),
                ),
              ),
            ],
          ),
        ),
        const SectionTitle('Apps on the connected phone'),
        if (_loadingApps)
          const Padding(
            padding: EdgeInsets.all(24),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (apps.isEmpty)
          const Notice(
            icon: Icons.apps,
            title: 'No app matches',
            message: 'Clear the search, or allow built-in apps.',
          )
        else
          for (final app in apps)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Material(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(12),
                child: InkWell(
                  borderRadius: BorderRadius.circular(12),
                  onTap: () => _inspect(app),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 12,
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Text(
                            app.packageName,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 13.5,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        if (app.isSystemApp)
                          const Tag('BUILT-IN', color: AppColors.info),
                        if (!app.enabled)
                          const Tag('OFF', color: AppColors.danger),
                        const Icon(
                          Icons.chevron_right,
                          color: AppColors.textMuted,
                          size: 20,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
      ],
    );
  }

  Widget _buildDetail(TargetApp app) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        Row(
          children: [
            IconButton(
              key: const Key('app-back'),
              onPressed: () => setState(() {
                _selected = null;
                _report = null;
                _error = null;
              }),
              icon: const Icon(Icons.arrow_back),
            ),
            Expanded(
              child: Text(
                app.packageName,
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                  fontSize: 15,
                ),
              ),
            ),
            IconButton(
              onPressed: () => _inspect(app),
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        if (_loadingReport) ...[
          const SizedBox(height: 12),
          const LinearProgressIndicator(),
        ],
        if (_error != null) ...[
          const SizedBox(height: 12),
          Notice(
            icon: Icons.error_outline,
            color: AppColors.danger,
            title: 'The app could not be read',
            message: _error!,
            actionLabel: 'Try again',
            onAction: () => _inspect(app),
          ),
        ],
        if (_report != null) ...[
          const SizedBox(height: 12),
          _buildReport(_report!),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('App Workbench')),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          if (!status.connected) {
            return ListView(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
              children: const [
                TargetBanner(),
                SizedBox(height: 12),
                NeedsPhone(what: 'The app workbench'),
              ],
            );
          }

          final app = _selected;
          return app == null ? _buildPicker() : _buildDetail(app);
        },
      ),
    );
  }
}
