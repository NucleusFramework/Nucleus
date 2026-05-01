// main_thread_dispatch.m
//
// Bridge that routes our Tao event loop onto the macOS main thread regardless
// of which thread the JNI/native-image entry was invoked from. Modelled on
// JWM's `App.mm` (HumbleUI/JWM, MIT) — the trick is to use
// `performSelectorOnMainThread:withObject:waitUntilDone:YES`, which uses a
// run-loop source (NSPort-based) rather than GCD. Unlike `dispatch_sync` on
// the main queue, it works even when the main thread has not yet entered an
// `[NSApp run]` loop, because the message wakes any CFRunLoop the main
// thread eventually enters.
//
// Threading model:
//   - Caller (worker thread, possibly the only thread the JVM gave us)
//     invokes nucleus_tao_run_on_main_blocking(entry, ctx).
//   - We post the work to the main thread and BLOCK the caller until the
//     work returns. Inside `entry` we typically call Tao's `event_loop.run`
//     which itself drives `[NSApp run]` on the main thread — and only
//     returns when the application terminates.

#import <Cocoa/Cocoa.h>
#import <objc/runtime.h>
#include <stdio.h>
#include <stdatomic.h>

@interface NucleusTaoMainLauncher : NSObject
{
@public
    void (*entry)(void *);
    void *context;
}
- (void)runEntry;
@end

@implementation NucleusTaoMainLauncher
- (void)runEntry {
    if (self->entry != NULL) {
        self->entry(self->context);
    }
}
@end

void nucleus_tao_run_on_main_blocking(void (*entry)(void *), void *context) {
    NucleusTaoMainLauncher *launcher = [[NucleusTaoMainLauncher alloc] init];
    launcher->entry   = entry;
    launcher->context = context;
    // waitUntilDone:YES blocks the caller. If the caller IS the main thread
    // this just calls `runEntry` synchronously on the same stack.
    [launcher performSelectorOnMainThread:@selector(runEntry)
                               withObject:nil
                            waitUntilDone:YES];
}

int nucleus_tao_is_main_thread(void) {
    return [NSThread isMainThread] ? 1 : 0;
}

// Provided by the Rust crate (lib.rs). Posts a UserEvent::Exit on the running
// Tao event-loop proxy so the loop drains gracefully.
extern void nucleus_tao_post_exit(void);

// Installs a process-local NSEvent monitor that intercepts Cmd-Q (and any
// modifier-only-Q with Cmd) and posts our Tao exit. AppKit's default Cmd-Q
// (via the application menu) calls `[NSApp terminate:]` which goes through
// the AWT NSApplication delegate and may never reach our event loop — this
// gives us a deterministic, JVM-independent exit path.
//
// Idempotent: only the first invocation actually installs.
static id sCmdQMonitor = nil;

void nucleus_tao_install_cmd_q_handler(void) {
    if (sCmdQMonitor != nil) return;
    sCmdQMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown
        handler:^NSEvent * _Nullable(NSEvent * _Nonnull event) {
            NSEventModifierFlags mods = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
            if ((mods & NSEventModifierFlagCommand) &&
                [event.charactersIgnoringModifiers isEqualToString:@"q"]) {
                nucleus_tao_post_exit();
                return nil; // consume — prevents AWT from also seeing it
            }
            return event;
        }];
}

// macOS press-and-hold (long-press a key → accent picker) is gated by the
// `ApplePressAndHoldEnabled` user default. AppKit caches it inside
// NSTextInputContext at the moment NSApplication initialises; on a JVM
// process AWT often spins up NSApplication before our dylib gets a chance
// to run, so a runtime call from the Tao event-loop entry point is too
// late — AppKit has already snapshotted "NO".
//
// The reliable fix is to set the value before AWT can touch AppKit. We do
// it from a `+load` method, which the dynamic linker invokes the moment
// libnucleus_tao.dylib is `dlopen`-ed (= during `System.loadLibrary`).
// That happens before any Compose Desktop / AWT class is loaded, so AppKit
// sees `YES` whenever it eventually initialises. We also keep the runtime
// call so users who load the lib lazily after AWT still get a "best effort"
// flip (and so that `defaults write … YES` from the user has a sane default).
// Aggressively flip `ApplePressAndHoldEnabled` everywhere it might be read:
//  1. App-domain via NSUserDefaults (the standard path)
//  2. CFPreferences for the *current* application (covers cases where the
//     JVM-process bundle id is different from what we'd expect)
//  3. Argument domain (`-ApplePressAndHoldEnabled YES`) so the value wins
//     over any prior cached lookup AppKit might have done
//
// AppKit's NSTextInputContext caches the flag the first time it's read; on a
// JVM process that cache is taken once AWT spins up NSApplication, which
// usually happens before we get a chance to run. We therefore write the flag
// in `+load` (= dyld load time, before any Compose/AWT static init) AND at
// runtime (best-effort flip for any caller who lazy-loads the lib).
static void nucleus_tao_force_press_and_hold(void) {
    @autoreleasepool {
        // 1. Volatile domain `NSArgumentDomain` — the *highest priority* domain
        //    in NSUserDefaults' search order (above app, global, registration).
        //    It's a runtime in-memory dictionary; setting it via
        //    `setVolatileDomain:forName:` makes AppKit observe `YES` even if
        //    it has already cached the boolean from a lower-priority domain
        //    when NSApplication first initialised.
        [[NSUserDefaults standardUserDefaults]
            setVolatileDomain:@{@"ApplePressAndHoldEnabled": @YES}
                      forName:NSArgumentDomain];

        // 2. Persistent app domain — writes to ~/Library/Preferences so the
        //    flag survives across runs (some macOS versions only consult the
        //    plist file at NSApplication startup).
        [[NSUserDefaults standardUserDefaults]
            setBool:YES forKey:@"ApplePressAndHoldEnabled"];
        [[NSUserDefaults standardUserDefaults] synchronize];

        // 3. CFPreferences current app — bypasses NSUserDefaults entirely and
        //    writes directly to the preferences daemon for our bundle id.
        CFPreferencesSetAppValue(CFSTR("ApplePressAndHoldEnabled"),
                                 kCFBooleanTrue,
                                 kCFPreferencesCurrentApplication);
        CFPreferencesAppSynchronize(kCFPreferencesCurrentApplication);

        // 4. Registration domain — last-resort fallback for code that goes
        //    through `registerDefaults`-aware lookups.
        [[NSUserDefaults standardUserDefaults] registerDefaults:@{
            @"ApplePressAndHoldEnabled": @YES,
        }];
    }
}

@interface NucleusTaoPressAndHoldEnabler : NSObject
@end

@implementation NucleusTaoPressAndHoldEnabler
+ (void)load {
    nucleus_tao_force_press_and_hold();
    fprintf(stderr,
        "[nucleus_tao] +load: NSApp=%p class=%s bundleId=%s\n",
        (__bridge void *)NSApp,
        NSApp ? object_getClassName(NSApp) : "(nil)",
        [[NSBundle mainBundle] bundleIdentifier].UTF8String ?: "(none)");
    fflush(stderr);
}
@end

void nucleus_tao_enable_press_and_hold(void) {
    nucleus_tao_force_press_and_hold();
    fprintf(stderr,
        "[nucleus_tao] runtime: NSApp=%p class=%s\n",
        (__bridge void *)NSApp,
        NSApp ? object_getClassName(NSApp) : "(nil)");
    fflush(stderr);
}

// Tao's `selectedRange` returns `{NSNotFound, 0}`; we swap it to `{0, 0}`.
static NSRange tao_view_selected_range(id self, SEL _cmd) {
    (void)self; (void)_cmd;
    static int s_logged = 0;
    if (s_logged < 10) { s_logged++; fprintf(stderr, "[tao-trace] selectedRange called\n"); fflush(stderr); }
    return NSMakeRange(0, 0);
}

static BOOL tao_view_has_marked_text(id self, SEL _cmd) {
    (void)self; (void)_cmd;
    static int s_logged = 0;
    if (s_logged < 5) { s_logged++; fprintf(stderr, "[tao-trace] hasMarkedText called\n"); fflush(stderr); }
    // Defer to original by checking ivar directly — simplified: always NO since
    // we swizzle for diagnostic only when there's no real marked text.
    return NO;
}

static NSRange tao_view_marked_range(id self, SEL _cmd) {
    (void)self; (void)_cmd;
    static int s_logged = 0;
    if (s_logged < 5) { s_logged++; fprintf(stderr, "[tao-trace] markedRange called\n"); fflush(stderr); }
    return NSMakeRange(NSNotFound, 0);
}

static NSArray<NSAttributedStringKey> *tao_view_valid_attrs(id self, SEL _cmd) {
    (void)self; (void)_cmd;
    static int s_logged = 0;
    if (s_logged < 3) { s_logged++; fprintf(stderr, "[tao-trace] validAttributesForMarkedText called\n"); fflush(stderr); }
    return @[];
}

// Tao's `firstRectForCharacterRange:actualRange:` returns an `NSRect` with
// `size = NSSize(0, 0)`. AppKit's press-and-hold logic interprets a zero-size
// rect as "no visible text area" and skips the marked-text phase, jumping
// straight to `insertText:`. compose-native-host
// (`runtime/src/macosMain/swift/ComposePlatformView.swift`) returns a real-
// sized rect derived from the caret bounds, and press-and-hold works.
//
// We mirror that: store the caret rect (in screen coordinates with top-down Y)
// pushed from the JVM side via `nativeSetImeRect`, and serve it from a
// swizzled `firstRectForCharacterRange:`.
static _Atomic CGFloat g_ime_screen_x = 0;
static _Atomic CGFloat g_ime_screen_y = 0;
static _Atomic CGFloat g_ime_w = 1;
static _Atomic CGFloat g_ime_h = 18;

static NSRect tao_view_first_rect_for_character_range(
    id self, SEL _cmd, NSRange range, NSRangePointer actual_range
) {
    (void)self; (void)_cmd; (void)range;
    if (actual_range) {
        *actual_range = range;
    }
    NSRect r = NSMakeRect(g_ime_screen_x, g_ime_screen_y, g_ime_w, g_ime_h);
    static int s_logged = 0;
    if (s_logged < 5) {
        s_logged++;
        fprintf(stderr,
            "[nucleus_tao] firstRect called → %.1f,%.1f %.1fx%.1f\n",
            r.origin.x, r.origin.y, r.size.width, r.size.height);
        fflush(stderr);
    }
    return r;
}

/// Converts a caret rectangle expressed in **NSView-local logical points**
/// (top-left origin) to Cocoa screen coordinates (bottom-up origin) and
/// stores it for the swizzled `firstRectForCharacterRange:`.
void nucleus_tao_set_ime_local_rect(long ns_view_handle,
                                    double x, double y, double w, double h) {
    NSView *view = (__bridge NSView *)(void *)ns_view_handle;
    NSWindow *window = view.window;
    if (!window) return;

    // NSView coordinate system: bottom-left origin. We received top-left
    // origin (Compose convention) → flip Y within the view's bounds.
    NSRect viewBounds = view.bounds;
    NSRect rectInView = NSMakeRect(x, viewBounds.size.height - y - h, w, h);
    NSRect rectInWindow = [view convertRect:rectInView toView:nil];
    NSRect rectOnScreen = [window convertRectToScreen:rectInWindow];

    atomic_store(&g_ime_screen_x, rectOnScreen.origin.x);
    atomic_store(&g_ime_screen_y, rectOnScreen.origin.y);
    atomic_store(&g_ime_w, rectOnScreen.size.width > 0 ? rectOnScreen.size.width : 1);
    atomic_store(&g_ime_h, rectOnScreen.size.height > 0 ? rectOnScreen.size.height : 18);
}

static void nucleus_tao_swizzle_view_methods_once(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        Class taoViewClass = objc_getClass("TaoView");
        if (!taoViewClass) {
            fprintf(stderr, "[nucleus_tao] swizzle: TaoView class not found\n");
            return;
        }
        BOOL r1 = class_replaceMethod(taoViewClass,
                                      @selector(selectedRange),
                                      (IMP)tao_view_selected_range,
                                      "{_NSRange=QQ}@:") != NULL;
        BOOL r2 = class_replaceMethod(taoViewClass,
                                      @selector(firstRectForCharacterRange:actualRange:),
                                      (IMP)tao_view_first_rect_for_character_range,
                                      "{CGRect={CGPoint=dd}{CGSize=dd}}@:{_NSRange=QQ}^{_NSRange=QQ}") != NULL;
        BOOL r3 = class_replaceMethod(taoViewClass,
                                      @selector(hasMarkedText),
                                      (IMP)tao_view_has_marked_text,
                                      "B@:") != NULL;
        BOOL r4 = class_replaceMethod(taoViewClass,
                                      @selector(markedRange),
                                      (IMP)tao_view_marked_range,
                                      "{_NSRange=QQ}@:") != NULL;
        BOOL r5 = class_replaceMethod(taoViewClass,
                                      @selector(validAttributesForMarkedText),
                                      (IMP)tao_view_valid_attrs,
                                      "@@:") != NULL;
        fprintf(stderr,
            "[nucleus_tao] swizzle selectedRange=%d firstRect=%d "
            "hasMarkedText=%d markedRange=%d validAttrs=%d\n",
            (int)r1, (int)r2, (int)r3, (int)r4, (int)r5);
        fflush(stderr);
    });
}

// Forces NSTextInputContext into the "active" state for the given view.
// AppKit's press-and-hold logic in `_NSKeyBindingManager` short-circuits to
// `insertText:` (no marked-text phase, no accent picker timer) when the view's
// inputContext is not the *current* one. AWT-managed text views call this
// implicitly via NSTextField/JTextComponent plumbing; Tao's bare NSView does
// not, so we have to do it ourselves once the view is first responder.
void nucleus_tao_activate_input_context(long ns_view_handle) {
    nucleus_tao_swizzle_view_methods_once();
    NSView *view = (__bridge NSView *)(void *)ns_view_handle;
    NSTextInputContext *ctx = view.inputContext;
    if (ctx) {
        [ctx activate];
    }
}

void nucleus_tao_diag_view(long ns_view_handle) {
    NSView *view = (__bridge NSView *)(void *)ns_view_handle;
    Class cls = object_getClass(view);
    BOOL conforms = [view conformsToProtocol:@protocol(NSTextInputClient)];
    NSWindow *window = view.window;
    BOOL isFirstResponder = (window.firstResponder == view);
    BOOL isKeyWindow = window.isKeyWindow;
    NSTextInputContext *viewCtx = view.inputContext;
    NSTextInputContext *currentCtx = [NSTextInputContext currentInputContext];
    BOOL flag = [[NSUserDefaults standardUserDefaults] boolForKey:@"ApplePressAndHoldEnabled"];
    fprintf(stderr,
        "[nucleus_tao] view-diag class=%s conforms=%d "
        "isFirstResponder=%d isKeyWindow=%d "
        "view.inputContext=%p NSTextInputContext.currentInputContext=%p match=%d "
        "flag=%d\n",
        class_getName(cls), (int)conforms,
        (int)isFirstResponder, (int)isKeyWindow,
        (__bridge void *)viewCtx,
        (__bridge void *)currentCtx,
        (int)(viewCtx == currentCtx),
        (int)flag);
    fflush(stderr);
}
