import 'package:flutter/material.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';
import 'connection_screen.dart';

/// Browses the shared storage of the connected phone and copies single
/// files in either direction.
class FileManagerScreen extends StatefulWidget {
  const FileManagerScreen({super.key});

  @override
  State<FileManagerScreen> createState() => _FileManagerScreenState();
}

class _FileManagerScreenState extends State<FileManagerScreen> {
  static const String _startPath = '/sdcard';

  final DeviceBridge _bridge = DeviceBridge.instance;

  /// Folders visited before the current one, for the back button.
  final List<String> _trail = <String>[];

  List<NamedItem> _shortcuts = const <NamedItem>[];
  List<RemoteFileEntry> _entries = const <RemoteFileEntry>[];
  String _path = _startPath;
  String _parent = '';
  bool _loading = false;
  bool _loadedOnce = false;
  String _error = '';
  String _transfer = '';

  @override
  void initState() {
    super.initState();
    _bridge.start();
    _bridge.status.addListener(_onStatusChanged);
    _loadShortcuts();
    if (_bridge.status.value.connected) _openFolder(_startPath, remember: false);
  }

  @override
  void dispose() {
    _bridge.status.removeListener(_onStatusChanged);
    super.dispose();
  }

  void _onStatusChanged() {
    final connected = _bridge.status.value.connected;

    if (connected && !_loadedOnce && !_loading) {
      _openFolder(_startPath, remember: false);
    } else if (!connected && _loadedOnce && mounted) {
      // The next phone starts from the top, not from this phone's folder.
      setState(() {
        _entries = const <RemoteFileEntry>[];
        _trail.clear();
        _path = _startPath;
        _parent = '';
        _loadedOnce = false;
        _error = '';
      });
    }
  }

  Future<void> _loadShortcuts() async {
    try {
      final shortcuts = await _bridge.getFileShortcuts();
      if (!mounted) return;
      setState(() => _shortcuts = shortcuts);
    } catch (_) {
      // Shortcuts are a convenience; browsing works without them.
    }
  }

  Future<void> _openFolder(String path, {bool remember = true}) async {
    if (_loading || _transfer.isNotEmpty) return;

    final previous = _path;

    setState(() {
      _loading = true;
      _error = '';
    });

    try {
      final folder = await _bridge.listFiles(path);
      if (!mounted) return;

      setState(() {
        if (remember && _loadedOnce && folder.path != previous) {
          _trail.add(previous);
        }
        _path = folder.path;
        _parent = folder.parent;
        _entries = folder.entries;
        _loading = false;
        _loadedOnce = true;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = describeError(error);
        _loading = false;
      });
    }
  }

  Future<void> _goBack() async {
    if (_trail.isEmpty) return;
    final previous = _trail.removeLast();
    await _openFolder(previous, remember: false);
  }

  Future<void> _pull(RemoteFileEntry entry) async {
    if (_transfer.isNotEmpty) return;

    setState(() => _transfer = 'Copying ${entry.name} from the phone...');

    try {
      final result = await _bridge.pullFile(entry.path);
      final localPath = asText(result['localPath']);
      final size = asInt(result['sizeBytes']);
      final hash = asText(result['sha256']);

      await _bridge.saveHistory(
        operation: 'File copied from phone: ${entry.name}',
        category: 'Files',
        result: 'Completed',
        details: '${entry.path}\n${formatBytes(size)}\nSHA-256 $hash',
        onTarget: true,
      );

      if (!mounted) return;
      setState(() => _transfer = '');

      final share = await showDialog<bool>(
        context: context,
        builder: (dialogContext) {
          return AlertDialog(
            backgroundColor: AppColors.surface,
            title: const Row(
              children: [
                Icon(Icons.verified, color: AppColors.ok),
                SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'File copied',
                    style: TextStyle(color: Colors.white),
                  ),
                ),
              ],
            ),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    asText(result['fileName'], entry.name),
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    formatBytes(size),
                    style: const TextStyle(color: Colors.white70),
                  ),
                  const SizedBox(height: 10),
                  ReportView('SHA-256\n$hash'),
                  const SizedBox(height: 10),
                  const Text(
                    'The copy is kept privately inside this app for 24 '
                    'hours. Share it now to save or send it.',
                    style: TextStyle(color: Colors.white70, height: 1.4),
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: const Text('Close'),
              ),
              FilledButton.icon(
                onPressed: () => Navigator.pop(dialogContext, true),
                icon: const Icon(Icons.share),
                label: const Text('Share or save'),
              ),
            ],
          );
        },
      );

      if (share == true) await _bridge.shareTransferredFile(localPath);
    } catch (error) {
      if (!mounted) return;
      setState(() => _transfer = '');
      showMessage(context, describeError(error), error: true);
    }
  }

  Future<void> _push() async {
    if (_transfer.isNotEmpty || _loading) return;

    final folder = _path;

    final confirmed = await confirmAction(
      context,
      title: 'Send a file to the phone',
      message: 'Choose a file on this phone. It will be copied to:\n\n'
          '$folder\n\n'
          'A file with the same name in that folder is replaced.',
      confirmLabel: 'Choose file',
    );

    if (!confirmed || !mounted) return;
    setState(() => _transfer = 'Sending the file to the phone...');

    try {
      final result = await _bridge.pickAndPushFile(folder);
      if (!mounted) return;
      setState(() => _transfer = '');

      if (asBool(result['cancelled'])) return;

      final name = asText(result['fileName']);
      final size = asInt(result['sizeBytes']);

      await _bridge.saveHistory(
        operation: 'File sent to phone: $name',
        category: 'Files',
        result: 'Completed',
        details: '${asText(result['remotePath'])}\n${formatBytes(size)}',
        onTarget: true,
      );

      if (!mounted) return;
      showMessage(context, 'Sent $name (${formatBytes(size)}).');
      await _openFolder(folder, remember: false);
    } catch (error) {
      if (!mounted) return;
      setState(() => _transfer = '');
      showMessage(context, describeError(error), error: true);
    }
  }

  void _showFile(RemoteFileEntry entry) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.surfaceDeep,
      builder: (sheetContext) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  entry.name,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 17,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  entry.path,
                  style: const TextStyle(
                    color: Colors.white60,
                    fontFamily: 'monospace',
                    fontSize: 12.5,
                  ),
                ),
                const SizedBox(height: 10),
                Text(
                  [
                    formatBytes(entry.sizeBytes),
                    if (entry.modified > 0) formatDateTime(entry.modified),
                    if (entry.permissions.isNotEmpty) entry.permissions,
                  ].join('   '),
                  style: const TextStyle(color: Colors.white70),
                ),
                const SizedBox(height: 18),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: () {
                      Navigator.pop(sheetContext);
                      _pull(entry);
                    },
                    icon: const Icon(Icons.download),
                    label: const Text('Copy to this phone'),
                  ),
                ),
                const SizedBox(height: 8),
                const Text(
                  'The file on the connected phone is not changed.',
                  style: TextStyle(color: AppColors.textMuted, fontSize: 12.5),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  IconData _iconFor(RemoteFileEntry entry) {
    if (entry.isDirectory) return Icons.folder;
    if (entry.isLink) return Icons.link;

    final name = entry.name.toLowerCase();
    bool ends(List<String> extensions) =>
        extensions.any((extension) => name.endsWith(extension));

    if (ends(['.jpg', '.jpeg', '.png', '.gif', '.webp', '.heic'])) {
      return Icons.image;
    }
    if (ends(['.mp4', '.mkv', '.3gp', '.webm', '.mov'])) return Icons.movie;
    if (ends(['.mp3', '.m4a', '.ogg', '.wav', '.opus', '.aac'])) {
      return Icons.audiotrack;
    }
    if (ends(['.apk'])) return Icons.android;
    if (ends(['.zip', '.rar', '.7z', '.tar', '.gz'])) return Icons.archive;
    if (ends(['.pdf', '.doc', '.docx', '.txt', '.xls', '.xlsx'])) {
      return Icons.description;
    }
    return Icons.insert_drive_file;
  }

  Widget _buildEntry(RemoteFileEntry entry) {
    final opensAsFolder = entry.isDirectory || entry.isLink;

    return ListTile(
      leading: Icon(
        _iconFor(entry),
        color: entry.isDirectory ? AppColors.warning : AppColors.info,
      ),
      title: Text(
        entry.name,
        style: const TextStyle(color: Colors.white, fontSize: 14.5),
      ),
      subtitle: Text(
        [
          if (entry.isDirectory)
            'Folder'
          else if (entry.isLink)
            'Link'
          else
            formatBytes(entry.sizeBytes),
          if (entry.modified > 0) formatDateTime(entry.modified),
        ].join('   '),
        style: const TextStyle(color: Colors.white60, fontSize: 12.5),
      ),
      trailing: opensAsFolder
          ? const Icon(Icons.chevron_right, color: Colors.white38)
          : null,
      onTap: () {
        if (opensAsFolder) {
          _openFolder(entry.path);
        } else {
          _showFile(entry);
        }
      },
    );
  }

  Widget _buildBody(AdbStatus status) {
    if (!status.connected) {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: NeedsPhone(
          what: 'The file manager',
          onConnect: () => openConnectionScreen(context),
        ),
      );
    }

    if (_loading && !_loadedOnce) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_error.isNotEmpty) {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Notice(
          icon: Icons.error_outline,
          color: AppColors.danger,
          title: 'This folder could not be opened',
          message: _error,
          actionLabel: _loadedOnce ? 'Stay in $_path' : 'Try again',
          onAction: () {
            if (_loadedOnce) {
              setState(() => _error = '');
            } else {
              _openFolder(_startPath, remember: false);
            }
          },
        ),
      );
    }

    if (_entries.isEmpty) {
      return const Center(
        child: Text(
          'This folder is empty.',
          style: TextStyle(color: Colors.white60),
        ),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(4, 0, 4, 24),
      itemCount: _entries.length,
      itemBuilder: (context, index) => _buildEntry(_entries[index]),
    );
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: _trail.isEmpty,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) _goBack();
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('File Manager'),
          actions: [
            IconButton(
              tooltip: 'Send a file to this folder',
              onPressed: _bridge.status.value.connected && _loadedOnce
                  ? _push
                  : null,
              icon: const Icon(Icons.upload_file),
            ),
            IconButton(
              tooltip: 'Refresh',
              onPressed: _bridge.status.value.connected
                  ? () => _openFolder(_path, remember: false)
                  : null,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: ValueListenableBuilder<AdbStatus>(
          valueListenable: _bridge.status,
          builder: (context, status, _) {
            return Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                  child: TargetBanner(
                    onConnect: () => openConnectionScreen(context),
                  ),
                ),
                if (status.connected) ...[
                  if (_shortcuts.isNotEmpty)
                    SizedBox(
                      height: 50,
                      child: ListView(
                        scrollDirection: Axis.horizontal,
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 2),
                        children: [
                          for (final shortcut in _shortcuts)
                            Padding(
                              padding: const EdgeInsets.only(right: 8),
                              child: ActionChip(
                                label: Text(shortcut.name),
                                onPressed: () =>
                                    _openFolder(shortcut.description),
                              ),
                            ),
                        ],
                      ),
                    ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(8, 4, 16, 0),
                    child: Row(
                      children: [
                        IconButton(
                          tooltip: 'Up one folder',
                          onPressed: _parent.isEmpty || _parent == _path
                              ? null
                              : () => _openFolder(_parent),
                          icon: const Icon(Icons.arrow_upward),
                        ),
                        Expanded(
                          child: SingleChildScrollView(
                            scrollDirection: Axis.horizontal,
                            reverse: true,
                            child: Text(
                              _path,
                              style: const TextStyle(
                                color: Colors.white,
                                fontFamily: 'monospace',
                                fontSize: 13,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (_loading || _transfer.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const LinearProgressIndicator(),
                          if (_transfer.isNotEmpty) ...[
                            const SizedBox(height: 6),
                            Text(
                              _transfer,
                              style: const TextStyle(color: AppColors.warning),
                            ),
                          ],
                        ],
                      ),
                    ),
                ],
                Expanded(child: _buildBody(status)),
              ],
            );
          },
        ),
      ),
    );
  }
}
