/**
 * nucleus_launcher_windows.cpp
 *
 * JNI bridge for Windows Launcher APIs:
 * - Badge Notifications via WinRT/WRL (BadgeUpdateManager)
 * - Jump Lists via COM (ICustomDestinationList)
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
#include <shellapi.h>
#include <ShObjIdl.h>
#include <ShlObj.h>
#include <Psapi.h>
#include <roapi.h>
#include <winstring.h>
#include <propvarutil.h>
#include <functiondiscoverykeys.h>
#include <propkey.h>
#include <objectarray.h>
#include <strsafe.h>

#include <wrl/implements.h>
#include <wrl/event.h>
#include <windows.ui.notifications.h>
#include <windows.data.xml.dom.h>

#include <jni.h>

#include <string>
#include <vector>
#include <mutex>
#include <cstdio>

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "runtimeobject.lib")
#pragma comment(lib, "kernel32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "shlwapi.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "propsys.lib")
#pragma comment(lib, "uuid.lib")

using namespace Microsoft::WRL;
using namespace ABI::Windows::UI::Notifications;
using namespace ABI::Windows::Data::Xml::Dom;

// ============================================================================
// Globals
// ============================================================================

static JavaVM *g_jvm = nullptr;
static bool g_initialized = false;
static std::mutex g_mutex;
static std::wstring g_aumid;
static bool g_isAppx = false;

static ComPtr<IBadgeUpdateManagerStatics> g_badgeManager;
static ComPtr<IBadgeUpdater> g_badgeUpdater;

// Jump List state
static std::mutex g_jl_mutex;
static ComPtr<ICustomDestinationList> g_jumpList;

// Taskbar (ITaskbarList3) state
static std::mutex g_tb_mutex;
static ComPtr<ITaskbarList3> g_taskbarList;
static bool g_taskbarListInitialized = false;

// Per-HWND thumbnail toolbar callback state
#define THUMBBAR_PROP L"NucleusThumbBarState"
#define THBN_CLICKED 0x1800

struct ThumbBarState {
    WNDPROC originalWndProc;
    jobject callbackRef;     // GlobalRef to ThumbBarClickListener
    jmethodID onClickMethod;
    std::vector<HICON> icons;
    bool buttonsAdded;
    int buttonCount;
    UINT buttonIds[7];
};


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

static std::wstring toWString(JNIEnv *env, jstring jstr) {
    if (!jstr) return L"";
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    jsize len = env->GetStringLength(jstr);
    std::wstring result((const wchar_t *)chars, len);
    env->ReleaseStringChars(jstr, chars);
    return result;
}

static jstring errorString(JNIEnv *env, const char *context, HRESULT hr) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%s: HRESULT 0x%08lX", context, (unsigned long)hr);
    return env->NewStringUTF(buf);
}

static std::vector<std::wstring> toWStringArray(JNIEnv *env, jobjectArray jArray) {
    std::vector<std::wstring> result;
    if (!jArray) return result;
    jsize len = env->GetArrayLength(jArray);
    result.reserve(len);
    for (jsize i = 0; i < len; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(jArray, i);
        result.push_back(toWString(env, jstr));
        if (jstr) env->DeleteLocalRef(jstr);
    }
    return result;
}

static std::vector<int> toIntVector(JNIEnv *env, jintArray jArray) {
    std::vector<int> result;
    if (!jArray) return result;
    jsize len = env->GetArrayLength(jArray);
    result.resize(len);
    env->GetIntArrayRegion(jArray, 0, len, (jint*)result.data());
    return result;
}

static std::vector<bool> toBoolVector(JNIEnv *env, jbooleanArray jArray) {
    std::vector<bool> result;
    if (!jArray) return result;
    jsize len = env->GetArrayLength(jArray);
    jboolean *raw = env->GetBooleanArrayElements(jArray, nullptr);
    result.assign(raw, raw + len);
    env->ReleaseBooleanArrayElements(jArray, raw, JNI_ABORT);
    return result;
}

// ============================================================================
// Jump List helpers
// ============================================================================

static HRESULT createShellLinkItem(
    const std::wstring &title,
    const std::wstring &arguments,
    const std::wstring &description,
    int iconType,
    const std::wstring &iconPath,
    int iconIndex,
    IShellLinkW **ppLink
) {
    WCHAR exePath[MAX_PATH]{};
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);

    ComPtr<IShellLinkW> link;
    HRESULT hr = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&link));
    if (FAILED(hr)) return hr;

    hr = link->SetPath(exePath);
    if (FAILED(hr)) return hr;

    hr = link->SetArguments(arguments.c_str());
    if (FAILED(hr)) return hr;

    if (!description.empty()) {
        hr = link->SetDescription(description.c_str());
        if (FAILED(hr)) return hr;
    }

    // Resolve icon location based on type: 0=stock, 1=file, 2=resource, -1=app icon
    switch (iconType) {
    case 0: { // Stock icon — resolve to DLL path + index via SHGetStockIconInfo
        SHSTOCKICONINFO sii = {};
        sii.cbSize = sizeof(sii);
        hr = SHGetStockIconInfo((SHSTOCKICONID)iconIndex, SHGSI_ICONLOCATION, &sii);
        if (SUCCEEDED(hr)) {
            hr = link->SetIconLocation(sii.szPath, sii.iIcon);
        }
        break;
    }
    case 1: // .ico file
    case 2: // DLL resource
        if (!iconPath.empty()) {
            hr = link->SetIconLocation(iconPath.c_str(), iconIndex);
        } else {
            hr = link->SetIconLocation(exePath, 0);
        }
        break;
    default: // -1 or unknown — use app icon
        hr = link->SetIconLocation(exePath, 0);
        break;
    }
    if (FAILED(hr)) return hr;

    ComPtr<IPropertyStore> propStore;
    hr = link.As(&propStore);
    if (FAILED(hr)) return hr;

    PROPVARIANT pv;
    hr = InitPropVariantFromString(title.c_str(), &pv);
    if (FAILED(hr)) return hr;
    hr = propStore->SetValue(PKEY_Title, pv);
    PropVariantClear(&pv);
    if (FAILED(hr)) return hr;

    hr = propStore->Commit();
    if (FAILED(hr)) return hr;

    *ppLink = link.Detach();
    return S_OK;
}

static HRESULT createSeparatorLink(IShellLinkW **ppLink) {
    ComPtr<IShellLinkW> link;
    HRESULT hr = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&link));
    if (FAILED(hr)) return hr;

    ComPtr<IPropertyStore> propStore;
    hr = link.As(&propStore);
    if (FAILED(hr)) return hr;

    PROPVARIANT pv;
    hr = InitPropVariantFromBoolean(TRUE, &pv);
    if (FAILED(hr)) return hr;
    hr = propStore->SetValue(PKEY_AppUserModel_IsDestListSeparator, pv);
    PropVariantClear(&pv);
    if (FAILED(hr)) return hr;

    hr = propStore->Commit();
    if (FAILED(hr)) return hr;

    *ppLink = link.Detach();
    return S_OK;
}

// ============================================================================
// Badge XML helper
// ============================================================================

static HRESULT setBadgeXml(const std::wstring &value) {
    if (!g_badgeUpdater) return E_NOT_VALID_STATE;

    std::wstring xml = L"<badge value=\"" + value + L"\"/>";

    // Create XmlDocument and load XML
    ComPtr<IXmlDocument> xmlDoc;
    HRESULT hr = RoActivateInstance(
        HStringWrapper(RuntimeClass_Windows_Data_Xml_Dom_XmlDocument).Get(),
        reinterpret_cast<IInspectable**>(xmlDoc.GetAddressOf()));
    if (FAILED(hr)) return hr;

    ComPtr<IXmlDocumentIO> xmlDocIO;
    hr = xmlDoc.As(&xmlDocIO);
    if (FAILED(hr)) return hr;

    hr = xmlDocIO->LoadXml(HStringWrapper(xml).Get());
    if (FAILED(hr)) return hr;

    // Create BadgeNotification
    ComPtr<IBadgeNotificationFactory> factory;
    hr = RoGetActivationFactory(
        HStringWrapper(RuntimeClass_Windows_UI_Notifications_BadgeNotification).Get(),
        IID_PPV_ARGS(&factory));
    if (FAILED(hr)) return hr;

    ComPtr<IBadgeNotification> badge;
    hr = factory->CreateBadgeNotification(xmlDoc.Get(), &badge);
    if (FAILED(hr)) return hr;

    // Update badge
    hr = g_badgeUpdater->Update(badge.Get());
    return hr;
}

// ============================================================================
// JNI exports
// ============================================================================

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeInitialize(
    JNIEnv *env, jclass clazz, jstring jAumid, jboolean jIsAppx
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) return nullptr; // already initialized = success

    HRESULT hr = RoInitialize(RO_INIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) {
        return errorString(env, "RoInitialize failed", hr);
    }

    g_aumid = toWString(env, jAumid);
    g_isAppx = (bool)jIsAppx;

    if (!g_isAppx && !g_aumid.empty()) {
        SetCurrentProcessExplicitAppUserModelID(g_aumid.c_str());
    }

    // Get BadgeUpdateManager
    hr = RoGetActivationFactory(
        HStringWrapper(RuntimeClass_Windows_UI_Notifications_BadgeUpdateManager).Get(),
        IID_PPV_ARGS(&g_badgeManager));
    if (FAILED(hr) || !g_badgeManager) {
        return errorString(env, "Failed to get BadgeUpdateManager", hr);
    }

    // Create badge updater
    if (g_isAppx || g_aumid.empty()) {
        hr = g_badgeManager->CreateBadgeUpdaterForApplication(&g_badgeUpdater);
    } else {
        hr = g_badgeManager->CreateBadgeUpdaterForApplicationWithId(
            HStringWrapper(g_aumid).Get(), &g_badgeUpdater);
    }

    if (FAILED(hr) || !g_badgeUpdater) {
        g_badgeManager.Reset();
        return errorString(env, "Failed to create BadgeUpdater", hr);
    }

    g_initialized = true;
    return nullptr; // success
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeSetBadgeNumber(
    JNIEnv *env, jclass clazz, jint value
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return env->NewStringUTF("Not initialized");

    std::wstring valStr = std::to_wstring(value);
    HRESULT hr = setBadgeXml(valStr);
    if (FAILED(hr)) return errorString(env, "setBadgeNumber failed", hr);
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeSetBadgeGlyph(
    JNIEnv *env, jclass clazz, jstring jGlyph
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return env->NewStringUTF("Not initialized");

    std::wstring glyph = toWString(env, jGlyph);
    HRESULT hr = setBadgeXml(glyph);
    if (FAILED(hr)) return errorString(env, "setBadgeGlyph failed", hr);
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeClearBadge(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized || !g_badgeUpdater) return env->NewStringUTF("Not initialized");

    g_badgeUpdater->Clear();
    return nullptr;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeUninitialize(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_badgeUpdater.Reset();
    g_badgeManager.Reset();
    g_initialized = false;
}

// ============================================================================
// Jump List JNI exports
// ============================================================================

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeSetProcessAppId(
    JNIEnv *env, jclass clazz, jstring jAumid
) {
    std::wstring aumid = toWString(env, jAumid);
    if (aumid.empty()) return env->NewStringUTF("AUMID is empty");

    // Do NOT call CoInitializeEx here: SetCurrentProcessExplicitAppUserModelID
    // is a pure Win32 API and does not require COM. Initializing COM as MTA on
    // the caller's thread (typically the JVM main thread) would lock its
    // apartment and break later STA initialization (e.g. Tao's OleInitialize,
    // which fails with RPC_E_CHANGED_MODE).
    HRESULT hr = SetCurrentProcessExplicitAppUserModelID(aumid.c_str());
    if (FAILED(hr)) return errorString(env, "SetCurrentProcessExplicitAppUserModelID failed", hr);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeBeginList(
    JNIEnv *env, jclass clazz, jstring jAumid, jboolean jIsAppx
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) {
        return errorString(env, "CoInitializeEx failed", hr);
    }

    g_jumpList.Reset();
    hr = CoCreateInstance(CLSID_DestinationList, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&g_jumpList));
    if (FAILED(hr) || !g_jumpList) {
        return errorString(env, "Failed to create ICustomDestinationList", hr);
    }

    std::wstring aumid = toWString(env, jAumid);
    bool isAppx = (bool)jIsAppx;

    if (!isAppx && !aumid.empty()) {
        SetCurrentProcessExplicitAppUserModelID(aumid.c_str());

        hr = g_jumpList->SetAppID(aumid.c_str());
        if (FAILED(hr)) {
            g_jumpList.Reset();
            return errorString(env, "SetAppID failed", hr);
        }
    }

    UINT minSlots = 0;
    ComPtr<IObjectArray> removedItems;
    hr = g_jumpList->BeginList(&minSlots, IID_PPV_ARGS(&removedItems));
    if (FAILED(hr)) {
        g_jumpList.Reset();
        return errorString(env, "BeginList failed", hr);
    }

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeAppendCategory(
    JNIEnv *env, jclass clazz, jstring jName,
    jobjectArray jTitles, jobjectArray jArguments, jobjectArray jDescriptions,
    jintArray jIconTypes, jobjectArray jIconPaths, jintArray jIconIndices
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);
    if (!g_jumpList) return env->NewStringUTF("No active jump list session");

    std::wstring name = toWString(env, jName);
    auto titles = toWStringArray(env, jTitles);
    auto arguments = toWStringArray(env, jArguments);
    auto descriptions = toWStringArray(env, jDescriptions);
    auto iconTypes = toIntVector(env, jIconTypes);
    auto iconPaths = toWStringArray(env, jIconPaths);
    auto iconIndices = toIntVector(env, jIconIndices);

    size_t count = titles.size();

    ComPtr<IObjectCollection> collection;
    HRESULT hr = CoCreateInstance(CLSID_EnumerableObjectCollection, nullptr,
        CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&collection));
    if (FAILED(hr)) return errorString(env, "Failed to create IObjectCollection", hr);

    for (size_t i = 0; i < count; i++) {
        IShellLinkW *link = nullptr;
        hr = createShellLinkItem(
            titles[i],
            i < arguments.size() ? arguments[i] : L"",
            i < descriptions.size() ? descriptions[i] : L"",
            i < iconTypes.size() ? iconTypes[i] : -1,
            i < iconPaths.size() ? iconPaths[i] : L"",
            i < iconIndices.size() ? iconIndices[i] : 0,
            &link);
        if (FAILED(hr)) return errorString(env, "Failed to create shell link item", hr);
        collection->AddObject(link);
        link->Release();
    }

    ComPtr<IObjectArray> array;
    hr = collection.As(&array);
    if (FAILED(hr)) return errorString(env, "Failed to get IObjectArray", hr);

    hr = g_jumpList->AppendCategory(name.c_str(), array.Get());
    if (FAILED(hr)) return errorString(env, "AppendCategory failed", hr);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeAppendKnownCategory(
    JNIEnv *env, jclass clazz, jint categoryId
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);
    if (!g_jumpList) return env->NewStringUTF("No active jump list session");

    HRESULT hr = g_jumpList->AppendKnownCategory((KNOWNDESTCATEGORY)categoryId);
    if (FAILED(hr)) return errorString(env, "AppendKnownCategory failed", hr);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeAddUserTasks(
    JNIEnv *env, jclass clazz,
    jobjectArray jTitles, jobjectArray jArguments, jobjectArray jDescriptions,
    jintArray jIconTypes, jobjectArray jIconPaths, jintArray jIconIndices,
    jbooleanArray jIsSeparator
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);
    if (!g_jumpList) return env->NewStringUTF("No active jump list session");

    auto titles = toWStringArray(env, jTitles);
    auto arguments = toWStringArray(env, jArguments);
    auto descriptions = toWStringArray(env, jDescriptions);
    auto iconTypes = toIntVector(env, jIconTypes);
    auto iconPaths = toWStringArray(env, jIconPaths);
    auto iconIndices = toIntVector(env, jIconIndices);
    auto isSeparator = toBoolVector(env, jIsSeparator);

    size_t count = titles.size();

    ComPtr<IObjectCollection> collection;
    HRESULT hr = CoCreateInstance(CLSID_EnumerableObjectCollection, nullptr,
        CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&collection));
    if (FAILED(hr)) return errorString(env, "Failed to create IObjectCollection", hr);

    for (size_t i = 0; i < count; i++) {
        IShellLinkW *link = nullptr;
        if (i < isSeparator.size() && isSeparator[i]) {
            hr = createSeparatorLink(&link);
        } else {
            hr = createShellLinkItem(
                titles[i],
                i < arguments.size() ? arguments[i] : L"",
                i < descriptions.size() ? descriptions[i] : L"",
                i < iconTypes.size() ? iconTypes[i] : -1,
                i < iconPaths.size() ? iconPaths[i] : L"",
                i < iconIndices.size() ? iconIndices[i] : 0,
                &link);
        }
        if (FAILED(hr)) return errorString(env, "Failed to create task item", hr);
        collection->AddObject(link);
        link->Release();
    }

    ComPtr<IObjectArray> array;
    hr = collection.As(&array);
    if (FAILED(hr)) return errorString(env, "Failed to get IObjectArray", hr);

    hr = g_jumpList->AddUserTasks(array.Get());
    if (FAILED(hr)) return errorString(env, "AddUserTasks failed", hr);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeCommitList(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);
    if (!g_jumpList) return env->NewStringUTF("No active jump list session");

    HRESULT hr = g_jumpList->CommitList();
    g_jumpList.Reset();
    if (FAILED(hr)) return errorString(env, "CommitList failed", hr);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeDeleteList(
    JNIEnv *env, jclass clazz, jstring jAumid, jboolean jIsAppx
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) {
        return errorString(env, "CoInitializeEx failed", hr);
    }

    ComPtr<ICustomDestinationList> destList;
    hr = CoCreateInstance(CLSID_DestinationList, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&destList));
    if (FAILED(hr)) return errorString(env, "Failed to create ICustomDestinationList", hr);

    std::wstring aumid = toWString(env, jAumid);
    bool isAppx = (bool)jIsAppx;

    if (!isAppx && !aumid.empty()) {
        hr = destList->SetAppID(aumid.c_str());
        if (FAILED(hr)) return errorString(env, "SetAppID failed", hr);
    }

    hr = destList->DeleteList(isAppx ? nullptr : aumid.c_str());
    if (FAILED(hr)) return errorString(env, "DeleteList failed", hr);

    return nullptr;
}

// ============================================================================
// ITaskbarList3 helpers (overlay icon + thumbnail toolbar)
// ============================================================================

static bool EnsureTaskbarList() {
    if (g_taskbarListInitialized) return g_taskbarList != nullptr;
    g_taskbarListInitialized = true;

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) return false;

    hr = CoCreateInstance(CLSID_TaskbarList, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&g_taskbarList));
    if (FAILED(hr)) return false;

    hr = g_taskbarList->HrInit();
    if (FAILED(hr)) { g_taskbarList.Reset(); return false; }
    return true;
}

// HWND extraction from java.awt.Window (bypasses JPMS)
static HWND GetHwndFromAwtWindow(JNIEnv *env, jobject awtWindow) {
    if (!awtWindow) return nullptr;

    jclass awtAccessorClass = env->FindClass("sun/awt/AWTAccessor");
    if (!awtAccessorClass || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jmethodID getCompAccessor = env->GetStaticMethodID(awtAccessorClass,
        "getComponentAccessor", "()Lsun/awt/AWTAccessor$ComponentAccessor;");
    if (!getCompAccessor || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jobject compAccessor = env->CallStaticObjectMethod(awtAccessorClass, getCompAccessor);
    if (!compAccessor || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jclass compAccessorClass = env->FindClass("sun/awt/AWTAccessor$ComponentAccessor");
    if (!compAccessorClass || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jmethodID getPeer = env->GetMethodID(compAccessorClass,
        "getPeer", "(Ljava/awt/Component;)Ljava/awt/peer/ComponentPeer;");
    if (!getPeer || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jobject peer = env->CallObjectMethod(compAccessor, getPeer, awtWindow);
    if (!peer || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jclass wCompPeerClass = env->FindClass("sun/awt/windows/WComponentPeer");
    if (!wCompPeerClass || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jmethodID getHWnd = env->GetMethodID(wCompPeerClass, "getHWnd", "()J");
    if (!getHWnd || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jlong hwnd = env->CallLongMethod(peer, getHWnd);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    return (HWND)(intptr_t)hwnd;
}

// Icon loading: type 0=stock, 1=file, 2=resource
static HICON LoadIconByType(int iconType, const std::wstring &iconPath, int iconIndex) {
    switch (iconType) {
    case 0: { // Stock icon via SHGetStockIconInfo
        SHSTOCKICONINFO sii = {};
        sii.cbSize = sizeof(sii);
        HRESULT hr = SHGetStockIconInfo((SHSTOCKICONID)iconIndex, SHGSI_ICON | SHGSI_SMALLICON, &sii);
        return SUCCEEDED(hr) ? sii.hIcon : nullptr;
    }
    case 1: { // .ico file
        return (HICON)LoadImageW(nullptr, iconPath.c_str(), IMAGE_ICON, 16, 16, LR_LOADFROMFILE);
    }
    case 2: { // DLL resource
        HICON hSmall = nullptr;
        ExtractIconExW(iconPath.c_str(), iconIndex, nullptr, &hSmall, 1);
        return hSmall;
    }
    default: return nullptr;
    }
}

// Fill THUMBBUTTON from arrays
static void FillThumbButton(THUMBBUTTON &tb, int id, const std::wstring &tooltip,
                             int flags, HICON hIcon)
{
    memset(&tb, 0, sizeof(THUMBBUTTON));
    tb.dwMask = THB_FLAGS | THB_TOOLTIP;
    tb.iId = (UINT)id;
    tb.dwFlags = (THUMBBUTTONFLAGS)flags;
    wcsncpy_s(tb.szTip, _countof(tb.szTip), tooltip.c_str(), _TRUNCATE);
    if (hIcon) {
        tb.dwMask |= THB_ICON;
        tb.hIcon = hIcon;
    }
}

// WndProc subclass for thumbnail button clicks
static LRESULT CALLBACK ThumbBarWndProc(HWND hwnd, UINT uMsg, WPARAM wParam, LPARAM lParam) {
    auto *state = (ThumbBarState *)GetPropW(hwnd, THUMBBAR_PROP);
    if (!state) return DefWindowProcW(hwnd, uMsg, wParam, lParam);

    if (uMsg == WM_COMMAND && HIWORD(wParam) == THBN_CLICKED) {
        int buttonId = LOWORD(wParam);
        if (state->callbackRef && g_jvm) {
            JNIEnv *env = nullptr;
            if (g_jvm->GetEnv((void **)&env, JNI_VERSION_1_8) == JNI_OK && env) {
                env->CallVoidMethod(state->callbackRef, state->onClickMethod, (jint)buttonId);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
        }
    }

    return CallWindowProcW(state->originalWndProc, hwnd, uMsg, wParam, lParam);
}

// Hide buttons and detach callback, but keep state so buttons can be re-shown
static void CleanupThumbBarState(JNIEnv *env, HWND hwnd) {
    auto *state = (ThumbBarState *)GetPropW(hwnd, THUMBBAR_PROP);
    if (!state) return;

    // Hide all buttons (Windows doesn't allow removing them)
    if (state->buttonsAdded && g_taskbarList && IsWindow(hwnd)) {
        EnsureTaskbarList();
        THUMBBUTTON hidden[7] = {};
        for (int i = 0; i < state->buttonCount; i++) {
            hidden[i].dwMask = THB_FLAGS;
            hidden[i].iId = state->buttonIds[i];
            hidden[i].dwFlags = THBF_HIDDEN | THBF_DISABLED | THBF_NOBACKGROUND;
        }
        g_taskbarList->ThumbBarUpdateButtons(hwnd, (UINT)state->buttonCount, hidden);
    }

    // Restore original WndProc
    if (state->originalWndProc) {
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)state->originalWndProc);
        state->originalWndProc = nullptr;
    }

    // Release icons
    for (HICON h : state->icons) { if (h) DestroyIcon(h); }
    state->icons.clear();

    // Release callback
    if (state->callbackRef && env) {
        env->DeleteGlobalRef(state->callbackRef);
        state->callbackRef = nullptr;
    }
    // Keep state + prop so re-add uses ThumbBarUpdateButtons instead of ThumbBarAddButtons
}

// ============================================================================
// JNI: HWND extraction
// ============================================================================

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsTaskbarBridge_nativeGetHwnd(
    JNIEnv *env, jclass, jobject awtWindow)
{
    HWND hwnd = GetHwndFromAwtWindow(env, awtWindow);
    return (jlong)(intptr_t)hwnd;
}

// ============================================================================
// JNI: Overlay Icon
// ============================================================================

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsTaskbarBridge_nativeSetOverlayIcon(
    JNIEnv *env, jclass, jobject awtWindow,
    jint iconType, jstring jIconPath, jint iconIndex, jstring jDescription)
{
    std::lock_guard<std::mutex> lock(g_tb_mutex);
    if (!EnsureTaskbarList()) return env->NewStringUTF("ITaskbarList3 not available");

    HWND hwnd = GetHwndFromAwtWindow(env, awtWindow);
    if (!hwnd) return env->NewStringUTF("Could not get HWND");

    std::wstring path = toWString(env, jIconPath);
    HICON hIcon = LoadIconByType(iconType, path, iconIndex);
    if (!hIcon) return env->NewStringUTF("Failed to load icon");

    std::wstring desc = toWString(env, jDescription);
    HRESULT hr = g_taskbarList->SetOverlayIcon(hwnd, hIcon, desc.c_str());
    DestroyIcon(hIcon);

    if (FAILED(hr)) return errorString(env, "SetOverlayIcon failed", hr);
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsTaskbarBridge_nativeClearOverlayIcon(
    JNIEnv *env, jclass, jobject awtWindow)
{
    std::lock_guard<std::mutex> lock(g_tb_mutex);
    if (!EnsureTaskbarList()) return env->NewStringUTF("ITaskbarList3 not available");

    HWND hwnd = GetHwndFromAwtWindow(env, awtWindow);
    if (!hwnd) return env->NewStringUTF("Could not get HWND");

    HRESULT hr = g_taskbarList->SetOverlayIcon(hwnd, nullptr, nullptr);
    if (FAILED(hr)) return errorString(env, "ClearOverlayIcon failed", hr);
    return nullptr;
}

// ============================================================================
// JNI: Thumbnail Toolbar
// ============================================================================

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsTaskbarBridge_nativeThumbBarSetButtons(
    JNIEnv *env, jclass, jobject awtWindow,
    jintArray jIds, jobjectArray jTooltips, jintArray jFlags,
    jintArray jIconTypes, jobjectArray jIconPaths, jintArray jIconIndices,
    jobject jCallback)
{
    std::lock_guard<std::mutex> lock(g_tb_mutex);
    if (!EnsureTaskbarList()) return env->NewStringUTF("ITaskbarList3 not available");

    HWND hwnd = GetHwndFromAwtWindow(env, awtWindow);
    if (!hwnd) return env->NewStringUTF("Could not get HWND");

    int count = env->GetArrayLength(jIds);
    if (count <= 0 || count > 7) return env->NewStringUTF("Invalid button count (1-7)");

    // Check if buttons were already added for this window — re-show them
    auto *existingState = (ThumbBarState *)GetPropW(hwnd, THUMBBAR_PROP);
    if (existingState && existingState->buttonsAdded) {
        // Buttons already registered, update them instead of adding
        jint *ids = env->GetIntArrayElements(jIds, nullptr);
        jint *flags = env->GetIntArrayElements(jFlags, nullptr);
        jint *iconTypes = env->GetIntArrayElements(jIconTypes, nullptr);
        jint *iconIndices = env->GetIntArrayElements(jIconIndices, nullptr);
        auto tooltips = toWStringArray(env, jTooltips);
        auto iconPaths = toWStringArray(env, jIconPaths);

        // Clean old icons
        for (HICON h : existingState->icons) { if (h) DestroyIcon(h); }
        existingState->icons.clear();

        THUMBBUTTON buttons[7] = {};
        for (int i = 0; i < count; i++) {
            HICON hIcon = LoadIconByType(iconTypes[i], iconPaths[i], iconIndices[i]);
            if (hIcon) existingState->icons.push_back(hIcon);
            FillThumbButton(buttons[i], ids[i], tooltips[i], flags[i], hIcon);
        }

        env->ReleaseIntArrayElements(jIds, ids, JNI_ABORT);
        env->ReleaseIntArrayElements(jFlags, flags, JNI_ABORT);
        env->ReleaseIntArrayElements(jIconTypes, iconTypes, JNI_ABORT);
        env->ReleaseIntArrayElements(jIconIndices, iconIndices, JNI_ABORT);

        HRESULT hr = g_taskbarList->ThumbBarUpdateButtons(hwnd, (UINT)count, buttons);
        if (FAILED(hr)) return errorString(env, "ThumbBarUpdateButtons failed", hr);

        // Update callback and re-subclass WndProc
        if (existingState->callbackRef && env) {
            env->DeleteGlobalRef(existingState->callbackRef);
            existingState->callbackRef = nullptr;
        }
        if (jCallback) {
            // Resolve the method via the interface class, not GetObjectClass(jCallback).
            // Kotlin's `fun interface` lambdas produce synthetic classes that are not
            // registered as JNI-accessible under GraalVM native-image.
            jclass cbClass = env->FindClass("io/github/kdroidfilter/nucleus/launcher/windows/ThumbBarClickListener");
            jmethodID method = cbClass ? env->GetMethodID(cbClass, "onThumbButtonClick", "(I)V") : nullptr;
            if (cbClass) env->DeleteLocalRef(cbClass);
            if (method) {
                existingState->callbackRef = env->NewGlobalRef(jCallback);
                existingState->onClickMethod = method;
                if (!existingState->originalWndProc) {
                    existingState->originalWndProc = (WNDPROC)SetWindowLongPtrW(
                        hwnd, GWLP_WNDPROC, (LONG_PTR)ThumbBarWndProc);
                }
            }
        }
        return nullptr;
    }

    auto *state = new ThumbBarState{};
    state->buttonCount = count;

    jint *ids = env->GetIntArrayElements(jIds, nullptr);
    jint *flags = env->GetIntArrayElements(jFlags, nullptr);
    jint *iconTypes = env->GetIntArrayElements(jIconTypes, nullptr);
    jint *iconIndices = env->GetIntArrayElements(jIconIndices, nullptr);
    auto tooltips = toWStringArray(env, jTooltips);
    auto iconPaths = toWStringArray(env, jIconPaths);

    THUMBBUTTON buttons[7] = {};
    for (int i = 0; i < count; i++) {
        state->buttonIds[i] = (UINT)ids[i];
        HICON hIcon = LoadIconByType(iconTypes[i], iconPaths[i], iconIndices[i]);
        if (hIcon) state->icons.push_back(hIcon);
        FillThumbButton(buttons[i], ids[i], tooltips[i], flags[i], hIcon);
    }

    env->ReleaseIntArrayElements(jIds, ids, JNI_ABORT);
    env->ReleaseIntArrayElements(jFlags, flags, JNI_ABORT);
    env->ReleaseIntArrayElements(jIconTypes, iconTypes, JNI_ABORT);
    env->ReleaseIntArrayElements(jIconIndices, iconIndices, JNI_ABORT);

    HRESULT hr = g_taskbarList->ThumbBarAddButtons(hwnd, (UINT)count, buttons);
    if (FAILED(hr)) {
        for (HICON h : state->icons) { if (h) DestroyIcon(h); }
        delete state;
        return errorString(env, "ThumbBarAddButtons failed", hr);
    }

    state->buttonsAdded = true;

    if (jCallback) {
        jclass cbClass = env->FindClass("io/github/kdroidfilter/nucleus/launcher/windows/ThumbBarClickListener");
        jmethodID method = cbClass ? env->GetMethodID(cbClass, "onThumbButtonClick", "(I)V") : nullptr;
        if (cbClass) env->DeleteLocalRef(cbClass);
        if (method) {
            state->callbackRef = env->NewGlobalRef(jCallback);
            state->onClickMethod = method;
            state->originalWndProc = (WNDPROC)SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)ThumbBarWndProc);
        }
    }

    SetPropW(hwnd, THUMBBAR_PROP, (HANDLE)state);
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsTaskbarBridge_nativeThumbBarUpdateButtons(
    JNIEnv *env, jclass, jobject awtWindow,
    jintArray jIds, jobjectArray jTooltips, jintArray jFlags,
    jintArray jIconTypes, jobjectArray jIconPaths, jintArray jIconIndices)
{
    std::lock_guard<std::mutex> lock(g_tb_mutex);
    if (!EnsureTaskbarList()) return env->NewStringUTF("ITaskbarList3 not available");

    HWND hwnd = GetHwndFromAwtWindow(env, awtWindow);
    if (!hwnd) return env->NewStringUTF("Could not get HWND");

    auto *state = (ThumbBarState *)GetPropW(hwnd, THUMBBAR_PROP);
    if (!state || !state->buttonsAdded)
        return env->NewStringUTF("Buttons not yet added — call setButtons first");

    // Destroy old icons
    for (HICON h : state->icons) { if (h) DestroyIcon(h); }
    state->icons.clear();

    int count = env->GetArrayLength(jIds);
    jint *ids = env->GetIntArrayElements(jIds, nullptr);
    jint *flags = env->GetIntArrayElements(jFlags, nullptr);
    jint *iconTypes = env->GetIntArrayElements(jIconTypes, nullptr);
    jint *iconIndices = env->GetIntArrayElements(jIconIndices, nullptr);
    auto tooltips = toWStringArray(env, jTooltips);
    auto iconPaths = toWStringArray(env, jIconPaths);

    THUMBBUTTON buttons[7] = {};
    for (int i = 0; i < count; i++) {
        HICON hIcon = LoadIconByType(iconTypes[i], iconPaths[i], iconIndices[i]);
        if (hIcon) state->icons.push_back(hIcon);
        FillThumbButton(buttons[i], ids[i], tooltips[i], flags[i], hIcon);
    }

    env->ReleaseIntArrayElements(jIds, ids, JNI_ABORT);
    env->ReleaseIntArrayElements(jFlags, flags, JNI_ABORT);
    env->ReleaseIntArrayElements(jIconTypes, iconTypes, JNI_ABORT);
    env->ReleaseIntArrayElements(jIconIndices, iconIndices, JNI_ABORT);

    HRESULT hr = g_taskbarList->ThumbBarUpdateButtons(hwnd, (UINT)count, buttons);
    if (FAILED(hr)) return errorString(env, "ThumbBarUpdateButtons failed", hr);
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsTaskbarBridge_nativeThumbBarUnregister(
    JNIEnv *env, jclass, jobject awtWindow)
{
    std::lock_guard<std::mutex> lock(g_tb_mutex);
    HWND hwnd = GetHwndFromAwtWindow(env, awtWindow);
    if (!hwnd) return env->NewStringUTF("Could not get HWND");
    CleanupThumbBarState(env, hwnd);
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsTaskbarBridge_nativeThumbBarUnregisterByHwnd(
    JNIEnv *env, jclass, jlong jHwnd)
{
    std::lock_guard<std::mutex> lock(g_tb_mutex);
    HWND hwnd = (HWND)(intptr_t)jHwnd;
    if (!hwnd) return env->NewStringUTF("Invalid HWND");
    CleanupThumbBarState(env, hwnd);
    return nullptr;
}

} // extern "C"

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID lpReserved) {
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(hModule);
    return TRUE;
}
