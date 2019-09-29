import 'dart:async';

import 'package:flutter/services.dart';

class AutofillService {
  factory AutofillService() => _instance;
  AutofillService._();

  static const MethodChannel _channel =
      MethodChannel('codeux.design/autofill_service');

  static final _instance = AutofillService._();

  Future<bool> get hasEnabledAutofillServices async {
    return await _channel.invokeMethod('hasEnabledAutofillServices');
  }

  Future<bool> requestSetAutofillService() async {
    return await _channel.invokeMethod('requestSetAutofillService');
  }

  Future<bool> resultWithDataset() async {
    return await _channel.invokeMethod(
        'resultWithDataset', <String, dynamic>{'autofillIdIndex': 0});
  }
}
