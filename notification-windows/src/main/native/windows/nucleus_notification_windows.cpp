/**
 * nucleus_notification_windows.cpp
 *
 * JNI bridge for Windows Toast Notifications via WinRT/WRL.
 *
 * Uses Microsoft WRL (Windows Runtime Library) for proper COM event handling.
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
#include <sdkddkver.h>
#include <ShObjIdl.h>
#include <ShlObj.h>
#include <Psapi.h>
#include <roapi.h>
#include <winstring.h>
#include <propvarutil.h>
#include <functiondiscoverykeys.h>
#include <strsafe.h>

#include <wrl/implements.h>
#include <wrl/event.h>
#include <windows.foundation.h>
#include <windows.ui.notifications.h>
#include <windows.data.xml.dom.h>

#include <jni.h>

#include <string>
#include <vector>
#include <mutex>
#include <map>

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "runtimeobject.lib")
#pragma comment(lib, "kernel32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "shlwapi.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "propsys.lib")

using namespace Microsoft::WRL;
using namespace ABI::Windows::UI::Notifications;
using namespace ABI::Windows::Data::Xml::Dom;

#define BRIDGE_CLASS "dev/nucleusframework/notification/windows/NativeWindowsNotificationBridge"

// ============================================================================
// Globals
// ============================================================================

static JavaVM *g_jvm = nullptr;
static bool g_initialized = false;
static std::mutex g_mutex;
static std::wstring g_aumid;
static bool g_isAppx = false;

static ComPtr<IToastNotificationManagerStatics> g_toastManager;
static ComPtr<IToastNotifier> g_toastNotifier;

struct ToastInfo {
    std::wstring tag;
    std::wstring group;
    ComPtr<IToastNotification> notification;
    EventRegistrationToken activatedToken;
    EventRegistrationToken dismissedToken;
    EventRegistrationToken failedToken;
};

static std::map<INT64, ToastInfo> g_activeToasts;
static std::mutex g_toastsMutex;
static INT64 g_nextToastId = 1;

// ============================================================================
// HSTRING wrapper
// ============================================================================

class HStringWrapper {
    HSTRING _hstr = nullptr;
public:
    HStringWrapper(const wchar_t *str) {
        if (str) WindowsCreateString(str, (UINT32)wcslen(str), &_hstr);
    }
    HStringWrapper(const std::wstring &str) {
        WindowsCreateString(str.c_str(), (UINT32)str.length(), &_hstr);
    }
    ~HStringWrapper() { if (_hstr) WindowsDeleteString(_hstr); }
    HSTRING Get() const { return _hstr; }
    HStringWrapper(const HStringWrapper&) = delete;
    HStringWrapper& operator=(const HStringWrapper&) = delete;
};

// ============================================================================
// JNI helpers
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

static JNIEnv *getEnv(bool *didAttach) {
    *didAttach = false;
    if (!g_jvm) return nullptr;
    JNIEnv *env = nullptr;
    jint status = g_jvm->GetEnv((void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThreadAsDaemon((void **)&env, nullptr) != JNI_OK)
            return nullptr;
        *didAttach = true;
    } else if (status != JNI_OK) {
        return nullptr;
    }
    return env;
}

static void releaseEnv(bool didAttach) {
    if (didAttach && g_jvm) g_jvm->DetachCurrentThread();
}

static void clearException(JNIEnv *env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static jstring toJString(JNIEnv *env, const wchar_t *wstr) {
    if (!wstr) return env->NewStringUTF("");
    return env->NewString((const jchar *)wstr, (jsize)wcslen(wstr));
}

static std::wstring toWString(JNIEnv *env, jstring jstr) {
    if (!jstr) return L"";
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    jsize len = env->GetStringLength(jstr);
    std::wstring result((const wchar_t *)chars, len);
    env->ReleaseStringChars(jstr, chars);
    return result;
}

// ============================================================================
// Shortcut policy constants (mirrors WinToastLibC)
// ============================================================================

static const int SHORTCUT_POLICY_IGNORE = 0;
static const int SHORTCUT_POLICY_REQUIRE_NO_CREATE = 1;
static const int SHORTCUT_POLICY_REQUIRE_CREATE = 2;

// ============================================================================
// Shortcut management (WinToastLibC approach)
// ============================================================================

static void buildShellLinkPath(const WCHAR *startMenuRoot, const std::wstring &appName, WCHAR *path, DWORD pathLen) {
    StringCchCopyW(path, pathLen, startMenuRoot);
    StringCchCatW(path, pathLen, L"\\Programs\\");
    StringCchCatW(path, pathLen, appName.c_str());
    StringCchCatW(path, pathLen, L".lnk");
}

// Find existing shortcut in user or system-wide Start Menu
static bool findExistingShellLink(const std::wstring &appName, WCHAR *outPath, DWORD pathLen) {
    // 1. User Start Menu (%APPDATA%\Microsoft\Windows\Start Menu)
    WCHAR userStartMenu[MAX_PATH]{};
    if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_STARTMENU, nullptr, 0, userStartMenu))) {
        buildShellLinkPath(userStartMenu, appName, outPath, pathLen);
        if (GetFileAttributesW(outPath) != INVALID_FILE_ATTRIBUTES) return true;
    }
    // 2. System-wide Start Menu (C:\ProgramData\Microsoft\Windows\Start Menu)
    WCHAR commonStartMenu[MAX_PATH]{};
    if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_COMMON_STARTMENU, nullptr, 0, commonStartMenu))) {
        buildShellLinkPath(commonStartMenu, appName, outPath, pathLen);
        if (GetFileAttributesW(outPath) != INVALID_FILE_ATTRIBUTES) return true;
    }
    return false;
}

// Default path for creating new shortcuts (user Start Menu)
static void defaultShellLinkPath(const std::wstring &appName, WCHAR *path, DWORD pathLen) {
    WCHAR userStartMenu[MAX_PATH]{};
    SHGetFolderPathW(nullptr, CSIDL_STARTMENU, nullptr, 0, userStartMenu);
    buildShellLinkPath(userStartMenu, appName, path, pathLen);
}

static HRESULT validateShellLinkAt(const WCHAR *path, const std::wstring &aumid, int policy, bool &wasChanged) {
    ComPtr<IShellLinkW> shellLink;
    HRESULT hr = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&shellLink));
    if (FAILED(hr)) return hr;

    ComPtr<IPersistFile> persistFile;
    hr = shellLink.As(&persistFile);
    if (FAILED(hr)) return hr;

    // Try read-write first, fall back to read-only (system-wide shortcuts may be admin-protected)
    bool readOnly = false;
    hr = persistFile->Load(path, STGM_READWRITE);
    if (FAILED(hr)) {
        hr = persistFile->Load(path, STGM_READ);
        if (FAILED(hr)) return hr;
        readOnly = true;
    }

    ComPtr<IPropertyStore> propertyStore;
    hr = shellLink.As(&propertyStore);
    if (FAILED(hr)) return hr;

    PROPVARIANT appIdPropVar;
    PropVariantInit(&appIdPropVar);
    hr = propertyStore->GetValue(PKEY_AppUserModel_ID, &appIdPropVar);
    if (FAILED(hr)) { PropVariantClear(&appIdPropVar); return hr; }

    wasChanged = false;
    WCHAR existingAumi[MAX_PATH]{};
    hr = PropVariantToString(appIdPropVar, existingAumi, MAX_PATH);
    if (SUCCEEDED(hr) && aumid == existingAumi) {
        // AUMID matches — shortcut is valid as-is
        PropVariantClear(&appIdPropVar);
        return S_OK;
    }

    // AUMID mismatch — try to update if allowed and writable
    if (readOnly || policy != SHORTCUT_POLICY_REQUIRE_CREATE) {
        PropVariantClear(&appIdPropVar);
        // Read-only but exists → still usable, don't fail
        return readOnly ? S_OK : E_FAIL;
    }

    wasChanged = true;
    PropVariantClear(&appIdPropVar);
    hr = InitPropVariantFromString(aumid.c_str(), &appIdPropVar);
    if (SUCCEEDED(hr)) {
        hr = propertyStore->SetValue(PKEY_AppUserModel_ID, appIdPropVar);
        if (SUCCEEDED(hr)) {
            hr = propertyStore->Commit();
            if (SUCCEEDED(hr)) hr = persistFile->Save(path, TRUE);
        }
    }
    PropVariantClear(&appIdPropVar);
    return hr;
}

// Validate existing shortcut — searches user then system-wide Start Menu
static HRESULT validateShellLink(const std::wstring &appName, const std::wstring &aumid, int policy, bool &wasChanged) {
    WCHAR path[MAX_PATH]{};
    if (!findExistingShellLink(appName, path, MAX_PATH)) return E_FAIL;
    return validateShellLinkAt(path, aumid, policy, wasChanged);
}

static HRESULT createShellLink(const std::wstring &appName, const std::wstring &aumid) {
    WCHAR exePath[MAX_PATH]{};
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);

    WCHAR slPath[MAX_PATH]{};
    defaultShellLinkPath(appName, slPath, MAX_PATH);

    std::wstring exeDir(exePath);
    size_t lastSlash = exeDir.find_last_of(L'\\');
    if (lastSlash != std::wstring::npos) exeDir = exeDir.substr(0, lastSlash);

    ComPtr<IShellLinkW> shellLink;
    HRESULT hr = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&shellLink));
    if (FAILED(hr)) return hr;

    hr = shellLink->SetPath(exePath);
    if (FAILED(hr)) return hr;
    hr = shellLink->SetArguments(L"");
    if (FAILED(hr)) return hr;
    hr = shellLink->SetWorkingDirectory(exeDir.c_str());
    if (FAILED(hr)) return hr;

    ComPtr<IPropertyStore> propertyStore;
    hr = shellLink.As(&propertyStore);
    if (FAILED(hr)) return hr;

    PROPVARIANT appIdPropVar;
    hr = InitPropVariantFromString(aumid.c_str(), &appIdPropVar);
    if (FAILED(hr)) return hr;

    hr = propertyStore->SetValue(PKEY_AppUserModel_ID, appIdPropVar);
    PropVariantClear(&appIdPropVar);
    if (FAILED(hr)) return hr;

    hr = propertyStore->Commit();
    if (FAILED(hr)) return hr;

    ComPtr<IPersistFile> persistFile;
    hr = shellLink.As(&persistFile);
    if (FAILED(hr)) return hr;

    return persistFile->Save(slPath, TRUE);
}

static int ensureShortcut(const std::wstring &appName, const std::wstring &aumid, int policy) {
    if (policy == SHORTCUT_POLICY_IGNORE) return 0;

    bool wasChanged = false;
    HRESULT hr = validateShellLink(appName, aumid, policy, wasChanged);
    if (SUCCEEDED(hr)) return wasChanged ? 1 : 0;

    if (policy == SHORTCUT_POLICY_REQUIRE_CREATE) {
        hr = createShellLink(appName, aumid);
        return SUCCEEDED(hr) ? 2 : -1;
    }
    return -1;
}

// ============================================================================
// JNI fire callbacks
// ============================================================================

static void fireActivated(const std::wstring &tag, const std::wstring &group,
                          const std::wstring &arguments,
                          const std::vector<std::wstring> &inputKeys,
                          const std::vector<std::wstring> &inputValues) {
    bool didAttach = false;
    JNIEnv *env = getEnv(&didAttach);
    if (!env) return;

    jclass cls = env->FindClass(BRIDGE_CLASS);
    if (!cls) { clearException(env); releaseEnv(didAttach); return; }

    jmethodID mid = env->GetStaticMethodID(cls, "onToastActivated",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V");
    if (!mid) { clearException(env); releaseEnv(didAttach); return; }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray jKeys = env->NewObjectArray((jsize)inputKeys.size(), stringClass, nullptr);
    jobjectArray jValues = env->NewObjectArray((jsize)inputValues.size(), stringClass, nullptr);
    for (size_t i = 0; i < inputKeys.size(); i++) {
        env->SetObjectArrayElement(jKeys, (jsize)i, toJString(env, inputKeys[i].c_str()));
        env->SetObjectArrayElement(jValues, (jsize)i, toJString(env, inputValues[i].c_str()));
    }

    env->CallStaticVoidMethod(cls, mid,
        toJString(env, tag.c_str()),
        toJString(env, group.c_str()),
        toJString(env, arguments.c_str()),
        jKeys, jValues);
    clearException(env);
    releaseEnv(didAttach);
}

static void fireDismissed(const std::wstring &tag, const std::wstring &group, int reason) {
    bool didAttach = false;
    JNIEnv *env = getEnv(&didAttach);
    if (!env) return;

    jclass cls = env->FindClass(BRIDGE_CLASS);
    if (!cls) { clearException(env); releaseEnv(didAttach); return; }

    jmethodID mid = env->GetStaticMethodID(cls, "onToastDismissed",
        "(Ljava/lang/String;Ljava/lang/String;I)V");
    if (!mid) { clearException(env); releaseEnv(didAttach); return; }

    env->CallStaticVoidMethod(cls, mid,
        toJString(env, tag.c_str()),
        toJString(env, group.c_str()),
        (jint)reason);
    clearException(env);
    releaseEnv(didAttach);
}

static void fireFailed(const std::wstring &tag, const std::wstring &group, int errorCode) {
    bool didAttach = false;
    JNIEnv *env = getEnv(&didAttach);
    if (!env) return;

    jclass cls = env->FindClass(BRIDGE_CLASS);
    if (!cls) { clearException(env); releaseEnv(didAttach); return; }

    jmethodID mid = env->GetStaticMethodID(cls, "onToastFailed",
        "(Ljava/lang/String;Ljava/lang/String;I)V");
    if (!mid) { clearException(env); releaseEnv(didAttach); return; }

    env->CallStaticVoidMethod(cls, mid,
        toJString(env, tag.c_str()),
        toJString(env, group.c_str()),
        (jint)errorCode);
    clearException(env);
    releaseEnv(didAttach);
}

// ============================================================================
// JNI exports
// ============================================================================

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_notification_windows_NativeWindowsNotificationBridge_nativeInitialize(
    JNIEnv *env, jclass clazz, jstring jAumid, jboolean jIsAppx,
    jstring jAppName, jint jShortcutPolicy
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) return JNI_TRUE;

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) {
        return JNI_FALSE;
    }

    g_aumid = toWString(env, jAumid);
    g_isAppx = (bool)jIsAppx;

    if (!g_isAppx && !g_aumid.empty()) {
        std::wstring appName = toWString(env, jAppName);
        int policy = (int)jShortcutPolicy;

        if (!appName.empty() && policy != SHORTCUT_POLICY_IGNORE) {
            // Best-effort: don't fail init if shortcut is missing in REQUIRE_NO_CREATE mode
            ensureShortcut(appName, g_aumid, policy);
        }

        SetCurrentProcessExplicitAppUserModelID(g_aumid.c_str());
    }

    // Get toast notification manager
    hr = RoGetActivationFactory(
        HStringWrapper(RuntimeClass_Windows_UI_Notifications_ToastNotificationManager).Get(),
        IID_PPV_ARGS(&g_toastManager));
    if (FAILED(hr) || !g_toastManager) return JNI_FALSE;

    // Create notifier
    if (g_isAppx || g_aumid.empty()) {
        hr = g_toastManager->CreateToastNotifier(&g_toastNotifier);
    } else {
        hr = g_toastManager->CreateToastNotifierWithId(
            HStringWrapper(g_aumid).Get(), &g_toastNotifier);
    }

    if (FAILED(hr) || !g_toastNotifier) {
        g_toastManager.Reset();
        return JNI_FALSE;
    }

    g_initialized = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_notification_windows_NativeWindowsNotificationBridge_nativeShowToast(
    JNIEnv *env, jclass clazz,
    jstring jXml, jstring jTag, jstring jGroup,
    jboolean expiresOnReboot, jlong expirationTimeMs,
    jboolean suppressPopup, jint priority,
    jobjectArray jDataKeys, jobjectArray jDataValues, jint dataSequenceNumber,
    jlong callbackId
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    std::wstring xml = toWString(env, jXml);
    std::wstring tag = toWString(env, jTag);
    std::wstring group = toWString(env, jGroup);

    // Create XmlDocument and load XML
    ComPtr<IXmlDocument> xmlDoc;
    HRESULT hr = RoActivateInstance(
        HStringWrapper(RuntimeClass_Windows_Data_Xml_Dom_XmlDocument).Get(),
        reinterpret_cast<IInspectable**>(xmlDoc.GetAddressOf()));
    if (FAILED(hr)) goto fail;

    {
        ComPtr<IXmlDocumentIO> xmlDocIO;
        hr = xmlDoc.As(&xmlDocIO);
        if (FAILED(hr)) goto fail;

        hr = xmlDocIO->LoadXml(HStringWrapper(xml).Get());
        if (FAILED(hr)) goto fail;
    }

    {
        // Create toast notification
        ComPtr<IToastNotificationFactory> factory;
        hr = RoGetActivationFactory(
            HStringWrapper(RuntimeClass_Windows_UI_Notifications_ToastNotification).Get(),
            IID_PPV_ARGS(&factory));
        if (FAILED(hr)) goto fail;

        ComPtr<IToastNotification> toast;
        hr = factory->CreateToastNotification(xmlDoc.Get(), &toast);
        if (FAILED(hr)) goto fail;

        // Set tag, group, suppress via IToastNotification2
        ComPtr<IToastNotification2> toast2;
        if (SUCCEEDED(toast.As(&toast2))) {
            if (!tag.empty())
                toast2->put_Tag(HStringWrapper(tag).Get());
            if (!group.empty())
                toast2->put_Group(HStringWrapper(group).Get());
            if (suppressPopup)
                toast2->put_SuppressPopup(TRUE);
        }

        // Set priority via IToastNotification4 (0 = default, 1 = high)
        if (priority > 0) {
            ComPtr<IToastNotification4> toast4prio;
            if (SUCCEEDED(toast.As(&toast4prio))) {
                toast4prio->put_Priority(ToastNotificationPriority_High);
            }
        }

        // Auto-expire after expirationTimeMs via IToastNotification::put_ExpirationTime
        if (expirationTimeMs > 0) {
            FILETIME ft;
            GetSystemTimePreciseAsFileTime(&ft);
            ULARGE_INTEGER uli;
            uli.LowPart = ft.dwLowDateTime;
            uli.HighPart = ft.dwHighDateTime;

            ABI::Windows::Foundation::DateTime dt;
            // DateTime and FILETIME share the 1601 epoch in 100ns ticks; ms -> 100ns == *10000.
            dt.UniversalTime = (INT64)uli.QuadPart + (INT64)expirationTimeMs * 10000LL;

            ComPtr<ABI::Windows::Foundation::IPropertyValueStatics> propStatics;
            if (SUCCEEDED(RoGetActivationFactory(
                    HStringWrapper(RuntimeClass_Windows_Foundation_PropertyValue).Get(),
                    IID_PPV_ARGS(&propStatics)))) {
                ComPtr<IInspectable> dtInsp;
                if (SUCCEEDED(propStatics->CreateDateTime(dt, &dtInsp))) {
                    ComPtr<ABI::Windows::Foundation::IReference<ABI::Windows::Foundation::DateTime>> dtRef;
                    if (SUCCEEDED(dtInsp.As(&dtRef))) {
                        toast->put_ExpirationTime(dtRef.Get());
                    }
                }
            }
        }

        // Remove on reboot via IToastNotification6
        if (expiresOnReboot) {
            ComPtr<IToastNotification6> toast6;
            if (SUCCEEDED(toast.As(&toast6))) {
                toast6->put_ExpiresOnReboot(TRUE);
            }
        }

        // Attach initial NotificationData for data binding (progress bars)
        jsize dataCount = env->GetArrayLength(jDataKeys);
        if (dataCount > 0) {
            ComPtr<IInspectable> dataInsp;
            if (SUCCEEDED(RoActivateInstance(
                    HStringWrapper(RuntimeClass_Windows_UI_Notifications_NotificationData).Get(),
                    &dataInsp))) {
                ComPtr<INotificationData> notifData;
                if (SUCCEEDED(dataInsp.As(&notifData))) {
                    notifData->put_SequenceNumber((UINT32)dataSequenceNumber);
                    typedef ABI::Windows::Foundation::Collections::IMap<HSTRING, HSTRING> IStringMap;
                    ComPtr<IStringMap> values;
                    if (SUCCEEDED(notifData->get_Values(&values)) && values) {
                        for (jsize i = 0; i < dataCount; i++) {
                            std::wstring key = toWString(env, (jstring)env->GetObjectArrayElement(jDataKeys, i));
                            std::wstring val = toWString(env, (jstring)env->GetObjectArrayElement(jDataValues, i));
                            boolean replaced = false;
                            values->Insert(HStringWrapper(key).Get(), HStringWrapper(val).Get(), &replaced);
                        }
                    }
                    // Attach via IToastNotification4
                    ComPtr<IToastNotification4> toast4;
                    if (SUCCEEDED(toast.As(&toast4))) {
                        toast4->put_Data(notifData.Get());
                    }
                }
            }
        }

        // Capture for event handlers
        std::wstring capturedTag = tag;
        std::wstring capturedGroup = group;
        INT64 toastId = g_nextToastId++;

        // Register event handlers using WRL Callback
        EventRegistrationToken activatedToken, dismissedToken, failedToken;

        hr = toast->add_Activated(
            Callback<Implements<RuntimeClassFlags<ClassicCom>,
                ABI::Windows::Foundation::ITypedEventHandler<ToastNotification*, IInspectable*>>>(
                [capturedTag, capturedGroup, toastId](IToastNotification*, IInspectable* inspectable) -> HRESULT {
                    std::wstring arguments;
                    std::vector<std::wstring> inputKeys, inputValues;

                    if (inspectable) {
                        // Extract arguments
                        ComPtr<IToastActivatedEventArgs> args;
                        if (SUCCEEDED(inspectable->QueryInterface(IID_PPV_ARGS(&args)))) {
                            HSTRING argStr = nullptr;
                            if (SUCCEEDED(args->get_Arguments(&argStr)) && argStr) {
                                UINT32 len = 0;
                                const wchar_t *raw = WindowsGetStringRawBuffer(argStr, &len);
                                if (raw) arguments.assign(raw, len);
                                WindowsDeleteString(argStr);
                            }
                        }

                        // Extract user inputs from IToastActivatedEventArgs2
                        ComPtr<IToastActivatedEventArgs2> args2;
                        if (SUCCEEDED(inspectable->QueryInterface(IID_PPV_ARGS(&args2)))) {
                            using IPropertySet = ABI::Windows::Foundation::Collections::IPropertySet;
                            using IStringMap = ABI::Windows::Foundation::Collections::IMap<HSTRING, IInspectable*>;
                            using IIterableKVP = ABI::Windows::Foundation::Collections::IIterable<
                                ABI::Windows::Foundation::Collections::IKeyValuePair<HSTRING, IInspectable*>*>;
                            using IIteratorKVP = ABI::Windows::Foundation::Collections::IIterator<
                                ABI::Windows::Foundation::Collections::IKeyValuePair<HSTRING, IInspectable*>*>;
                            using IKVP = ABI::Windows::Foundation::Collections::IKeyValuePair<HSTRING, IInspectable*>;

                            ComPtr<IPropertySet> propSet;
                            if (SUCCEEDED(args2->get_UserInput(&propSet)) && propSet) {
                                ComPtr<IIterableKVP> iterable;
                                if (SUCCEEDED(propSet->QueryInterface(IID_PPV_ARGS(&iterable)))) {
                                    ComPtr<IIteratorKVP> iterator;
                                    if (SUCCEEDED(iterable->First(&iterator))) {
                                        boolean hasCurrent = false;
                                        iterator->get_HasCurrent(&hasCurrent);
                                        while (hasCurrent) {
                                            ComPtr<IKVP> kvp;
                                            if (SUCCEEDED(iterator->get_Current(&kvp)) && kvp) {
                                                HSTRING hKey = nullptr;
                                                kvp->get_Key(&hKey);
                                                if (hKey) {
                                                    UINT32 kLen = 0;
                                                    const wchar_t *kRaw = WindowsGetStringRawBuffer(hKey, &kLen);
                                                    if (kRaw) inputKeys.emplace_back(kRaw, kLen);
                                                    WindowsDeleteString(hKey);

                                                    // Get value as string via IPropertyValue
                                                    ComPtr<IInspectable> valInsp;
                                                    kvp->get_Value(&valInsp);
                                                    if (valInsp) {
                                                        ComPtr<ABI::Windows::Foundation::IPropertyValue> pv;
                                                        if (SUCCEEDED(valInsp.As(&pv))) {
                                                            HSTRING hVal = nullptr;
                                                            if (SUCCEEDED(pv->GetString(&hVal)) && hVal) {
                                                                UINT32 vLen = 0;
                                                                const wchar_t *vRaw = WindowsGetStringRawBuffer(hVal, &vLen);
                                                                if (vRaw) inputValues.emplace_back(vRaw, vLen);
                                                                else inputValues.emplace_back();
                                                                WindowsDeleteString(hVal);
                                                            } else {
                                                                inputValues.emplace_back();
                                                            }
                                                        } else {
                                                            inputValues.emplace_back();
                                                        }
                                                    } else {
                                                        inputValues.emplace_back();
                                                    }
                                                }
                                            }
                                            iterator->MoveNext(&hasCurrent);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    fireActivated(capturedTag, capturedGroup, arguments, inputKeys, inputValues);
                    std::lock_guard<std::mutex> tlock(g_toastsMutex);
                    g_activeToasts.erase(toastId);
                    return S_OK;
                }).Get(),
            &activatedToken);

        toast->add_Dismissed(
            Callback<Implements<RuntimeClassFlags<ClassicCom>,
                ABI::Windows::Foundation::ITypedEventHandler<ToastNotification*, ToastDismissedEventArgs*>>>(
                [capturedTag, capturedGroup, toastId](IToastNotification*, IToastDismissedEventArgs* e) -> HRESULT {
                    ToastDismissalReason reason = ToastDismissalReason_ApplicationHidden;
                    if (e) e->get_Reason(&reason);
                    fireDismissed(capturedTag, capturedGroup, (int)reason);
                    std::lock_guard<std::mutex> tlock(g_toastsMutex);
                    g_activeToasts.erase(toastId);
                    return S_OK;
                }).Get(),
            &dismissedToken);

        toast->add_Failed(
            Callback<Implements<RuntimeClassFlags<ClassicCom>,
                ABI::Windows::Foundation::ITypedEventHandler<ToastNotification*, ToastFailedEventArgs*>>>(
                [capturedTag, capturedGroup, toastId](IToastNotification*, IToastFailedEventArgs* e) -> HRESULT {
                    HRESULT errorCode = E_FAIL;
                    if (e) e->get_ErrorCode(&errorCode);
                    fireFailed(capturedTag, capturedGroup, (int)errorCode);
                    std::lock_guard<std::mutex> tlock(g_toastsMutex);
                    g_activeToasts.erase(toastId);
                    return S_OK;
                }).Get(),
            &failedToken);

        // Track
        {
            std::lock_guard<std::mutex> tlock(g_toastsMutex);
            g_activeToasts[toastId] = { tag, group, toast, activatedToken, dismissedToken, failedToken };
        }

        // Show
        hr = g_toastNotifier->Show(toast.Get());
        if (FAILED(hr)) {
            std::lock_guard<std::mutex> tlock(g_toastsMutex);
            g_activeToasts.erase(toastId);
            goto fail;
        }

        // Success
        jclass cls = env->FindClass(BRIDGE_CLASS);
        jmethodID mid = env->GetStaticMethodID(cls, "onToastShown", "(JLjava/lang/String;)V");
        env->CallStaticVoidMethod(cls, mid, callbackId, (jstring)nullptr);
        return;
    }

fail:
    {
        jclass cls = env->FindClass(BRIDGE_CLASS);
        jmethodID mid = env->GetStaticMethodID(cls, "onToastShown", "(JLjava/lang/String;)V");
        char buf[80];
        snprintf(buf, sizeof(buf), "Failed: HRESULT 0x%08lX", (unsigned long)hr);
        env->CallStaticVoidMethod(cls, mid, callbackId, env->NewStringUTF(buf));
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_notification_windows_NativeWindowsNotificationBridge_nativeUpdateToast(
    JNIEnv *env, jclass clazz,
    jstring jTag, jstring jGroup, jint sequenceNumber,
    jobjectArray jKeys, jobjectArray jValues, jlong callbackId
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    std::wstring tag = toWString(env, jTag);
    std::wstring group = toWString(env, jGroup);

    ComPtr<IToastNotifier2> notifier2;
    HRESULT hr = g_toastNotifier.As(&notifier2);
    if (FAILED(hr)) {
        jclass cls = env->FindClass(BRIDGE_CLASS);
        jmethodID mid = env->GetStaticMethodID(cls, "onToastUpdated", "(JLjava/lang/String;)V");
        env->CallStaticVoidMethod(cls, mid, callbackId,
            env->NewStringUTF("Update not supported on this Windows version"));
        return;
    }

    ComPtr<IInspectable> dataInspectable;
    hr = RoActivateInstance(
        HStringWrapper(RuntimeClass_Windows_UI_Notifications_NotificationData).Get(),
        &dataInspectable);
    if (FAILED(hr)) {
        jclass cls = env->FindClass(BRIDGE_CLASS);
        jmethodID mid = env->GetStaticMethodID(cls, "onToastUpdated", "(JLjava/lang/String;)V");
        env->CallStaticVoidMethod(cls, mid, callbackId,
            env->NewStringUTF("Failed to create NotificationData"));
        return;
    }

    ComPtr<INotificationData> notifData;
    hr = dataInspectable.As(&notifData);
    if (SUCCEEDED(hr)) {
        notifData->put_SequenceNumber((UINT32)sequenceNumber);

        // Get the IMap<HSTRING, HSTRING> for values
        typedef ABI::Windows::Foundation::Collections::IMap<HSTRING, HSTRING> IStringMap;
        ComPtr<IStringMap> values;
        if (SUCCEEDED(notifData->get_Values(&values)) && values) {
            jsize count = env->GetArrayLength(jKeys);
            for (jsize i = 0; i < count; i++) {
                std::wstring key = toWString(env, (jstring)env->GetObjectArrayElement(jKeys, i));
                std::wstring val = toWString(env, (jstring)env->GetObjectArrayElement(jValues, i));
                boolean replaced = false;
                values->Insert(HStringWrapper(key).Get(), HStringWrapper(val).Get(), &replaced);
            }
        }

        NotificationUpdateResult updateResult;
        hr = notifier2->UpdateWithTagAndGroup(notifData.Get(),
            HStringWrapper(tag).Get(), HStringWrapper(group).Get(), &updateResult);
    }

    jclass cls = env->FindClass(BRIDGE_CLASS);
    jmethodID mid = env->GetStaticMethodID(cls, "onToastUpdated", "(JLjava/lang/String;)V");
    if (FAILED(hr)) {
        char buf[80];
        snprintf(buf, sizeof(buf), "Update failed: HRESULT 0x%08lX", (unsigned long)hr);
        env->CallStaticVoidMethod(cls, mid, callbackId, env->NewStringUTF(buf));
    } else {
        env->CallStaticVoidMethod(cls, mid, callbackId, (jstring)nullptr);
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_notification_windows_NativeWindowsNotificationBridge_nativeRemoveToast(
    JNIEnv *env, jclass clazz, jstring jTag, jstring jGroup
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_toastManager) return;

    ComPtr<IToastNotificationManagerStatics2> manager2;
    if (FAILED(g_toastManager.As(&manager2))) return;

    ComPtr<IToastNotificationHistory> history;
    if (FAILED(manager2->get_History(&history))) return;

    std::wstring tag = toWString(env, jTag);
    std::wstring group = toWString(env, jGroup);

    if (!group.empty()) {
        history->RemoveGroupedTagWithId(
            HStringWrapper(tag).Get(), HStringWrapper(group).Get(),
            g_aumid.empty() ? nullptr : HStringWrapper(g_aumid).Get());
    } else {
        history->Remove(HStringWrapper(tag).Get());
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_notification_windows_NativeWindowsNotificationBridge_nativeRemoveGroupToasts(
    JNIEnv *env, jclass clazz, jstring jGroup
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_toastManager) return;

    ComPtr<IToastNotificationManagerStatics2> manager2;
    if (FAILED(g_toastManager.As(&manager2))) return;

    ComPtr<IToastNotificationHistory> history;
    if (FAILED(manager2->get_History(&history))) return;

    history->RemoveGroup(HStringWrapper(toWString(env, jGroup)).Get());
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_notification_windows_NativeWindowsNotificationBridge_nativeClearAllToasts(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_toastManager) return;

    ComPtr<IToastNotificationManagerStatics2> manager2;
    if (FAILED(g_toastManager.As(&manager2))) return;

    ComPtr<IToastNotificationHistory> history;
    if (FAILED(manager2->get_History(&history))) return;

    if (!g_aumid.empty()) {
        history->ClearWithId(HStringWrapper(g_aumid).Get());
    } else {
        history->Clear();
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_notification_windows_NativeWindowsNotificationBridge_nativeGetHistory(
    JNIEnv *env, jclass clazz, jlong callbackId
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    std::vector<std::wstring> tags, groups;
    {
        std::lock_guard<std::mutex> tlock(g_toastsMutex);
        for (auto &p : g_activeToasts) {
            tags.push_back(p.second.tag);
            groups.push_back(p.second.group);
        }
    }

    jclass cls = env->FindClass(BRIDGE_CLASS);
    jmethodID mid = env->GetStaticMethodID(cls, "onHistoryResult",
        "(J[Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)V");
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray jTags = env->NewObjectArray((jsize)tags.size(), stringClass, nullptr);
    jobjectArray jGroups = env->NewObjectArray((jsize)groups.size(), stringClass, nullptr);
    for (size_t i = 0; i < tags.size(); i++) {
        env->SetObjectArrayElement(jTags, (jsize)i, toJString(env, tags[i].c_str()));
        env->SetObjectArrayElement(jGroups, (jsize)i, toJString(env, groups[i].c_str()));
    }
    env->CallStaticVoidMethod(cls, mid, callbackId, jTags, jGroups, (jstring)nullptr);
    clearException(env);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_notification_windows_NativeWindowsNotificationBridge_nativeUninitialize(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    {
        std::lock_guard<std::mutex> tlock(g_toastsMutex);
        for (auto &p : g_activeToasts) {
            p.second.notification->remove_Activated(p.second.activatedToken);
            p.second.notification->remove_Dismissed(p.second.dismissedToken);
            p.second.notification->remove_Failed(p.second.failedToken);
        }
        g_activeToasts.clear();
    }
    g_toastNotifier.Reset();
    g_toastManager.Reset();
    g_initialized = false;
}

} // extern "C"

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID lpReserved) {
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(hModule);
    return TRUE;
}
