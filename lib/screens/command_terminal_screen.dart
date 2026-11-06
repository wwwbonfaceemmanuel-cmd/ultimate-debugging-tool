
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class CommandTerminalScreen extends StatefulWidget {
  const CommandTerminalScreen({super.key});

  @override
  State<CommandTerminalScreen> createState() =>
      _CommandTerminalScreenState();
}

class _CommandTerminalScreenState
    extends State<CommandTerminalScreen> {
  
  static const MethodChannel _terminalChannel =
    MethodChannel('com.ultimate.debugging/usb');
  final TextEditingController _commandController =
      TextEditingController();

  final ScrollController _scrollController =
      ScrollController();

  final List<String> _terminalOutput = [
    'ULTIMATE DEBUGGING TOOL',
    'Advanced Command Terminal',
    'Terminal interface initialized.',
    '',
    'Execution engine: Not connected',
    'Mode: STANDARD ANDROID',
    '',
    'Type a command below to begin.',
  ];

  String _executionMode = 'STANDARD ANDROID';

  @override
  void dispose() {
    _commandController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _clearTerminal() {
    setState(() {
      _terminalOutput.clear();
    });
  }

  void _addOutput(String message) {
    setState(() {
      _terminalOutput.add(message);
    });

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _submitCommand() async {
  final command = _commandController.text.trim();

  if (command.isEmpty) return;

  _commandController.clear();

  if (command.toLowerCase() == 'clear') {
    _clearTerminal();
    return;
  }

  _addOutput('> $command');

    final method = _executionMode == 'ADB MODE'
      ? 'executeAdbCommand'
      : _executionMode == 'ROOT MODE'
          ? 'executeRootCommand'
          : 'executeStandardDiagnostic';

  try {
    final output = await _terminalChannel.invokeMethod<String>(
            method,
      {
        'command': command,
      },
    );

    if (!mounted) return;

    _addOutput(output ?? 'No output returned.');
  } on PlatformException catch (e) {
    if (!mounted) return;

    _addOutput('ERROR: ${e.message ?? e.code}');
  } catch (e) {
    if (!mounted) return;

    _addOutput('UNEXPECTED ERROR: $e');
  }

  if (!mounted) return;

  _addOutput('');
}

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Command Terminal'),
        backgroundColor: const Color(0xFF122235),
        actions: [
          IconButton(
            tooltip: 'Clear terminal',
            onPressed: _clearTerminal,
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            _buildStatusBar(),
            Expanded(
              child: _buildTerminalOutput(),
            ),
            _buildCommandInput(),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusBar() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      color: const Color(0xFF172638),
      child: Row(
        children: [
          const Icon(
            Icons.circle,
            color: Colors.orangeAccent,
            size: 10,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'ENGINE NOT CONNECTED',
              style: TextStyle(
                color: Colors.orangeAccent,
                fontSize: 11,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          PopupMenuButton<String>(
            initialValue: _executionMode,
            tooltip: 'Execution mode',
            onSelected: (value) {
              setState(() {
                _executionMode = value;
              });
            },
            itemBuilder: (context) => const [
              PopupMenuItem(
                value: 'STANDARD ANDROID',
                child: Text('Standard Android'),
              ),
              PopupMenuItem(
                value: 'ADB MODE',
                child: Text('ADB Mode'),
              ),
              PopupMenuItem(
                value: 'ROOT MODE',
                child: Text('Root Mode'),
              ),
            ],
            child: Row(
              children: [
                const Icon(
                  Icons.settings,
                  size: 16,
                ),
                const SizedBox(width: 5),
                Text(
                  _executionMode,
                  style: const TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const Icon(
                  Icons.arrow_drop_down,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTerminalOutput() {
    return Container(
      width: double.infinity,
      color: const Color(0xFF07111C),
      padding: const EdgeInsets.all(14),
      child: ListView.builder(
        controller: _scrollController,
        itemCount: _terminalOutput.length,
        itemBuilder: (context, index) {
          final line = _terminalOutput[index];

          final isCommand = line.startsWith('> ');

          return Padding(
            padding: const EdgeInsets.symmetric(
              vertical: 3,
            ),
            child: Text(
              line,
              style: TextStyle(
                color: isCommand
                    ? Colors.lightGreenAccent
                    : Colors.white70,
                fontFamily: 'monospace',
                fontSize: 12,
                height: 1.5,
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildCommandInput() {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: const BoxDecoration(
        color: Color(0xFF122235),
        border: Border(
          top: BorderSide(
            color: Colors.white12,
          ),
        ),
      ),
      child: Row(
        children: [
          const Text(
            '\$',
            style: TextStyle(
              color: Colors.lightGreenAccent,
              fontSize: 20,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: TextField(
              controller: _commandController,
              style: const TextStyle(
                color: Colors.white,
                fontFamily: 'monospace',
                fontSize: 13,
              ),
              decoration: const InputDecoration(
                hintText: 'Enter command...',
                border: InputBorder.none,
                isDense: true,
              ),
              onSubmitted: (_) => _submitCommand(),
            ),
          ),
          IconButton(
            onPressed: _submitCommand,
            tooltip: 'Run command',
            icon: const Icon(
              Icons.play_arrow,
              color: Colors.lightGreenAccent,
            ),
          ),
        ],
      ),
    );
  }
}
