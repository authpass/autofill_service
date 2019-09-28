import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:autofill_service/autofill_service.dart';

void main() {
  const MethodChannel channel = MethodChannel('autofill_service');

  setUp(() {
    channel.setMockMethodCallHandler((MethodCall methodCall) async {
      return '42';
    });
  });

  tearDown(() {
    channel.setMockMethodCallHandler(null);
  });

  test('getPlatformVersion', () async {
    expect(await AutofillService.platformVersion, '42');
  });
}
