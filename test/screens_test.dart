// Runs every screen against a pretend native side, on a small phone
// screen, so layout problems and broken wiring are caught without a
// real device.

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:ultimate_debugging_tool/app/app_theme.dart';
import 'package:ultimate_debugging_tool/main.dart';
import 'package:ultimate_debugging_tool/screens/app_workbench_screen.dart';
import 'package:ultimate_debugging_tool/screens/bootloader_screen.dart';
import 'package:ultimate_debugging_tool/screens/command_terminal_screen.dart';
import 'package:ultimate_debugging_tool/screens/connection_screen.dart';
import 'package:ultimate_debugging_tool/screens/diagnostics_screen.dart';
import 'package:ultimate_debugging_tool/screens/file_manager_screen.dart';
import 'package:ultimate_debugging_tool/screens/hardware_check_screen.dart';
import 'package:ultimate_debugging_tool/screens/recovery_update_screen.dart';
import 'package:ultimate_debugging_tool/screens/repair_history_screen.dart';
import 'package:ultimate_debugging_tool/screens/repair_tools_screen.dart';
import 'package:ultimate_debugging_tool/screens/reset_screen.dart';
import 'package:ultimate_debugging_tool/screens/security_center_screen.dart';
import 'package:ultimate_debugging_tool/screens/target_apps_screen.dart';
import 'package:ultimate_debugging_tool/services/device_bridge.dart';

const String usbName = '/dev/bus/usb/001/002';

const Map<String, Object?> disconnectedStatus = <String, Object?>{
  'link': 'NONE',
  'status': 'DISCONNECTED',
  'connected': false,
  'busy': false,
};

const Map<String, Object?> connectedStatus = <String, Object?>{
  'link': 'USB',
  'status': 'CONNECTED',
  'connected': true,
  'busy': true,
  'target': usbName,
  'deviceLabel': 'Samsung SM-A125F',
  'device': <String, Object?>{
    'manufacturer': 'samsung',
    'model': 'SM-A125F',
    'android': '12',
    'sdk': '31',
    'securityPatch': '2024-01-01',
    'serial': 'R58N1',
  },
};

const String fastbootName = '/dev/bus/usb/002/003';

const Map<String, Object?> fastbootInfo = <String, Object?>{
  'product': 'a12',
  'serial': 'R58N1',
  'unlocked': false,
  'unlockText': 'Locked',
  'slots': 2,
  'currentSlot': 'a',
  'partitions': <Object?>[
    <String, Object?>{
      'name': 'boot',
      'sizeBytes': 67108864,
      'sizeText': '64.00 MB',
    },
    <String, Object?>{
      'name': 'recovery',
      'sizeBytes': 67108864,
      'sizeText': '64.00 MB',
    },
  ],
  'variables': <String, Object?>{'version': '0.4'},
};

bool fastbootVisible = true;
bool nativeConnected = false;
final List<MethodCall> calls = <MethodCall>[];

bool called(String method) => calls.any((call) => call.method == method);

Map<String, dynamic> argsOf(String method) {
  return asMap(calls.lastWhere((call) => call.method == method).arguments);
}

Map<String, Object?> folder(String path) {
  if (path == '/sdcard/DCIM') {
    return <String, Object?>{
      'path': '/sdcard/DCIM',
      'parent': '/sdcard',
      'entries': <Object?>[
        <String, Object?>{
          'name': 'photo.jpg',
          'path': '/sdcard/DCIM/photo.jpg',
          'type': 'file',
          'sizeBytes': 90000,
          'modified': 1700000000000,
          'permissions': 'rw-rw----',
        },
      ],
    };
  }

  return <String, Object?>{
    'path': '/sdcard',
    'parent': '/',
    'entries': <Object?>[
      <String, Object?>{
        'name': 'DCIM',
        'path': '/sdcard/DCIM',
        'type': 'directory',
        'sizeBytes': 4096,
        'modified': 1700000000000,
        'permissions': 'rwxrwx---',
      },
      <String, Object?>{
        'name': 'notes.txt',
        'path': '/sdcard/notes.txt',
        'type': 'file',
        'sizeBytes': 5,
        'modified': 1700000000000,
        'permissions': 'rw-rw----',
      },
    ],
  };
}

/// Stands in for MainActivity.kt.
Future<Object?> fakeNative(MethodCall call) async {
  calls.add(call);
  final args = asMap(call.arguments);

  switch (call.method) {
    case 'getConnectionStatus':
      return nativeConnected ? connectedStatus : disconnectedStatus;
    case 'isUsbHostSupported':
      return true;
    case 'getUsbDevices':
      return <Object?>[
        <String, Object?>{
          'deviceName': usbName,
          'deviceId': 1002,
          'vendorId': 0x04e8,
          'productId': 0x6860,
          'manufacturerName': 'samsung',
          'productName': 'SM-A125F',
          'interfaceCount': 2,
          'hasPermission': true,
        },
      ];
    case 'getAdbInterfaces':
      return <Object?>[
        <String, Object?>{
          'deviceName': usbName,
          'interfaceId': 1,
          'hasAdbEndpoints': true,
          'hasPermission': true,
        },
      ];
    case 'requestUsbPermission':
      return <String, Object?>{'granted': true, 'requested': false};
    case 'connectAdb':
      nativeConnected = true;
      return connectedStatus;
    case 'disconnectAdb':
      nativeConnected = false;
      return disconnectedStatus;
    case 'getAdbKeyFingerprint':
      return <String, Object?>{
        'fingerprint': 'AA:BB:CC:DD',
        'sha256': 'x',
        'storage': 'Android Keystore',
      };
    case 'getDiagnosticReports':
      return <Object?>[
        <String, Object?>{
          'name': 'battery',
          'description': 'Battery condition and charging',
        },
        <String, Object?>{'name': 'storage', 'description': 'Storage usage'},
      ];
    case 'executeStandardDiagnostic':
      return 'BATTERY REPORT\nLevel: 80%';
    case 'runSecurityAudit':
      return <String, Object?>{
        'score': 65,
        'rating': 'AT RISK',
        'report': 'SECURITY AUDIT\nScore: 65',
        'findings': <Object?>[
          <String, Object?>{
            'level': 'OK',
            'title': 'SELinux',
            'detail': 'Enforcing.',
            'advice': '',
          },
          <String, Object?>{
            'level': 'CRITICAL',
            'title': 'Security patch',
            'detail': 'Last security update is 2022-01-01.',
            'advice': 'Install system updates.',
          },
        ],
      };
    case 'runHardwareCheckup':
      return <String, Object?>{
        'totalCount': 3,
        'attentionCount': 1,
        'summary': '3 readings, 1 needing attention.',
        'report': 'HARDWARE CHECK\nPhone: samsung SM-A125F',
        'sections': <Object?>[
          <String, Object?>{
            'title': 'Identity',
            'items': <Object?>[
              <String, Object?>{
                'name': 'Phone',
                'value': 'samsung SM-A125F',
                'status': 'INFO',
                'advice': '',
              },
            ],
          },
          <String, Object?>{
            'title': 'Battery',
            'items': <Object?>[
              <String, Object?>{
                'name': 'Charge level',
                'value': '74 %',
                'status': 'INFO',
                'advice': '',
              },
              <String, Object?>{
                'name': 'Battery temperature',
                'value': '45.5 \u00b0C',
                'status': 'ATTENTION',
                'advice': 'Warm. Keep an eye on it while charging.',
              },
            ],
          },
        ],
        'manualChecks': <Object?>[
          <String, Object?>{
            'id': 'screen',
            'title': 'Screen and pixels',
            'instruction': 'Open a white picture, then a black one.',
          },
          <String, Object?>{
            'id': 'charging',
            'title': 'Charging port',
            'instruction': 'Plug in the charger and watch the battery icon.',
          },
        ],
      };
    case 'getTerminalCommands':
      return <Object?>[
        <String, Object?>{
          'name': 'device-info',
          'description': 'Model and Android version',
          'group': 'Device',
          'needsArgument': false,
        },
        <String, Object?>{
          'name': 'app-info',
          'description': 'Details of one app',
          'group': 'Apps',
          'needsArgument': true,
        },
      ];
    case 'executeAdbCommand':
      if (args['command'] != 'device-info') {
        throw PlatformException(
          code: 'COMMAND_NOT_ALLOWED',
          message: 'That command is not in the allowed list.',
        );
      }
      return 'model: SM-A125F';
    case 'getRepairCatalog':
      return <Object?>[
        <String, Object?>{
          'id': 'restart-wifi',
          'title': 'Restart Wi-Fi',
          'category': 'Network',
          'risk': 'LOW',
          'needsPackage': false,
          'dataPreserved': true,
          'description': 'Turns Wi-Fi off and on again.',
          'warning': '',
          'minSdk': 0,
          'endsSession': false,
        },
        <String, Object?>{
          'id': 'clear-app-data',
          'title': 'Clear app data',
          'category': 'Apps',
          'risk': 'HIGH',
          'needsPackage': true,
          'dataPreserved': false,
          'description': 'Erases the data of one app.',
          'warning': 'The app is signed out and its data is lost.',
          'minSdk': 0,
          'endsSession': false,
        },
      ];
    case 'executeRepairCommand':
      return <String, Object?>{
        'success': true,
        'operation': args['operation'],
        'title': 'Repair',
        'message': 'The service finished.',
        'verification': 'Checked on the phone.',
        'output': '',
        'endsSession': false,
      };
    case 'getTargetApplications':
      return <Object?>[
        <String, Object?>{
          'packageName': 'com.example.app',
          'isSystemApp': false,
          'enabled': true,
        },
        <String, Object?>{
          'packageName': 'com.android.systemui',
          'isSystemApp': true,
          'enabled': true,
        },
      ];
    case 'pickUpdatePackage':
      return <String, Object?>{
        'cancelled': false,
        'path': '/data/user/0/app/files/updates/1-ota.zip',
        'fileName': 'ota.zip',
        'sizeBytes': 1073741824,
        'sha256': 'feedface99',
        'looksLikePackage': true,
      };
    case 'sideloadUpdatePackage':
      return <String, Object?>{
        'bytesSent': 2147483648,
        'blocksServed': 32768,
        'packageBytes': 1073741824,
        'message': 'The phone accepted the package and is installing it.',
        'sha256': args['sha256'],
        'fileName': 'ota.zip',
      };
    case 'getFastbootDevices':
      if (!fastbootVisible) return <Object?>[];
      return <Object?>[
        <String, Object?>{
          'deviceName': fastbootName,
          'vendorId': 0x04e8,
          'productId': 0x6860,
          'manufacturerName': 'samsung',
          'productName': 'SM-A125F',
          'interfaceId': 0,
          'hasPermission': true,
        },
      ];
    case 'connectFastboot':
      return fastbootInfo;
    case 'getFastbootInfo':
      return fastbootInfo;
    case 'pickBootImage':
      return <String, Object?>{
        'cancelled': false,
        'path': '/data/user/0/app/files/boot_images/1-boot.img',
        'fileName': 'boot.img',
        'sizeBytes': 33554432,
        'sha256': 'abc123def456',
      };
    case 'flashFastbootImage':
      return <String, Object?>{
        'partition': args['partition'],
        'sizeBytes': 33554432,
        'sha256': args['sha256'],
        'message': 'The boot partition was written.',
      };
    case 'rebootFastboot':
      return true;
    case 'disconnectFastboot':
      return true;
    case 'getAppDiagnosis':
      return <String, Object?>{
        'packageName': args['packageName'],
        'versionName': '2.24.1',
        'versionCode': '2241',
        'targetSdk': '34',
        'system': false,
        'enabled': true,
        'running': true,
        'standbyBucket': 'Working set',
        'background': 'Blocked from running in the background',
        'alarmCount': 3,
        'services': <Object?>['com.example.app/.Sync'],
        'crashes': <Object?>[
          'E AndroidRuntime: FATAL EXCEPTION: main',
          'E AndroidRuntime: Process: com.example.app, PID: 4321',
        ],
        'anrs': <Object?>[],
        'verdict': 'The app has crashed recently.',
        'report': 'APP DIAGNOSIS - com.example.app\nVersion: 2.24.1',
      };
    case 'getTargetApplicationDetails':
      return <String, Object?>{
        'packageName': args['packageName'],
        'versionName': '2.24.1',
        'versionCode': '2241',
        'targetSdk': '34',
        'minSdk': '23',
        'installer': 'com.android.vending',
        'system': false,
        'debuggable': false,
        'enabled': true,
        'requestedPermissions': <Object?>['android.permission.CAMERA'],
        'grantedPermissions': <Object?>['android.permission.CAMERA'],
      };
    case 'getTargetFileShortcuts':
      return <Object?>[
        <String, Object?>{'name': 'Downloads', 'path': '/sdcard/Download'},
      ];
    case 'listTargetFiles':
      return folder(asText(args['path']));
    case 'pullTargetFile':
      return <String, Object?>{
        'remotePath': args['path'],
        'localPath': '/data/user/0/app/files/transfers/1-photo.jpg',
        'fileName': 'photo.jpg',
        'sizeBytes': 90000,
        'sha256': 'ABCDEF0123',
      };
    case 'getRepairHistory':
      return <Object?>[
        <String, Object?>{
          'id': '2',
          'timestamp': 1700000100000,
          'operation': 'Restart Wi-Fi',
          'category': 'Repair - Network',
          'result': 'Completed',
          'details': 'Wi-Fi is on.',
          'scope': 'target',
          'deviceLabel': 'Tecno Spark 10',
          'deviceSerial': 'ABC',
          'link': 'USB',
        },
        <String, Object?>{
          'id': '1',
          'timestamp': 1700000000000,
          'operation': 'Report: Battery',
          'category': 'Diagnostics',
          'result': 'Failed',
          'details': 'No answer.',
          'scope': 'workstation',
        },
      ];
    default:
      return null;
  }
}

void connectPhone() {
  nativeConnected = true;
  DeviceBridge.instance.status.value = AdbStatus.fromMap(connectedStatus);
}

/// Lets pending native answers and short animations finish. Not
/// pumpAndSettle: progress spinners never settle.
Future<void> settle(WidgetTester tester) async {
  for (var i = 0; i < 6; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

/// Lets temporary messages disappear before the test ends.
Future<void> finish(WidgetTester tester) async {
  await tester.pump(const Duration(seconds: 8));
  await settle(tester);
}

Future<void> show(WidgetTester tester, Widget screen) async {
  // A small phone: 360 x 740.
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = const Size(360, 740);
  addTearDown(tester.view.reset);

  await tester.pumpWidget(MaterialApp(theme: buildAppTheme(), home: screen));
  await settle(tester);
}

/// A very tall phone, so long screens build completely and a test can
/// reach a control without scrolling. The 360 pixel width is kept.
Future<void> showTall(WidgetTester tester, Widget screen) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = const Size(360, 4000);
  addTearDown(tester.view.reset);

  await tester.pumpWidget(MaterialApp(theme: buildAppTheme(), home: screen));
  await settle(tester);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    calls.clear();
    nativeConnected = false;
    fastbootVisible = true;
    DeviceBridge.instance.status.value = AdbStatus.disconnected;

    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      const MethodChannel(kMethodChannelName),
      fakeNative,
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel(kEventChannelName),
      (call) async => null,
    );
  });

  testWidgets('dashboard follows the live connection', (tester) async {
    // Tall, so every group heading is built and can be found.
    await showTall(tester, const DashboardScreen());

    expect(find.text('No phone connected'), findsOneWidget);
    expect(find.text('Connect a phone'), findsOneWidget);
    expect(find.text('DIAGNOSE'), findsOneWidget);
    expect(find.text('REPAIR'), findsOneWidget);
    expect(find.text('FIRMWARE'), findsOneWidget);
    expect(find.text('Diagnostics'), findsOneWidget);
    expect(find.text('Bootloader'), findsOneWidget);

    connectPhone();
    await settle(tester);

    expect(find.text('Samsung SM-A125F'), findsOneWidget);
    expect(find.text('Manage connection'), findsOneWidget);
    expect(find.text('No phone connected'), findsNothing);

    await finish(tester);
  });

  testWidgets('connection screen connects a USB phone', (tester) async {
    await show(tester, const ConnectionScreen());

    expect(find.text('samsung SM-A125F'), findsOneWidget);
    expect(find.text('USB DEBUGGING ON'), findsOneWidget);

    await tester.ensureVisible(find.text('Connect'));
    await settle(tester);
    await tester.tap(find.text('Connect'));
    await settle(tester);

    expect(argsOf('requestUsbPermission')['deviceName'], usbName);
    expect(argsOf('connectAdb')['deviceName'], usbName);
    expect(DeviceBridge.instance.status.value.connected, isTrue);
    expect(find.text('Connected to Samsung SM-A125F.'), findsOneWidget);

    await finish(tester);
  });

  testWidgets('diagnostics ask the right phone', (tester) async {
    connectPhone();
    await show(tester, const DiagnosticsScreen());

    await tester.tap(find.text('Battery'));
    await settle(tester);

    expect(find.textContaining('Level: 80%'), findsWidgets);
    expect(argsOf('executeStandardDiagnostic')['scope'], 'target');
    expect(argsOf('saveRepairHistory')['scope'], 'target');

    await tester.tap(find.text('This phone'));
    await settle(tester);
    await tester.ensureVisible(find.text('Battery'));
    await settle(tester);
    await tester.tap(find.text('Battery'));
    await settle(tester);

    expect(argsOf('executeStandardDiagnostic')['scope'], 'workstation');
    expect(argsOf('saveRepairHistory')['scope'], 'workstation');

    await finish(tester);
  });

  testWidgets('diagnostics explain when no phone is connected', (tester) async {
    await show(tester, const DiagnosticsScreen());

    await tester.tap(find.text('Connected phone'));
    await settle(tester);

    expect(find.text('Connect a phone first'), findsOneWidget);
    expect(called('executeStandardDiagnostic'), isFalse);

    await finish(tester);
  });

  testWidgets('terminal runs a command and shows a refusal', (tester) async {
    connectPhone();
    await show(tester, const CommandTerminalScreen());

    await tester.enterText(find.byType(TextField), 'device-info');
    await settle(tester);
    await tester.tap(find.byIcon(Icons.send));
    await settle(tester);

    expect(argsOf('executeAdbCommand')['command'], 'device-info');
    expect(find.text('\$ device-info'), findsOneWidget);
    expect(find.textContaining('model: SM-A125F'), findsWidgets);

    await tester.enterText(find.byType(TextField), 'rm -rf /sdcard');
    await settle(tester);
    await tester.tap(find.byIcon(Icons.send));
    await settle(tester);

    expect(find.textContaining('not in the allowed list'), findsWidgets);

    await finish(tester);
  });

  testWidgets('a safe repair asks once, runs and reports', (tester) async {
    connectPhone();
    await show(tester, const RepairToolsScreen());

    await tester.tap(find.text('Restart Wi-Fi'));
    await settle(tester);

    expect(find.text('KEEPS DATA'), findsOneWidget);
    expect(called('executeRepairCommand'), isFalse);

    await tester.tap(find.text('Run'));
    await settle(tester);

    final args = argsOf('executeRepairCommand');
    expect(args['operation'], 'restart-wifi');
    expect(args['confirmed'], true);
    expect(args['packageName'], isNull);
    expect(find.text('Done'), findsOneWidget);
    expect(find.text('The service finished.'), findsOneWidget);

    await tester.tap(find.text('Close'));
    await finish(tester);
  });

  testWidgets('a repair that erases data needs the owner\'s agreement',
      (tester) async {
    connectPhone();
    await show(tester, const RepairToolsScreen());

    await tester.ensureVisible(find.text('Clear app data'));
    await settle(tester);
    await tester.tap(find.text('Clear app data'));
    await settle(tester);

    // The app is chosen from the connected phone; system apps are hidden.
    expect(find.text('com.android.systemui'), findsNothing);
    await tester.tap(find.text('com.example.app'));
    await settle(tester);

    // "Run" does nothing until the box is ticked.
    await tester.tap(find.text('Run'));
    await settle(tester);
    expect(called('executeRepairCommand'), isFalse);

    await tester.ensureVisible(find.byType(Checkbox));
    await tester.tap(find.byType(Checkbox));
    await settle(tester);
    await tester.tap(find.text('Run'));
    await settle(tester);

    final args = argsOf('executeRepairCommand');
    expect(args['operation'], 'clear-app-data');
    expect(args['packageName'], 'com.example.app');
    expect(find.text('Done'), findsOneWidget);

    await tester.tap(find.text('Close'));
    await finish(tester);
  });

  testWidgets('repairs cannot start without a phone', (tester) async {
    await show(tester, const RepairToolsScreen());

    await tester.tap(find.text('Restart Wi-Fi'));
    await settle(tester);

    expect(find.text('Run'), findsNothing);
    expect(called('executeRepairCommand'), isFalse);

    await finish(tester);
  });

  testWidgets('apps of the connected phone open their details',
      (tester) async {
    connectPhone();
    await show(tester, const TargetAppsScreen());

    expect(find.text('com.example.app'), findsOneWidget);
    expect(find.text('com.android.systemui'), findsNothing);

    await tester.tap(find.text('com.example.app'));
    await settle(tester);

    expect(argsOf('getTargetApplicationDetails')['packageName'],
        'com.example.app');
    expect(find.text('2.24.1'), findsWidgets);
    expect(find.text('Back up the APK to this phone'), findsOneWidget);

    await finish(tester);
  });

  testWidgets('file manager browses and copies a file', (tester) async {
    connectPhone();
    await show(tester, const FileManagerScreen());

    expect(find.text('DCIM'), findsOneWidget);
    expect(find.text('notes.txt'), findsOneWidget);

    await tester.tap(find.text('DCIM'));
    await settle(tester);

    expect(argsOf('listTargetFiles')['path'], '/sdcard/DCIM');

    await tester.tap(find.text('photo.jpg'));
    await settle(tester);
    await tester.tap(find.text('Copy to this phone'));
    await settle(tester);

    expect(argsOf('pullTargetFile')['path'], '/sdcard/DCIM/photo.jpg');
    expect(find.text('File copied'), findsOneWidget);
    expect(argsOf('saveRepairHistory')['scope'], 'target');

    await tester.tap(find.text('Close'));
    await finish(tester);
  });

  testWidgets('security center shows the score and findings', (tester) async {
    connectPhone();
    await show(tester, const SecurityCenterScreen());

    await tester.tap(find.text('Run security audit'));
    await settle(tester);

    expect(find.text('AT RISK'), findsOneWidget);
    expect(find.text('65'), findsOneWidget);

    // The findings sit below the score; the most serious comes first.
    await tester.scrollUntilVisible(
      find.text('Security patch'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('Security patch'), findsOneWidget);
    expect(find.text('What to do: Install system updates.'), findsOneWidget);
    expect(argsOf('saveRepairHistory')['operation'], 'Security audit');

    await finish(tester);
  });

  testWidgets('history names the phone and can be filtered', (tester) async {
    await show(tester, const RepairHistoryScreen());

    expect(find.text('Restart Wi-Fi'), findsOneWidget);
    expect(find.text('Tecno Spark 10 - serial ABC - USB'), findsOneWidget);
    expect(find.text('Report: Battery'), findsOneWidget);

    await tester.tap(find.text('Failed'));
    await settle(tester);

    expect(find.text('Restart Wi-Fi'), findsNothing);
    expect(find.text('Report: Battery'), findsOneWidget);

    await finish(tester);
  });

  testWidgets('hardware check reads the phone and records a hand check',
      (tester) async {
    connectPhone();
    await showTall(tester, const HardwareCheckScreen());

    expect(called('runHardwareCheckup'), isTrue);
    expect(find.text('3 readings, 1 needing attention.'), findsOneWidget);
    expect(find.text('samsung SM-A125F'), findsOneWidget);
    expect(find.text('Warm. Keep an eye on it while charging.'), findsOneWidget);
    expect(find.text('1 to check'), findsOneWidget);

    expect(find.text('Screen and pixels'), findsOneWidget);
    await tester.tap(find.byKey(const Key('hardware-pass-screen')));
    await settle(tester);

    final args = argsOf('saveRepairHistory');
    expect(args['category'], 'Hardware check');
    expect(args['operation'], 'Hardware check: Screen and pixels');
    expect(args['result'], 'Pass');
    expect(args['scope'], 'target');
    expect(find.text('PASSED'), findsOneWidget);

    await finish(tester);
  });

  testWidgets('hardware check needs a phone first', (tester) async {
    await show(tester, const HardwareCheckScreen());

    expect(find.text('Connect a phone first'), findsOneWidget);
    expect(called('runHardwareCheckup'), isFalse);

    await finish(tester);
  });

  testWidgets('reset will not restart the phone without the owner agreement',
      (tester) async {
    connectPhone();
    await showTall(tester, const ResetScreen());

    expect(find.text('This tool does not remove locks'), findsOneWidget);

    final restart = find.byKey(const Key('reset-restart'));

    // The button is there but does nothing until the box is ticked.
    expect(tester.widget<FilledButton>(restart).onPressed, isNull);

    await tester.tap(find.byKey(const Key('reset-approved')));
    await settle(tester);

    expect(tester.widget<FilledButton>(restart).onPressed, isNotNull);

    await tester.tap(restart);
    await settle(tester);

    // A second, plain warning stands between the button and the phone.
    expect(called('executeRepairCommand'), isFalse);
    expect(find.text('Restart into recovery?'), findsOneWidget);

    await tester.tap(find.text('Restart'));
    await settle(tester);

    final args = argsOf('executeRepairCommand');
    expect(args['operation'], 'reboot-recovery');
    expect(args['confirmed'], true);

    await finish(tester);
  });

  testWidgets('a reset is written into the service history', (tester) async {
    connectPhone();
    await showTall(tester, const ResetScreen());

    await tester.tap(find.byKey(const Key('reset-record')));
    await settle(tester);

    final args = argsOf('saveRepairHistory');
    expect(args['category'], 'Reset');
    expect(args['operation'], 'Factory reset');
    expect(args['scope'], 'target');
    expect(find.text('Reset recorded'), findsOneWidget);

    await finish(tester);
  });

  testWidgets('the app workbench explains an app and clears its cache',
      (tester) async {
    connectPhone();
    await showTall(tester, const AppWorkbenchScreen());

    expect(called('getTargetApplications'), isTrue);
    expect(find.text('com.example.app'), findsOneWidget);

    await tester.tap(find.text('com.example.app'));
    await settle(tester);

    expect(argsOf('getAppDiagnosis')['packageName'], 'com.example.app');
    expect(find.text('The app has crashed recently.'), findsOneWidget);
    expect(find.textContaining('APP DIAGNOSIS'), findsWidgets);

    await tester.tap(find.byKey(const Key('app-action-clear-app-cache')));
    await settle(tester);
    expect(find.text('Clear only the cache?'), findsOneWidget);

    await tester.tap(find.text('Do it'));
    await settle(tester);

    final args = argsOf('executeRepairCommand');
    expect(args['operation'], 'clear-app-cache');
    expect(args['packageName'], 'com.example.app');
    expect(args['confirmed'], true);

    await finish(tester);
  });

  testWidgets('the app workbench needs a phone first', (tester) async {
    await show(tester, const AppWorkbenchScreen());

    expect(find.text('Connect a phone first'), findsOneWidget);
    expect(called('getTargetApplications'), isFalse);

    await finish(tester);
  });

  testWidgets('the bootloader reads a phone in fastboot mode',
      (tester) async {
    await showTall(tester, const BootloaderScreen());

    expect(called('getFastbootDevices'), isTrue);
    expect(find.textContaining('SM-A125F'), findsWidgets);

    await tester.tap(find.text('Connect'));
    await settle(tester);

    expect(argsOf('connectFastboot')['deviceName'], fastbootName);
    expect(find.text('a12'), findsOneWidget);
    expect(find.text('LOCKED'), findsOneWidget);
    expect(find.text('Serial R58N1 - slot a of 2'), findsOneWidget);
    expect(find.text('64.00 MB'), findsWidgets);

    await finish(tester);
  });

  testWidgets('flashing needs the partition typed out first',
      (tester) async {
    await showTall(tester, const BootloaderScreen());

    await tester.tap(find.text('Connect'));
    await settle(tester);
    await tester.tap(find.byKey(const Key('boot-pick-image')));
    await settle(tester);

    expect(called('pickBootImage'), isTrue);
    expect(find.text('boot.img'), findsOneWidget);
    expect(find.textContaining('SHA-256 abc123def456'), findsOneWidget);

    // Nothing happens until the partition name is typed.
    final flash = find.byKey(const Key('boot-flash'));
    expect(tester.widget<FilledButton>(flash).onPressed, isNull);

    await tester.enterText(find.byType(TextField).last, 'boot');
    await settle(tester);
    expect(tester.widget<FilledButton>(flash).onPressed, isNotNull);

    await tester.tap(flash);
    await settle(tester);
    expect(find.text('Write to the boot partition?'), findsOneWidget);

    await tester.tap(find.text('Write it'));
    await settle(tester);

    final args = argsOf('flashFastbootImage');
    expect(args['partition'], 'boot');
    expect(args['sha256'], 'abc123def456');
    expect(args['path'], endsWith('1-boot.img'));
    expect(argsOf('saveRepairHistory')['category'], 'Bootloader');
    expect(find.text('The boot partition was written.'), findsWidgets);

    await finish(tester);
  });

  testWidgets('the bootloader explains when no phone is in fastboot',
      (tester) async {
    fastbootVisible = false;
    await showTall(tester, const BootloaderScreen());

    expect(find.text('No phone is in bootloader mode'), findsOneWidget);
    expect(called('connectFastboot'), isFalse);

    await finish(tester);
  });

  testWidgets('an update package is checked and sideloaded',
      (tester) async {
    connectPhone();
    await showTall(tester, const RecoveryUpdateScreen());

    await tester.tap(find.byKey(const Key('update-pick')));
    await settle(tester);

    expect(called('pickUpdatePackage'), isTrue);
    expect(find.text('ota.zip'), findsOneWidget);
    expect(find.textContaining('SHA-256 feedface99'), findsOneWidget);

    await tester.tap(find.byKey(const Key('update-send')));
    await settle(tester);
    expect(find.text('Send the update package?'), findsOneWidget);

    await tester.tap(find.text('Send it'));
    await settle(tester);

    final args = argsOf('sideloadUpdatePackage');
    expect(args['path'], endsWith('1-ota.zip'));
    expect(args['sha256'], 'feedface99');

    expect(argsOf('saveRepairHistory')['category'], 'Firmware');
    expect(argsOf('saveRepairHistory')['operation'], 'Update from ADB');
    expect(
      find.textContaining('is installing it'),
      findsOneWidget,
    );

    await finish(tester);
  });

  testWidgets('the update screen asks for a phone in recovery',
      (tester) async {
    await showTall(tester, const RecoveryUpdateScreen());

    expect(find.text('Connect the phone in recovery'), findsOneWidget);
    expect(called('pickUpdatePackage'), isFalse);

    await finish(tester);
  });
}
