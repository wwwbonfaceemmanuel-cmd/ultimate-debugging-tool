
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class RepairHistoryScreen extends StatefulWidget {
  const RepairHistoryScreen({super.key});

  @override
  State<RepairHistoryScreen> createState() =>
      _RepairHistoryScreenState();
}

class _RepairHistoryScreenState
    extends State<RepairHistoryScreen> {
  static const MethodChannel _channel =
      MethodChannel('com.ultimate.debugging/usb');

  List<Map<String, dynamic>> _records = [];

  bool _isLoading = true;
  bool _isClearing = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadHistory();
  }

  Future<void> _loadHistory() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final response =
          await _channel.invokeListMethod<dynamic>(
        'getRepairHistory',
      );

      final records = (response ?? [])
          .whereType<Map>()
          .map(
            (item) => Map<String, dynamic>.from(item),
          )
          .toList();

      if (!mounted) return;

      setState(() {
        _records = records;
        _isLoading = false;
      });
    } on PlatformException catch (e) {
      if (!mounted) return;

      setState(() {
        _error = e.message ??
            'Unable to retrieve repair history.';
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;

      setState(() {
        _error = 'An unexpected error occurred: $e';
        _isLoading = false;
      });
    }
  }

  Future<void> _clearHistory() async {
    if (_records.isEmpty || _isClearing) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Clear repair history?'),
        content: const Text(
          'This will permanently delete all saved '
          'diagnostic and repair history records.',
        ),
        actions: [
          TextButton(
            onPressed: () =>
                Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.pop(context, true),
            child: const Text('Clear history'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    setState(() {
      _isClearing = true;
    });

    try {
      final success =
          await _channel.invokeMethod<bool>(
        'clearRepairHistory',
      );

      if (!mounted) return;

      if (success == true) {
        setState(() {
          _records.clear();
          _isClearing = false;
        });

        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Repair history cleared.'),
          ),
        );
      } else {
        setState(() {
          _isClearing = false;
        });

        _showMessage('Unable to clear repair history.');
      }
    } catch (e) {
      if (!mounted) return;

      setState(() {
        _isClearing = false;
      });

      _showMessage('Clear failed: $e');
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  String _formatTimestamp(dynamic value) {
    final milliseconds =
        value is num ? value.toInt() : 0;

    if (milliseconds <= 0) {
      return 'Unknown date';
    }

    final date = DateTime.fromMillisecondsSinceEpoch(
      milliseconds,
    ).toLocal();

    String twoDigits(int number) =>
        number.toString().padLeft(2, '0');

    return '${twoDigits(date.day)}/'
        '${twoDigits(date.month)}/'
        '${date.year} '
        '${twoDigits(date.hour)}:'
        '${twoDigits(date.minute)}';
  }

  IconData _resultIcon(String result) {
    switch (result.toLowerCase()) {
      case 'success':
      case 'completed':
      case 'passed':
        return Icons.check_circle_outline;

      case 'failed':
      case 'error':
        return Icons.error_outline;

      case 'warning':
      case 'partial':
        return Icons.warning_amber_rounded;

      default:
        return Icons.info_outline;
    }
  }

  Color _resultColor(
    String result,
    ColorScheme colors,
  ) {
    switch (result.toLowerCase()) {
      case 'success':
      case 'completed':
      case 'passed':
        return Colors.green;

      case 'failed':
      case 'error':
        return colors.error;

      case 'warning':
      case 'partial':
        return Colors.orange;

      default:
        return colors.primary;
    }
  }

  Widget _buildRecord(
    Map<String, dynamic> record,
  ) {
    final colors = Theme.of(context).colorScheme;

    final operation =
        record['operation']?.toString() ?? 'Unknown operation';

    final category =
        record['category']?.toString() ?? 'General';

    final result =
        record['result']?.toString() ?? 'Unknown';

    final details =
        record['details']?.toString() ?? '';

    final resultColor = _resultColor(result, colors);

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  _resultIcon(result),
                  color: resultColor,
                  size: 25,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    operation,
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                  ),
                ),
                Text(
                  result,
                  style: TextStyle(
                    color: resultColor,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Icon(
                  Icons.category_outlined,
                  size: 16,
                  color: colors.secondary,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    category,
                    style: Theme.of(context)
                        .textTheme
                        .bodySmall,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Row(
              children: [
                Icon(
                  Icons.access_time,
                  size: 16,
                  color: colors.secondary,
                ),
                const SizedBox(width: 6),
                Text(
                  _formatTimestamp(record['timestamp']),
                  style: Theme.of(context)
                      .textTheme
                      .bodySmall,
                ),
              ],
            ),
            if (details.isNotEmpty) ...[
              const SizedBox(height: 12),
              const Divider(),
              Text(
                details,
                style: Theme.of(context)
                    .textTheme
                    .bodyMedium,
              ),
            ],
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Repair History'),
        actions: [
          IconButton(
            tooltip: 'Refresh history',
            onPressed: _isLoading ? null : _loadHistory,
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: 'Clear history',
            onPressed: _records.isEmpty || _isClearing
                ? null
                : _clearHistory,
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _loadHistory,
        child: _isLoading
            ? const Center(
                child: CircularProgressIndicator(),
              )
            : _error != null
                ? ListView(
                    physics:
                        const AlwaysScrollableScrollPhysics(),
                    children: [
                      const SizedBox(height: 100),
                      Icon(
                        Icons.error_outline,
                        size: 55,
                        color: colors.error,
                      ),
                      const SizedBox(height: 16),
                      Center(
                        child: Text(
                          _error!,
                          textAlign: TextAlign.center,
                        ),
                      ),
                      const SizedBox(height: 16),
                      Center(
                        child: FilledButton.icon(
                          onPressed: _loadHistory,
                          icon: const Icon(Icons.refresh),
                          label: const Text('Try again'),
                        ),
                      ),
                    ],
                  )
                : _records.isEmpty
                    ? ListView(
                        physics:
                            const AlwaysScrollableScrollPhysics(),
                        children: [
                          const SizedBox(height: 110),
                          Icon(
                            Icons.history,
                            size: 75,
                            color: colors.secondary,
                          ),
                          const SizedBox(height: 16),
                          Center(
                            child: Text(
                              'No repair history yet',
                              style: Theme.of(context)
                                  .textTheme
                                  .titleLarge,
                            ),
                          ),
                          const SizedBox(height: 8),
                          const Center(
                            child: Padding(
                              padding: EdgeInsets.symmetric(
                                horizontal: 30,
                              ),
                              child: Text(
                                'Diagnostic checks and repair '
                                'operations will appear here '
                                'when history recording is connected.',
                                textAlign: TextAlign.center,
                              ),
                            ),
                          ),
                        ],
                      )
                    : ListView(
                        physics:
                            const AlwaysScrollableScrollPhysics(),
                        padding: const EdgeInsets.all(16),
                        children: [
                          Text(
                            '${_records.length} saved record(s)',
                            style: Theme.of(context)
                                .textTheme
                                .titleSmall,
                          ),
                          const SizedBox(height: 14),
                          ..._records.map(_buildRecord),
                        ],
                      ),
      ),
    );
  }
}
