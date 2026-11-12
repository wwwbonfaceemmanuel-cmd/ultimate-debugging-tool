import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ultimate_debugging_tool/app/app_theme.dart';
import 'package:ultimate_debugging_tool/screens/repair_tools_screen.dart';
import 'package:ultimate_debugging_tool/services/device_bridge.dart';

void main() {
  group('safe readers', () {
    test('tolerate missing and wrongly typed values', () {
      expect(asMap(null), isEmpty);
      expect(asMap('text'), isEmpty);
      expect(asMap(<Object?, Object?>{'a': 1})['a'], 1);
      expect(asMapList(null), isEmpty);
      expect(asMapList(<Object?>[<Object?, Object?>{'a': 1}, 'x']).length, 2);
      expect(asStringList(<Object?>['a', null, 3]), <String>['a', '3']);
      expect(asText(null, 'fallback'), 'fallback');
      expect(asText('', 'fallback'), 'fallback');
      expect(asInt('42'), 42);
      expect(asInt(3.9), 3);
      expect(asInt('x', 7), 7);
      expect(asBool(null), isFalse);
      expect(asBool(true), isTrue);
    });

    test('format sizes and dates', () {
      expect(formatBytes(512), '512 B');
      expect(formatBytes(1536), '1.5 KB');
      expect(formatBytes(5 * 1024 * 1024), '5.0 MB');
      expect(formatDateTime(0), '');
      expect(formatDateTime(1700000000000).length, 16);
    });

    test('describe errors in plain words', () {
      expect(
        describeError(PlatformException(code: 'X', message: 'Phone unplugged.')),
        'Phone unplugged.',
      );
      expect(
        describeError(PlatformException(code: 'ADB_TIMEOUT')),
        contains('ADB_TIMEOUT'),
      );
      expect(errorCode(PlatformException(code: 'ADB_CANCELLED')), 'ADB_CANCELLED');
      expect(errorCode(StateError('x')), '');
    });
  });

  group('AdbStatus', () {
    test('defaults to disconnected for an empty answer', () {
      final status = AdbStatus.fromMap(null);

      expect(status.connected, isFalse);
      expect(status.link, 'NONE');
      expect(status.title, 'No phone connected');
      expect(status.connecting, isFalse);
    });

    test('reads a connected USB session', () {
      final status = AdbStatus.fromMap(<Object?, Object?>{
        'link': 'USB',
        'status': 'CONNECTED',
        'connected': true,
        'busy': true,
        'target': '/dev/bus/usb/001/002',
        'deviceLabel': 'Samsung SM-A125F',
        'device': <Object?, Object?>{'android': '12', 'serial': 'R58N1', 'sdk': '31'},
      });

      expect(status.connected, isTrue);
      expect(status.isUsb, isTrue);
      expect(status.title, 'Samsung SM-A125F');
      expect(status.subtitle, contains('USB cable'));
      expect(status.subtitle, contains('Android 12'));
      expect(status.device['serial'], 'R58N1');
    });

    test('explains the approval prompt', () {
      final status = AdbStatus.fromMap(<Object?, Object?>{
        'link': 'USB',
        'status': 'AUTHENTICATION_REQUIRED',
        'connected': false,
        'busy': true,
      });

      expect(status.waitingForApproval, isTrue);
      expect(status.connecting, isTrue);
      expect(status.subtitle, contains('Allow'));
    });

    test('shows the last error when disconnected', () {
      final status = AdbStatus.fromMap(<Object?, Object?>{
        'status': 'FAILED',
        'error': 'The phone refused the connection.',
      });

      expect(status.connected, isFalse);
      expect(status.subtitle, 'The phone refused the connection.');
    });
  });

  group('models', () {
    test('history record keeps the phone identity', () {
      final record = HistoryRecord.fromMap(<String, dynamic>{
        'id': 'a',
        'timestamp': 1700000000000,
        'operation': 'Restart Wi-Fi',
        'category': 'Repair - Network',
        'result': 'Failed',
        'scope': 'target',
        'deviceLabel': 'Tecno Spark 10',
        'deviceSerial': 'ABC',
        'link': 'USB',
      });

      expect(record.onTarget, isTrue);
      expect(record.failed, isTrue);
      expect(record.deviceSerial, 'ABC');
    });

    test('old history records without a scope belong to this phone', () {
      final record = HistoryRecord.fromMap(<String, dynamic>{'operation': 'Old'});

      expect(record.onTarget, isFalse);
      expect(record.failed, isFalse);
    });

    test('repair service reads the data-loss flag', () {
      final service = RepairServiceInfo.fromMap(<String, dynamic>{
        'id': 'clear-app-data',
        'title': 'Clear app data',
        'risk': 'HIGH',
        'needsPackage': true,
        'dataPreserved': false,
      });

      expect(service.dataPreserved, isFalse);
      expect(service.needsPackage, isTrue);
      expect(service.endsSession, isFalse);
    });

    test('USB device shows vendor and product ids as hex', () {
      final device = UsbDeviceInfo.fromMap(<String, dynamic>{
        'deviceName': '/dev/bus/usb/001/002',
        'vendorId': 0x04e8,
        'productId': 0x6860,
        'manufacturerName': 'samsung',
        'productName': 'SM-A125F',
      });

      expect(device.ids, '04e8:6860');
      expect(device.displayName, 'samsung SM-A125F');
      expect(device.hasPermission, isFalse);
    });
  });

  group('repair guides', () {
    test('every guide offers checks and services', () {
      expect(kRepairGuides.length, 6);
      for (final guide in kRepairGuides) {
        expect(guide.reports, isNotEmpty, reason: guide.title);
        expect(guide.services, isNotEmpty, reason: guide.title);
      }
    });
  });

  testWidgets('Notice shows its title, message and action', (tester) async {
    var tapped = false;

    await tester.pumpWidget(
      MaterialApp(
        theme: buildAppTheme(),
        home: Scaffold(
          body: Notice(
            icon: Icons.info,
            title: 'Connect a phone first',
            message: 'Use a cable or Wi-Fi.',
            actionLabel: 'Open Connection',
            onAction: () => tapped = true,
          ),
        ),
      ),
    );

    expect(find.text('Connect a phone first'), findsOneWidget);
    expect(find.text('Use a cable or Wi-Fi.'), findsOneWidget);

    await tester.tap(find.text('Open Connection'));
    expect(tapped, isTrue);
  });
}
