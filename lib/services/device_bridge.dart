import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// The single place where the native channel names are written down.
const String kMethodChannelName = 'com.ultimate.debugging/usb';
const String kEventChannelName = 'com.ultimate.debugging/usb_events';

// ---------------------------------------------------------------------
// Safe readers for values that arrive from the native side
// ---------------------------------------------------------------------

Map<String, dynamic> asMap(Object? value) {
  if (value is Map) {
    final result = <String, dynamic>{};
    value.forEach((key, item) {
      result[key.toString()] = item;
    });
    return result;
  }
  return <String, dynamic>{};
}

List<Map<String, dynamic>> asMapList(Object? value) {
  final result = <Map<String, dynamic>>[];
  if (value is List) {
    for (final item in value) {
      result.add(asMap(item));
    }
  }
  return result;
}

List<String> asStringList(Object? value) {
  final result = <String>[];
  if (value is List) {
    for (final item in value) {
      if (item != null) result.add(item.toString());
    }
  }
  return result;
}

String asText(Object? value, [String fallback = '']) {
  if (value == null) return fallback;
  final text = value.toString();
  return text.isEmpty ? fallback : text;
}

int asInt(Object? value, [int fallback = 0]) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value) ?? fallback;
  return fallback;
}

bool asBool(Object? value) => value == true;

/// Turns any failure into a sentence that can be shown to the technician.
String describeError(Object error) {
  if (error is PlatformException) {
    final message = error.message;
    if (message != null && message.trim().isNotEmpty) return message.trim();
    return 'The operation failed (${error.code}).';
  }
  if (error is MissingPluginException) {
    return 'This feature is not available in this build of the app.';
  }
  return error.toString();
}

String errorCode(Object error) {
  return error is PlatformException ? error.code : '';
}

String formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  const units = ['KB', 'MB', 'GB', 'TB'];
  double value = bytes / 1024;
  int unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return '${value.toStringAsFixed(value >= 100 ? 0 : 1)} ${units[unit]}';
}

String _twoDigits(int value) => value.toString().padLeft(2, '0');

String formatDateTime(int milliseconds) {
  if (milliseconds <= 0) return '';
  final time = DateTime.fromMillisecondsSinceEpoch(milliseconds);
  return '${time.year}-${_twoDigits(time.month)}-${_twoDigits(time.day)} '
      '${_twoDigits(time.hour)}:${_twoDigits(time.minute)}';
}

// ---------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------

/// The state of the link to the phone being serviced.
class AdbStatus {
  final String link;
  final String state;
  final bool connected;
  final bool busy;
  final String target;
  final String host;
  final int port;
  final String deviceLabel;
  final Map<String, String> device;
  final String error;

  const AdbStatus({
    this.link = 'NONE',
    this.state = 'DISCONNECTED',
    this.connected = false,
    this.busy = false,
    this.target = '',
    this.host = '',
    this.port = 0,
    this.deviceLabel = '',
    this.device = const <String, String>{},
    this.error = '',
  });

  static const AdbStatus disconnected = AdbStatus();

  factory AdbStatus.fromMap(Object? raw) {
    final map = asMap(raw);
    final device = <String, String>{};
    asMap(map['device']).forEach((key, value) {
      if (value != null) device[key] = value.toString();
    });

    return AdbStatus(
      link: asText(map['link'], 'NONE'),
      state: asText(map['status'], 'DISCONNECTED'),
      connected: asBool(map['connected']),
      busy: asBool(map['busy']),
      target: asText(map['target']),
      host: asText(map['host']),
      port: asInt(map['port']),
      deviceLabel: asText(map['deviceLabel']),
      device: device,
      error: asText(map['error']),
    );
  }

  bool get isUsb => link == 'USB';

  bool get isWireless => link == 'WIRELESS';

  /// The phone is showing "Allow USB debugging?" and waits for a tap.
  bool get waitingForApproval => state == 'AUTHENTICATION_REQUIRED';

  bool get connecting => state == 'CONNECTING' || waitingForApproval;

  String get linkLabel {
    if (isUsb) return 'USB cable';
    if (isWireless) return 'Wi-Fi';
    return 'Not connected';
  }

  String get title {
    if (connected) {
      return deviceLabel.isEmpty ? 'Phone connected' : deviceLabel;
    }
    if (waitingForApproval) return 'Waiting for approval on the phone';
    if (state == 'CONNECTING') return 'Connecting...';
    return 'No phone connected';
  }

  String get subtitle {
    if (connected) {
      final android = device['android'] ?? '';
      final parts = <String>[
        'Connected over $linkLabel',
        if (android.isNotEmpty) 'Android $android',
      ];
      return parts.join(' - ');
    }
    if (waitingForApproval) {
      return 'Unlock the phone and tap "Allow" on the USB debugging prompt.';
    }
    if (state == 'CONNECTING') return 'Starting the ADB session.';
    if (error.isNotEmpty) return error;
    return 'Open Connection to link a phone by cable or Wi-Fi.';
  }
}

class UsbDeviceInfo {
  final String deviceName;
  final int deviceId;
  final int vendorId;
  final int productId;
  final String manufacturerName;
  final String productName;
  final int deviceClass;
  final int deviceSubclass;
  final int interfaceCount;
  final bool hasPermission;

  const UsbDeviceInfo({
    required this.deviceName,
    required this.deviceId,
    required this.vendorId,
    required this.productId,
    required this.manufacturerName,
    required this.productName,
    required this.deviceClass,
    required this.deviceSubclass,
    required this.interfaceCount,
    required this.hasPermission,
  });

  factory UsbDeviceInfo.fromMap(Map<String, dynamic> map) {
    return UsbDeviceInfo(
      deviceName: asText(map['deviceName']),
      deviceId: asInt(map['deviceId'], -1),
      vendorId: asInt(map['vendorId']),
      productId: asInt(map['productId']),
      manufacturerName: asText(map['manufacturerName'], 'Unknown'),
      productName: asText(map['productName'], 'Unknown'),
      deviceClass: asInt(map['deviceClass']),
      deviceSubclass: asInt(map['deviceSubclass']),
      interfaceCount: asInt(map['interfaceCount']),
      hasPermission: asBool(map['hasPermission']),
    );
  }

  String get displayName {
    final maker = manufacturerName == 'Unknown' ? '' : manufacturerName;
    final product = productName == 'Unknown' ? '' : productName;
    final name = '$maker $product'.trim();
    return name.isEmpty ? 'USB device' : name;
  }

  String get ids {
    String hex(int value) => value.toRadixString(16).padLeft(4, '0');
    return '${hex(vendorId)}:${hex(productId)}';
  }
}

class UsbEvent {
  final String event;
  final String deviceName;
  final List<UsbDeviceInfo> devices;

  const UsbEvent({
    required this.event,
    required this.deviceName,
    required this.devices,
  });
}

class AdbInterfaceInfo {
  final String deviceName;
  final int interfaceId;
  final bool hasAdbEndpoints;
  final bool hasPermission;

  const AdbInterfaceInfo({
    required this.deviceName,
    required this.interfaceId,
    required this.hasAdbEndpoints,
    required this.hasPermission,
  });

  factory AdbInterfaceInfo.fromMap(Map<String, dynamic> map) {
    return AdbInterfaceInfo(
      deviceName: asText(map['deviceName']),
      interfaceId: asInt(map['interfaceId'], -1),
      hasAdbEndpoints: asBool(map['hasAdbEndpoints']),
      hasPermission: asBool(map['hasPermission']),
    );
  }
}

class NamedItem {
  final String name;
  final String description;

  const NamedItem(this.name, this.description);
}

class TerminalCommandInfo {
  final String name;
  final String description;
  final String group;
  final bool needsArgument;

  const TerminalCommandInfo({
    required this.name,
    required this.description,
    required this.group,
    required this.needsArgument,
  });
}

class RepairServiceInfo {
  final String id;
  final String title;
  final String category;
  final String risk;
  final bool needsPackage;
  final bool dataPreserved;
  final String description;
  final String warning;
  final int minSdk;
  final bool endsSession;

  const RepairServiceInfo({
    required this.id,
    required this.title,
    required this.category,
    required this.risk,
    required this.needsPackage,
    required this.dataPreserved,
    required this.description,
    required this.warning,
    required this.minSdk,
    required this.endsSession,
  });

  factory RepairServiceInfo.fromMap(Map<String, dynamic> map) {
    return RepairServiceInfo(
      id: asText(map['id']),
      title: asText(map['title']),
      category: asText(map['category'], 'General'),
      risk: asText(map['risk'], 'LOW'),
      needsPackage: asBool(map['needsPackage']),
      dataPreserved: map['dataPreserved'] != false,
      description: asText(map['description']),
      warning: asText(map['warning']),
      minSdk: asInt(map['minSdk']),
      endsSession: asBool(map['endsSession']),
    );
  }
}

class RepairOutcome {
  final String title;
  final String message;
  final String verification;
  final String output;
  final bool endsSession;

  const RepairOutcome({
    required this.title,
    required this.message,
    required this.verification,
    required this.output,
    required this.endsSession,
  });

  factory RepairOutcome.fromMap(Map<String, dynamic> map) {
    return RepairOutcome(
      title: asText(map['title'], 'Repair'),
      message: asText(map['message'], 'Completed.'),
      verification: asText(map['verification']),
      output: asText(map['output']),
      endsSession: asBool(map['endsSession']),
    );
  }
}

class TargetApp {
  final String packageName;
  final bool isSystemApp;
  final bool enabled;

  const TargetApp({
    required this.packageName,
    required this.isSystemApp,
    required this.enabled,
  });

  factory TargetApp.fromMap(Map<String, dynamic> map) {
    return TargetApp(
      packageName: asText(map['packageName']),
      isSystemApp: asBool(map['isSystemApp']),
      enabled: map['enabled'] != false,
    );
  }
}

class RemoteFileEntry {
  final String name;
  final String path;
  final String type;
  final int sizeBytes;
  final int modified;
  final String permissions;

  const RemoteFileEntry({
    required this.name,
    required this.path,
    required this.type,
    required this.sizeBytes,
    required this.modified,
    required this.permissions,
  });

  factory RemoteFileEntry.fromMap(Map<String, dynamic> map) {
    return RemoteFileEntry(
      name: asText(map['name']),
      path: asText(map['path']),
      type: asText(map['type'], 'file'),
      sizeBytes: asInt(map['sizeBytes']),
      modified: asInt(map['modified']),
      permissions: asText(map['permissions']),
    );
  }

  bool get isDirectory => type == 'directory';

  bool get isLink => type == 'link';
}

class RemoteFolder {
  final String path;
  final String parent;
  final List<RemoteFileEntry> entries;

  const RemoteFolder({
    required this.path,
    required this.parent,
    required this.entries,
  });
}

class SecurityFinding {
  final String level;
  final String title;
  final String detail;
  final String advice;

  const SecurityFinding({
    required this.level,
    required this.title,
    required this.detail,
    required this.advice,
  });
}

class SecurityAuditResult {
  final int score;
  final String rating;
  final List<SecurityFinding> findings;
  final String report;

  const SecurityAuditResult({
    required this.score,
    required this.rating,
    required this.findings,
    required this.report,
  });
}

/// A USB device that is sitting in its bootloader (fastboot mode).
class FastbootDevice {
  final String deviceName;
  final int vendorId;
  final int productId;
  final String productName;
  final String manufacturerName;
  final bool hasPermission;

  const FastbootDevice({
    required this.deviceName,
    this.vendorId = 0,
    this.productId = 0,
    this.productName = '',
    this.manufacturerName = '',
    this.hasPermission = false,
  });

  String get title {
    final name = productName.isNotEmpty
        ? productName
        : manufacturerName.isNotEmpty
            ? manufacturerName
            : 'USB device';
    return '$name (${vendorId.toRadixString(16)}:${productId.toRadixString(16)})';
  }

  factory FastbootDevice.fromMap(Map<String, dynamic> map) {
    return FastbootDevice(
      deviceName: asText(map['deviceName']),
      vendorId: asInt(map['vendorId']),
      productId: asInt(map['productId']),
      productName: asText(map['productName']),
      manufacturerName: asText(map['manufacturerName']),
      hasPermission: asBool(map['hasPermission']),
    );
  }
}

/// One partition as the bootloader reports it.
class FastbootPartition {
  final String name;
  final int sizeBytes;
  final String sizeText;

  const FastbootPartition({
    required this.name,
    this.sizeBytes = 0,
    this.sizeText = '',
  });

  factory FastbootPartition.fromMap(Map<String, dynamic> map) {
    return FastbootPartition(
      name: asText(map['name']),
      sizeBytes: asInt(map['sizeBytes']),
      sizeText: asText(map['sizeText']),
    );
  }
}

/// What a phone in bootloader mode says about itself.
class FastbootInfo {
  final String product;
  final String serial;
  final bool? unlocked;
  final String unlockText;
  final int slots;
  final String currentSlot;
  final List<FastbootPartition> partitions;
  final Map<String, String> variables;

  const FastbootInfo({
    this.product = '',
    this.serial = '',
    this.unlocked,
    this.unlockText = 'Not reported',
    this.slots = 0,
    this.currentSlot = '',
    this.partitions = const <FastbootPartition>[],
    this.variables = const <String, String>{},
  });

  /// The size of a partition, or 0 when the bootloader did not say.
  int sizeOf(String name) {
    for (final partition in partitions) {
      if (partition.name == name) return partition.sizeBytes;
    }
    return 0;
  }

  factory FastbootInfo.fromMap(Map<String, dynamic> map) {
    final partitions = <FastbootPartition>[];
    for (final item in asMapList(map['partitions'])) {
      partitions.add(FastbootPartition.fromMap(item));
    }

    final variables = <String, String>{};
    asMap(map['variables']).forEach((key, value) {
      variables[key] = asText(value);
    });

    final rawUnlocked = map['unlocked'];

    return FastbootInfo(
      product: asText(map['product'], 'Unknown'),
      serial: asText(map['serial']),
      unlocked: rawUnlocked is bool ? rawUnlocked : null,
      unlockText: asText(map['unlockText'], 'Not reported'),
      slots: asInt(map['slots']),
      currentSlot: asText(map['currentSlot']),
      partitions: partitions,
      variables: variables,
    );
  }
}

/// One reading from the hardware check-up.
class HardwareItem {
  final String name;
  final String value;
  final String status;
  final String advice;

  const HardwareItem({
    required this.name,
    required this.value,
    this.status = 'INFO',
    this.advice = '',
  });

  /// True when the reading needs the technician's attention.
  bool get needsAttention => status == 'ATTENTION' || status == 'FAIL';

  factory HardwareItem.fromMap(Map<String, dynamic> map) {
    return HardwareItem(
      name: asText(map['name'], 'Reading'),
      value: asText(map['value'], 'Unavailable'),
      status: asText(map['status'], 'INFO'),
      advice: asText(map['advice']),
    );
  }
}

/// One group of hardware readings, for example the battery.
class HardwareSection {
  final String title;
  final List<HardwareItem> items;

  const HardwareSection(this.title, this.items);

  int get attentionCount => items.where((item) => item.needsAttention).length;

  factory HardwareSection.fromMap(Map<String, dynamic> map) {
    final items = <HardwareItem>[];
    for (final item in asMapList(map['items'])) {
      items.add(HardwareItem.fromMap(item));
    }
    return HardwareSection(asText(map['title'], 'Readings'), items);
  }
}

/// A check the technician performs on the phone itself.
class ManualCheck {
  final String id;
  final String title;
  final String instruction;

  const ManualCheck({
    required this.id,
    required this.title,
    required this.instruction,
  });

  factory ManualCheck.fromMap(Map<String, dynamic> map) {
    return ManualCheck(
      id: asText(map['id']),
      title: asText(map['title']),
      instruction: asText(map['instruction']),
    );
  }
}

/// Everything the hardware check-up returned.
class HardwareCheckup {
  final List<HardwareSection> sections;
  final List<ManualCheck> manualChecks;
  final int totalCount;
  final int attentionCount;
  final String summary;
  final String report;

  const HardwareCheckup({
    this.sections = const <HardwareSection>[],
    this.manualChecks = const <ManualCheck>[],
    this.totalCount = 0,
    this.attentionCount = 0,
    this.summary = '',
    this.report = '',
  });

  factory HardwareCheckup.fromMap(Map<String, dynamic> map) {
    final sections = <HardwareSection>[];
    for (final section in asMapList(map['sections'])) {
      sections.add(HardwareSection.fromMap(section));
    }

    final checks = <ManualCheck>[];
    for (final check in asMapList(map['manualChecks'])) {
      checks.add(ManualCheck.fromMap(check));
    }

    return HardwareCheckup(
      sections: sections,
      manualChecks: checks,
      totalCount: asInt(map['totalCount']),
      attentionCount: asInt(map['attentionCount']),
      summary: asText(map['summary']),
      report: asText(map['report']),
    );
  }
}

/// Why one app on the connected phone is misbehaving.
class AppDiagnosisReport {
  final String packageName;
  final String versionName;
  final String versionCode;
  final String targetSdk;
  final String lastUpdated;
  final bool system;
  final bool enabled;
  final bool running;
  final String standbyBucket;
  final String background;
  final int alarmCount;
  final List<String> services;
  final List<String> crashes;
  final List<String> anrs;
  final String verdict;
  final String report;

  const AppDiagnosisReport({
    this.packageName = '',
    this.versionName = '',
    this.versionCode = '',
    this.targetSdk = '',
    this.lastUpdated = '',
    this.system = false,
    this.enabled = true,
    this.running = false,
    this.standbyBucket = '',
    this.background = '',
    this.alarmCount = 0,
    this.services = const <String>[],
    this.crashes = const <String>[],
    this.anrs = const <String>[],
    this.verdict = '',
    this.report = '',
  });

  /// True when the phone recorded a crash or a stall for this app.
  bool get hasProblems => crashes.isNotEmpty || anrs.isNotEmpty;

  factory AppDiagnosisReport.fromMap(Map<String, dynamic> map) {
    return AppDiagnosisReport(
      packageName: asText(map['packageName']),
      versionName: asText(map['versionName']),
      versionCode: asText(map['versionCode']),
      targetSdk: asText(map['targetSdk']),
      lastUpdated: asText(map['lastUpdated']),
      system: asBool(map['system']),
      enabled: map['enabled'] != false,
      running: asBool(map['running']),
      standbyBucket: asText(map['standbyBucket']),
      background: asText(map['background']),
      alarmCount: asInt(map['alarmCount']),
      services: asStringList(map['services']),
      crashes: asStringList(map['crashes']),
      anrs: asStringList(map['anrs']),
      verdict: asText(map['verdict']),
      report: asText(map['report']),
    );
  }
}

class HistoryRecord {
  final String id;
  final int timestamp;
  final String operation;
  final String category;
  final String result;
  final String details;
  final String scope;
  final String deviceLabel;
  final String deviceSerial;
  final String link;

  const HistoryRecord({
    required this.id,
    required this.timestamp,
    required this.operation,
    required this.category,
    required this.result,
    required this.details,
    required this.scope,
    required this.deviceLabel,
    required this.deviceSerial,
    required this.link,
  });

  factory HistoryRecord.fromMap(Map<String, dynamic> map) {
    return HistoryRecord(
      id: asText(map['id']),
      timestamp: asInt(map['timestamp']),
      operation: asText(map['operation'], 'Unknown operation'),
      category: asText(map['category'], 'General'),
      result: asText(map['result'], 'Unknown'),
      details: asText(map['details']),
      scope: asText(map['scope'], 'workstation'),
      deviceLabel: asText(map['deviceLabel']),
      deviceSerial: asText(map['deviceSerial']),
      link: asText(map['link']),
    );
  }

  bool get onTarget => scope == 'target';

  bool get failed => result.toLowerCase().contains('fail');
}

// ---------------------------------------------------------------------
// Bridge
// ---------------------------------------------------------------------

/// Every call to the native side goes through this class, and the live
/// connection state is kept here so all screens show the same thing.
class DeviceBridge {
  DeviceBridge._();

  static final DeviceBridge instance = DeviceBridge._();

  static const MethodChannel _channel = MethodChannel(kMethodChannelName);
  static const EventChannel _events = EventChannel(kEventChannelName);

  /// Live state of the link to the phone being serviced.
  final ValueNotifier<AdbStatus> status =
      ValueNotifier<AdbStatus>(AdbStatus.disconnected);

  /// Devices currently plugged into this phone's USB port.
  final ValueNotifier<List<UsbDeviceInfo>> usbDevices =
      ValueNotifier<List<UsbDeviceInfo>>(const <UsbDeviceInfo>[]);

  final StreamController<UsbEvent> _usbEvents =
      StreamController<UsbEvent>.broadcast();

  StreamSubscription<dynamic>? _subscription;

  Stream<UsbEvent> get usbEvents => _usbEvents.stream;

  /// Starts listening for native events. Safe to call more than once.
  void start() {
    if (_subscription != null) return;

    _subscription = _events.receiveBroadcastStream().listen(
      _onEvent,
      onError: (Object error) {
        debugPrint('Native event error: $error');
      },
    );

    unawaited(refreshStatus());
  }

  void _onEvent(dynamic raw) {
    final map = asMap(raw);
    final event = asText(map['event']);

    if (event == 'adb_state') {
      status.value = AdbStatus.fromMap(map['adb']);
      return;
    }

    final devices = <UsbDeviceInfo>[];
    for (final item in asMapList(map['devices'])) {
      devices.add(UsbDeviceInfo.fromMap(item));
    }

    usbDevices.value = devices;
    _usbEvents.add(
      UsbEvent(
        event: event,
        deviceName: asText(map['deviceName']),
        devices: devices,
      ),
    );
  }

  Future<AdbStatus> _statusCall(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    final result = await _channel.invokeMethod<dynamic>(method, arguments);
    final value = AdbStatus.fromMap(result);
    status.value = value;
    return value;
  }

  // ----- Connection -----

  Future<AdbStatus> refreshStatus() async {
    try {
      return await _statusCall('getConnectionStatus');
    } catch (error) {
      debugPrint('Unable to read the connection status: $error');
      return status.value;
    }
  }

  Future<bool> isUsbHostSupported() async {
    final result = await _channel.invokeMethod<dynamic>('isUsbHostSupported');
    return asBool(result);
  }

  Future<List<UsbDeviceInfo>> getUsbDevices() async {
    final result = await _channel.invokeMethod<dynamic>('getUsbDevices');
    final devices = <UsbDeviceInfo>[];
    for (final item in asMapList(result)) {
      devices.add(UsbDeviceInfo.fromMap(item));
    }
    usbDevices.value = devices;
    return devices;
  }

  Future<List<AdbInterfaceInfo>> getAdbInterfaces() async {
    final result = await _channel.invokeMethod<dynamic>('getAdbInterfaces');
    final interfaces = <AdbInterfaceInfo>[];
    for (final item in asMapList(result)) {
      interfaces.add(AdbInterfaceInfo.fromMap(item));
    }
    return interfaces;
  }

  /// Returns true when access was already allowed. Otherwise Android
  /// shows its prompt and the answer arrives as a USB event
  /// (`permission_granted` or `permission_denied`).
  Future<bool> requestUsbPermission(String deviceName) async {
    final result = await _channel.invokeMethod<dynamic>(
      'requestUsbPermission',
      <String, dynamic>{'deviceName': deviceName},
    );
    return asBool(asMap(result)['granted']);
  }

  Future<AdbStatus> connectUsb(String deviceName) {
    return _statusCall(
      'connectAdb',
      <String, dynamic>{'deviceName': deviceName},
    );
  }

  Future<AdbStatus> connectWireless(String host, int port) {
    return _statusCall(
      'connectWirelessAdb',
      <String, dynamic>{'host': host, 'port': port},
    );
  }

  /// Ends the session, or cancels a connection attempt in progress.
  Future<AdbStatus> disconnect() => _statusCall('disconnectAdb');

  /// Moves a USB session to Wi-Fi so the cable can be unplugged.
  Future<AdbStatus> switchToWireless() => _statusCall('switchToWireless');

  /// Puts the phone's ADB back into cable-only mode and disconnects.
  Future<void> stopWirelessDebugging() async {
    await _channel.invokeMethod<dynamic>('stopWirelessDebugging');
    await refreshStatus();
  }

  Future<Map<String, dynamic>> getKeyFingerprint() async {
    final result = await _channel.invokeMethod<dynamic>('getAdbKeyFingerprint');
    return asMap(result);
  }

  // ----- Diagnostics -----

  Future<List<NamedItem>> getTargetReports() async {
    final result = await _channel.invokeMethod<dynamic>('getDiagnosticReports');
    final reports = <NamedItem>[];
    for (final item in asMapList(result)) {
      reports.add(NamedItem(asText(item['name']), asText(item['description'])));
    }
    return reports;
  }

  Future<String> runDiagnostic(String command, {required bool onTarget}) async {
    final result = await _channel.invokeMethod<dynamic>(
      'executeStandardDiagnostic',
      <String, dynamic>{
        'command': command,
        'scope': onTarget ? 'target' : 'workstation',
      },
    );
    return asText(result, 'The report returned no text.');
  }

  // ----- Bootloader (fastboot) -----

  /// USB devices that are in bootloader mode.
  Future<List<FastbootDevice>> getFastbootDevices() async {
    final result = await _channel.invokeMethod<dynamic>('getFastbootDevices');
    final devices = <FastbootDevice>[];
    for (final item in asMapList(result)) {
      devices.add(FastbootDevice.fromMap(item));
    }
    return devices;
  }

  /// Opens the bootloader link and reads the phone.
  Future<FastbootInfo> connectFastboot(String deviceName) async {
    final result = await _channel.invokeMethod<dynamic>(
      'connectFastboot',
      <String, dynamic>{'deviceName': deviceName},
    );
    return FastbootInfo.fromMap(asMap(result));
  }

  Future<void> disconnectFastboot() async {
    await _channel.invokeMethod<dynamic>('disconnectFastboot');
  }

  Future<FastbootInfo> getFastbootInfo() async {
    final result = await _channel.invokeMethod<dynamic>('getFastbootInfo');
    return FastbootInfo.fromMap(asMap(result));
  }

  /// Restarts the phone: '', 'bootloader', 'recovery' or 'fastboot'.
  Future<void> rebootFastboot(String target) async {
    await _channel.invokeMethod<dynamic>(
      'rebootFastboot',
      <String, dynamic>{'target': target},
    );
  }

  /// Asks for an image file and copies it into the app, with its hash.
  Future<Map<String, dynamic>> pickBootImage() async {
    final result = await _channel.invokeMethod<dynamic>('pickBootImage');
    return asMap(result);
  }

  /// Writes a checked image to one partition.
  Future<Map<String, dynamic>> flashFastbootImage({
    required String partition,
    required String path,
    required String sha256,
  }) async {
    final result = await _channel.invokeMethod<dynamic>(
      'flashFastbootImage',
      <String, dynamic>{
        'partition': partition,
        'path': path,
        'sha256': sha256,
      },
    );
    return asMap(result);
  }

  /// Explains one app on the connected phone.
  Future<AppDiagnosisReport> getAppDiagnosis(String packageName) async {
    final result = await _channel.invokeMethod<dynamic>(
      'getAppDiagnosis',
      <String, dynamic>{'packageName': packageName},
    );
    return AppDiagnosisReport.fromMap(asMap(result));
  }

  /// Reads every hardware section of the connected phone.
  Future<HardwareCheckup> runHardwareCheckup() async {
    final result = await _channel.invokeMethod<dynamic>('runHardwareCheckup');
    return HardwareCheckup.fromMap(asMap(result));
  }

  Future<SecurityAuditResult> runSecurityAudit() async {
    final result = await _channel.invokeMethod<dynamic>('runSecurityAudit');
    final map = asMap(result);

    final findings = <SecurityFinding>[];
    for (final item in asMapList(map['findings'])) {
      findings.add(
        SecurityFinding(
          level: asText(item['level'], 'INFO'),
          title: asText(item['title']),
          detail: asText(item['detail']),
          advice: asText(item['advice']),
        ),
      );
    }

    return SecurityAuditResult(
      score: asInt(map['score']),
      rating: asText(map['rating'], 'UNKNOWN'),
      findings: findings,
      report: asText(map['report']),
    );
  }

  // ----- Terminal -----

  Future<List<TerminalCommandInfo>> getTerminalCommands() async {
    final result = await _channel.invokeMethod<dynamic>('getTerminalCommands');
    final commands = <TerminalCommandInfo>[];
    for (final item in asMapList(result)) {
      commands.add(
        TerminalCommandInfo(
          name: asText(item['name']),
          description: asText(item['description']),
          group: asText(item['group'], 'General'),
          needsArgument: asBool(item['needsArgument']),
        ),
      );
    }
    return commands;
  }

  Future<String> runTerminalCommand(
    String command, {
    required bool rootMode,
  }) async {
    final result = await _channel.invokeMethod<dynamic>(
      rootMode ? 'executeRootCommand' : 'executeAdbCommand',
      <String, dynamic>{'command': command},
    );
    return asText(result, 'Command completed without output.');
  }

  // ----- Repair services -----

  Future<List<RepairServiceInfo>> getRepairCatalog() async {
    final result = await _channel.invokeMethod<dynamic>('getRepairCatalog');
    final services = <RepairServiceInfo>[];
    for (final item in asMapList(result)) {
      services.add(RepairServiceInfo.fromMap(item));
    }
    return services;
  }

  /// Runs one catalog service. The native side writes the service record.
  Future<RepairOutcome> runRepair(String id, {String? packageName}) async {
    final result = await _channel.invokeMethod<dynamic>(
      'executeRepairCommand',
      <String, dynamic>{
        'operation': id,
        'packageName': packageName,
        'confirmed': true,
      },
    );
    return RepairOutcome.fromMap(asMap(result));
  }

  // ----- Apps on the connected phone -----

  Future<List<TargetApp>> getTargetApplications() async {
    final result =
        await _channel.invokeMethod<dynamic>('getTargetApplications');
    final apps = <TargetApp>[];
    for (final item in asMapList(result)) {
      apps.add(TargetApp.fromMap(item));
    }
    return apps;
  }

  Future<Map<String, dynamic>> getTargetApplicationDetails(
    String packageName,
  ) async {
    final result = await _channel.invokeMethod<dynamic>(
      'getTargetApplicationDetails',
      <String, dynamic>{'packageName': packageName},
    );
    return asMap(result);
  }

  Future<Map<String, dynamic>> backupTargetApplicationApk(
    String packageName,
  ) async {
    final result = await _channel.invokeMethod<dynamic>(
      'backupTargetApplicationApk',
      <String, dynamic>{'packageName': packageName},
    );
    return asMap(result);
  }

  Future<void> shareApkBackup(
    String backupDirectory,
    List<Map<String, dynamic>> files,
  ) async {
    await _channel.invokeMethod<dynamic>(
      'shareApplicationApkBackup',
      <String, dynamic>{'backupDirectory': backupDirectory, 'files': files},
    );
  }

  // ----- Files on the connected phone -----

  Future<List<NamedItem>> getFileShortcuts() async {
    final result =
        await _channel.invokeMethod<dynamic>('getTargetFileShortcuts');
    final shortcuts = <NamedItem>[];
    for (final item in asMapList(result)) {
      shortcuts.add(NamedItem(asText(item['name']), asText(item['path'])));
    }
    return shortcuts;
  }

  Future<RemoteFolder> listFiles(String path) async {
    final result = await _channel.invokeMethod<dynamic>(
      'listTargetFiles',
      <String, dynamic>{'path': path},
    );
    final map = asMap(result);

    final entries = <RemoteFileEntry>[];
    for (final item in asMapList(map['entries'])) {
      entries.add(RemoteFileEntry.fromMap(item));
    }

    return RemoteFolder(
      path: asText(map['path'], path),
      parent: asText(map['parent']),
      entries: entries,
    );
  }

  Future<Map<String, dynamic>> pullFile(String path) async {
    final result = await _channel.invokeMethod<dynamic>(
      'pullTargetFile',
      <String, dynamic>{'path': path},
    );
    return asMap(result);
  }

  Future<void> shareTransferredFile(String localPath) async {
    await _channel.invokeMethod<dynamic>(
      'shareTransferredFile',
      <String, dynamic>{'path': localPath},
    );
  }

  /// Lets the technician choose a file on this phone and sends it to
  /// [remoteDirectory]. The map has `cancelled: true` when nothing was
  /// chosen.
  Future<Map<String, dynamic>> pickAndPushFile(String remoteDirectory) async {
    final result = await _channel.invokeMethod<dynamic>(
      'pickAndPushFile',
      <String, dynamic>{'remoteDirectory': remoteDirectory},
    );
    return asMap(result);
  }

  // ----- Service history -----

  /// Records a report or check. Repairs are recorded by the native side.
  Future<void> saveHistory({
    required String operation,
    required String category,
    required String result,
    String details = '',
    required bool onTarget,
  }) async {
    try {
      await _channel.invokeMethod<dynamic>(
        'saveRepairHistory',
        <String, dynamic>{
          'operation': operation,
          'category': category,
          'result': result,
          'details': details,
          'scope': onTarget ? 'target' : 'workstation',
        },
      );
    } catch (error) {
      debugPrint('Unable to save the service record: $error');
    }
  }

  Future<List<HistoryRecord>> getHistory() async {
    final result = await _channel.invokeMethod<dynamic>('getRepairHistory');
    final records = <HistoryRecord>[];
    for (final item in asMapList(result)) {
      records.add(HistoryRecord.fromMap(item));
    }
    return records;
  }

  Future<void> clearHistory() async {
    await _channel.invokeMethod<dynamic>('clearRepairHistory');
  }

  Future<void> exportHistory() async {
    await _channel.invokeMethod<dynamic>('exportRepairHistory');
  }
}
