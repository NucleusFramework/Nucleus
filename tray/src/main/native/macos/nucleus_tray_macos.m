/*
 * nucleus_tray_macos.m - macOS status-bar icon (NSStatusItem) of nucleus_tray.
 *
 * Kotlin class: dev.nucleusframework.tray.internal.macos.MacNativeBridge
 *
 * Threading model. AppKit objects are touched on the main thread only. A caller on any thread
 * publishes the whole state it wants shown (icon, tooltip, callbacks, menu) as immutable
 * Objective-C objects built on its own thread, then the main thread applies the newest one:
 * a burst of updates costs one rebuild and always ends on the last state. The main thread never
 * reads memory owned by the JVM side, so replacing or disposing a menu cannot race AppKit, and
 * every callback (a JNI global ref) is owned by the object that fires it: one tray going away
 * cannot touch another tray's callbacks.
 *
 * Work reaches the main thread with CFRunLoopPerformBlock in the common modes, so it also runs
 * while a menu is tracking: a dispose closes a menu that is still open. Nothing here ever waits
 * for the main thread (except the test hook nativeDebugDescribe, which times out).
 */

#import <AppKit/AppKit.h>
#import <jni.h>
#import <os/lock.h>
#import <stdatomic.h>

#include "jni_utf8.h"
#include "../../../../../native-common/nucleus_jni.h"

#define BRIDGE(name) Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_##name

/* Flags of a flattened menu entry (MacTrayManager.flatten). */
enum { NT_DISABLED = 1, NT_CHECKED = 2, NT_SEPARATOR = 4 };

static JavaVM *g_jvm = NULL;
static jmethodID g_run = NULL;
static jmethodID g_onThemeChanged = NULL;

static JNIEnv *nt_env(void)
{
    JNIEnv *env = NULL;
    if (!g_jvm) return NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8) == JNI_OK) return env;
    /* The AppKit main thread outside a Tao app (plain JVM, tests): attach it once, for good. */
    if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return NULL;
    return env;
}

/* Always queued, even from the main thread (test hooks that open a menu must not block). The
 * block gets its own autorelease pool: a run loop that is not NSApp's (a plain JVM's main thread)
 * drains none, and AppKit autoreleases windows and images. */
static void nt_post_main(dispatch_block_t block)
{
    CFRunLoopRef main = CFRunLoopGetMain();
    CFRunLoopPerformBlock(main, kCFRunLoopCommonModes, ^{
        @autoreleasepool {
            block();
        }
    });
    CFRunLoopWakeUp(main);
}

/* Runs block from a one-shot timer of the main run loop (common modes). A block that opens a menu
 * runs the menu's tracking loop inside its own callout; from a perform block, CoreFoundation
 * would hold back every perform block queued behind it (a cancel included) until the menu closes.
 * A real click arrives as an event, so only the test hook needs this. */
static void nt_post_main_timer(dispatch_block_t block)
{
    CFRunLoopTimerRef timer = CFRunLoopTimerCreateWithHandler(NULL, CFAbsoluteTimeGetCurrent(), 0, 0, 0, ^(CFRunLoopTimerRef t) {
        @autoreleasepool {
            block();
        }
    });
    CFRunLoopAddTimer(CFRunLoopGetMain(), timer, kCFRunLoopCommonModes);
    CFRelease(timer);
    CFRunLoopWakeUp(CFRunLoopGetMain());
}

static void nt_on_main(dispatch_block_t block)
{
    if ([NSThread isMainThread]) {
        @autoreleasepool {
            block();
        }
        return;
    }
    nt_post_main(block);
}

static NSString *nt_string(JNIEnv *env, jstring value)
{
    if (!value) return nil;
    char *utf8 = jni_utf8_dup(env, value);
    if (!utf8) return nil;
    NSString *s = [NSString stringWithUTF8String:utf8];
    free(utf8);
    return s;
}

/* NewStringUTF takes modified UTF-8 and mangles supplementary characters: go through UTF-16. */
static jstring nt_jstring(JNIEnv *env, NSString *s)
{
    if (!s) return NULL;
    NSUInteger length = s.length;
    unichar *chars = malloc(MAX(length, 1) * sizeof(unichar));
    if (!chars) return NULL;
    [s getCharacters:chars range:NSMakeRange(0, length)];
    jstring result = (*env)->NewString(env, (const jchar *)chars, (jsize)length);
    free(chars);
    return result;
}

/* ------------------------------------------------------------------ model */

/** A Kotlin Runnable, released with this object. */
@interface NTCallback : NSObject
- (instancetype)initWithEnv:(JNIEnv *)env runnable:(jobject)runnable;
- (void)run;
@end

@implementation NTCallback {
    jobject _ref;
}

- (instancetype)initWithEnv:(JNIEnv *)env runnable:(jobject)runnable
{
    if ((self = [super init])) _ref = (*env)->NewGlobalRef(env, runnable);
    return self;
}

- (void)run
{
    JNIEnv *env = nt_env();
    if (!env || !_ref) return;
    (*env)->CallVoidMethod(env, _ref, g_run);
    nucleus_jni_clear_exception(env);
}

- (void)dealloc
{
    JNIEnv *env = nt_env();
    if (env && _ref) (*env)->DeleteGlobalRef(env, _ref);
}

@end

static NTCallback *nt_callback(JNIEnv *env, jobject runnable)
{
    return runnable ? [[NTCallback alloc] initWithEnv:env runnable:runnable] : nil;
}

/** One menu entry, immutable once published. */
@interface NTItem : NSObject
@property(nonatomic, copy) NSString *title;
@property(nonatomic, copy) NSString *iconPath;
@property(nonatomic, copy) NSString *keyEquivalent;
@property(nonatomic) NSEventModifierFlags modifiers;
@property(nonatomic) int flags;
@property(nonatomic, strong) NTCallback *callback;
@property(nonatomic, strong) NSMutableArray<NTItem *> *children;
@end

@implementation NTItem
@end

/** Everything one status item shows. */
@interface NTState : NSObject
@property(nonatomic, copy) NSString *iconPath;
@property(nonatomic, copy) NSString *lightIconPath;
@property(nonatomic, copy) NSString *darkIconPath;
@property(nonatomic, copy) NSString *tooltip;
@property(nonatomic, strong) NTCallback *click;
@property(nonatomic, strong) NTCallback *menuOpened;
@property(nonatomic, strong) NSArray<NTItem *> *items;
@end

@implementation NTState
- (NTState *)copyWithLight:(NSString *)light dark:(NSString *)dark
{
    NTState *s = [NTState new];
    s.iconPath = self.iconPath;
    s.tooltip = self.tooltip;
    s.click = self.click;
    s.menuOpened = self.menuOpened;
    s.items = self.items;
    s.lightIconPath = light;
    s.darkIconPath = dark;
    return s;
}
@end

/* ------------------------------------------------------------------ theme */

static os_unfair_lock g_themeLock = OS_UNFAIR_LOCK_INIT;
static jobject g_themeCallback = NULL;     /* guarded by g_themeLock */
static atomic_int g_menuBarDark = -1;      /* last appearance seen on a status item, -1 = none */
static NSMutableSet *g_liveTrays = nil;    /* main thread only */
static NSHashTable *g_allItems = nil;      /* main thread only: every status item created, held weakly */
@class NTTray;
/* Main thread only: the tray whose menu is up, if any. Strong: it keeps a tray disposed while its
 * menu tracks alive until popUpMenu returns. */
static NTTray *g_trackingTray = nil;

static BOOL nt_is_dark(NSAppearance *appearance)
{
    return [[appearance bestMatchFromAppearancesWithNames:@[ NSAppearanceNameAqua, NSAppearanceNameDarkAqua ]]
        isEqualToString:NSAppearanceNameDarkAqua];
}

static BOOL nt_system_dark(void)
{
    return [[[NSUserDefaults standardUserDefaults] stringForKey:@"AppleInterfaceStyle"] isEqualToString:@"Dark"];
}

/** Menus follow the system theme, not the menu bar's (which follows the wallpaper). */
static NSAppearance *nt_system_appearance(void)
{
    return [NSAppearance appearanceNamed:nt_system_dark() ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua];
}

static void nt_set_menu_appearance(NSMenu *menu, NSAppearance *appearance)
{
    menu.appearance = appearance;
    for (NSMenuItem *item in menu.itemArray) {
        if (item.submenu) nt_set_menu_appearance(item.submenu, appearance);
    }
}

static void nt_notify_theme(BOOL dark)
{
    int now = dark ? 1 : 0;
    if (atomic_exchange(&g_menuBarDark, now) == now) return;
    JNIEnv *env = nt_env();
    if (!env) return;
    os_unfair_lock_lock(&g_themeLock);
    jobject cb = g_themeCallback ? (*env)->NewLocalRef(env, g_themeCallback) : NULL;
    os_unfair_lock_unlock(&g_themeLock);
    if (!cb) return;
    (*env)->CallVoidMethod(env, cb, g_onThemeChanged, (jint)now);
    nucleus_jni_clear_exception(env);
    (*env)->DeleteLocalRef(env, cb);
}

/* ------------------------------------------------------------------ tray */

static NSImage *nt_load_image(NSString *path, CGFloat height)
{
    if (path.length == 0) return nil;
    NSImage *image = [[NSImage alloc] initWithContentsOfFile:path];
    if (!image || image.size.width <= 0 || image.size.height <= 0) return nil;
    image.size = NSMakeSize(image.size.width * (height / image.size.height), height);
    return image;
}

static CGFloat nt_icon_height(void)
{
    return MIN([NSStatusBar systemStatusBar].thickness - 4, 18);
}

@interface NTTray : NSObject <NSMenuDelegate>
- (NSAppearance *)buttonAppearance;
- (void)cancelMenu;
@end

@implementation NTTray {
    os_unfair_lock _lock;
    /* Guarded by _lock. */
    NTState *_pending;
    NTState *_latest; /* last state published, base of an appearance-icons change */
    BOOL _scheduled;
    BOOL _disposed;
    BOOL _hasGeometry;
    NSRect _rect;
    NSRect _screenFrame;
    CGFloat _primaryHeight;

    /* Main thread only. */
    NSStatusItem *_item;
    NTState *_applied;
    NSMenu *_menu;
    NSMenu *_openMenu;
    NSString *_iconPath, *_lightPath, *_darkPath;
    NSImage *_icon, *_light, *_dark;
    BOOL _observing;
    int _simulatedClick; /* test hook: 1 primary, 2 secondary, 0 a real event */
    BOOL _menuVisible;   /* between menuWillOpen and menuDidClose of _openMenu */
    BOOL _cancelPending; /* _openMenu must close, even if AppKit has not shown it yet */
}

- (instancetype)init
{
    if ((self = [super init])) _lock = OS_UNFAIR_LOCK_INIT;
    return self;
}

/* Main thread: the menu-bar appearance this tray's button sees, nil before it exists. */
- (NSAppearance *)buttonAppearance
{
    return _item.button.effectiveAppearance;
}

- (BOOL)isDisposed
{
    os_unfair_lock_lock(&_lock);
    BOOL disposed = _disposed;
    os_unfair_lock_unlock(&_lock);
    return disposed;
}

/* Any thread. */
- (void)publish:(NTState *)state
{
    BOOL schedule = NO;
    NTState *superseded = nil;
    os_unfair_lock_lock(&_lock);
    if (!_disposed) {
        superseded = _pending; /* released outside the lock: its callbacks delete JNI refs */
        _pending = state;
        _latest = state;
        schedule = !_scheduled;
        _scheduled = YES;
    }
    os_unfair_lock_unlock(&_lock);
    (void)superseded; /* released here, outside the lock */
    if (schedule) nt_on_main(^{ [self drain]; });
}

/* Any thread. */
- (void)publishAppearanceLight:(NSString *)light dark:(NSString *)dark
{
    os_unfair_lock_lock(&_lock);
    NTState *base = _latest;
    os_unfair_lock_unlock(&_lock);
    if (base) [self publish:[base copyWithLight:light dark:dark]];
}

/* Any thread: the dispose is final. */
- (void)dispose
{
    os_unfair_lock_lock(&_lock);
    BOOL already = _disposed;
    _disposed = YES;
    NTState *dropped = _pending;
    _pending = nil;
    _latest = nil;
    os_unfair_lock_unlock(&_lock);
    (void)dropped; /* released here, outside the lock */
    if (!already) nt_on_main(^{ [self teardown]; });
}

- (void)drain
{
    os_unfair_lock_lock(&_lock);
    NTState *state = _disposed ? nil : _pending;
    _pending = nil;
    _scheduled = NO;
    os_unfair_lock_unlock(&_lock);
    if (state) [self apply:state];
}

- (void)apply:(NTState *)state
{
    if (!_item) [self createItem];
    NSStatusBarButton *button = _item.button;
    CGFloat height = nt_icon_height();

    /* A path that does not load keeps the image shown so far. */
    if (![state.iconPath isEqualToString:_iconPath]) {
        NSImage *image = nt_load_image(state.iconPath, height);
        if (image) _icon = image;
        _iconPath = [state.iconPath copy];
    }
    if (!(_lightPath == state.lightIconPath || [_lightPath isEqualToString:state.lightIconPath])) {
        _light = nt_load_image(state.lightIconPath, height);
        _lightPath = [state.lightIconPath copy];
    }
    if (!(_darkPath == state.darkIconPath || [_darkPath isEqualToString:state.darkIconPath])) {
        _dark = nt_load_image(state.darkIconPath, height);
        _darkPath = [state.darkIconPath copy];
    }
    [self refreshImage];
    button.toolTip = state.tooltip.length ? state.tooltip : nil;

    _menu = state.items.count ? [self menuFrom:state.items] : nil;
    _applied = state;
    [self refreshGeometry];
}

- (void)createItem
{
    if (!g_liveTrays) g_liveTrays = [NSMutableSet new];
    if (!g_allItems) g_allItems = [NSHashTable weakObjectsHashTable];
    [NSApplication sharedApplication];
    _item = [[NSStatusBar systemStatusBar] statusItemWithLength:NSVariableStatusItemLength];
    [g_allItems addObject:_item];
    NSStatusBarButton *button = _item.button;
    button.target = self;
    button.action = @selector(statusItemClicked:);
    [button sendActionOn:NSEventMaskLeftMouseUp | NSEventMaskRightMouseUp];
    [button addObserver:self
             forKeyPath:@"effectiveAppearance"
                options:NSKeyValueObservingOptionInitial | NSKeyValueObservingOptionNew
                context:NULL];
    _observing = YES;
    NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
    for (NSNotificationName name in @[
             NSWindowDidMoveNotification, NSWindowDidResizeNotification, NSWindowDidChangeScreenNotification
         ]) {
        [center addObserver:self selector:@selector(windowChanged:) name:name object:nil];
    }
    [center addObserver:self
               selector:@selector(screensChanged:)
                   name:NSApplicationDidChangeScreenParametersNotification
                 object:nil];
    [[NSDistributedNotificationCenter defaultCenter] addObserver:self
                                                        selector:@selector(systemThemeChanged:)
                                                            name:@"AppleInterfaceThemeChangedNotification"
                                                          object:nil];
    [g_liveTrays addObject:self];
}

- (void)teardown
{
    if (_openMenu) {
        _cancelPending = YES;
        [self closeOpenMenu];
    }
    if (_item) {
        if (_observing) [_item.button removeObserver:self forKeyPath:@"effectiveAppearance"];
        _observing = NO;
        _item.button.target = nil;
        _item.button.action = NULL;
        _item.menu = nil;
        [[NSStatusBar systemStatusBar] removeStatusItem:_item];
        _item = nil;
    }
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [[NSDistributedNotificationCenter defaultCenter] removeObserver:self];
    [g_liveTrays removeObject:self];
    _menu = nil;
    _applied = nil;
    _icon = _light = _dark = nil;
    os_unfair_lock_lock(&_lock);
    _hasGeometry = NO;
    os_unfair_lock_unlock(&_lock);
}

- (NSMenu *)menuFrom:(NSArray<NTItem *> *)items
{
    NSMenu *menu = [[NSMenu alloc] initWithTitle:@""];
    menu.autoenablesItems = NO;
    menu.delegate = self;
    menu.appearance = nt_system_appearance();
    for (NTItem *spec in items) {
        if (spec.flags & NT_SEPARATOR) {
            [menu addItem:[NSMenuItem separatorItem]];
            continue;
        }
        NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:spec.title ?: @""
                                                      action:NULL
                                               keyEquivalent:spec.keyEquivalent ?: @""];
        if (spec.keyEquivalent) item.keyEquivalentModifierMask = spec.modifiers;
        item.enabled = !(spec.flags & NT_DISABLED);
        item.state = (spec.flags & NT_CHECKED) ? NSControlStateValueOn : NSControlStateValueOff;
        NSImage *image = nt_load_image(spec.iconPath, 16);
        if (image) {
            image.size = NSMakeSize(16, 16);
            item.image = image;
        }
        if (spec.callback) {
            item.representedObject = spec.callback;
            item.target = self;
            item.action = @selector(menuItemClicked:);
        }
        if (spec.children.count) item.submenu = [self menuFrom:spec.children];
        [menu addItem:item];
    }
    return menu;
}

- (void)refreshImage
{
    NSImage *image = _icon;
    if (_light || _dark) {
        BOOL dark = nt_is_dark(_item.button.effectiveAppearance);
        image = (dark ? _dark : _light) ?: _icon;
    }
    _item.button.image = image;
}

/* Main thread: caches the laid-out rect for queries from any thread. */
- (void)refreshGeometry
{
    NSStatusBarButton *button = _item.button;
    NSWindow *window = button.window;
    BOOL valid = NO;
    NSRect rect = NSZeroRect;
    NSScreen *screen = nil;
    if (button && window) {
        rect = [window convertRectToScreen:[button convertRect:button.bounds toView:nil]];
        /* window.screen stays nil until NSApp has run its launch sequence (a plain JVM): fall back
         * to the screen holding the item. */
        screen = window.screen;
        for (NSScreen *candidate in NSScreen.screens) {
            if (screen) break;
            if (NSIntersectsRect(candidate.frame, rect)) screen = candidate;
        }
    }
    /* Neither may be known yet (no NSScreen list before NSApp launches): the window server's main
     * display, whose frame has the same origin in both coordinate systems. */
    NSRect screenFrame = screen ? screen.frame : NSRectFromCGRect(CGDisplayBounds(CGMainDisplayID()));
    if (button && window && screenFrame.size.width > 0) {
        /* A brand-new status item sits at the menu bar's left edge until AppKit lays it out;
         * reporting that lands a TrayApp popup at the top-left of the screen. Menu extras always
         * live further right, also on a display arranged left of the primary one. */
        valid = rect.size.width >= 1 && rect.size.height >= 1 && NSMinX(rect) - NSMinX(screenFrame) >= 8;
    }
    CGFloat primary = NSScreen.screens.firstObject.frame.size.height;
    if (primary <= 0) primary = CGDisplayBounds(CGMainDisplayID()).size.height;
    os_unfair_lock_lock(&_lock);
    _hasGeometry = valid;
    _rect = rect;
    _screenFrame = screenFrame;
    _primaryHeight = primary > 0 ? primary : NSHeight(_screenFrame);
    os_unfair_lock_unlock(&_lock);
}

/* Any thread. Returns NO until the item is laid out. */
- (BOOL)position:(int *)x y:(int *)y region:(NSString **)region
{
    if ([NSThread isMainThread]) {
        [self refreshGeometry];
    } else {
        /* Off the main thread the cache answers; refresh it for the next poll. */
        nt_on_main(^{
            if (![self isDisposed]) [self refreshGeometry];
        });
    }
    os_unfair_lock_lock(&_lock);
    BOOL valid = _hasGeometry && !_disposed;
    NSRect rect = _rect;
    NSRect screen = _screenFrame;
    CGFloat primary = _primaryHeight;
    os_unfair_lock_unlock(&_lock);
    if (!valid) return NO;
    /* Top-left-origin coordinates against the primary screen, like the rest of the toolkit. */
    if (x) *x = (int)lround(NSMidX(rect));
    if (y) *y = (int)(primary - NSMaxY(rect));
    if (region) *region = NSMinX(rect) < NSMidX(screen) ? @"top-left" : @"top-right";
    return YES;
}

/* ------------------------------------------------------------------ AppKit callbacks */

- (void)statusItemClicked:(id)sender
{
    __attribute__((objc_precise_lifetime)) NTTray *keepAlive = self;
    (void)keepAlive;
    if ([self isDisposed] || !_applied) return;
    /* A real click elsewhere ends a tracking menu and does nothing else. Opening a second menu
     * from inside the first one's tracking loop would orphan the outer session once the inner one
     * closes: the main thread would stay in the tracking mode for good. */
    if (g_trackingTray) {
        _simulatedClick = 0;
        [g_trackingTray cancelMenu];
        return;
    }
    BOOL secondary;
    if (_simulatedClick) {
        secondary = _simulatedClick == 2;
    } else {
        NSEvent *event = NSApp.currentEvent;
        secondary = event.type == NSEventTypeRightMouseUp || event.type == NSEventTypeRightMouseDown ||
                    (event.modifierFlags & NSEventModifierFlagControl) != 0;
    }
    _simulatedClick = 0;
    [self refreshGeometry];
    NTState *state = _applied;
    if (!secondary && state.click) {
        [state.click run];
        return;
    }
    if (_menu) [self popUpMenu];
}

- (void)popUpMenu
{
    /* ARC does not retain self for a method call: a dispose landing while the menu tracks would
     * free this tray under the frame below. */
    __attribute__((objc_precise_lifetime)) NTTray *keepAlive = self;
    NSMenu *menu = _menu;
    NSStatusItem *item = _item;
    [_applied.menuOpened run];
    /* The callback may have disposed the tray. */
    if ([self isDisposed] || _item != item) return;
    /* Marked before the menu shows: AppKit presents it asynchronously, and a click handled in
     * between must not start a second session. */
    _openMenu = menu;
    _cancelPending = NO;
    g_trackingTray = self;
    item.menu = menu;
    [item.button performClick:nil]; /* tracks the menu until it closes */
    item.menu = nil;
    if (g_trackingTray == self) g_trackingTray = nil;
    _openMenu = nil;
    _menuVisible = NO;
    _cancelPending = NO;
}

- (void)menuItemClicked:(NSMenuItem *)sender
{
    if ([self isDisposed]) return;
    [(NTCallback *)sender.representedObject run];
}

- (void)menuWillOpen:(NSMenu *)menu
{
    if (menu.supermenu) return;
    _menuVisible = menu == _openMenu;
    /* Dismissed (or the tray disposed) before AppKit got to show it: close it now that it is up. */
    if (_cancelPending) [self closeOpenMenu];
    else if (menu != _openMenu) [menu cancelTrackingWithoutAnimation];
}

- (void)menuDidClose:(NSMenu *)menu
{
    if (menu == _openMenu) _menuVisible = NO;
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary *)change
                       context:(void *)context
{
    if (!_item || object != _item.button) return;
    [self refreshImage];
    nt_notify_theme(nt_is_dark(_item.button.effectiveAppearance));
}

- (void)windowChanged:(NSNotification *)note
{
    if (_item && note.object == _item.button.window) [self refreshGeometry];
}

- (void)screensChanged:(NSNotification *)note
{
    if (_item) [self refreshGeometry];
}

- (void)systemThemeChanged:(NSNotification *)note
{
    if (_menu) nt_set_menu_appearance(_menu, nt_system_appearance());
}

/* ------------------------------------------------------------------ test hooks (main thread) */

static NSString *nt_escape(NSString *s)
{
    NSMutableString *out = [NSMutableString string];
    for (NSUInteger i = 0; i < s.length; i++) {
        unichar c = [s characterAtIndex:i];
        switch (c) {
            case '\\': [out appendString:@"\\\\"]; break;
            case '\n': [out appendString:@"\\n"]; break;
            case '\r': [out appendString:@"\\r"]; break;
            case '\t': [out appendString:@"\\t"]; break;
            default:
                if (c < 0x20) [out appendFormat:@"\\u%04x", c];
                else [out appendFormat:@"%C", c];
        }
    }
    return out;
}

static void nt_describe_menu(NSMenu *menu, int depth, NSMutableString *out)
{
    for (NSMenuItem *item in menu.itemArray) {
        [out appendFormat:@"item\t%d\t%d\t%d\t%d\t%@\t%lu\t%d\t%d\t%@\n", depth, item.isSeparatorItem ? 1 : 0,
                          item.isEnabled ? 1 : 0, item.state == NSControlStateValueOn ? 1 : 0,
                          nt_escape(item.keyEquivalent), (unsigned long)item.keyEquivalentModifierMask,
                          item.image ? 1 : 0, item.action ? 1 : 0, nt_escape(item.title)];
        if (item.submenu) nt_describe_menu(item.submenu, depth + 1, out);
    }
}

- (NSString *)describe
{
    os_unfair_lock_lock(&_lock);
    NSString *queue = [NSString stringWithFormat:@"queue\t%d\t%d\t%d\n", _scheduled, _pending != nil, _disposed];
    os_unfair_lock_unlock(&_lock);
    if (!_item) return [@"none\n" stringByAppendingString:queue];
    NSMutableString *out = [NSMutableString string];
    NSStatusBarButton *button = _item.button;
    [out appendFormat:@"item\n"];
    [out appendString:queue];
    [out appendFormat:@"image\t%d\t%d\n", (int)button.image.size.width, (int)button.image.size.height];
    [out appendFormat:@"tooltip\t%@\n", button.toolTip ? nt_escape(button.toolTip) : @"<nil>"];
    [out appendFormat:@"open\t%d\n", _menuVisible ? 1 : 0];
    [out appendFormat:@"tracking\t%@\n", g_trackingTray ? (g_trackingTray == self ? @"self" : @"other") : @"none"];
    CFStringRef mode = CFRunLoopCopyCurrentMode(CFRunLoopGetMain());
    [out appendFormat:@"mode\t%@\n", mode ? (__bridge NSString *)mode : @"<none>"];
    if (mode) CFRelease(mode);
    [out appendFormat:@"window\t%ld\t%d\t%@\t%@\n", (long)button.window.windowNumber, button.window.isVisible ? 1 : 0,
                      NSStringFromRect(button.window.frame),
                      button.window.screen ? NSStringFromRect(button.window.screen.frame) : @"<no screen>"];
    if (_menu) nt_describe_menu(_menu, 0, out);
    return out;
}

/* The status item's window belongs to the system menu bar (no event reaches it from inside the
 * process), so a simulated click goes through the button's own action, telling it the button. */
- (void)simulateClick:(BOOL)secondary
{
    /* The click may dispose this tray (the caller holds no reference): keep it for this frame. */
    __attribute__((objc_precise_lifetime)) NTTray *keepAlive = self;
    (void)keepAlive;
    NSStatusBarButton *button = _item.button;
    if (!button) return;
    if (g_trackingTray) {
        /* While a menu tracks, AppKit takes a real click itself: it ends the menu. */
        [g_trackingTray cancelMenu];
        return;
    }
    _simulatedClick = secondary ? 2 : 1;
    [button performClick:nil];
    _simulatedClick = 0;
}

- (BOOL)performItemAtPath:(NSArray<NSNumber *> *)path
{
    NSMenu *menu = _openMenu ?: _menu;
    for (NSUInteger i = 0; i < path.count; i++) {
        NSInteger index = path[i].integerValue;
        if (!menu || index < 0 || index >= menu.numberOfItems) return NO;
        if (i + 1 == path.count) {
            [menu performActionForItemAtIndex:index];
            return YES;
        }
        menu = [menu itemAtIndex:index].submenu;
    }
    return NO;
}

- (void)cancelMenu
{
    if (!_openMenu) return;
    _cancelPending = YES;
    [self closeOpenMenu];
}

/* AppKit drops a cancel that lands while it is still presenting the menu, and a menu left
 * tracking keeps every other menu of the system from opening: insist until it is closed. */
- (void)closeOpenMenu
{
    NSMenu *menu = _openMenu;
    if (!menu || !_cancelPending) return;
    [menu cancelTrackingWithoutAnimation];
    __weak NTTray *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 50 * NSEC_PER_MSEC), dispatch_get_global_queue(0, 0), ^{
        nt_post_main(^{
            NTTray *tray = weakSelf;
            if (tray && tray->_openMenu == menu) [tray closeOpenMenu];
        });
    });
}

@end

/* ------------------------------------------------------------------ JNI */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_jvm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
    /* java.lang.Runnable and the theme interface, not each lambda class: GraalVM only needs
     * these registered for JNI. */
    jclass runnable = (*env)->FindClass(env, "java/lang/Runnable");
    if (!runnable) return JNI_ERR;
    g_run = (*env)->GetMethodID(env, runnable, "run", "()V");
    (*env)->DeleteLocalRef(env, runnable);
    jclass theme = (*env)->FindClass(env, "dev/nucleusframework/tray/internal/macos/MacNativeBridge$ThemeChangeCallback");
    if (!theme) return JNI_ERR;
    g_onThemeChanged = (*env)->GetMethodID(env, theme, "onThemeChanged", "(I)V");
    (*env)->DeleteLocalRef(env, theme);
    return g_run && g_onThemeChanged ? JNI_VERSION_1_8 : JNI_ERR;
}

static NTTray *nt_tray(jlong handle) { return (__bridge NTTray *)(void *)(uintptr_t)handle; }

JNIEXPORT jlong JNICALL BRIDGE(nativeCreateTray)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jlong)(uintptr_t)CFBridgingRetain([NTTray new]);
}

/* Rebuilds the flattened (pre-order, with depths) menu as a tree. */
static NSArray<NTItem *> *nt_items(JNIEnv *env, jobjectArray titles, jobjectArray icons, jobjectArray keys,
                                   jlongArray masks, jintArray flags, jintArray depths, jobjectArray callbacks)
{
    jsize count = titles ? (*env)->GetArrayLength(env, titles) : 0;
    if (count == 0) return nil;
    jlong *maskValues = (*env)->GetLongArrayElements(env, masks, NULL);
    jint *flagValues = (*env)->GetIntArrayElements(env, flags, NULL);
    jint *depthValues = (*env)->GetIntArrayElements(env, depths, NULL);
    NSMutableArray<NTItem *> *root = [NSMutableArray array];
    NSMutableArray<NSMutableArray<NTItem *> *> *stack = [NSMutableArray arrayWithObject:root];
    for (jsize i = 0; i < count; i++) {
        NTItem *item = [NTItem new];
        jstring title = (jstring)(*env)->GetObjectArrayElement(env, titles, i);
        jstring icon = (jstring)(*env)->GetObjectArrayElement(env, icons, i);
        jstring key = (jstring)(*env)->GetObjectArrayElement(env, keys, i);
        jobject callback = (*env)->GetObjectArrayElement(env, callbacks, i);
        item.title = nt_string(env, title);
        item.iconPath = nt_string(env, icon);
        item.keyEquivalent = nt_string(env, key);
        item.modifiers = (NSEventModifierFlags)maskValues[i];
        item.flags = flagValues[i];
        item.callback = nt_callback(env, callback);
        if (title) (*env)->DeleteLocalRef(env, title);
        if (icon) (*env)->DeleteLocalRef(env, icon);
        if (key) (*env)->DeleteLocalRef(env, key);
        if (callback) (*env)->DeleteLocalRef(env, callback);

        NSInteger depth = MAX(0, depthValues[i]);
        while ((NSInteger)stack.count > depth + 1) [stack removeLastObject];
        [stack.lastObject addObject:item];
        item.children = [NSMutableArray array];
        [stack addObject:item.children];
    }
    (*env)->ReleaseLongArrayElements(env, masks, maskValues, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, flags, flagValues, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, depths, depthValues, JNI_ABORT);
    return root;
}

JNIEXPORT void JNICALL BRIDGE(nativeSubmit)(JNIEnv *env, jclass clazz, jlong handle, jstring iconPath,
                                            jstring tooltip, jobject click, jobject menuOpened,
                                            jobjectArray titles, jobjectArray icons, jobjectArray keys,
                                            jlongArray masks, jintArray flags, jintArray depths,
                                            jobjectArray callbacks)
{
    (void)clazz;
    NTTray *tray = nt_tray(handle);
    if (!tray) return;
    @autoreleasepool {
        NTState *state = [NTState new];
        state.iconPath = nt_string(env, iconPath);
        state.tooltip = nt_string(env, tooltip);
        state.click = nt_callback(env, click);
        state.menuOpened = nt_callback(env, menuOpened);
        state.items = nt_items(env, titles, icons, keys, masks, flags, depths, callbacks);
        [tray publish:state];
    }
}

JNIEXPORT void JNICALL BRIDGE(nativeSetIconsForAppearance)(JNIEnv *env, jclass clazz, jlong handle,
                                                           jstring lightIcon, jstring darkIcon)
{
    (void)clazz;
    NTTray *tray = nt_tray(handle);
    if (!tray) return;
    @autoreleasepool {
        [tray publishAppearanceLight:nt_string(env, lightIcon) dark:nt_string(env, darkIcon)];
    }
}

/* Final: the handle must not be used afterwards. */
JNIEXPORT void JNICALL BRIDGE(nativeDispose)(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    if (!handle) return;
    @autoreleasepool {
        NTTray *tray = (__bridge_transfer NTTray *)(void *)(uintptr_t)handle;
        [tray dispose];
    }
}

JNIEXPORT jint JNICALL BRIDGE(nativeGetStatusItemPosition)(JNIEnv *env, jclass clazz, jlong handle, jintArray outXY)
{
    (void)clazz;
    NTTray *tray = nt_tray(handle);
    int x = 0, y = 0;
    BOOL precise = NO;
    @autoreleasepool {
        precise = tray && [tray position:&x y:&y region:NULL];
    }
    jint buf[2] = { (jint)x, (jint)y };
    (*env)->SetIntArrayRegion(env, outXY, 0, 2, buf);
    return precise ? 1 : 0;
}

JNIEXPORT jstring JNICALL BRIDGE(nativeGetStatusItemRegion)(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    NTTray *tray = nt_tray(handle);
    NSString *region = nil;
    @autoreleasepool {
        if (!tray || ![tray position:NULL y:NULL region:&region]) return NULL;
        return nt_jstring(env, region);
    }
}

JNIEXPORT void JNICALL BRIDGE(nativeSetThemeCallback)(JNIEnv *env, jclass clazz, jobject callback)
{
    (void)clazz;
    jobject ref = callback ? (*env)->NewGlobalRef(env, callback) : NULL;
    os_unfair_lock_lock(&g_themeLock);
    jobject old = g_themeCallback;
    g_themeCallback = ref;
    os_unfair_lock_unlock(&g_themeLock);
    if (old) (*env)->DeleteGlobalRef(env, old);
}

JNIEXPORT jint JNICALL BRIDGE(nativeIsMenuDark)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    @autoreleasepool {
        if ([NSThread isMainThread]) {
            NSAppearance *appearance = [(NTTray *)g_liveTrays.anyObject buttonAppearance]
                                           ?: [NSApplication sharedApplication].effectiveAppearance;
            return nt_is_dark(appearance) ? 1 : 0;
        }
        int seen = atomic_load(&g_menuBarDark);
        return seen >= 0 ? seen : (nt_system_dark() ? 1 : 0);
    }
}

/* ------------------------------------------------------------------ test hooks */

/* Snapshot of the live AppKit state of a tray, or NULL if the main thread did not answer in time. */
JNIEXPORT jstring JNICALL BRIDGE(nativeDebugDescribe)(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    NTTray *tray = nt_tray(handle);
    if (!tray) return NULL;
    __block NSString *result = nil;
    @autoreleasepool {
        if ([NSThread isMainThread]) {
            result = [tray describe];
        } else {
            dispatch_semaphore_t done = dispatch_semaphore_create(0);
            nt_post_main(^{
                result = [tray describe];
                dispatch_semaphore_signal(done);
            });
            if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC)) != 0) return NULL;
        }
        return nt_jstring(env, result);
    }
}

/* NSStatusItems created by this library that are still alive (held weakly): once a tray is
 * disposed, neither this library nor AppKit may keep its item. -1 if the main thread is busy. */
JNIEXPORT jint JNICALL BRIDGE(nativeDebugStatusItemCount)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    __block jint count = -1;
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    nt_post_main(^{
        count = (jint)g_allItems.allObjects.count;
        dispatch_semaphore_signal(done);
    });
    if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC)) != 0) return -1;
    return count;
}

/* The main thread's current run-loop mode, read from the calling thread (no hop). */
JNIEXPORT jstring JNICALL BRIDGE(nativeDebugMainMode)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    CFStringRef mode = CFRunLoopCopyCurrentMode(CFRunLoopGetMain());
    if (!mode) return NULL;
    jstring result = nt_jstring(env, (__bridge NSString *)mode);
    CFRelease(mode);
    return result;
}

/* Queues a real mouse click (press + release events) on the status item. */
JNIEXPORT void JNICALL BRIDGE(nativeDebugClick)(JNIEnv *env, jclass clazz, jlong handle, jboolean secondary)
{
    (void)env; (void)clazz;
    NTTray *tray = nt_tray(handle);
    if (!tray) return;
    /* Weak, then unretained for the call: like AppKit sending the action to its (weak) target,
     * nothing but the backend itself keeps the tray alive while the click runs. */
    __weak NTTray *weakTray = tray;
    nt_post_main_timer(^{
        __unsafe_unretained NTTray *target = weakTray;
        if (target && ![target isDisposed]) [target simulateClick:secondary == JNI_TRUE];
    });
}

/* Queues the selection of a menu item (indices from the root), like AppKit does on a click. */
JNIEXPORT void JNICALL BRIDGE(nativeDebugPerformItem)(JNIEnv *env, jclass clazz, jlong handle, jintArray path)
{
    (void)clazz;
    NTTray *tray = nt_tray(handle);
    if (!tray || !path) return;
    jsize n = (*env)->GetArrayLength(env, path);
    jint *values = (*env)->GetIntArrayElements(env, path, NULL);
    NSMutableArray<NSNumber *> *indices = [NSMutableArray arrayWithCapacity:(NSUInteger)n];
    for (jsize i = 0; i < n; i++) [indices addObject:@(values[i])];
    (*env)->ReleaseIntArrayElements(env, path, values, JNI_ABORT);
    nt_post_main(^{
        [tray performItemAtPath:indices];
    });
}

/* Queues the dismissal of the tray's open menu, if any. */
JNIEXPORT void JNICALL BRIDGE(nativeDebugCancelMenu)(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    NTTray *tray = nt_tray(handle);
    if (!tray) return;
    nt_post_main(^{
        [tray cancelMenu];
    });
}
