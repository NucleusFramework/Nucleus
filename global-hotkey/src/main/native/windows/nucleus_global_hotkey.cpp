#include <jni.h>
#include <windows.h>
#include <string>

// ---- Global state ----
static JavaVM* g_jvm = nullptr;
static jobject g_bridgeRef = nullptr;  // Global ref to NativeWindowsHotKeyBridge
static jmethodID g_onHotKeyMethod = nullptr;
static DWORD g_threadId = 0;
static HANDLE g_thread = nullptr;
static HANDLE g_readyEvent = nullptr;   // Signaled when message loop is ready
static bool g_running = false;

// Custom WM message to signal register/unregister/shutdown
#define WM_HOTKEY_REGISTER   (WM_USER + 100)
#define WM_HOTKEY_UNREGISTER (WM_USER + 101)
#define WM_HOTKEY_SHUTDOWN   (WM_USER + 102)

struct HotKeyRequest {
    jlong id;
    int modifiers;
    int keyCode;
    HANDLE completionEvent;  // Signaled when the message loop has processed the request
    char error[256];         // Empty on success, error message on failure
};

static JNIEnv* attachCurrentThread() {
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
    }
    return env;
}

static void fireHotKey(jlong id, int keyCode, int modifiers) {
    JNIEnv* env = attachCurrentThread();
    if (env && g_bridgeRef && g_onHotKeyMethod) {
        env->CallStaticVoidMethod(
            env->GetObjectClass(g_bridgeRef),
            g_onHotKeyMethod,
            id,
            static_cast<jint>(keyCode),
            static_cast<jint>(modifiers)
        );
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
}

static DWORD WINAPI messageLoopThread(LPVOID) {
    // Create a message queue for this thread
    MSG msg;
    PeekMessage(&msg, nullptr, 0, 0, PM_NOREMOVE);

    g_running = true;
    SetEvent(g_readyEvent);

    while (GetMessage(&msg, nullptr, 0, 0)) {
        if (msg.message == WM_HOTKEY) {
            jlong id = static_cast<jlong>(msg.wParam);
            int mods = LOWORD(msg.lParam);
            int vk = HIWORD(msg.lParam);
            fireHotKey(id, vk, mods);
        } else if (msg.message == WM_HOTKEY_REGISTER) {
            auto* req = reinterpret_cast<HotKeyRequest*>(msg.lParam);
            if (req) {
                if (!RegisterHotKey(nullptr, static_cast<int>(req->id), req->modifiers, req->keyCode)) {
                    DWORD err = GetLastError();
                    if (err == ERROR_HOTKEY_ALREADY_REGISTERED) {
                        strncpy(req->error, "Hotkey already registered by another application", sizeof(req->error) - 1);
                    } else {
                        _snprintf(req->error, sizeof(req->error) - 1, "RegisterHotKey failed (error %lu)", err);
                    }
                    req->error[sizeof(req->error) - 1] = '\0';
                }
                SetEvent(req->completionEvent);
            }
        } else if (msg.message == WM_HOTKEY_UNREGISTER) {
            auto* req = reinterpret_cast<HotKeyRequest*>(msg.lParam);
            if (req) {
                if (!UnregisterHotKey(nullptr, static_cast<int>(req->id))) {
                    _snprintf(req->error, sizeof(req->error) - 1, "UnregisterHotKey failed (error %lu)", GetLastError());
                    req->error[sizeof(req->error) - 1] = '\0';
                }
                SetEvent(req->completionEvent);
            }
        } else if (msg.message == WM_HOTKEY_SHUTDOWN) {
            break;
        }
    }

    g_running = false;

    JNIEnv* env = attachCurrentThread();
    if (env && g_bridgeRef) {
        env->DeleteGlobalRef(g_bridgeRef);
        g_bridgeRef = nullptr;
    }
    g_jvm->DetachCurrentThread();

    return 0;
}

// ---- JNI exports ----

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_globalhotkey_windows_NativeWindowsHotKeyBridge_nativeInit(
    JNIEnv* env, jclass clazz
) {
    if (g_running) return nullptr; // Already initialized

    // Cache the bridge class and callback method
    g_bridgeRef = env->NewGlobalRef(clazz);
    g_onHotKeyMethod = env->GetStaticMethodID(clazz, "onHotKey", "(JII)V");
    if (!g_onHotKeyMethod) {
        env->DeleteGlobalRef(g_bridgeRef);
        g_bridgeRef = nullptr;
        return env->NewStringUTF("Failed to find onHotKey callback method");
    }

    // Create event signaled by the message loop thread once its queue is ready
    g_readyEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!g_readyEvent) {
        env->DeleteGlobalRef(g_bridgeRef);
        g_bridgeRef = nullptr;
        return env->NewStringUTF("Failed to create ready event");
    }

    // Start message loop thread
    g_thread = CreateThread(nullptr, 0, messageLoopThread, nullptr, 0, &g_threadId);
    if (!g_thread) {
        CloseHandle(g_readyEvent);
        g_readyEvent = nullptr;
        env->DeleteGlobalRef(g_bridgeRef);
        g_bridgeRef = nullptr;
        return env->NewStringUTF("Failed to create message loop thread");
    }

    // Wait for the message queue to be created (5s timeout)
    if (WaitForSingleObject(g_readyEvent, 5000) != WAIT_OBJECT_0) {
        CloseHandle(g_readyEvent);
        g_readyEvent = nullptr;
        return env->NewStringUTF("Timeout waiting for message loop thread");
    }

    return nullptr; // success
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_globalhotkey_windows_NativeWindowsHotKeyBridge_nativeRegister(
    JNIEnv* env, jclass, jlong id, jint modifiers, jint keyCode
) {
    if (!g_running || g_threadId == 0) {
        return env->NewStringUTF("Not initialized");
    }

    HotKeyRequest req{};
    req.id = id;
    req.modifiers = static_cast<int>(modifiers);
    req.keyCode = static_cast<int>(keyCode);
    req.error[0] = '\0';
    req.completionEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!req.completionEvent) {
        return env->NewStringUTF("Failed to create completion event");
    }

    if (!PostThreadMessage(g_threadId, WM_HOTKEY_REGISTER, 0, reinterpret_cast<LPARAM>(&req))) {
        CloseHandle(req.completionEvent);
        return env->NewStringUTF("Failed to post register message to hotkey thread");
    }

    WaitForSingleObject(req.completionEvent, 5000);
    CloseHandle(req.completionEvent);

    if (req.error[0] != '\0') {
        return env->NewStringUTF(req.error);
    }
    return nullptr; // success
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_globalhotkey_windows_NativeWindowsHotKeyBridge_nativeUnregister(
    JNIEnv* env, jclass, jlong id
) {
    if (!g_running || g_threadId == 0) {
        return env->NewStringUTF("Not initialized");
    }

    HotKeyRequest req{};
    req.id = id;
    req.error[0] = '\0';
    req.completionEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!req.completionEvent) {
        return env->NewStringUTF("Failed to create completion event");
    }

    if (!PostThreadMessage(g_threadId, WM_HOTKEY_UNREGISTER, 0, reinterpret_cast<LPARAM>(&req))) {
        CloseHandle(req.completionEvent);
        return env->NewStringUTF("Failed to post unregister message to hotkey thread");
    }

    WaitForSingleObject(req.completionEvent, 5000);
    CloseHandle(req.completionEvent);

    if (req.error[0] != '\0') {
        return env->NewStringUTF(req.error);
    }
    return nullptr; // success
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_globalhotkey_windows_NativeWindowsHotKeyBridge_nativeShutdown(
    JNIEnv*, jclass
) {
    if (!g_running || g_threadId == 0) return;

    PostThreadMessage(g_threadId, WM_HOTKEY_SHUTDOWN, 0, 0);

    if (g_thread) {
        WaitForSingleObject(g_thread, 5000);
        CloseHandle(g_thread);
        g_thread = nullptr;
    }

    if (g_readyEvent) {
        CloseHandle(g_readyEvent);
        g_readyEvent = nullptr;
    }

    g_threadId = 0;
}

} // extern "C"
