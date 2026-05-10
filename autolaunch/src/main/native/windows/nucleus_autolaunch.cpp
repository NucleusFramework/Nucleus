/**
 * nucleus_autolaunch.cpp
 *
 * JNI bridge for auto-launch at Windows login.
 *
 * Two code paths, dispatched by the Kotlin side based on nativeIsPackaged():
 *   1. MSIX: Windows.ApplicationModel.StartupTask (WinRT via WRL)
 *   2. Win32: HKCU\...\Run + StartupApproved\Run (advapi32)
 *
 * Prerequisites: Windows 10 SDK, MSVC (C++17).
 */

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN

#include <Windows.h>
#include <appmodel.h>
#include <roapi.h>
#include <winternl.h>
#include <winstring.h>
#include <wrl/client.h>
#include <wrl/event.h>
#include <wrl/wrappers/corewrappers.h>
#include <windows.applicationmodel.h>
#include <windows.applicationmodel.activation.h>
#include <windows.foundation.h>

#include <jni.h>

#include <cstdio>
#include <string>
#include <mutex>
#include <thread>
#include <functional>

#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "kernel32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "runtimeobject.lib")

using namespace Microsoft::WRL;
using namespace Microsoft::WRL::Wrappers;
using namespace ABI::Windows::ApplicationModel;
using namespace ABI::Windows::ApplicationModel::Activation;
using namespace ABI::Windows::Foundation;
using namespace ABI::Windows::Foundation::Collections;

// ============================================================================
// MTA execution helper
// ============================================================================
//
// All WinRT StartupTask calls are marshaled onto a dedicated MTA thread.
//
// Rationale: the JVM's AWT/Compose EDT is STA-initialized. If we call WinRT
// APIs directly from that thread, RoInitialize(MTA) fails with
// RPC_E_CHANGED_MODE, we stay STA, and the IAsyncOperation completion / state
// persistence relies on a message pump the JVM thread does not provide.
// StartupTask.GetAsync happens to work synchronously in most cases, but
// Disable() queues a state mutation that never lands — it returns S_OK
// yet Task Manager keeps showing the old state.
//
// Running on a fresh thread with RO_INIT_MULTITHREADED sidesteps the whole
// message-pump dependency: MTA completions are delivered directly.

// Diagnostic log — MSIX apps launched at login have no console/stderr, so
// we accumulate messages in a process-wide string retrievable from Kotlin.
static std::mutex g_diagMutex;
static std::string g_diagnostic;

static void alLog(const char *msg, long detail = 0) {
    char buf[256];
    snprintf(buf, sizeof(buf), "[autolaunch] %s (0x%lx)\n", msg, detail);
    fprintf(stderr, "%s", buf);
    fflush(stderr);
    OutputDebugStringA(buf);
    std::lock_guard<std::mutex> lock(g_diagMutex);
    g_diagnostic += buf;
}

static int runOnMtaThread(const std::function<int()> &fn) {
    int result = -1;
    std::thread t([&]() {
        HRESULT hr = RoInitialize(RO_INIT_MULTITHREADED);
        if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) return;
        bool initialized = SUCCEEDED(hr);
        result = fn();
        if (initialized) RoUninitialize();
    });
    t.join();
    return result;
}

// ============================================================================
// JNI helpers
// ============================================================================

static std::wstring toWString(JNIEnv *env, jstring jstr) {
    if (!jstr) return L"";
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    jsize len = env->GetStringLength(jstr);
    std::wstring result((const wchar_t *)chars, len);
    env->ReleaseStringChars(jstr, chars);
    return result;
}

// ============================================================================
// Packaging detection
// ============================================================================

static bool isCurrentProcessPackaged() {
    UINT32 len = 0;
    LONG rc = GetCurrentPackageFullName(&len, nullptr);
    return rc != APPMODEL_ERROR_NO_PACKAGE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeIsPackaged(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    bool p = isCurrentProcessPackaged();
    alLog(p ? "isPackaged = true" : "isPackaged = false");
    return p ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeGetDiagnostic(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    std::lock_guard<std::mutex> lock(g_diagMutex);
    return env->NewStringUTF(g_diagnostic.c_str());
}

// ============================================================================
// Win32 registry: HKCU\...\Run + StartupApproved\Run
// ============================================================================

static const wchar_t *RUN_KEY =
    L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
static const wchar_t *SA_RUN_KEY =
    L"Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";

// Returns: 0 = not present, 1 = enabled, 2 = disabled-by-user, -1 = error
extern "C" JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeRegReadState(
    JNIEnv *env, jclass clazz, jstring jValueName) {
    (void)clazz;
    std::wstring name = toWString(env, jValueName);
    if (name.empty()) return -1;

    // 1. Is there a value in Run?
    HKEY hRun = nullptr;
    LSTATUS st = RegOpenKeyExW(HKEY_CURRENT_USER, RUN_KEY, 0, KEY_READ, &hRun);
    if (st == ERROR_FILE_NOT_FOUND) return 0;
    if (st != ERROR_SUCCESS) return -1;

    DWORD type = 0, cb = 0;
    st = RegQueryValueExW(hRun, name.c_str(), nullptr, &type, nullptr, &cb);
    RegCloseKey(hRun);
    if (st == ERROR_FILE_NOT_FOUND) return 0;
    if (st != ERROR_SUCCESS) return -1;

    // 2. Consult StartupApproved\Run
    HKEY hSa = nullptr;
    st = RegOpenKeyExW(HKEY_CURRENT_USER, SA_RUN_KEY, 0, KEY_READ, &hSa);
    if (st == ERROR_FILE_NOT_FOUND) return 1; // Absent => enabled by default
    if (st != ERROR_SUCCESS) return -1;

    BYTE buf[32]{};
    DWORD cb2 = sizeof(buf), type2 = 0;
    st = RegQueryValueExW(hSa, name.c_str(), nullptr, &type2, buf, &cb2);
    RegCloseKey(hSa);
    if (st == ERROR_FILE_NOT_FOUND) return 1;
    if (st != ERROR_SUCCESS) return -1;
    if (type2 != REG_BINARY || cb2 < 4) return -1;

    DWORD flag = *(DWORD *)buf;
    // Parity rule: odd = disabled by user (0x01/0x03/0x05), even = enabled (0x00/0x02/0x04/0x06).
    return (flag & 1u) ? 2 : 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeRegWriteRun(
    JNIEnv *env, jclass clazz, jstring jValueName, jstring jCommand) {
    (void)clazz;
    std::wstring name = toWString(env, jValueName);
    std::wstring cmd = toWString(env, jCommand);
    if (name.empty() || cmd.empty()) return -1;

    HKEY hKey = nullptr;
    LSTATUS st = RegCreateKeyExW(
        HKEY_CURRENT_USER, RUN_KEY, 0, nullptr, REG_OPTION_NON_VOLATILE,
        KEY_SET_VALUE, nullptr, &hKey, nullptr);
    if (st != ERROR_SUCCESS) return -1;

    DWORD cb = (DWORD)((cmd.size() + 1) * sizeof(wchar_t));
    st = RegSetValueExW(hKey, name.c_str(), 0, REG_SZ, (const BYTE *)cmd.c_str(), cb);
    RegCloseKey(hKey);
    return (st == ERROR_SUCCESS) ? 0 : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeRegDeleteRun(
    JNIEnv *env, jclass clazz, jstring jValueName, jboolean alsoSa) {
    (void)clazz;
    std::wstring name = toWString(env, jValueName);
    if (name.empty()) return -1;

    bool ok = true;

    HKEY hRun = nullptr;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, RUN_KEY, 0, KEY_SET_VALUE, &hRun) == ERROR_SUCCESS) {
        LSTATUS st = RegDeleteValueW(hRun, name.c_str());
        if (st != ERROR_SUCCESS && st != ERROR_FILE_NOT_FOUND) ok = false;
        RegCloseKey(hRun);
    }
    if (alsoSa) {
        HKEY hSa = nullptr;
        if (RegOpenKeyExW(HKEY_CURRENT_USER, SA_RUN_KEY, 0, KEY_SET_VALUE, &hSa) == ERROR_SUCCESS) {
            LSTATUS st = RegDeleteValueW(hSa, name.c_str());
            if (st != ERROR_SUCCESS && st != ERROR_FILE_NOT_FOUND) ok = false;
            RegCloseKey(hSa);
        }
    }
    return ok ? 0 : -1;
}

// ============================================================================
// MSIX StartupTask (WinRT)
// ============================================================================

// Mirrors ABI::Windows::ApplicationModel::StartupTaskState
//   0=Disabled, 1=DisabledByUser, 2=Enabled, 3=DisabledByPolicy, 4=EnabledByPolicy

class HStringWrapper {
    HSTRING _h = nullptr;
public:
    HStringWrapper(const std::wstring &s) {
        WindowsCreateString(s.c_str(), (UINT32)s.length(), &_h);
    }
    ~HStringWrapper() { if (_h) WindowsDeleteString(_h); }
    HSTRING Get() const { return _h; }
    HStringWrapper(const HStringWrapper &) = delete;
    HStringWrapper &operator=(const HStringWrapper &) = delete;
};

// Waits synchronously for an IAsyncOperation<T> to complete.
// Uses event + callback — safe because we never call this on the UI thread.
template <typename T>
static HRESULT waitAsync(ComPtr<IAsyncOperation<T>> op) {
    if (!op) return E_POINTER;
    Event ready(CreateEventW(nullptr, TRUE, FALSE, nullptr));
    if (!ready.IsValid()) return HRESULT_FROM_WIN32(GetLastError());

    HRESULT hr = op->put_Completed(
        Callback<IAsyncOperationCompletedHandler<T>>(
            [&ready](IAsyncOperation<T> *, AsyncStatus) -> HRESULT {
                SetEvent(ready.Get());
                return S_OK;
            }).Get());
    if (FAILED(hr)) return hr;
    WaitForSingleObject(ready.Get(), INFINITE);
    return S_OK;
}

static HRESULT findStartupTask(const std::wstring &taskId, ComPtr<IStartupTask> &outTask) {
    ComPtr<IStartupTaskStatics> statics;
    HStringWrapper cls(L"Windows.ApplicationModel.StartupTask");
    HRESULT hr = RoGetActivationFactory(cls.Get(), IID_PPV_ARGS(&statics));
    if (FAILED(hr)) return hr;

    HStringWrapper id(taskId);
    ComPtr<IAsyncOperation<StartupTask *>> op;
    hr = statics->GetAsync(id.Get(), &op);
    if (FAILED(hr)) return hr;
    hr = waitAsync<StartupTask *>(op);
    if (FAILED(hr)) return hr;

    outTask.Reset();
    hr = op->GetResults(outTask.GetAddressOf());
    if (FAILED(hr)) return hr;
    return outTask ? S_OK : E_FAIL;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeMsixGetState(
    JNIEnv *env, jclass clazz, jstring jTaskId) {
    (void)clazz;
    std::wstring taskId = toWString(env, jTaskId);
    if (taskId.empty()) return -1;

    return runOnMtaThread([&]() -> int {
        ComPtr<IStartupTask> task;
        if (FAILED(findStartupTask(taskId, task)) || !task) return -1;
        StartupTaskState state;
        if (FAILED(task->get_State(&state))) return -1;
        return (int)state;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeMsixRequestEnable(
    JNIEnv *env, jclass clazz, jstring jTaskId) {
    (void)clazz;
    std::wstring taskId = toWString(env, jTaskId);
    if (taskId.empty()) return -1;

    return runOnMtaThread([&]() -> int {
        ComPtr<IStartupTask> task;
        if (FAILED(findStartupTask(taskId, task)) || !task) return -1;

        ComPtr<IAsyncOperation<StartupTaskState>> op;
        if (FAILED(task->RequestEnableAsync(&op))) return -1;
        if (FAILED(waitAsync<StartupTaskState>(op))) return -1;
        StartupTaskState newState;
        if (FAILED(op->GetResults(&newState))) return -1;
        return (int)newState;
    });
}

// ============================================================================
// Startup activation detection (MSIX packaged desktop)
// ============================================================================
//
// Windows.ApplicationModel.AppInstance.GetActivatedEventArgs() returns the
// activation context for packaged apps (including full-trust packaged desktop
// since Windows 10 v1809). When the process was launched by a
// `windows.startupTask` extension, Kind == ActivationKind::StartupTask.
//
// Must be called early in process startup — the activation args are consumed
// by the first call and subsequent queries return the same object, but other
// frameworks (rarely) may interfere.

extern "C" JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeIsStartupActivation(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;

    return runOnMtaThread([]() -> int {
        ComPtr<IAppInstanceStatics> statics;
        HStringWrapper cls(L"Windows.ApplicationModel.AppInstance");
        HRESULT hr = RoGetActivationFactory(cls.Get(), IID_PPV_ARGS(&statics));
        if (FAILED(hr)) {
            alLog("RoGetActivationFactory(AppInstance) failed", hr);
            return -10;
        }

        ComPtr<IActivatedEventArgs> args;
        hr = statics->GetActivatedEventArgs(&args);
        if (FAILED(hr)) {
            alLog("GetActivatedEventArgs failed", hr);
            return -11;
        }
        if (!args) {
            alLog("GetActivatedEventArgs returned null (not a UWP-style activation)", 0);
            return 0;
        }

        ActivationKind kind;
        if (FAILED(args->get_Kind(&kind))) {
            alLog("get_Kind failed", hr);
            return -12;
        }
        alLog("ActivationKind", (long)kind);
        return (kind == ActivationKind_StartupTask) ? 1 : 0;
    });
}

// ============================================================================
// Parent-process check: detect MSIX startup-task activation
// ============================================================================
//
// For MSIX packaged full-trust desktop apps, Windows routes auto-launches
// through the Shell Infrastructure Host (sihost.exe). Manual launches (Start
// menu, taskbar, Explorer, Run dialog) produce explorer.exe / runtimebroker.exe
// as the external ancestor.
//
// (Some sources claim taskhostw.exe via the Task Scheduler — kept as a
// fallback match in case of build/version differences — but empirical tests
// on Windows 11 show sihost.exe is the actual process for MSIX startup tasks.)
//
// jpackage-generated launchers often re-spawn themselves (the app's own exe
// appears as direct parent), so we walk up the tree skipping any ancestor
// matching our own image filename until we reach an external process.
//
// This works for any Win32 packaged app (JVM, C++, Rust…) because the kernel
// sets parent PID at CreateProcess time — no user-mode activation pipeline
// is involved.

typedef NTSTATUS(NTAPI *NtQueryInformationProcess_t)(
    HANDLE, PROCESSINFOCLASS, PVOID, ULONG, PULONG);

static NtQueryInformationProcess_t getNtQIP() {
    static NtQueryInformationProcess_t p =
        (NtQueryInformationProcess_t)GetProcAddress(
            GetModuleHandleW(L"ntdll.dll"), "NtQueryInformationProcess");
    return p;
}

static DWORD getParentPidOf(DWORD pid) {
    auto pNtQIP = getNtQIP();
    if (!pNtQIP) return 0;
    HANDLE h = (pid == GetCurrentProcessId())
                   ? GetCurrentProcess()
                   : OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!h) return 0;
    PROCESS_BASIC_INFORMATION pbi{};
    ULONG len = 0;
    NTSTATUS s = pNtQIP(h, ProcessBasicInformation, &pbi, sizeof(pbi), &len);
    if (h != GetCurrentProcess()) CloseHandle(h);
    if (s != 0) return 0;
    return (DWORD)(ULONG_PTR)pbi.Reserved3; // InheritedFromUniqueProcessId
}

static bool getProcessImageName(DWORD pid, wchar_t *out, DWORD cch) {
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!h) return false;
    DWORD n = cch;
    bool ok = QueryFullProcessImageNameW(h, 0, out, &n) != 0;
    CloseHandle(h);
    return ok;
}

// Walks up the process tree, skipping any ancestor whose image filename
// matches our own (jpackage launcher + JVM re-exec create a self-chain
// for MSIX full-trust apps). Returns the first "external" ancestor's name.
static bool resolveExternalAncestorName(wchar_t *outName, size_t cchName) {
    wchar_t ownPath[MAX_PATH] = {};
    DWORD n = MAX_PATH;
    if (!QueryFullProcessImageNameW(GetCurrentProcess(), 0, ownPath, &n)) {
        return false;
    }
    const wchar_t *ownName = wcsrchr(ownPath, L'\\');
    ownName = ownName ? ownName + 1 : ownPath;

    DWORD pid = GetCurrentProcessId();
    for (int depth = 0; depth < 10; ++depth) {
        DWORD ppid = getParentPidOf(pid);
        if (ppid == 0 || ppid == pid) return false;

        wchar_t path[MAX_PATH] = {};
        if (!getProcessImageName(ppid, path, MAX_PATH)) return false;
        const wchar_t *name = wcsrchr(path, L'\\');
        name = name ? name + 1 : path;

        char diag[256];
        char nameAscii[MAX_PATH] = {};
        WideCharToMultiByte(CP_UTF8, 0, name, -1, nameAscii, sizeof(nameAscii), nullptr, nullptr);
        snprintf(diag, sizeof(diag), "ancestor[%d] = %s", depth, nameAscii);
        alLog(diag);

        if (_wcsicmp(name, ownName) != 0) {
            wcsncpy_s(outName, cchName, name, _TRUNCATE);
            return true;
        }
        pid = ppid;
    }
    return false;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeIsLaunchedByTaskScheduler(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;

    wchar_t ancestor[MAX_PATH] = {};
    if (!resolveExternalAncestorName(ancestor, MAX_PATH)) {
        alLog("external ancestor resolution failed", 0);
        return -1;
    }
    char nameAscii[MAX_PATH] = {};
    WideCharToMultiByte(CP_UTF8, 0, ancestor, -1, nameAscii, sizeof(nameAscii), nullptr, nullptr);
    char msg[256];
    snprintf(msg, sizeof(msg), "external parent = %s", nameAscii);
    alLog(msg);

    // MSIX startup-task launches are spawned by the Shell Infrastructure Host
    // (sihost.exe). Manual launches come from explorer.exe / runtimebroker.exe.
    // Also accept taskhostw.exe in case some Windows revisions route through
    // the Task Scheduler host instead.
    if (_wcsicmp(ancestor, L"sihost.exe") == 0) return 1;
    if (_wcsicmp(ancestor, L"taskhostw.exe") == 0) return 1;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_windows_NativeAutoLaunchBridge_nativeMsixDisable(
    JNIEnv *env, jclass clazz, jstring jTaskId) {
    (void)clazz;
    std::wstring taskId = toWString(env, jTaskId);
    if (taskId.empty()) return -1;

    return runOnMtaThread([&]() -> int {
        ComPtr<IStartupTask> task;
        if (FAILED(findStartupTask(taskId, task)) || !task) return -1;
        return SUCCEEDED(task->Disable()) ? 0 : -1;
    });
}

// ============================================================================
// JNI_OnLoad (unused but required by some linkers)
// ============================================================================

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_8;
}
