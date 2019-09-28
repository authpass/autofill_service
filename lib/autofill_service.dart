import 'dart:async';

import 'package:flutter/services.dart';

class AutofillService {
  static const MethodChannel _channel =
      const MethodChannel('autofill_service');

  static Future<String> get platformVersion async {
    final String version = await _channel.invokeMethod('getPlatformVersion');
    return version;
  }
}
