// apple_events.m
//
// Bridges macOS AppleEvents (kInternetEventClass / kAEGetURL) into the
// Nucleus Tao runtime. Replaces the AWT-based `Desktop.setOpenURIHandler`
// path, which is incompatible with Tao because `java.awt.Desktop` boots a
// second NSApp on the JVM side.
//
// Lifecycle:
//   1. JNI calls `nucleus_tao_apple_events_install` from `TaoLauncher`,
//      *before* Tao's `NSApp run` starts.
//   2. NSAppleEventManager retains our handler; AppKit holds the cold-start
//      kAEGetURL until `applicationDidFinishLaunching`, then dispatches it.
//   3. Our handler extracts the `keyDirectObject` URL string and forwards it
//      to Rust via `nucleus_tao_apple_events_dispatch`. Rust hops into the
//      JVM and calls `NativeTaoBridge.dispatchDeepLink(String)`.
//
// Idempotent: a second call to install replaces the previous handler.

#import <Cocoa/Cocoa.h>
#import <Foundation/Foundation.h>

extern void nucleus_tao_apple_events_dispatch(const char *utf8, int len);

@interface NucleusTaoAppleEventsHandler : NSObject
- (void)handleGetURL:(NSAppleEventDescriptor *)event
      withReplyEvent:(NSAppleEventDescriptor *)reply;
@end

@implementation NucleusTaoAppleEventsHandler
- (void)handleGetURL:(NSAppleEventDescriptor *)event
      withReplyEvent:(NSAppleEventDescriptor *)reply {
    (void)reply;
    NSAppleEventDescriptor *direct = [event paramDescriptorForKeyword:keyDirectObject];
    NSString *url = [direct stringValue];
    if (url == nil) return;

    const char *utf8 = [url UTF8String];
    if (utf8 == NULL) return;
    NSUInteger len = strlen(utf8);
    if (len > (NSUInteger)INT_MAX) return;
    nucleus_tao_apple_events_dispatch(utf8, (int)len);
}
@end

static NucleusTaoAppleEventsHandler *g_apple_events_handler = nil;

void nucleus_tao_apple_events_install(void) {
    if (g_apple_events_handler == nil) {
        g_apple_events_handler = [[NucleusTaoAppleEventsHandler alloc] init];
    }
    [[NSAppleEventManager sharedAppleEventManager]
        setEventHandler:g_apple_events_handler
            andSelector:@selector(handleGetURL:withReplyEvent:)
          forEventClass:kInternetEventClass
             andEventID:kAEGetURL];
}
