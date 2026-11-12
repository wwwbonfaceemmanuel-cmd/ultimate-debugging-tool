import 'package:flutter/material.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';

/// The service record: every report, check and repair, with the phone
/// it was done on.
class RepairHistoryScreen extends StatefulWidget {
  const RepairHistoryScreen({super.key});

  @override
  State<RepairHistoryScreen> createState() => _RepairHistoryScreenState();
}

class _RepairHistoryScreenState extends State<RepairHistoryScreen> {
  static const Map<String, String> _filterLabels = {
    'ALL': 'All',
    'TARGET': 'Connected phones',
    'WORKSTATION': 'This phone',
    'FAILED': 'Failed',
  };

  final DeviceBridge _bridge = DeviceBridge.instance;

  List<HistoryRecord> _records = const <HistoryRecord>[];
  bool _loading = true;
  bool _exporting = false;
  String _error = '';
  String _filter = 'ALL';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = '';
    });

    try {
      final records = await _bridge.getHistory();
      if (!mounted) return;
      setState(() {
        _records = records;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = describeError(error);
        _loading = false;
      });
    }
  }

  List<HistoryRecord> get _visible {
    return _records.where((record) {
      switch (_filter) {
        case 'TARGET':
          return record.onTarget;
        case 'WORKSTATION':
          return !record.onTarget;
        case 'FAILED':
          return record.failed;
        default:
          return true;
      }
    }).toList();
  }

  Future<void> _export() async {
    if (_exporting || _records.isEmpty) return;
    setState(() => _exporting = true);

    try {
      await _bridge.exportHistory();
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  Future<void> _clear() async {
    final confirmed = await confirmAction(
      context,
      title: 'Delete the whole service record?',
      message: 'All ${_records.length} entries will be deleted from this '
          'phone. This cannot be undone. Export the record first if it '
          'may be needed.',
      confirmLabel: 'Delete all',
      dangerous: true,
    );

    if (!confirmed || !mounted) return;

    try {
      await _bridge.clearHistory();
      await _load();
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    }
  }

  String _deviceLine(HistoryRecord record) {
    if (!record.onTarget) return 'This phone';

    final parts = <String>[
      record.deviceLabel.isEmpty ? 'Connected phone' : record.deviceLabel,
      if (record.deviceSerial.isNotEmpty) 'serial ${record.deviceSerial}',
      if (record.link.isNotEmpty) record.link == 'WIRELESS' ? 'Wi-Fi' : record.link,
    ];

    return parts.join(' - ');
  }

  String _recordText(HistoryRecord record) {
    return '${formatDateTime(record.timestamp)}  ${record.result}\n'
        '${record.operation}\n'
        '${record.category}\n'
        '${_deviceLine(record)}\n\n'
        '${record.details}';
  }

  Widget _buildRecord(HistoryRecord record) {
    final color = record.failed ? AppColors.danger : AppColors.ok;

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        clipBehavior: Clip.antiAlias,
        child: Theme(
          data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
          child: ExpansionTile(
            leading: Icon(
              record.failed ? Icons.error : Icons.check_circle,
              color: color,
            ),
            title: Text(
              record.operation,
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                fontSize: 14.5,
              ),
            ),
            subtitle: Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${formatDateTime(record.timestamp)}  -  ${record.category}',
                    style: const TextStyle(
                      color: Colors.white60,
                      fontSize: 12.5,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Row(
                    children: [
                      Icon(
                        record.onTarget ? Icons.phonelink : Icons.smartphone,
                        size: 14,
                        color: AppColors.textMuted,
                      ),
                      const SizedBox(width: 4),
                      Expanded(
                        child: Text(
                          _deviceLine(record),
                          style: const TextStyle(
                            color: AppColors.textMuted,
                            fontSize: 12.5,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            childrenPadding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            expandedCrossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (record.details.isEmpty)
                const Text(
                  'No details were recorded.',
                  style: TextStyle(color: Colors.white60),
                )
              else
                ReportView(record.details),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  onPressed: () => copyText(context, _recordText(record)),
                  icon: const Icon(Icons.copy, size: 18),
                  label: const Text('Copy'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final visible = _visible;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Repair History'),
        actions: [
          IconButton(
            tooltip: 'Export as a text report',
            onPressed: _records.isEmpty || _exporting ? null : _export,
            icon: const Icon(Icons.ios_share),
          ),
          IconButton(
            tooltip: 'Delete all',
            onPressed: _records.isEmpty ? null : _clear,
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _load,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
                children: [
                  if (_error.isNotEmpty)
                    Notice(
                      icon: Icons.error_outline,
                      color: AppColors.danger,
                      title: 'The record could not be read',
                      message: _error,
                      actionLabel: 'Try again',
                      onAction: _load,
                    )
                  else if (_records.isEmpty)
                    const Notice(
                      icon: Icons.history,
                      title: 'Nothing recorded yet',
                      message: 'Reports, security audits, file copies and '
                          'repairs appear here with the phone they were '
                          'done on.',
                    )
                  else ...[
                    Wrap(
                      spacing: 8,
                      runSpacing: 4,
                      children: [
                        for (final entry in _filterLabels.entries)
                          ChoiceChip(
                            label: Text(entry.value),
                            selected: _filter == entry.key,
                            onSelected: (_) =>
                                setState(() => _filter = entry.key),
                          ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      '${visible.length} of ${_records.length} entries '
                      '(the newest 500 are kept)',
                      style: const TextStyle(
                        color: AppColors.textMuted,
                        fontSize: 12.5,
                      ),
                    ),
                    const SizedBox(height: 10),
                    if (visible.isEmpty)
                      const Padding(
                        padding: EdgeInsets.only(top: 24),
                        child: Center(
                          child: Text(
                            'No entry matches this filter.',
                            style: TextStyle(color: Colors.white60),
                          ),
                        ),
                      ),
                    for (final record in visible) _buildRecord(record),
                  ],
                ],
              ),
            ),
    );
  }
}
