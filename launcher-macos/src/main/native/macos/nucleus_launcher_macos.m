/**
 * JNI bridge for macOS dock context menu.
 *
 * Intercepts applicationDockMenu: via method swizzling on the existing
 * NSApplicationDelegate class. Menu items are built from flat arrays
 * passed from Kotlin via JNI.
 *
 * Frameworks: Cocoa
 */

#import <Cocoa/Cocoa.h>
#import <objc/runtime.h>
#include <jni.h>
#include <string.h>

// ============================================================================
// Globals
// ============================================================================

static JavaVM *g_jvm = NULL;
static NSMenu *g_dockMenu = nil;
static BOOL g_swizzled = NO;

#define BRIDGE_CLASS "dev/nucleusframework/nucleus/launcher/macos/NativeMacOsDockMenuBridge"

// ============================================================================
// JNI helpers
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

static JNIEnv *getEnv(BOOL *didAttach) {
    *didAttach = NO;
    if (g_jvm == NULL) return NULL;

    JNIEnv *env = NULL;
    jint status = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) {
            return NULL;
        }
        *didAttach = YES;
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

static void releaseEnv(BOOL didAttach) {
    if (didAttach && g_jvm != NULL) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

static void clearException(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

// Helper: run a block on the main thread (sync if off-main, direct if on-main)
static void runOnMain(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

// ============================================================================
// Dock Menu: item action handler
// ============================================================================

@interface NucleusDockMenuTarget : NSObject
+ (instancetype)shared;
- (void)menuItemClicked:(NSMenuItem *)sender;
@end

@implementation NucleusDockMenuTarget

+ (instancetype)shared {
    static NucleusDockMenuTarget *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[NucleusDockMenuTarget alloc] init];
    });
    return instance;
}

- (void)menuItemClicked:(NSMenuItem *)sender {
    int itemId = (int)[sender tag];

    BOOL didAttach = NO;
    JNIEnv *cbEnv = getEnv(&didAttach);
    if (cbEnv == NULL) return;

    jclass cls = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
    if (cls != NULL) {
        jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, cls,
            "onMenuItemClicked", "(I)V");
        if (method != NULL) {
            (*cbEnv)->CallStaticVoidMethod(cbEnv, cls, method, (jint)itemId);
        }
    }
    clearException(cbEnv);
    releaseEnv(didAttach);
}

@end

// ============================================================================
// Dock Menu: swizzle applicationDockMenu:
// ============================================================================

static IMP g_originalDockMenuIMP = NULL;

static NSMenu *swizzled_applicationDockMenu(id self, SEL _cmd, NSApplication *sender) {
    if (g_dockMenu != nil) {
        return g_dockMenu;
    }
    if (g_originalDockMenuIMP != NULL) {
        return ((NSMenu *(*)(id, SEL, NSApplication *))g_originalDockMenuIMP)(self, _cmd, sender);
    }
    return nil;
}

static void ensureSwizzled(void) {
    if (g_swizzled) return;

    NSApplication *app = [NSApplication sharedApplication];
    id delegate = [app delegate];
    if (delegate == nil) return;

    Class delegateClass = [delegate class];
    SEL sel = @selector(applicationDockMenu:);
    Method existing = class_getInstanceMethod(delegateClass, sel);

    if (existing != NULL) {
        g_originalDockMenuIMP = method_setImplementation(existing,
            (IMP)swizzled_applicationDockMenu);
    } else {
        class_addMethod(delegateClass, sel,
            (IMP)swizzled_applicationDockMenu, "@@:@");
    }

    g_swizzled = YES;
}

// ============================================================================
// Dock Menu: C structs for JNI → main-thread handoff
// ============================================================================

typedef struct {
    int itemId;
    char *title;
    BOOL enabled;
    int parentIndex;
    BOOL isSeparator;
} MenuItemData;

static MenuItemData *extractMenuData(JNIEnv *env,
                                     jintArray jIds, jobjectArray jTitles,
                                     jbooleanArray jEnabled,
                                     jintArray jParentIndices,
                                     jbooleanArray jSeparators,
                                     jsize *outCount) {
    jsize count = (*env)->GetArrayLength(env, jIds);
    *outCount = count;
    if (count == 0) return NULL;

    jint *ids = (*env)->GetIntArrayElements(env, jIds, NULL);
    jboolean *enabled = (*env)->GetBooleanArrayElements(env, jEnabled, NULL);
    jint *parentIndices = (*env)->GetIntArrayElements(env, jParentIndices, NULL);
    jboolean *separators = (*env)->GetBooleanArrayElements(env, jSeparators, NULL);

    MenuItemData *data = (MenuItemData *)calloc(count, sizeof(MenuItemData));

    for (jsize i = 0; i < count; i++) {
        data[i].itemId = ids[i];
        data[i].enabled = (BOOL)enabled[i];
        data[i].parentIndex = parentIndices[i];
        data[i].isSeparator = (BOOL)separators[i];

        jstring jTitle = (*env)->GetObjectArrayElement(env, jTitles, i);
        if (jTitle != NULL) {
            const char *utf = (*env)->GetStringUTFChars(env, jTitle, NULL);
            data[i].title = utf ? strdup(utf) : strdup("");
            (*env)->ReleaseStringUTFChars(env, jTitle, utf);
        } else {
            data[i].title = strdup("");
        }
    }

    (*env)->ReleaseIntArrayElements(env, jIds, ids, JNI_ABORT);
    (*env)->ReleaseBooleanArrayElements(env, jEnabled, enabled, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jParentIndices, parentIndices, JNI_ABORT);
    (*env)->ReleaseBooleanArrayElements(env, jSeparators, separators, JNI_ABORT);

    return data;
}

static void freeMenuData(MenuItemData *data, jsize count) {
    for (jsize i = 0; i < count; i++) {
        free(data[i].title);
    }
    free(data);
}

static NSMenu *buildMenuFromData(MenuItemData *data, jsize count) {
    if (count == 0) return nil;

    NSMutableArray<NSMenuItem *> *menuItems = [NSMutableArray arrayWithCapacity:count];
    NucleusDockMenuTarget *target = [NucleusDockMenuTarget shared];

    for (jsize i = 0; i < count; i++) {
        NSMenuItem *item;
        if (data[i].isSeparator) {
            item = [NSMenuItem separatorItem];
            [item setTag:data[i].itemId];
        } else {
            NSString *title = [NSString stringWithUTF8String:data[i].title];
            item = [[NSMenuItem alloc] initWithTitle:title
                                              action:@selector(menuItemClicked:)
                                       keyEquivalent:@""];
            [item setTarget:target];
            [item setTag:data[i].itemId];
            [item setEnabled:data[i].enabled];
        }
        [menuItems addObject:item];
    }

    NSMenu *rootMenu = [[NSMenu alloc] init];
    for (jsize i = 0; i < count; i++) {
        int parentIdx = data[i].parentIndex;
        if (parentIdx < 0) {
            [rootMenu addItem:menuItems[i]];
        } else if (parentIdx < (int)menuItems.count) {
            NSMenuItem *parent = menuItems[parentIdx];
            NSMenu *submenu = [parent submenu];
            if (submenu == nil) {
                submenu = [[NSMenu alloc] init];
                [parent setSubmenu:submenu];
            }
            [submenu addItem:menuItems[i]];
        }
    }

    return rootMenu;
}

// ============================================================================
// JNI exports
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_launcher_macos_NativeMacOsDockMenuBridge_nativeSetDockMenu(
    JNIEnv *env, jclass clazz,
    jintArray ids, jobjectArray titles, jbooleanArray enabled,
    jintArray parentIndices, jbooleanArray separators) {
    (void)clazz;

    jsize count = 0;
    MenuItemData *data = extractMenuData(env, ids, titles, enabled,
                                          parentIndices, separators, &count);

    runOnMain(^{
        @autoreleasepool {
            g_dockMenu = buildMenuFromData(data, count);
            freeMenuData(data, count);
            ensureSwizzled();
        }
    });
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_launcher_macos_NativeMacOsDockMenuBridge_nativeClearDockMenu(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;

    runOnMain(^{
        @autoreleasepool {
            g_dockMenu = nil;
        }
    });
}
