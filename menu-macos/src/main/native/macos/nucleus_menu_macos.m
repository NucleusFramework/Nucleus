/**
 * nucleus_menu_macos.m
 *
 * Complete JNI bridge for macOS NSMenu / NSMenuItem / NSMenuItemBadge /
 * NSMenuDelegate. All handles exchanged with Kotlin are CFBridgingRetain'd
 * pointers that must be CFBridgingRelease'd via nativeRelease().
 *
 * Frameworks: Cocoa
 */

#import <Cocoa/Cocoa.h>
#import <objc/runtime.h>
#include <jni.h>

// ============================================================================
// JNI function name macro
// ============================================================================

#define JNI_FN(name) \
    Java_dev_nucleusframework_menu_macos_NativeNsMenuBridge_##name

#define BRIDGE_CLASS "dev/nucleusframework/menu/macos/NativeNsMenuBridge"

// ============================================================================
// Handle helpers
// ============================================================================

#define HANDLE_TO_OBJ(h, type) ((__bridge type *)(void *)(h))
#define HANDLE_TO_MENU(h)      HANDLE_TO_OBJ(h, NSMenu)
#define HANDLE_TO_ITEM(h)      HANDLE_TO_OBJ(h, NSMenuItem)

static inline jlong retainToHandle(id obj) {
    if (obj == nil) return 0;
    return (jlong)CFBridgingRetain(obj);
}

static inline void releaseHandle(jlong handle) {
    if (handle != 0) {
        CFBridgingRelease((void *)handle);
    }
}

// ============================================================================
// Property ID constants (must match Kotlin *Prop objects)
// ============================================================================

// MenuStringProp
enum { MENU_STR_TITLE = 1 };

// MenuBoolProp
enum {
    MENU_BOOL_AUTO_ENABLES   = 1,
    MENU_BOOL_SHOWS_STATE    = 2,
    MENU_BOOL_ALLOWS_PLUGINS = 3,
};

// MenuIntProp
enum {
    MENU_INT_PRESENTATION = 1,
    MENU_INT_SELECTION    = 2,
    MENU_INT_LAYOUT_DIR   = 3,
};

// MenuFloatProp
enum { MENU_FLOAT_MIN_WIDTH = 1 };

// ItemStringProp
enum {
    ITEM_STR_TITLE    = 1,
    ITEM_STR_KEY_EQ   = 2,
    ITEM_STR_TOOLTIP  = 3,
    ITEM_STR_SUBTITLE = 4,
};

// ItemBoolProp
enum {
    ITEM_BOOL_ENABLED              = 1,
    ITEM_BOOL_HIDDEN               = 2,
    ITEM_BOOL_ALTERNATE            = 3,
    ITEM_BOOL_HIDDEN_OR_ANCESTOR   = 4,
    ITEM_BOOL_IS_SEPARATOR         = 5,
    ITEM_BOOL_IS_HIGHLIGHTED       = 6,
    ITEM_BOOL_HAS_SUBMENU          = 7,
    ITEM_BOOL_IS_SECTION_HEADER    = 8,
    ITEM_BOOL_KEY_EQ_LOCALIZATION  = 9,
    ITEM_BOOL_KEY_EQ_MIRRORING     = 10,
    ITEM_BOOL_KEY_EQ_WHEN_HIDDEN   = 11,
};

// ItemIntProp
enum {
    ITEM_INT_TAG         = 1,
    ITEM_INT_STATE       = 2,
    ITEM_INT_INDENT      = 3,
    ITEM_INT_KEY_EQ_MASK = 4,
};

// Image types
enum { IMG_CLEAR = 0, IMG_NAMED = 1, IMG_SYMBOL = 2, IMG_FILE = 3 };

// State image targets
enum { STATE_IMG_ON = 0, STATE_IMG_OFF = 1, STATE_IMG_MIXED = 2 };

// Badge types
enum {
    BADGE_CLEAR     = 0,
    BADGE_COUNT     = 1,
    BADGE_STRING    = 2,
    BADGE_ALERTS    = 3,
    BADGE_NEW_ITEMS = 4,
    BADGE_UPDATES   = 5,
};

// ============================================================================
// Globals
// ============================================================================

static JavaVM *g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

// ============================================================================
// JNI helpers
// ============================================================================

static JNIEnv *getEnv(BOOL *didAttach) {
    *didAttach = NO;
    if (g_jvm == NULL) return NULL;
    JNIEnv *env = NULL;
    jint st = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    if (st == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK)
            return NULL;
        *didAttach = YES;
    } else if (st != JNI_OK) {
        return NULL;
    }
    return env;
}

static void releaseEnv(BOOL didAttach) {
    if (didAttach && g_jvm) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void clearException(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static NSString *toNSString(JNIEnv *env, jstring jstr) {
    if (jstr == NULL) return nil;
    const char *utf = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (utf == NULL) return nil;
    NSString *s = [NSString stringWithUTF8String:utf];
    (*env)->ReleaseStringUTFChars(env, jstr, utf);
    return s;
}

static jstring toJString(JNIEnv *env, NSString *s) {
    if (s == nil) return NULL;
    return (*env)->NewStringUTF(env, [s UTF8String]);
}

static void runOnMain(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

// Runs an AppKit mutation on the main thread, swallowing any Objective-C
// exception so it never unwinds through the JNI frame (an ObjC exception
// crossing back into a C/JVM frame calls abort() and kills the process).
// AppKit's setWindowsMenu:/setHelpMenu: raise NSRangeException on an empty
// menu on macOS 12 and earlier; this keeps the app alive there.
static void runOnMainGuarded(const char *what, void (^block)(void)) {
    runOnMain(^{
        @try {
            block();
        } @catch (NSException *ex) {
            NSLog(@"[nucleus_menu_macos] %s raised %@: %@", what, ex.name, ex.reason);
        }
    });
}

// ============================================================================
// Action Target — routes NSMenuItem actions back to Kotlin
// ============================================================================

@interface NucleusMenuActionTarget : NSObject
+ (instancetype)shared;
- (void)menuItemAction:(NSMenuItem *)sender;
@end

@implementation NucleusMenuActionTarget

+ (instancetype)shared {
    static NucleusMenuActionTarget *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{ instance = [[NucleusMenuActionTarget alloc] init]; });
    return instance;
}

- (void)menuItemAction:(NSMenuItem *)sender {
    jlong handle = (jlong)(void *)(__bridge void *)sender;

    BOOL didAttach = NO;
    JNIEnv *env = getEnv(&didAttach);
    if (env == NULL) return;

    jclass cls = (*env)->FindClass(env, BRIDGE_CLASS);
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "onMenuItemAction", "(J)V");
        if (mid) (*env)->CallStaticVoidMethod(env, cls, mid, handle);
    }
    clearException(env);
    releaseEnv(didAttach);
}

@end

// ============================================================================
// Delegate Proxy — forwards NSMenuDelegate to Kotlin
// ============================================================================

@interface NucleusMenuDelegateProxy : NSObject <NSMenuDelegate>
@end

@implementation NucleusMenuDelegateProxy

static void callDelegateVoid(NSMenu *menu, const char *methodName, const char *sig) {
    jlong menuHandle = (jlong)(void *)(__bridge void *)menu;
    BOOL didAttach = NO;
    JNIEnv *env = getEnv(&didAttach);
    if (!env) return;
    jclass cls = (*env)->FindClass(env, BRIDGE_CLASS);
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, methodName, sig);
        if (mid) (*env)->CallStaticVoidMethod(env, cls, mid, menuHandle);
    }
    clearException(env);
    releaseEnv(didAttach);
}

- (void)menuWillOpen:(NSMenu *)menu {
    callDelegateVoid(menu, "onMenuWillOpen", "(J)V");
}

- (void)menuDidClose:(NSMenu *)menu {
    callDelegateVoid(menu, "onMenuDidClose", "(J)V");
}

- (void)menuNeedsUpdate:(NSMenu *)menu {
    callDelegateVoid(menu, "onMenuNeedsUpdate", "(J)V");
}

- (void)menu:(NSMenu *)menu willHighlightItem:(NSMenuItem *)item {
    jlong menuHandle = (jlong)(void *)(__bridge void *)menu;
    jlong itemHandle = item ? (jlong)(void *)(__bridge void *)item : 0;
    BOOL didAttach = NO;
    JNIEnv *env = getEnv(&didAttach);
    if (!env) return;
    jclass cls = (*env)->FindClass(env, BRIDGE_CLASS);
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "onMenuWillHighlightItem", "(JJ)V");
        if (mid) (*env)->CallStaticVoidMethod(env, cls, mid, menuHandle, itemHandle);
    }
    clearException(env);
    releaseEnv(didAttach);
}

- (NSInteger)numberOfItemsInMenu:(NSMenu *)menu {
    jlong menuHandle = (jlong)(void *)(__bridge void *)menu;
    BOOL didAttach = NO;
    JNIEnv *env = getEnv(&didAttach);
    if (!env) return -1;
    jint result = -1;
    jclass cls = (*env)->FindClass(env, BRIDGE_CLASS);
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "onNumberOfItemsInMenu", "(J)I");
        if (mid) result = (*env)->CallStaticIntMethod(env, cls, mid, menuHandle);
    }
    clearException(env);
    releaseEnv(didAttach);
    return (NSInteger)result;
}

@end

// Global proxy storage (one per menu, keyed by menu pointer)
static NSMutableDictionary<NSValue *, NucleusMenuDelegateProxy *> *g_delegateProxies = nil;

static void ensureDelegateStorage(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        g_delegateProxies = [NSMutableDictionary new];
    });
}

// ============================================================================
// Image helper
// ============================================================================

static NSImage *makeImage(JNIEnv *env, jint imageType, jstring jValue, jstring jAccessDesc) {
    if (imageType == IMG_CLEAR) return nil;

    NSString *value = toNSString(env, jValue);
    if (value == nil) return nil;

    switch (imageType) {
        case IMG_NAMED:
            return [NSImage imageNamed:value];
        case IMG_SYMBOL:
            if (@available(macOS 11.0, *)) {
                NSString *desc = toNSString(env, jAccessDesc);
                return [NSImage imageWithSystemSymbolName:value accessibilityDescription:desc];
            }
            return nil;
        case IMG_FILE:
            return [[NSImage alloc] initWithContentsOfFile:value];
        default:
            return nil;
    }
}

// ============================================================================
// SECTION: Lifecycle
// ============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateMenu)(JNIEnv *env, jclass clazz, jstring jTitle) {
    (void)clazz;
    @autoreleasepool {
        NSString *title = toNSString(env, jTitle) ?: @"";
        __block jlong result = 0;
        runOnMain(^{
            NSMenu *menu = [[NSMenu alloc] initWithTitle:title];
            result = retainToHandle(menu);
        });
        return result;
    }
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateItem)(JNIEnv *env, jclass clazz,
                                                   jstring jTitle, jstring jKeyEq) {
    (void)clazz;
    @autoreleasepool {
        NSString *title = toNSString(env, jTitle) ?: @"";
        NSString *keyEq = toNSString(env, jKeyEq) ?: @"";
        __block jlong result = 0;
        runOnMain(^{
            NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:title
                                                          action:nil
                                                   keyEquivalent:keyEq];
            result = retainToHandle(item);
        });
        return result;
    }
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateSeparatorItem)(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    __block jlong result = 0;
    runOnMain(^{
        result = retainToHandle([NSMenuItem separatorItem]);
    });
    return result;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateSectionHeader)(JNIEnv *env, jclass clazz, jstring jTitle) {
    (void)clazz;
    @autoreleasepool {
        NSString *title = toNSString(env, jTitle) ?: @"";
        __block jlong result = 0;
        runOnMain(^{
            if (@available(macOS 14.0, *)) {
                result = retainToHandle([NSMenuItem sectionHeaderWithTitle:title]);
            } else {
                // Fallback: create a disabled item as a section header
                NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:title action:nil keyEquivalent:@""];
                [item setEnabled:NO];
                result = retainToHandle(item);
            }
        });
        return result;
    }
}

// ============================================================================
// SECTION: Search-field menu item (native in-menu filtering)
// ============================================================================

// Filters the sibling items of its enclosing menu as the user types: an item
// stays visible while its title contains the query (case/diacritic-insensitive).
// Separators are hidden while a query is active. Everything resets when the
// menu closes. Filtering is fully native — no JNI round-trip per keystroke.
@interface NucleusMenuSearchFilter : NSObject <NSSearchFieldDelegate>
@property (nonatomic, weak) NSSearchField *field;
@end

@implementation NucleusMenuSearchFilter

- (instancetype)init {
    if ((self = [super init])) {
        NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
        [nc addObserver:self selector:@selector(menuDidEndTracking:)
                   name:NSMenuDidEndTrackingNotification object:nil];
        [nc addObserver:self selector:@selector(menuDidBeginTracking:)
                   name:NSMenuDidBeginTrackingNotification object:nil];
    }
    return self;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (NSMenu *)hostMenu {
    return self.field.enclosingMenuItem.menu;
}

- (void)searchChanged:(id)sender {
    (void)sender;
    [self applyFilter];
}

// Fires on every keystroke — `continuous`/target-action alone only fires on
// Enter or the clear button, which is what made filtering feel non-live.
- (void)controlTextDidChange:(NSNotification *)notification {
    (void)notification;
    [self applyFilter];
}

- (void)applyFilter {
    NSSearchField *field = self.field;
    if (!field) return;
    NSMenuItem *host = field.enclosingMenuItem;
    NSMenu *menu = host.menu;
    if (!menu) return;
    NSString *query = [field.stringValue stringByTrimmingCharactersInSet:
                       [NSCharacterSet whitespaceCharacterSet]];
    BOOL filtering = query.length > 0;
    for (NSMenuItem *item in menu.itemArray) {
        if (item == host) continue;
        if (!filtering) {
            item.hidden = NO;
        } else if (item.separatorItem) {
            item.hidden = YES;
        } else {
            NSRange r = [item.title rangeOfString:query
                                          options:(NSCaseInsensitiveSearch |
                                                   NSDiacriticInsensitiveSearch |
                                                   NSWidthInsensitiveSearch)];
            item.hidden = (r.location == NSNotFound);
        }
    }
}

- (void)menuDidBeginTracking:(NSNotification *)note {
    if (note.object != [self hostMenu]) return;
    NSSearchField *field = self.field;
    // The item view's window only exists once the menu is on screen.
    dispatch_async(dispatch_get_main_queue(), ^{
        if (field.window) [field.window makeFirstResponder:field];
    });
}

- (void)menuDidEndTracking:(NSNotification *)note {
    if (note.object != [self hostMenu]) return;
    self.field.stringValue = @"";
    [self applyFilter];
}

@end

static char kNucleusSearchFilterKey;

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateSearchFieldItem)(JNIEnv *env, jclass clazz,
                                                            jstring jPlaceholder, jdouble width) {
    (void)clazz;
    @autoreleasepool {
        NSString *placeholder = toNSString(env, jPlaceholder) ?: @"";
        __block jlong result = 0;
        runOnMain(^{
            CGFloat w = width > 0 ? (CGFloat)width : 230.0;
            NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:@"" action:nil keyEquivalent:@""];
            NSView *container = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, w, 28)];
            NSSearchField *field =
                [[NSSearchField alloc] initWithFrame:NSMakeRect(10, 3, w - 20, 22)];
            field.placeholderString = placeholder;
            field.autoresizingMask = NSViewWidthSizable;

            NucleusMenuSearchFilter *filter = [NucleusMenuSearchFilter new];
            filter.field = field;
            field.delegate = filter; // controlTextDidChange: → per-keystroke filtering
            field.target = filter;
            field.action = @selector(searchChanged:);
            // Tie the filter's lifetime to the menu item
            objc_setAssociatedObject(item, &kNucleusSearchFilterKey, filter,
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);

            [container addSubview:field];
            container.autoresizingMask = NSViewWidthSizable;
            item.view = container;
            result = retainToHandle(item);
        });
        return result;
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeRelease)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    releaseHandle(handle);
}

// ============================================================================
// SECTION: Menu properties (get/set by type + property ID)
// ============================================================================

JNIEXPORT jstring JNICALL JNI_FN(nativeMenuGetString)(JNIEnv *env, jclass clazz,
                                                       jlong handle, jint propId) {
    (void)clazz;
    @autoreleasepool {
        NSMenu *menu = HANDLE_TO_MENU(handle);
        NSString *value = nil;
        switch (propId) {
            case MENU_STR_TITLE: value = [menu title]; break;
        }
        return toJString(env, value);
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuSetString)(JNIEnv *env, jclass clazz,
                                                    jlong handle, jint propId, jstring jValue) {
    (void)clazz;
    @autoreleasepool {
        NSMenu *menu = HANDLE_TO_MENU(handle);
        NSString *value = toNSString(env, jValue) ?: @"";
        runOnMain(^{
            switch (propId) {
                case MENU_STR_TITLE: [menu setTitle:value]; break;
            }
        });
    }
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeMenuGetBool)(JNIEnv *env, jclass clazz,
                                                      jlong handle, jint propId) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(handle);
    switch (propId) {
        case MENU_BOOL_AUTO_ENABLES:   return [menu autoenablesItems] ? JNI_TRUE : JNI_FALSE;
        case MENU_BOOL_SHOWS_STATE:    return [menu showsStateColumn] ? JNI_TRUE : JNI_FALSE;
        case MENU_BOOL_ALLOWS_PLUGINS: return [menu allowsContextMenuPlugIns] ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuSetBool)(JNIEnv *env, jclass clazz,
                                                  jlong handle, jint propId, jboolean value) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(handle);
    BOOL val = value ? YES : NO;
    runOnMain(^{
        switch (propId) {
            case MENU_BOOL_AUTO_ENABLES:   [menu setAutoenablesItems:val]; break;
            case MENU_BOOL_SHOWS_STATE:    [menu setShowsStateColumn:val]; break;
            case MENU_BOOL_ALLOWS_PLUGINS: [menu setAllowsContextMenuPlugIns:val]; break;
        }
    });
}

JNIEXPORT jint JNICALL JNI_FN(nativeMenuGetInt)(JNIEnv *env, jclass clazz,
                                                 jlong handle, jint propId) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(handle);
    switch (propId) {
        case MENU_INT_PRESENTATION:
            if (@available(macOS 15.0, *)) {
                return (jint)[menu presentationStyle];
            }
            return 0;
        case MENU_INT_SELECTION:
            if (@available(macOS 15.0, *)) {
                return (jint)[menu selectionMode];
            }
            return 0;
        case MENU_INT_LAYOUT_DIR:
            return (jint)[menu userInterfaceLayoutDirection];
    }
    return 0;
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuSetInt)(JNIEnv *env, jclass clazz,
                                                 jlong handle, jint propId, jint value) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(handle);
    runOnMain(^{
        switch (propId) {
            case MENU_INT_PRESENTATION:
                if (@available(macOS 15.0, *)) {
                    [menu setPresentationStyle:(NSMenuPresentationStyle)value];
                }
                break;
            case MENU_INT_SELECTION:
                if (@available(macOS 15.0, *)) {
                    [menu setSelectionMode:(NSMenuSelectionMode)value];
                }
                break;
            case MENU_INT_LAYOUT_DIR:
                [menu setUserInterfaceLayoutDirection:(NSUserInterfaceLayoutDirection)value];
                break;
        }
    });
}

JNIEXPORT jfloat JNICALL JNI_FN(nativeMenuGetFloat)(JNIEnv *env, jclass clazz,
                                                     jlong handle, jint propId) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(handle);
    switch (propId) {
        case MENU_FLOAT_MIN_WIDTH: return (jfloat)[menu minimumWidth];
    }
    return 0.0f;
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuSetFloat)(JNIEnv *env, jclass clazz,
                                                   jlong handle, jint propId, jfloat value) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(handle);
    runOnMain(^{
        switch (propId) {
            case MENU_FLOAT_MIN_WIDTH: [menu setMinimumWidth:(CGFloat)value]; break;
        }
    });
}

// ============================================================================
// SECTION: Menu item management
// ============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeMenuAddItem)(JNIEnv *env, jclass clazz,
                                                  jlong menuHandle, jlong itemHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSMenuItem *item = HANDLE_TO_ITEM(itemHandle);
    runOnMain(^{ [menu addItem:item]; });
}

JNIEXPORT jlong JNICALL JNI_FN(nativeMenuAddItemWithTitle)(JNIEnv *env, jclass clazz,
                                                            jlong menuHandle, jstring jTitle,
                                                            jstring jKeyEq) {
    (void)clazz;
    @autoreleasepool {
        NSMenu *menu = HANDLE_TO_MENU(menuHandle);
        NSString *title = toNSString(env, jTitle) ?: @"";
        NSString *keyEq = toNSString(env, jKeyEq) ?: @"";
        __block jlong result = 0;
        runOnMain(^{
            NSMenuItem *item = [menu addItemWithTitle:title action:nil keyEquivalent:keyEq];
            result = retainToHandle(item);
        });
        return result;
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuInsertItem)(JNIEnv *env, jclass clazz,
                                                     jlong menuHandle, jlong itemHandle, jint index) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSMenuItem *item = HANDLE_TO_ITEM(itemHandle);
    runOnMain(^{ [menu insertItem:item atIndex:(NSInteger)index]; });
}

JNIEXPORT jlong JNICALL JNI_FN(nativeMenuInsertItemWithTitle)(JNIEnv *env, jclass clazz,
                                                               jlong menuHandle, jstring jTitle,
                                                               jstring jKeyEq, jint index) {
    (void)clazz;
    @autoreleasepool {
        NSMenu *menu = HANDLE_TO_MENU(menuHandle);
        NSString *title = toNSString(env, jTitle) ?: @"";
        NSString *keyEq = toNSString(env, jKeyEq) ?: @"";
        __block jlong result = 0;
        runOnMain(^{
            NSMenuItem *item = [menu insertItemWithTitle:title action:nil
                                           keyEquivalent:keyEq atIndex:(NSInteger)index];
            result = retainToHandle(item);
        });
        return result;
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuRemoveItem)(JNIEnv *env, jclass clazz,
                                                     jlong menuHandle, jlong itemHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSMenuItem *item = HANDLE_TO_ITEM(itemHandle);
    runOnMain(^{ [menu removeItem:item]; });
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuRemoveItemAtIndex)(JNIEnv *env, jclass clazz,
                                                            jlong menuHandle, jint index) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    runOnMain(^{ [menu removeItemAtIndex:(NSInteger)index]; });
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuRemoveAllItems)(JNIEnv *env, jclass clazz,
                                                         jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    runOnMain(^{ [menu removeAllItems]; });
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuItemChanged)(JNIEnv *env, jclass clazz,
                                                      jlong menuHandle, jlong itemHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSMenuItem *item = HANDLE_TO_ITEM(itemHandle);
    runOnMain(^{ [menu itemChanged:item]; });
}

// ============================================================================
// SECTION: Menu finding
// ============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeMenuItemWithTag)(JNIEnv *env, jclass clazz,
                                                       jlong menuHandle, jint tag) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSMenuItem *item = [menu itemWithTag:(NSInteger)tag];
    return retainToHandle(item);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeMenuItemWithTitle)(JNIEnv *env, jclass clazz,
                                                         jlong menuHandle, jstring jTitle) {
    (void)clazz;
    @autoreleasepool {
        NSMenu *menu = HANDLE_TO_MENU(menuHandle);
        NSString *title = toNSString(env, jTitle);
        NSMenuItem *item = [menu itemWithTitle:title];
        return retainToHandle(item);
    }
}

JNIEXPORT jlong JNICALL JNI_FN(nativeMenuItemAtIndex)(JNIEnv *env, jclass clazz,
                                                       jlong menuHandle, jint index) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSInteger count = [menu numberOfItems];
    if (index < 0 || index >= count) return 0;
    NSMenuItem *item = [menu itemAtIndex:(NSInteger)index];
    return retainToHandle(item);
}

JNIEXPORT jint JNICALL JNI_FN(nativeMenuGetNumberOfItems)(JNIEnv *env, jclass clazz,
                                                           jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    return (jint)[menu numberOfItems];
}

JNIEXPORT jlongArray JNICALL JNI_FN(nativeMenuGetItems)(JNIEnv *env, jclass clazz,
                                                         jlong menuHandle) {
    (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSArray<NSMenuItem *> *items = [menu itemArray];
    NSUInteger count = [items count];

    jlongArray result = (*env)->NewLongArray(env, (jsize)count);
    if (result == NULL) return NULL;

    jlong *buf = (*env)->GetLongArrayElements(env, result, NULL);
    for (NSUInteger i = 0; i < count; i++) {
        buf[i] = retainToHandle(items[i]);
    }
    (*env)->ReleaseLongArrayElements(env, result, buf, 0);
    return result;
}

// ============================================================================
// SECTION: Menu indices
// ============================================================================

JNIEXPORT jint JNICALL JNI_FN(nativeMenuIndexOfItem)(JNIEnv *env, jclass clazz,
                                                      jlong menuHandle, jlong itemHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSMenuItem *item = HANDLE_TO_ITEM(itemHandle);
    return (jint)[menu indexOfItem:item];
}

JNIEXPORT jint JNICALL JNI_FN(nativeMenuIndexOfItemWithTitle)(JNIEnv *env, jclass clazz,
                                                               jlong menuHandle, jstring jTitle) {
    (void)clazz;
    @autoreleasepool {
        NSMenu *menu = HANDLE_TO_MENU(menuHandle);
        NSString *title = toNSString(env, jTitle);
        return (jint)[menu indexOfItemWithTitle:title];
    }
}

JNIEXPORT jint JNICALL JNI_FN(nativeMenuIndexOfItemWithTag)(JNIEnv *env, jclass clazz,
                                                             jlong menuHandle, jint tag) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    return (jint)[menu indexOfItemWithTag:(NSInteger)tag];
}

JNIEXPORT jint JNICALL JNI_FN(nativeMenuIndexOfItemWithSubmenu)(JNIEnv *env, jclass clazz,
                                                                 jlong menuHandle, jlong submenuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSMenu *submenu = HANDLE_TO_MENU(submenuHandle);
    return (jint)[menu indexOfItemWithSubmenu:submenu];
}

// ============================================================================
// SECTION: Menu special read-only properties
// ============================================================================

JNIEXPORT jfloatArray JNICALL JNI_FN(nativeMenuGetSize)(JNIEnv *env, jclass clazz,
                                                         jlong menuHandle) {
    (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSSize sz = [menu size];
    jfloatArray result = (*env)->NewFloatArray(env, 2);
    if (result == NULL) return NULL;
    jfloat buf[2] = { (jfloat)sz.width, (jfloat)sz.height };
    (*env)->SetFloatArrayRegion(env, result, 0, 2, buf);
    return result;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeMenuGetHighlightedItem)(JNIEnv *env, jclass clazz,
                                                              jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    return retainToHandle([menu highlightedItem]);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeMenuGetSupermenu)(JNIEnv *env, jclass clazz,
                                                        jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    return retainToHandle([menu supermenu]);
}

JNIEXPORT jlongArray JNICALL JNI_FN(nativeMenuGetSelectedItems)(JNIEnv *env, jclass clazz,
                                                                 jlong menuHandle) {
    (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);

    NSArray<NSMenuItem *> *items = nil;
    if (@available(macOS 15.0, *)) {
        items = [menu selectedItems];
    }
    if (items == nil) items = @[];

    NSUInteger count = [items count];
    jlongArray result = (*env)->NewLongArray(env, (jsize)count);
    if (result == NULL || count == 0) return result;

    jlong *buf = (*env)->GetLongArrayElements(env, result, NULL);
    for (NSUInteger i = 0; i < count; i++) {
        buf[i] = retainToHandle(items[i]);
    }
    (*env)->ReleaseLongArrayElements(env, result, buf, 0);
    return result;
}

// ============================================================================
// SECTION: Menu methods
// ============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeMenuUpdate)(JNIEnv *env, jclass clazz, jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    runOnMain(^{ [menu update]; });
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuSizeToFit)(JNIEnv *env, jclass clazz, jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    runOnMain(^{ [menu sizeToFit]; });
#pragma clang diagnostic pop
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuCancelTracking)(JNIEnv *env, jclass clazz, jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    runOnMain(^{ [menu cancelTracking]; });
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuCancelTrackingWithoutAnimation)(JNIEnv *env, jclass clazz,
                                                                         jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    runOnMain(^{ [menu cancelTrackingWithoutAnimation]; });
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuPerformActionForItemAtIndex)(JNIEnv *env, jclass clazz,
                                                                      jlong menuHandle, jint index) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    runOnMain(^{ [menu performActionForItemAtIndex:(NSInteger)index]; });
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeMenuPopUp)(JNIEnv *env, jclass clazz,
                                                    jlong menuHandle, jlong itemHandle,
                                                    jfloat x, jfloat y) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSMenuItem *item = (itemHandle != 0) ? HANDLE_TO_ITEM(itemHandle) : nil;
    NSPoint location = NSMakePoint((CGFloat)x, (CGFloat)y);

    __block BOOL result = NO;
    runOnMain(^{
        result = [menu popUpMenuPositioningItem:item atLocation:location inView:nil];
    });
    return result ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// SECTION: Menu bar
// ============================================================================

JNIEXPORT jboolean JNICALL JNI_FN(nativeMenuBarIsVisible)(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return [NSMenu menuBarVisible] ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeMenuBarSetVisible)(JNIEnv *env, jclass clazz, jboolean visible) {
    (void)env; (void)clazz;
    BOOL val = visible ? YES : NO;
    runOnMain(^{ [NSMenu setMenuBarVisible:val]; });
}

JNIEXPORT jfloat JNICALL JNI_FN(nativeMenuBarGetHeight)(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jfloat)[[NSApp mainMenu] menuBarHeight];
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetMainMenu)(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    __block jlong result = 0;
    runOnMain(^{
        result = retainToHandle([NSApp mainMenu]);
    });
    return result;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetMainMenu)(JNIEnv *env, jclass clazz, jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    runOnMainGuarded("setMainMenu", ^{
        [NSApp setMainMenu:menu];

        // Register Services menu (nested inside the app menu, i.e. first item's submenu).
        for (NSMenuItem *item in [menu itemArray]) {
            NSMenu *sub = [item submenu];
            if (sub == nil) continue;
            for (NSMenuItem *child in [sub itemArray]) {
                NSMenu *childSub = [child submenu];
                if (childSub && [[childSub title] isEqualToString:@"Services"]) {
                    [NSApp setServicesMenu:childSub];
                }
            }
        }
    });
}

JNIEXPORT void JNICALL JNI_FN(nativeSetWindowsMenu)(JNIEnv *env, jclass clazz, jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    runOnMainGuarded("setWindowsMenu", ^{
        [NSApp setWindowsMenu:menu];
    });
}

JNIEXPORT void JNICALL JNI_FN(nativeSetHelpMenu)(JNIEnv *env, jclass clazz, jlong menuHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    runOnMainGuarded("setHelpMenu", ^{
        [NSApp setHelpMenu:menu];
    });
}


// ============================================================================
// SECTION: Menu submenu
// ============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeMenuSetSubmenuForItem)(JNIEnv *env, jclass clazz,
                                                            jlong menuHandle, jlong submenuHandle,
                                                            jlong itemHandle) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    NSMenu *submenu = (submenuHandle != 0) ? HANDLE_TO_MENU(submenuHandle) : nil;
    NSMenuItem *item = HANDLE_TO_ITEM(itemHandle);
    runOnMain(^{ [menu setSubmenu:submenu forItem:item]; });
}

// ============================================================================
// SECTION: Menu delegate
// ============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeMenuSetDelegate)(JNIEnv *env, jclass clazz,
                                                      jlong menuHandle, jboolean enabled) {
    (void)env; (void)clazz;
    NSMenu *menu = HANDLE_TO_MENU(menuHandle);
    ensureDelegateStorage();

    runOnMain(^{
        NSValue *key = [NSValue valueWithPointer:(__bridge void *)menu];
        if (enabled) {
            NucleusMenuDelegateProxy *proxy = g_delegateProxies[key];
            if (proxy == nil) {
                proxy = [[NucleusMenuDelegateProxy alloc] init];
                g_delegateProxies[key] = proxy;
            }
            [menu setDelegate:proxy];
        } else {
            [menu setDelegate:nil];
            [g_delegateProxies removeObjectForKey:key];
        }
    });
}

// ============================================================================
// SECTION: Item properties (get/set by type + property ID)
// ============================================================================

JNIEXPORT jstring JNICALL JNI_FN(nativeItemGetString)(JNIEnv *env, jclass clazz,
                                                       jlong handle, jint propId) {
    (void)clazz;
    @autoreleasepool {
        NSMenuItem *item = HANDLE_TO_ITEM(handle);
        NSString *value = nil;
        switch (propId) {
            case ITEM_STR_TITLE:    value = [item title]; break;
            case ITEM_STR_KEY_EQ:   value = [item keyEquivalent]; break;
            case ITEM_STR_TOOLTIP:  value = [item toolTip]; break;
            case ITEM_STR_SUBTITLE:
                if (@available(macOS 14.4, *)) {
                    value = [item subtitle];
                }
                break;
        }
        return toJString(env, value);
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeItemSetString)(JNIEnv *env, jclass clazz,
                                                    jlong handle, jint propId, jstring jValue) {
    (void)clazz;
    @autoreleasepool {
        NSMenuItem *item = HANDLE_TO_ITEM(handle);
        NSString *value = toNSString(env, jValue);
        runOnMain(^{
            switch (propId) {
                case ITEM_STR_TITLE:    [item setTitle:value ?: @""]; break;
                case ITEM_STR_KEY_EQ:   [item setKeyEquivalent:value ?: @""]; break;
                case ITEM_STR_TOOLTIP:  [item setToolTip:value]; break;
                case ITEM_STR_SUBTITLE:
                    if (@available(macOS 14.4, *)) {
                        [item setSubtitle:value];
                    }
                    break;
            }
        });
    }
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeItemGetBool)(JNIEnv *env, jclass clazz,
                                                      jlong handle, jint propId) {
    (void)env; (void)clazz;
    NSMenuItem *item = HANDLE_TO_ITEM(handle);
    switch (propId) {
        case ITEM_BOOL_ENABLED:            return [item isEnabled] ? JNI_TRUE : JNI_FALSE;
        case ITEM_BOOL_HIDDEN:             return [item isHidden] ? JNI_TRUE : JNI_FALSE;
        case ITEM_BOOL_ALTERNATE:          return [item isAlternate] ? JNI_TRUE : JNI_FALSE;
        case ITEM_BOOL_HIDDEN_OR_ANCESTOR: return [item isHiddenOrHasHiddenAncestor] ? JNI_TRUE : JNI_FALSE;
        case ITEM_BOOL_IS_SEPARATOR:       return [item isSeparatorItem] ? JNI_TRUE : JNI_FALSE;
        case ITEM_BOOL_IS_HIGHLIGHTED:     return [item isHighlighted] ? JNI_TRUE : JNI_FALSE;
        case ITEM_BOOL_HAS_SUBMENU:        return [item hasSubmenu] ? JNI_TRUE : JNI_FALSE;
        case ITEM_BOOL_IS_SECTION_HEADER:
            if (@available(macOS 14.0, *)) {
                return [item isSectionHeader] ? JNI_TRUE : JNI_FALSE;
            }
            return JNI_FALSE;
        case ITEM_BOOL_KEY_EQ_LOCALIZATION:
            if (@available(macOS 12.0, *)) {
                return [item allowsAutomaticKeyEquivalentLocalization] ? JNI_TRUE : JNI_FALSE;
            }
            return JNI_TRUE;
        case ITEM_BOOL_KEY_EQ_MIRRORING:
            if (@available(macOS 12.0, *)) {
                return [item allowsAutomaticKeyEquivalentMirroring] ? JNI_TRUE : JNI_FALSE;
            }
            return JNI_TRUE;
        case ITEM_BOOL_KEY_EQ_WHEN_HIDDEN:
            return [item allowsKeyEquivalentWhenHidden] ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeItemSetBool)(JNIEnv *env, jclass clazz,
                                                  jlong handle, jint propId, jboolean value) {
    (void)env; (void)clazz;
    NSMenuItem *item = HANDLE_TO_ITEM(handle);
    BOOL val = value ? YES : NO;
    runOnMain(^{
        switch (propId) {
            case ITEM_BOOL_ENABLED:   [item setEnabled:val]; break;
            case ITEM_BOOL_HIDDEN:    [item setHidden:val]; break;
            case ITEM_BOOL_ALTERNATE: [item setAlternate:val]; break;
            case ITEM_BOOL_KEY_EQ_LOCALIZATION:
                if (@available(macOS 12.0, *)) {
                    [item setAllowsAutomaticKeyEquivalentLocalization:val];
                }
                break;
            case ITEM_BOOL_KEY_EQ_MIRRORING:
                if (@available(macOS 12.0, *)) {
                    [item setAllowsAutomaticKeyEquivalentMirroring:val];
                }
                break;
            case ITEM_BOOL_KEY_EQ_WHEN_HIDDEN:
                [item setAllowsKeyEquivalentWhenHidden:val];
                break;
            // Read-only properties are silently ignored
        }
    });
}

JNIEXPORT jint JNICALL JNI_FN(nativeItemGetInt)(JNIEnv *env, jclass clazz,
                                                 jlong handle, jint propId) {
    (void)env; (void)clazz;
    NSMenuItem *item = HANDLE_TO_ITEM(handle);
    switch (propId) {
        case ITEM_INT_TAG:         return (jint)[item tag];
        case ITEM_INT_STATE:       return (jint)[item state];
        case ITEM_INT_INDENT:      return (jint)[item indentationLevel];
        case ITEM_INT_KEY_EQ_MASK: return (jint)[item keyEquivalentModifierMask];
    }
    return 0;
}

JNIEXPORT void JNICALL JNI_FN(nativeItemSetInt)(JNIEnv *env, jclass clazz,
                                                 jlong handle, jint propId, jint value) {
    (void)env; (void)clazz;
    NSMenuItem *item = HANDLE_TO_ITEM(handle);
    runOnMain(^{
        switch (propId) {
            case ITEM_INT_TAG:         [item setTag:(NSInteger)value]; break;
            case ITEM_INT_STATE:       [item setState:(NSControlStateValue)value]; break;
            case ITEM_INT_INDENT:      [item setIndentationLevel:(NSInteger)value]; break;
            case ITEM_INT_KEY_EQ_MASK: [item setKeyEquivalentModifierMask:(NSEventModifierFlags)value]; break;
        }
    });
}

// ============================================================================
// SECTION: Item navigation
// ============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeItemGetSubmenu)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NSMenuItem *item = HANDLE_TO_ITEM(handle);
    return retainToHandle([item submenu]);
}

JNIEXPORT void JNICALL JNI_FN(nativeItemSetSubmenu)(JNIEnv *env, jclass clazz,
                                                     jlong handle, jlong submenuHandle) {
    (void)env; (void)clazz;
    NSMenuItem *item = HANDLE_TO_ITEM(handle);
    NSMenu *submenu = (submenuHandle != 0) ? HANDLE_TO_MENU(submenuHandle) : nil;
    runOnMain(^{ [item setSubmenu:submenu]; });
}

JNIEXPORT jlong JNICALL JNI_FN(nativeItemGetParentItem)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NSMenuItem *item = HANDLE_TO_ITEM(handle);
    return retainToHandle([item parentItem]);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeItemGetMenu)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NSMenuItem *item = HANDLE_TO_ITEM(handle);
    return retainToHandle([item menu]);
}

// ============================================================================
// SECTION: Item image
// ============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeItemSetImage)(JNIEnv *env, jclass clazz,
                                                   jlong handle, jint imageType,
                                                   jstring jValue, jstring jAccessDesc) {
    (void)clazz;
    @autoreleasepool {
        NSMenuItem *item = HANDLE_TO_ITEM(handle);
        NSImage *img = makeImage(env, imageType, jValue, jAccessDesc);
        runOnMain(^{ [item setImage:img]; });
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeItemSetStateImage)(JNIEnv *env, jclass clazz,
                                                        jlong handle, jint stateTarget,
                                                        jint imageType, jstring jValue,
                                                        jstring jAccessDesc) {
    (void)clazz;
    @autoreleasepool {
        NSMenuItem *item = HANDLE_TO_ITEM(handle);
        NSImage *img = makeImage(env, imageType, jValue, jAccessDesc);
        runOnMain(^{
            switch (stateTarget) {
                case STATE_IMG_ON:    [item setOnStateImage:img]; break;
                case STATE_IMG_OFF:   [item setOffStateImage:img]; break;
                case STATE_IMG_MIXED: [item setMixedStateImage:img]; break;
            }
        });
    }
}

// ============================================================================
// SECTION: Item badge (macOS 14+)
// ============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeItemSetBadge)(JNIEnv *env, jclass clazz,
                                                   jlong handle, jint badgeType,
                                                   jint count, jstring jString) {
    (void)clazz;
    @autoreleasepool {
        if (@available(macOS 14.0, *)) {
            NSMenuItem *item = HANDLE_TO_ITEM(handle);
            NSString *str = toNSString(env, jString);

            runOnMain(^{
                NSMenuItemBadge *badge = nil;
                switch (badgeType) {
                    case BADGE_CLEAR:     break;
                    case BADGE_COUNT:     badge = [[NSMenuItemBadge alloc] initWithCount:(NSInteger)count]; break;
                    case BADGE_STRING:    badge = [[NSMenuItemBadge alloc] initWithString:str ?: @""]; break;
                    case BADGE_ALERTS:    badge = [NSMenuItemBadge alertsWithCount:(NSInteger)count]; break;
                    case BADGE_NEW_ITEMS: badge = [NSMenuItemBadge newItemsWithCount:(NSInteger)count]; break;
                    case BADGE_UPDATES:   badge = [NSMenuItemBadge updatesWithCount:(NSInteger)count]; break;
                }
                [item setBadge:badge];
            });
        }
    }
}

// ============================================================================
// SECTION: Item action
// ============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeItemSetHasAction)(JNIEnv *env, jclass clazz,
                                                       jlong handle, jboolean enabled) {
    (void)env; (void)clazz;
    NSMenuItem *item = HANDLE_TO_ITEM(handle);
    runOnMain(^{
        if (enabled) {
            [item setTarget:[NucleusMenuActionTarget shared]];
            [item setAction:@selector(menuItemAction:)];
        } else {
            [item setTarget:nil];
            [item setAction:nil];
        }
    });
}
