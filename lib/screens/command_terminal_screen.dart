import 'package:material_ui/material_ui.dart';

import '../app/app_theme.dart';
import '../services/device_bridge.dart';
import 'connection_screen.dart';

class _TerminalEntry {
  final String command;
  final String output;
  final bool failed;
  final bool rootMode;

  const _TerminalEntry({
    required this.command,
    required this.output,
    required this.failed,
    required this.rootMode,
  });
}

/// Runs read-only inspection commands on the connected phone. Only the
/// commands in the built-in list are accepted; free-form shell text is
/// refused by the native side.
class CommandTerminalScreen extends StatefulWidget {
  const CommandTerminalScreen({super.key});

  @override
  State<CommandTerminalScreen> createState() => _CommandTerminalScreenState();
}

class _CommandTerminalScreenState extends State<CommandTerminalScreen> {
  static const int _maxEntries = 100;
  static const int _maxOutputCharacters = 60000;

  final DeviceBridge _bridge = DeviceBridge.instance;
  final TextEditingController _input = TextEditingController();
  final FocusNode _inputFocus = FocusNode();
  final ScrollController _scroll = ScrollController();

  final List<_TerminalEntry> _entries = <_TerminalEntry>[];
  final List<String> _recent = <String>[];

  List<TerminalCommandInfo> _commands = const <TerminalCommandInfo>[];
  bool _rootMode = false;
  bool _running = false;

  @override
  void initState() {
    super.initState();
    _bridge.start();
    _input.addListener(_onInputChanged);
    _loadCommands();
  }

  @override
  void dispose() {
    _input.removeListener(_onInputChanged);
    _input.dispose();
    _inputFocus.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _onInputChanged() {
    if (mounted) setState(() {});
  }

  Future<void> _loadCommands() async {
    try {
      final commands = await _bridge.getTerminalCommands();
      if (!mounted) return;
      setState(() => _commands = commands);
    } catch (error) {
      if (!mounted) return;
      showMessage(context, describeError(error), error: true);
    }
  }

  List<TerminalCommandInfo> get _suggestions {
    final typed = _input.text.trim().toLowerCase();
    if (typed.isEmpty) return const <TerminalCommandInfo>[];

    final firstWord = typed.split(' ').first;
    final matches = <TerminalCommandInfo>[];

    for (final command in _commands) {
      if (command.name == typed) continue;
      if (command.name.startsWith(firstWord) ||
          (firstWord.length >= 3 && command.name.contains(firstWord))) {
        matches.add(command);
      }
      if (matches.length == 6) break;
    }

    return matches;
  }

  void _choose(TerminalCommandInfo command) {
    if (command.needsArgument) {
      final text = '${command.name} ';
      _input.value = TextEditingValue(
        text: text,
        selection: TextSelection.collapsed(offset: text.length),
      );
      _inputFocus.requestFocus();
    } else {
      _run(command.name);
    }
  }

  Future<void> _run(String rawCommand) async {
    final command = rawCommand.trim();
    if (command.isEmpty || _running) return;

    if (command.toLowerCase() == 'clear') {
      setState(() {
        _entries.clear();
        _input.clear();
      });
      return;
    }

    final rootMode = _rootMode;

    setState(() {
      _running = true;
      _input.clear();
      _recent.remove(command);
      _recent.insert(0, command);
      if (_recent.length > 12) _recent.removeLast();
    });

    String output;
    bool failed = false;

    try {
      output = await _bridge.runTerminalCommand(command, rootMode: rootMode);
    } catch (error) {
      output = describeError(error);
      failed = true;
    }

    if (output.length > _maxOutputCharacters) {
      output = '${output.substring(0, _maxOutputCharacters)}\n\n'
          '(Shortened: only the first part is shown.)';
    }

    if (!mounted) return;

    setState(() {
      _running = false;
      _entries.add(
        _TerminalEntry(
          command: command,
          output: output,
          failed: failed,
          rootMode: rootMode,
        ),
      );
      if (_entries.length > _maxEntries) _entries.removeAt(0);
    });

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(
          _scroll.position.maxScrollExtent,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _setRootMode(bool value) async {
    if (!value) {
      setState(() => _rootMode = false);
      return;
    }

    final confirmed = await confirmAction(
      context,
      title: 'Use root mode?',
      message: 'Commands will run as root on the connected phone. This '
          'only works when that phone is rooted, and it may show a prompt '
          'asking to grant root to "shell".\n\n'
          'The same read-only command list applies.',
      confirmLabel: 'Use root mode',
    );

    if (!confirmed || !mounted) return;
    setState(() => _rootMode = true);
  }

  void _showCommandList() {
    final groups = <String, List<TerminalCommandInfo>>{};
    for (final command in _commands) {
      groups.putIfAbsent(command.group, () => <TerminalCommandInfo>[]).add(command);
    }

    showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.surfaceDeep,
      isScrollControlled: true,
      builder: (sheetContext) {
        return DraggableScrollableSheet(
          expand: false,
          initialChildSize: 0.75,
          maxChildSize: 0.95,
          builder: (context, controller) {
            return ListView(
              controller: controller,
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 24),
              children: [
                const Text(
                  'Available commands',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                const Text(
                  'All of these only read information. To change '
                  'something on the phone, use Repair Tools.',
                  style: TextStyle(color: Colors.white60),
                ),
                for (final group in groups.entries) ...[
                  SectionTitle(group.key),
                  for (final command in group.value)
                    ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(
                        command.needsArgument
                            ? '${command.name} <value>'
                            : command.name,
                        style: const TextStyle(
                          color: Colors.white,
                          fontFamily: 'monospace',
                        ),
                      ),
                      subtitle: Text(
                        command.description,
                        style: const TextStyle(color: Colors.white60),
                      ),
                      onTap: () {
                        Navigator.pop(sheetContext);
                        _choose(command);
                      },
                    ),
                ],
              ],
            );
          },
        );
      },
    );
  }

  String _allOutput() {
    final buffer = StringBuffer();
    for (final entry in _entries) {
      buffer.writeln('${entry.rootMode ? '#' : '\$'} ${entry.command}');
      buffer.writeln(entry.output);
      buffer.writeln();
    }
    return buffer.toString();
  }

  Widget _buildEntry(_TerminalEntry entry) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${entry.rootMode ? '#' : '\$'} ${entry.command}',
            style: TextStyle(
              color: entry.rootMode ? AppColors.warning : AppColors.ok,
              fontFamily: 'monospace',
              fontWeight: FontWeight.bold,
              fontSize: 13,
            ),
          ),
          const SizedBox(height: 3),
          SelectableText(
            entry.output,
            style: TextStyle(
              color: entry.failed ? AppColors.danger : const Color(0xFFD6E2F0),
              fontFamily: 'monospace',
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
    final suggestions = _suggestions;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Command Terminal'),
        actions: [
          IconButton(
            tooltip: 'Command list',
            onPressed: _showCommandList,
            icon: const Icon(Icons.list_alt),
          ),
          IconButton(
            tooltip: 'Copy everything',
            onPressed: _entries.isEmpty
                ? null
                : () => copyText(context, _allOutput()),
            icon: const Icon(Icons.copy),
          ),
          IconButton(
            tooltip: 'Clear',
            onPressed:
                _entries.isEmpty ? null : () => setState(_entries.clear),
            icon: const Icon(Icons.delete_sweep),
          ),
        ],
      ),
      body: SafeArea(
        child: ValueListenableBuilder<AdbStatus>(
          valueListenable: _bridge.status,
          builder: (context, status, _) {
            return Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 4, 12, 0),
                  child: TargetBanner(
                    onConnect: () => openConnectionScreen(context),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          _rootMode
                              ? 'Root mode: commands run as root'
                              : 'Standard mode: commands run as the ADB shell user',
                          style: TextStyle(
                            color: _rootMode
                                ? AppColors.warning
                                : AppColors.textMuted,
                            fontSize: 12.5,
                          ),
                        ),
                      ),
                      const Text(
                        'Root',
                        style: TextStyle(color: Colors.white70),
                      ),
                      Switch(
                        value: _rootMode,
                        onChanged: _running ? null : _setRootMode,
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: Container(
                    width: double.infinity,
                    margin: const EdgeInsets.symmetric(horizontal: 12),
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: AppColors.console,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: AppColors.border),
                    ),
                    child: _entries.isEmpty
                        ? const SingleChildScrollView(
                            child: Text(
                              'Type a command, or tap the list icon above to '
                              'see every command.\n\n'
                              'Try:  device-info   battery   df -h   help',
                              style: TextStyle(
                                color: Colors.white54,
                                fontFamily: 'monospace',
                                fontSize: 13,
                                height: 1.4,
                              ),
                            ),
                          )
                        : ListView.builder(
                            controller: _scroll,
                            itemCount: _entries.length,
                            itemBuilder: (context, index) =>
                                _buildEntry(_entries[index]),
                          ),
                  ),
                ),
                if (_running)
                  const Padding(
                    padding: EdgeInsets.fromLTRB(12, 8, 12, 0),
                    child: LinearProgressIndicator(),
                  ),
                if (suggestions.isNotEmpty || _recent.isNotEmpty)
                  SizedBox(
                    height: 46,
                    child: ListView(
                      scrollDirection: Axis.horizontal,
                      padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
                      children: [
                        if (suggestions.isNotEmpty)
                          for (final command in suggestions)
                            Padding(
                              padding: const EdgeInsets.only(right: 6),
                              child: ActionChip(
                                label: Text(command.name),
                                tooltip: command.description,
                                onPressed:
                                    _running ? null : () => _choose(command),
                              ),
                            )
                        else
                          for (final command in _recent)
                            Padding(
                              padding: const EdgeInsets.only(right: 6),
                              child: ActionChip(
                                avatar: const Icon(Icons.history, size: 16),
                                label: Text(command),
                                onPressed:
                                    _running ? null : () => _run(command),
                              ),
                            ),
                      ],
                    ),
                  ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
                  child: Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _input,
                          focusNode: _inputFocus,
                          enabled: status.connected,
                          autocorrect: false,
                          enableSuggestions: false,
                          textInputAction: TextInputAction.send,
                          style: const TextStyle(fontFamily: 'monospace'),
                          decoration: InputDecoration(
                            hintText: status.connected
                                ? 'Command'
                                : 'Connect a phone to run commands',
                            border: const OutlineInputBorder(),
                            isDense: true,
                          ),
                          onSubmitted: _run,
                        ),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filled(
                        tooltip: 'Run',
                        onPressed: status.connected && !_running
                            ? () => _run(_input.text)
                            : null,
                        icon: const Icon(Icons.send),
                      ),
                    ],
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}
