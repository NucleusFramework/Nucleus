#import <Carbon/Carbon.h>
#import <Cocoa/Cocoa.h>
#include <jni.h>
#include <pthread.h>

// ---- Global state ----
static JavaVM *g_jvm = NULL;
static jclass g_bridgeClass = NULL;
static jmethodID g_onHotKeyMethod = NULL;
// NSEvent monitors that intercept hot key NSEvents (type=SystemDefined,
// subtype=6) before they are dispatched. We use these instead of installing a
// Carbon EventHandler because the Tao backend's NSApplication event pipeline
// does not forward these events through the standard
// `[NSApp sendEvent:] → SendEventToEventTarget(GetApplicationEventTarget())`
// chain that Carbon handlers depend on. The local monitor fires upstream of
// sendEvent: when the app is focused; the global monitor fires when the
// hot key is pressed while the app is in the background. NSEventMaskSystemDefined
// monitoring does not require Accessibility / Input Monitoring permissions.
static id g_eventMonitorLocal = nil;
static id g_eventMonitorGlobal = nil;

// Map registration id → EventHotKeyRef for unregistration
#define MAX_HOTKEYS 256
static EventHotKeyRef g_hotKeyRefs[MAX_HOTKEYS];
static jlong g_hotKeyIds[MAX_HOTKEYS];
static int g_hotKeyCount = 0;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

// ---- AWT VK_* to macOS key code mapping ----
// Based on Carbon kVK_* constants (Events.h / HIToolbox)

typedef struct {
    int awtKeyCode;
    int macKeyCode;
} KeyMapping;

static const KeyMapping g_keyMap[] = {
    // Letters
    { 0x41 /* VK_A */, kVK_ANSI_A },
    { 0x42 /* VK_B */, kVK_ANSI_B },
    { 0x43 /* VK_C */, kVK_ANSI_C },
    { 0x44 /* VK_D */, kVK_ANSI_D },
    { 0x45 /* VK_E */, kVK_ANSI_E },
    { 0x46 /* VK_F */, kVK_ANSI_F },
    { 0x47 /* VK_G */, kVK_ANSI_G },
    { 0x48 /* VK_H */, kVK_ANSI_H },
    { 0x49 /* VK_I */, kVK_ANSI_I },
    { 0x4A /* VK_J */, kVK_ANSI_J },
    { 0x4B /* VK_K */, kVK_ANSI_K },
    { 0x4C /* VK_L */, kVK_ANSI_L },
    { 0x4D /* VK_M */, kVK_ANSI_M },
    { 0x4E /* VK_N */, kVK_ANSI_N },
    { 0x4F /* VK_O */, kVK_ANSI_O },
    { 0x50 /* VK_P */, kVK_ANSI_P },
    { 0x51 /* VK_Q */, kVK_ANSI_Q },
    { 0x52 /* VK_R */, kVK_ANSI_R },
    { 0x53 /* VK_S */, kVK_ANSI_S },
    { 0x54 /* VK_T */, kVK_ANSI_T },
    { 0x55 /* VK_U */, kVK_ANSI_U },
    { 0x56 /* VK_V */, kVK_ANSI_V },
    { 0x57 /* VK_W */, kVK_ANSI_W },
    { 0x58 /* VK_X */, kVK_ANSI_X },
    { 0x59 /* VK_Y */, kVK_ANSI_Y },
    { 0x5A /* VK_Z */, kVK_ANSI_Z },

    // Numbers (top row)
    { 0x30 /* VK_0 */, kVK_ANSI_0 },
    { 0x31 /* VK_1 */, kVK_ANSI_1 },
    { 0x32 /* VK_2 */, kVK_ANSI_2 },
    { 0x33 /* VK_3 */, kVK_ANSI_3 },
    { 0x34 /* VK_4 */, kVK_ANSI_4 },
    { 0x35 /* VK_5 */, kVK_ANSI_5 },
    { 0x36 /* VK_6 */, kVK_ANSI_6 },
    { 0x37 /* VK_7 */, kVK_ANSI_7 },
    { 0x38 /* VK_8 */, kVK_ANSI_8 },
    { 0x39 /* VK_9 */, kVK_ANSI_9 },

    // Function keys
    { 0x70 /* VK_F1  */, kVK_F1 },
    { 0x71 /* VK_F2  */, kVK_F2 },
    { 0x72 /* VK_F3  */, kVK_F3 },
    { 0x73 /* VK_F4  */, kVK_F4 },
    { 0x74 /* VK_F5  */, kVK_F5 },
    { 0x75 /* VK_F6  */, kVK_F6 },
    { 0x76 /* VK_F7  */, kVK_F7 },
    { 0x77 /* VK_F8  */, kVK_F8 },
    { 0x78 /* VK_F9  */, kVK_F9 },
    { 0x79 /* VK_F10 */, kVK_F10 },
    { 0x7A /* VK_F11 */, kVK_F11 },
    { 0x7B /* VK_F12 */, kVK_F12 },

    // Special keys
    { 0x0A /* VK_ENTER     */, kVK_Return },
    { 0x1B /* VK_ESCAPE    */, kVK_Escape },
    { 0x08 /* VK_BACK_SPACE*/, kVK_Delete },
    { 0x09 /* VK_TAB       */, kVK_Tab },
    { 0x20 /* VK_SPACE     */, kVK_Space },
    { 0x7F /* VK_DELETE    */, kVK_ForwardDelete },

    // Punctuation / symbols
    { 0xC0 /* VK_BACK_QUOTE */, kVK_ANSI_Grave },
    { 0x2D /* VK_MINUS      */, kVK_ANSI_Minus },
    { 0x3D /* VK_EQUALS     */, kVK_ANSI_Equal },
    { 0x5B /* VK_OPEN_BRACKET */, kVK_ANSI_LeftBracket },
    { 0x5D /* VK_CLOSE_BRACKET*/, kVK_ANSI_RightBracket },
    { 0x5C /* VK_BACK_SLASH */, kVK_ANSI_Backslash },
    { 0x3B /* VK_SEMICOLON  */, kVK_ANSI_Semicolon },
    { 0xDE /* VK_QUOTE      */, kVK_ANSI_Quote },
    { 0x2C /* VK_COMMA      */, kVK_ANSI_Comma },
    { 0x2E /* VK_PERIOD     */, kVK_ANSI_Period },
    { 0x2F /* VK_SLASH      */, kVK_ANSI_Slash },

    // Navigation
    { 0x26 /* VK_UP    */, kVK_UpArrow },
    { 0x28 /* VK_DOWN  */, kVK_DownArrow },
    { 0x25 /* VK_LEFT  */, kVK_LeftArrow },
    { 0x27 /* VK_RIGHT */, kVK_RightArrow },
    { 0x24 /* VK_HOME  */, kVK_Home },
    { 0x23 /* VK_END   */, kVK_End },
    { 0x21 /* VK_PAGE_UP   */, kVK_PageUp },
    { 0x22 /* VK_PAGE_DOWN */, kVK_PageDown },

    // Numpad
    { 0x60 /* VK_NUMPAD0 */, kVK_ANSI_Keypad0 },
    { 0x61 /* VK_NUMPAD1 */, kVK_ANSI_Keypad1 },
    { 0x62 /* VK_NUMPAD2 */, kVK_ANSI_Keypad2 },
    { 0x63 /* VK_NUMPAD3 */, kVK_ANSI_Keypad3 },
    { 0x64 /* VK_NUMPAD4 */, kVK_ANSI_Keypad4 },
    { 0x65 /* VK_NUMPAD5 */, kVK_ANSI_Keypad5 },
    { 0x66 /* VK_NUMPAD6 */, kVK_ANSI_Keypad6 },
    { 0x67 /* VK_NUMPAD7 */, kVK_ANSI_Keypad7 },
    { 0x68 /* VK_NUMPAD8 */, kVK_ANSI_Keypad8 },
    { 0x69 /* VK_NUMPAD9 */, kVK_ANSI_Keypad9 },
    { 0x6A /* VK_MULTIPLY */, kVK_ANSI_KeypadMultiply },
    { 0x6B /* VK_ADD      */, kVK_ANSI_KeypadPlus },
    { 0x6D /* VK_SUBTRACT */, kVK_ANSI_KeypadMinus },
    { 0x6E /* VK_DECIMAL  */, kVK_ANSI_KeypadDecimal },
    { 0x6F /* VK_DIVIDE   */, kVK_ANSI_KeypadDivide },
};

static const int g_keyMapSize = sizeof(g_keyMap) / sizeof(g_keyMap[0]);

static int awtKeyCodeToMac(int awtKeyCode) {
    for (int i = 0; i < g_keyMapSize; i++) {
        if (g_keyMap[i].awtKeyCode == awtKeyCode) {
            return g_keyMap[i].macKeyCode;
        }
    }
    return -1;
}

// ---- Hotkey ref tracking ----

static void storeHotKeyRef(jlong id, EventHotKeyRef ref) {
    pthread_mutex_lock(&g_mutex);
    if (g_hotKeyCount < MAX_HOTKEYS) {
        g_hotKeyIds[g_hotKeyCount] = id;
        g_hotKeyRefs[g_hotKeyCount] = ref;
        g_hotKeyCount++;
    }
    pthread_mutex_unlock(&g_mutex);
}

static EventHotKeyRef removeHotKeyRef(jlong id) {
    EventHotKeyRef ref = NULL;
    pthread_mutex_lock(&g_mutex);
    for (int i = 0; i < g_hotKeyCount; i++) {
        if (g_hotKeyIds[i] == id) {
            ref = g_hotKeyRefs[i];
            // Shift remaining entries
            for (int j = i; j < g_hotKeyCount - 1; j++) {
                g_hotKeyIds[j] = g_hotKeyIds[j + 1];
                g_hotKeyRefs[j] = g_hotKeyRefs[j + 1];
            }
            g_hotKeyCount--;
            break;
        }
    }
    pthread_mutex_unlock(&g_mutex);
    return ref;
}

// ---- NSEvent → JVM dispatch ----

// Forwards a single hot key event to the Kotlin bridge.
static void fireHotKeyToJVM(jlong id, jint keyCode) {
    if (g_jvm == NULL || g_bridgeClass == NULL || g_onHotKeyMethod == NULL) return;

    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    BOOL didAttach = NO;
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) {
            return;
        }
        didAttach = YES;
    } else if (attached != JNI_OK) {
        return;
    }

    (*env)->CallStaticVoidMethod(env, g_bridgeClass, g_onHotKeyMethod,
                                 id, keyCode, (jint)0);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    if (didAttach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

// NSSystemDefined event subtype emitted when a Carbon-registered hot key fires.
// Documented in HIToolbox internal headers as `kEventHotKeyPressedSubtype = 6`
// (released = 9).
#define NS_HOT_KEY_PRESSED_SUBTYPE 6

// ---- JNI exports ----

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_globalhotkey_macos_NativeMacOsHotKeyBridge_nativeInit(
    JNIEnv *env, jclass clazz) {
    @autoreleasepool {
        if (g_eventMonitorLocal != nil || g_eventMonitorGlobal != nil) return NULL; // Already initialized

        // Cache the bridge class (global ref) and callback method
        g_bridgeClass = (*env)->NewGlobalRef(env, clazz);
        g_onHotKeyMethod = (*env)->GetStaticMethodID(env, clazz, "onHotKey", "(JII)V");
        if (g_onHotKeyMethod == NULL) {
            (*env)->DeleteGlobalRef(env, g_bridgeClass);
            g_bridgeClass = NULL;
            return (*env)->NewStringUTF(env, "Failed to find onHotKey callback method");
        }

        void (^handle)(NSEvent *) = ^(NSEvent *event) {
            if (event.subtype != NS_HOT_KEY_PRESSED_SUBTYPE) return;
            // The Carbon EventRef behind the NSEvent carries the original
            // EventHotKeyID we passed to RegisterEventHotKey. NSEvent.data1
            // contains a packed value that doesn't trivially decode to our
            // id, so we go through Carbon's GetEventParameter instead.
            EventRef eventRef = (EventRef)[event eventRef];
            if (eventRef == NULL) return;
            EventHotKeyID hotKeyID;
            if (GetEventParameter(eventRef, kEventParamDirectObject, typeEventHotKeyID,
                                  NULL, sizeof(hotKeyID), NULL, &hotKeyID) != noErr) {
                return;
            }
            fireHotKeyToJVM((jlong)hotKeyID.id, (jint)hotKeyID.signature);
        };

        // Local: fires when our app has focus. Runs upstream of sendEvent:.
        g_eventMonitorLocal = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskSystemDefined
                                                                    handler:^NSEvent *(NSEvent *event) {
            handle(event);
            return event;
        }];
        // Global: fires for events delivered to other apps (i.e. when our app
        // is in the background). Required because RegisterEventHotKey hot keys
        // are typically pressed while the user is working in another app.
        g_eventMonitorGlobal = [NSEvent addGlobalMonitorForEventsMatchingMask:NSEventMaskSystemDefined
                                                                      handler:^(NSEvent *event) {
            handle(event);
        }];
        if (g_eventMonitorLocal == nil && g_eventMonitorGlobal == nil) {
            (*env)->DeleteGlobalRef(env, g_bridgeClass);
            g_bridgeClass = NULL;
            return (*env)->NewStringUTF(env, "Failed to install NSEvent monitors");
        }

        return NULL; // success
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_globalhotkey_macos_NativeMacOsHotKeyBridge_nativeRegister(
    JNIEnv *env, jclass clazz, jlong id, jint modifiers, jint keyCode) {
    (void)clazz;
    @autoreleasepool {
        if (g_eventMonitorLocal == nil && g_eventMonitorGlobal == nil) {
            return (*env)->NewStringUTF(env, "Not initialized");
        }

        int macKeyCode = awtKeyCodeToMac((int)keyCode);
        if (macKeyCode < 0) {
            return (*env)->NewStringUTF(env, "Unsupported key code");
        }

        // Map portable modifier flags to Carbon modifier constants
        UInt32 carbonMods = 0;
        if (modifiers & 0x0001) carbonMods |= optionKey;   // ALT → Option
        if (modifiers & 0x0002) carbonMods |= controlKey;  // CONTROL → Control
        if (modifiers & 0x0004) carbonMods |= shiftKey;     // SHIFT → Shift
        if (modifiers & 0x0008) carbonMods |= cmdKey;       // META → Command

        EventHotKeyID hotKeyID;
        hotKeyID.signature = (UInt32)keyCode; // Store AWT key code for callback
        hotKeyID.id = (UInt32)id;

        EventHotKeyRef hotKeyRef = NULL;
        OSStatus status = RegisterEventHotKey(
            (UInt32)macKeyCode, carbonMods, hotKeyID,
            GetApplicationEventTarget(), 0, &hotKeyRef
        );

        if (status != noErr) {
            return (*env)->NewStringUTF(env, "RegisterEventHotKey failed");
        }

        storeHotKeyRef(id, hotKeyRef);
        return NULL; // success
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_globalhotkey_macos_NativeMacOsHotKeyBridge_nativeUnregister(
    JNIEnv *env, jclass clazz, jlong id) {
    (void)clazz;
    @autoreleasepool {
        EventHotKeyRef ref = removeHotKeyRef(id);
        if (ref == NULL) {
            return (*env)->NewStringUTF(env, "Unknown hotkey id");
        }

        OSStatus status = UnregisterEventHotKey(ref);
        if (status != noErr) {
            return (*env)->NewStringUTF(env, "UnregisterEventHotKey failed");
        }
        return NULL; // success
    }
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_globalhotkey_macos_NativeMacOsHotKeyBridge_nativeShutdown(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    @autoreleasepool {
        // Unregister all remaining hotkeys
        pthread_mutex_lock(&g_mutex);
        for (int i = 0; i < g_hotKeyCount; i++) {
            UnregisterEventHotKey(g_hotKeyRefs[i]);
        }
        g_hotKeyCount = 0;
        pthread_mutex_unlock(&g_mutex);

        // Remove NSEvent monitors
        if (g_eventMonitorLocal != nil) {
            [NSEvent removeMonitor:g_eventMonitorLocal];
            g_eventMonitorLocal = nil;
        }
        if (g_eventMonitorGlobal != nil) {
            [NSEvent removeMonitor:g_eventMonitorGlobal];
            g_eventMonitorGlobal = nil;
        }

        // Release global ref
        if (g_bridgeClass != NULL) {
            (*env)->DeleteGlobalRef(env, g_bridgeClass);
            g_bridgeClass = NULL;
        }

        g_onHotKeyMethod = NULL;
    }
}
