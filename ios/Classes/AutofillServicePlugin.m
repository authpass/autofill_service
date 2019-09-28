#import "AutofillServicePlugin.h"
#import <autofill_service/autofill_service-Swift.h>

@implementation AutofillServicePlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  [SwiftAutofillServicePlugin registerWithRegistrar:registrar];
}
@end
