// Minimal macOS Network Extension provider used only to demonstrate packaging.
//
// This is a content-filter data provider (NEFilterDataProvider) that allows all
// traffic. It is intentionally trivial: the point of this example is the *build,
// sign, bundle and re-seal* pipeline around the .appex, not the filtering logic.
//
// The executable has no main() of its own — an app extension's entry point is
// NSExtensionMain (provided by Foundation). build.sh links it via `-e _NSExtensionMain`.
// The principal class is declared in Info.plist (NSExtensionPrincipalClass).

#import <Foundation/Foundation.h>
#import <NetworkExtension/NetworkExtension.h>

@interface FilterDataProvider : NEFilterDataProvider
@end

@implementation FilterDataProvider

- (void)startFilterWithCompletionHandler:(void (^)(NSError *_Nullable))completionHandler {
    // No filtering rules — start successfully.
    completionHandler(nil);
}

- (void)stopFilterWithReason:(NEProviderStopReason)reason
           completionHandler:(void (^)(void))completionHandler {
    completionHandler();
}

- (NEFilterNewFlowVerdict *)handleNewFlow:(NEFilterFlow *)flow {
    // Allow every new flow.
    return [NEFilterNewFlowVerdict allowVerdict];
}

@end
