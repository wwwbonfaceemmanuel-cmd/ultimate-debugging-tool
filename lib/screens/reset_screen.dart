import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import 'connection_screen.dart';
import '../services/device_bridge.dart';

/// Walks the technician through resetting a phone that cannot be used
/// any more (forgotten PIN, dead account, a phone that will not start).
///
/// It never removes a lock. Android encrypts the data with the lock
/// credential, so a reset is the only honest answer, and after the
/// reset the phone still asks for the account that was on it.
class ResetScreen extends StatefulWidget {
  const ResetScreen({super.key});

  @override
  State<ResetScreen> createState() => _ResetScreenState();
}

class _ResetScreenState extends State<ResetScreen> {
  final DeviceBridge _bridge = DeviceBridge.instance;

  bool _approved = false;
  bool _running = false;
  bool _recorded = false;
  bool _attesting = false;
  String _attestationResult = '';
  final _techCtrl = TextEditingController();
  bool _ownerPresent = false;
  bool _idChecked = false;
  bool _receiptChecked = false;
  final _notesCtrl = TextEditingController();

  @override
  void dispose() {
    _techCtrl.dispose();
    _notesCtrl.dispose();
    super.dispose();
  }

  Future<void> _recordAttestation() async {
    if (_techCtrl.text.trim().isEmpty) {
      showMessage(context, 'Enter technician name.', error: true);
      return;
    }
    setState(() => _attesting = true);
    try {
      final res = await _bridge.recordOwnershipAttestation(
        technicianName: _techCtrl.text.trim(),
        ownerPresent: _ownerPresent,
        idChecked: _idChecked,
        receiptChecked: _receiptChecked,
        notes: _notesCtrl.text.trim(),
      );
      if (!mounted) return;
      setState(() {
        _attestationResult = 'Consent saved: ${res['receiptPath'] ?? ''}\nLinks: ${res['links']}';
        _attesting = false;
      });
      showMessage(context, 'Ownership attestation recorded.');
      await _bridge.saveHistory(operation: 'Ownership attestation', category: 'Reset', result: 'Completed', details: _attestationResult, onTarget: true);
    } catch (e) {
      if (!mounted) return;
      setState(() => _attesting = false);
      showMessage(context, describeError(e), error: true);
    }
  }

  Future<void> _restartIntoRecovery() async {
    final agreed = await confirmAction(
      context,
      title: 'Restart into recovery?',
      message: 'The phone will restart into its recovery screen. Nothing '
          'is erased until someone chooses "Wipe data/factory reset" '
          'there, so you can still change your mind.',
      confirmLabel: 'Restart',
      dangerous: true,
    );

    if (!agreed || !mounted) return;

    setState(() => _running = true);

    try {
      final outcome = await _bridge.runRepair('reboot-recovery');
      if (!mounted) return;
      setState(() => _running = false);
      showMessage(
        context,
        outcome.message.isEmpty
            ? 'The phone is restarting into recovery.'
            : outcome.message,
      );
    } catch (error) {
      if (!mounted) return;
      setState(() => _running = false);
      showMessage(context, describeError(error), error: true);
    }
  }

  Future<void> _recordReset() async {
    await _bridge.saveHistory(
      operation: 'Factory reset',
      category: 'Reset',
      result: 'Completed',
      details: 'Wiped from recovery. On first start the phone asks for '
          'the Google account that was on it.',
      onTarget: true,
    );

    if (!mounted) return;
    setState(() => _recorded = true);
    showMessage(context, 'The reset is in the service history.');
  }

  Widget _buildSteps() {
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: const [
          Text(
            'In the recovery screen',
            style: TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.bold,
              fontSize: 15,
            ),
          ),
          SizedBox(height: 8),
          Text(
            '1. Use the volume keys to move and the power key to choose.\n'
            '2. Choose "Wipe data/factory reset", then confirm.\n'
            '3. Choose "Wipe cache partition" if it is offered.\n'
            '4. Choose "Reboot system now".',
            style: TextStyle(color: Colors.white70, height: 1.5),
          ),
          SizedBox(height: 10),
          Text(
            'Some phones hide recovery behind a key combination: hold '
            'power and volume down while the phone is off, then release '
            'when the logo appears.',
            style: TextStyle(
              color: AppColors.textMuted,
              fontSize: 12.5,
              height: 1.35,
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Reset & Wipe')),
      body: ValueListenableBuilder<AdbStatus>(
        valueListenable: _bridge.status,
        builder: (context, status, _) {
          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
            children: [
              const TargetBanner(),
              const SizedBox(height: 12),
              const Notice(
                icon: Icons.lock_outline,
                color: AppColors.warning,
                title: 'This tool does not remove locks',
                message: 'Android encrypts the phone with its PIN, pattern '
                    'or password. Without that credential the data cannot be '
                    'read, so a reset is the only way forward. After a reset '
                    'the phone asks for the Google account that was on it, '
                    'and that check cannot be bypassed by this tool or any '
                    'other.',
              ),
              const SectionTitle('Before you reset'),
              const Panel(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'A reset erases everything on the phone. Save what can '
                      'still be saved first:',
                      style: TextStyle(color: Colors.white70, height: 1.35),
                    ),
                    SizedBox(height: 8),
                    Text(
                      '- Photos, documents and downloads: File Manager.\n'
                      '- App installers the customer may need: Apps, then '
                      'back up the APK.\n'
                      '- Reports and this session: Repair History.',
                      style: TextStyle(color: Colors.white70, height: 1.5),
                    ),
                  ],
                ),
              ),
              if (!status.connected) ...[
                const SizedBox(height: 12),
                Notice(
                  icon: Icons.phonelink_off,
                  color: AppColors.info,
                  title: 'Connect the phone to restart it',
                  message: 'The steps below work by hand. Connecting the '
                      'phone lets the tool restart it into recovery for you '
                      'and record the reset.',
                  actionLabel: 'Open Connection',
                  onAction: () => openConnectionScreen(context),
                ),
              ],
              const SectionTitle('Ownership verification (for customer-brought phones)'),
              Panel(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const Text('For any phone with FRP/lock, verify ownership before helping. This tool never bypasses locks — it only records that you checked.', style: TextStyle(color: Colors.white70, height: 1.35)),
                  const SizedBox(height: 10),
                  TextField(controller: _techCtrl, decoration: const InputDecoration(labelText: 'Technician name', border: OutlineInputBorder()), style: const TextStyle(color: Colors.white)),
                  const SizedBox(height: 8),
                  CheckboxListTile(value: _ownerPresent, onChanged: (v) => setState(() => _ownerPresent = v == true), title: const Text('Owner is present in person', style: TextStyle(color: Colors.white70, fontSize: 13)), contentPadding: EdgeInsets.zero, controlAffinity: ListTileControlAffinity.leading),
                  CheckboxListTile(value: _idChecked, onChanged: (v) => setState(() => _idChecked = v == true), title: const Text('Government ID checked', style: TextStyle(color: Colors.white70, fontSize: 13)), contentPadding: EdgeInsets.zero, controlAffinity: ListTileControlAffinity.leading),
                  CheckboxListTile(value: _receiptChecked, onChanged: (v) => setState(() => _receiptChecked = v == true), title: const Text('Purchase receipt / box IMEI matches', style: TextStyle(color: Colors.white70, fontSize: 13)), contentPadding: EdgeInsets.zero, controlAffinity: ListTileControlAffinity.leading),
                  TextField(controller: _notesCtrl, decoration: const InputDecoration(labelText: 'Notes (optional)', border: OutlineInputBorder()), style: const TextStyle(color: Colors.white), maxLines: 2),
                  const SizedBox(height: 10),
                  FilledButton.icon(onPressed: _attesting ? null : _recordAttestation, icon: _attesting ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.verified_user), label: const Text('Record attestation (writes consent file)')),
                  if (_attestationResult.isNotEmpty) ...[const SizedBox(height: 8), ReportView(_attestationResult)],
                  const SizedBox(height: 8),
                  const Text('Refused by this tool: flash frp partition, remove locksettings.db, bypass gatekeeper, patch SetupWizard. Guide owner to official account recovery instead.', style: TextStyle(color: AppColors.danger, fontSize: 12, height: 1.35)),
                  const SizedBox(height: 6),
                  Wrap(spacing: 8, children: [
                    Tag('Google FRP help', color: AppColors.info),
                    Tag('Samsung Find My Mobile', color: AppColors.info),
                    Tag('Mi Account unlock', color: AppColors.info),
                  ]),
                ]),
              ),
              const SectionTitle('Step 1: the owner agrees'),
              Panel(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // The tile needs its own Material: the Panel around it
                    // has a background colour, which would hide its ink.
                    Material(
                      type: MaterialType.transparency,
                      child: CheckboxListTile(
                        key: const Key('reset-approved'),
                        value: _approved,
                        onChanged: (value) =>
                            setState(() => _approved = value == true),
                        contentPadding: EdgeInsets.zero,
                        controlAffinity: ListTileControlAffinity.leading,
                        title: const Text(
                          'The owner of this phone has asked for a reset and '
                          'understands that all data on it will be erased.',
                          style: TextStyle(color: Colors.white70, fontSize: 13),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SectionTitle('Step 2: restart into recovery'),
              Panel(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _approved
                          ? 'The phone restarts into recovery. Nothing is '
                              'erased until the wipe is chosen there.'
                          : 'Tick the box above to enable this.',
                      style: const TextStyle(
                        color: Colors.white70,
                        height: 1.35,
                      ),
                    ),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      key: const Key('reset-restart'),
                      onPressed: (!_approved || !status.connected || _running)
                          ? null
                          : _restartIntoRecovery,
                      icon: const Icon(Icons.restart_alt),
                      label: const Text('Restart into recovery'),
                    ),
                  ],
                ),
              ),
              const SectionTitle('Step 3: wipe in recovery'),
              _buildSteps(),
              const SectionTitle('Step 4: record what you did'),
              Panel(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'The phone now starts as new, and asks for the Google '
                      'account that was on it. That is the owner\'s step, '
                      'not yours: they must sign in with their own account.',
                      style: TextStyle(color: Colors.white70, height: 1.35),
                    ),
                    const SizedBox(height: 12),
                    FilledButton.tonalIcon(
                      key: const Key('reset-record'),
                      onPressed: _recorded ? null : _recordReset,
                      icon: Icon(_recorded ? Icons.check : Icons.history),
                      label: Text(
                        _recorded ? 'Reset recorded' : 'Record the reset',
                      ),
                    ),
                  ],
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
