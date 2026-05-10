/**
 * nucleus_media_control_windows.cpp
 *
 * JNI bridge for Windows System Media Transport Controls (SMTC) via WinRT/WRL.
 *
 * SMTC requires an HWND. Since the public API doesn't expose one, we create
 * a hidden message-only-like top-level window owned by this DLL and bind SMTC
 * to it. This matches the approach used by media players that don't have a
 * main window at startup (or run headless).
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
#include <roapi.h>
#include <winstring.h>
#include <shobjidl.h>
#include <shlobj.h>
#include <propvarutil.h>
#include <propkey.h>
#include <strsafe.h>

#include <wrl/implements.h>
#include <wrl/event.h>
#include <windows.media.h>
#include <windows.foundation.h>
#include <windows.storage.streams.h>
#include <systemmediatransportcontrolsinterop.h>

#include <jni.h>

#include <string>
#include <mutex>
#include <sstream>

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "runtimeobject.lib")
#pragma comment(lib, "kernel32.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "propsys.lib")

using namespace Microsoft::WRL;
using namespace ABI::Windows::Media;
using namespace ABI::Windows::Foundation;
using namespace ABI::Windows::Storage::Streams;

#define BRIDGE_CLASS "dev/nucleusframework/nucleus/media/control/windows/NativeWindowsBridge"

// ============================================================================
// Globals
// ============================================================================

static JavaVM *g_jvm = nullptr;
static std::mutex g_mutex;
static bool g_initialized = false;
static bool g_listening = false;

static HWND g_hwnd = nullptr;
static HANDLE g_messageThread = nullptr;
static DWORD g_messageThreadId = 0;
static HANDLE g_readyEvent = nullptr;
static std::wstring g_displayName;

static ComPtr<ISystemMediaTransportControls> g_smtc;
static ComPtr<ISystemMediaTransportControlsDisplayUpdater> g_updater;
static ComPtr<ISystemMediaTransportControlsTimelineProperties> g_timeline;
static EventRegistrationToken g_buttonToken = {};
static EventRegistrationToken g_positionToken = {};
static bool g_buttonHooked = false;
static bool g_positionHooked = false;

// ============================================================================
// HSTRING wrapper
// ============================================================================

class HStringWrapper {
    HSTRING _hstr = nullptr;
public:
    explicit HStringWrapper(const wchar_t *str) {
        if (str && *str) WindowsCreateString(str, (UINT32)wcslen(str), &_hstr);
    }
    explicit HStringWrapper(const std::wstring &str) {
        if (!str.empty()) WindowsCreateString(str.c_str(), (UINT32)str.length(), &_hstr);
    }
    ~HStringWrapper() { if (_hstr) WindowsDeleteString(_hstr); }
    HSTRING Get() const { return _hstr; }
    HStringWrapper(const HStringWrapper&) = delete;
    HStringWrapper& operator=(const HStringWrapper&) = delete;
};

// ============================================================================
// JNI helpers
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

static JNIEnv *getEnv(bool *didAttach) {
    *didAttach = false;
    if (!g_jvm) return nullptr;
    JNIEnv *env = nullptr;
    jint status = g_jvm->GetEnv((void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThreadAsDaemon((void **)&env, nullptr) != JNI_OK) return nullptr;
        *didAttach = true;
    } else if (status != JNI_OK) {
        return nullptr;
    }
    return env;
}

static void releaseEnv(bool didAttach) {
    if (didAttach && g_jvm) g_jvm->DetachCurrentThread();
}

static std::wstring toWString(JNIEnv *env, jstring jstr) {
    if (!jstr) return L"";
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    jsize len = env->GetStringLength(jstr);
    std::wstring result((const wchar_t *)chars, len);
    env->ReleaseStringChars(jstr, chars);
    return result;
}

static void fireEvent(const std::string &json) {
    bool didAttach = false;
    JNIEnv *env = getEnv(&didAttach);
    if (!env) return;
    jclass cls = env->FindClass(BRIDGE_CLASS);
    if (cls) {
        jmethodID mid = env->GetStaticMethodID(cls, "onMediaControlEvent", "(Ljava/lang/String;)V");
        if (mid) {
            jstring s = env->NewStringUTF(json.c_str());
            env->CallStaticVoidMethod(cls, mid, s);
            env->DeleteLocalRef(s);
        }
        env->DeleteLocalRef(cls);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    releaseEnv(didAttach);
}

// ============================================================================
// Hidden window (message loop thread)
// ============================================================================

static LRESULT CALLBACK BridgeWndProc(HWND hwnd, UINT msg, WPARAM w, LPARAM l) {
    return DefWindowProcW(hwnd, msg, w, l);
}

static DWORD WINAPI MessageThreadProc(LPVOID) {
    // STA apartment so SMTC events marshaled to this window's owner thread
    // are dispatched by the message pump below.
    HRESULT hrInit = RoInitialize(RO_INIT_SINGLETHREADED);

    WNDCLASSEXW wc = {};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = BridgeWndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = L"NucleusMediaControlBridge";
    RegisterClassExW(&wc);

    g_hwnd = CreateWindowExW(
        0,
        wc.lpszClassName, L"NucleusMediaControlBridge",
        WS_OVERLAPPEDWINDOW, 0, 0, 1, 1,
        nullptr, nullptr, wc.hInstance, nullptr);

    if (g_readyEvent) SetEvent(g_readyEvent);

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    if (g_hwnd) {
        DestroyWindow(g_hwnd);
        g_hwnd = nullptr;
    }
    UnregisterClassW(wc.lpszClassName, wc.hInstance);
    if (SUCCEEDED(hrInit)) RoUninitialize();
    return 0;
}

static bool ensureHwnd() {
    if (g_hwnd) return true;
    if (g_messageThread) return g_hwnd != nullptr;
    g_readyEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    g_messageThread = CreateThread(nullptr, 0, MessageThreadProc, nullptr, 0, &g_messageThreadId);
    if (!g_messageThread) return false;
    WaitForSingleObject(g_readyEvent, 5000);
    CloseHandle(g_readyEvent);
    g_readyEvent = nullptr;
    return g_hwnd != nullptr;
}

// ============================================================================
// SMTC init
// ============================================================================

static HRESULT initSmtc() {
    if (g_smtc) return S_OK;
    if (!ensureHwnd()) return E_FAIL;

    ComPtr<ISystemMediaTransportControlsInterop> interop;
    HRESULT hr = RoGetActivationFactory(
        HStringWrapper(RuntimeClass_Windows_Media_SystemMediaTransportControls).Get(),
        IID_PPV_ARGS(&interop));
    if (FAILED(hr)) return hr;

    hr = interop->GetForWindow(g_hwnd, IID_PPV_ARGS(&g_smtc));
    if (FAILED(hr)) return hr;

    hr = g_smtc->get_DisplayUpdater(&g_updater);
    if (FAILED(hr)) return hr;

    ComPtr<IInspectable> tpInsp;
    hr = RoActivateInstance(
        HStringWrapper(RuntimeClass_Windows_Media_SystemMediaTransportControlsTimelineProperties).Get(),
        &tpInsp);
    if (FAILED(hr)) return hr;
    hr = tpInsp.As(&g_timeline);
    if (FAILED(hr)) return hr;

    // Enable standard buttons
    g_smtc->put_IsEnabled(true);
    g_smtc->put_IsPlayEnabled(true);
    g_smtc->put_IsPauseEnabled(true);
    g_smtc->put_IsStopEnabled(true);
    g_smtc->put_IsNextEnabled(true);
    g_smtc->put_IsPreviousEnabled(true);
    g_smtc->put_IsFastForwardEnabled(true);
    g_smtc->put_IsRewindEnabled(true);

    g_updater->put_Type(MediaPlaybackType::MediaPlaybackType_Music);
    return S_OK;
}

// ============================================================================
// JSON helpers
// ============================================================================

static std::string jsonSimple(const char *type) {
    std::ostringstream os;
    os << "{\"type\":\"" << type << "\"}";
    return os.str();
}

static std::string jsonWithLong(const char *type, const char *field, long long value) {
    std::ostringstream os;
    os << "{\"type\":\"" << type << "\",\"" << field << "\":" << value << "}";
    return os.str();
}

// ============================================================================
// Listener hooks
// ============================================================================

static const long long HNS_PER_MS = 10000LL;
static const long long SEEK_STEP_MS = 10000LL; // 10 seconds for FF/RW

static HRESULT hookListeners() {
    if (!g_smtc) return E_FAIL;

    if (!g_buttonHooked) {
        auto buttonHandler = Callback<ITypedEventHandler<SystemMediaTransportControls*, SystemMediaTransportControlsButtonPressedEventArgs*>>(
            [](ISystemMediaTransportControls*, ISystemMediaTransportControlsButtonPressedEventArgs* args) -> HRESULT {
                if (!args) return S_OK;
                SystemMediaTransportControlsButton btn;
                if (FAILED(args->get_Button(&btn))) return S_OK;
                switch (btn) {
                    case SystemMediaTransportControlsButton_Play:
                        fireEvent(jsonSimple("play"));
                        break;
                    case SystemMediaTransportControlsButton_Pause:
                        fireEvent(jsonSimple("pause"));
                        break;
                    case SystemMediaTransportControlsButton_Stop:
                        fireEvent(jsonSimple("stop"));
                        break;
                    case SystemMediaTransportControlsButton_Next:
                        fireEvent(jsonSimple("next"));
                        break;
                    case SystemMediaTransportControlsButton_Previous:
                        fireEvent(jsonSimple("previous"));
                        break;
                    case SystemMediaTransportControlsButton_FastForward:
                        // Reported in microseconds by the Linux/macOS contracts, so offsetUs.
                        fireEvent(jsonWithLong("seek", "offsetUs", SEEK_STEP_MS * 1000LL));
                        break;
                    case SystemMediaTransportControlsButton_Rewind:
                        fireEvent(jsonWithLong("seek", "offsetUs", -SEEK_STEP_MS * 1000LL));
                        break;
                    default:
                        break;
                }
                return S_OK;
            });
        HRESULT hr = g_smtc->add_ButtonPressed(buttonHandler.Get(), &g_buttonToken);
        if (FAILED(hr)) return hr;
        g_buttonHooked = true;
    }

    if (!g_positionHooked) {
        ComPtr<ISystemMediaTransportControls2> smtc2;
        if (SUCCEEDED(g_smtc.As(&smtc2))) {
            auto positionHandler = Callback<ITypedEventHandler<SystemMediaTransportControls*, PlaybackPositionChangeRequestedEventArgs*>>(
                [](ISystemMediaTransportControls*, IPlaybackPositionChangeRequestedEventArgs* args) -> HRESULT {
                    if (!args) return S_OK;
                    TimeSpan position{};
                    if (FAILED(args->get_RequestedPlaybackPosition(&position))) return S_OK;
                    long long us = position.Duration / 10LL; // 100ns -> us
                    fireEvent(jsonWithLong("set_position", "positionUs", us));
                    return S_OK;
                });
            smtc2->add_PlaybackPositionChangeRequested(positionHandler.Get(), &g_positionToken);
            g_positionHooked = true;
        }
    }
    return S_OK;
}

static void unhookListeners() {
    if (g_smtc) {
        if (g_buttonHooked) {
            g_smtc->remove_ButtonPressed(g_buttonToken);
            g_buttonHooked = false;
        }
        if (g_positionHooked) {
            ComPtr<ISystemMediaTransportControls2> smtc2;
            if (SUCCEEDED(g_smtc.As(&smtc2))) {
                smtc2->remove_PlaybackPositionChangeRequested(g_positionToken);
            }
            g_positionHooked = false;
        }
    }
}

// ============================================================================
// Start Menu shortcut AUMID patching (NSIS/JPackage installers)
// ============================================================================
//
// SMTC resolves an AUMID to an icon + display name by looking for a Start Menu
// shortcut with a matching `System.AppUserModel.ID` property. APPX/MSIX sets
// this via the package manifest, but classic installers (NSIS, jpackage)
// create shortcuts without it — so the media overlay falls back to the bare
// javaw.exe identity. We patch the existing shortcut here.

static bool writeAumidToShortcut(const WCHAR *path, const std::wstring &aumid) {
    ComPtr<IShellLinkW> link;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)))) {
        return false;
    }
    ComPtr<IPersistFile> file;
    if (FAILED(link.As(&file))) return false;
    if (FAILED(file->Load(path, STGM_READWRITE))) return false;

    ComPtr<IPropertyStore> store;
    if (FAILED(link.As(&store))) return false;

    // Skip if the AUMID is already correct.
    PROPVARIANT existing{};
    PropVariantInit(&existing);
    if (SUCCEEDED(store->GetValue(PKEY_AppUserModel_ID, &existing))) {
        WCHAR current[MAX_PATH]{};
        if (SUCCEEDED(PropVariantToString(existing, current, MAX_PATH)) && aumid == current) {
            PropVariantClear(&existing);
            return true;
        }
    }
    PropVariantClear(&existing);

    PROPVARIANT var{};
    if (FAILED(InitPropVariantFromString(aumid.c_str(), &var))) return false;
    HRESULT hr = store->SetValue(PKEY_AppUserModel_ID, var);
    PropVariantClear(&var);
    if (FAILED(hr)) return false;
    if (FAILED(store->Commit())) return false;
    return SUCCEEDED(file->Save(path, TRUE));
}

static bool tryPatchShortcutIn(int csidl, const std::wstring &appName, const std::wstring &aumid) {
    WCHAR root[MAX_PATH]{};
    if (FAILED(SHGetFolderPathW(nullptr, csidl, nullptr, 0, root))) return false;

    WCHAR path[MAX_PATH]{};
    if (FAILED(StringCchCopyW(path, MAX_PATH, root))) return false;
    if (FAILED(StringCchCatW(path, MAX_PATH, L"\\Programs\\"))) return false;
    if (FAILED(StringCchCatW(path, MAX_PATH, appName.c_str()))) return false;
    if (FAILED(StringCchCatW(path, MAX_PATH, L".lnk"))) return false;

    if (GetFileAttributesW(path) == INVALID_FILE_ATTRIBUTES) return false;
    return writeAumidToShortcut(path, aumid);
}

static void patchStartMenuShortcut(const std::wstring &appName, const std::wstring &aumid) {
    if (appName.empty() || aumid.empty()) return;
    // Prefer user Start Menu; fall back to system-wide (admin-installed).
    if (tryPatchShortcutIn(CSIDL_STARTMENU, appName, aumid)) return;
    tryPatchShortcutIn(CSIDL_COMMON_STARTMENU, appName, aumid);
}

// ============================================================================
// JNI exports
// ============================================================================

extern "C" {

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_windows_NativeWindowsBridge_nativeConfigure(
    JNIEnv *env, jclass, jstring jAumid, jstring jDisplayName
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    // First arg is the Windows AUMID (already resolved by the Kotlin side via
    // NucleusApp.aumid — matches the value stamped by the plugin on the Start
    // Menu shortcut). Empty for APPX/MSIX: package identity takes over.
    std::wstring aumid = toWString(env, jAumid);
    std::wstring disp = toWString(env, jDisplayName);
    g_displayName = disp;

    if (!aumid.empty()) {
        SetCurrentProcessExplicitAppUserModelID(aumid.c_str());
    }

    if (!g_initialized) {
        HRESULT hr = RoInitialize(RO_INIT_MULTITHREADED);
        if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) return;
        g_initialized = true;
    }

    // For non-packaged (NSIS/jpackage) installs, patch the existing Start Menu
    // shortcut to carry our AUMID so SMTC can resolve it to the app icon+name.
    // Safe no-op when the shortcut is missing or on APPX (empty aumid skips).
    if (!disp.empty() && !aumid.empty()) {
        patchStartMenuShortcut(disp, aumid);
    }

    initSmtc();
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_windows_NativeWindowsBridge_nativeSetMetadata(
    JNIEnv *env, jclass,
    jstring jTitle, jstring jArtist, jstring jAlbum, jstring jCoverUrl, jlong durationMs
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (FAILED(initSmtc()) || !g_updater) return;

    std::wstring title = toWString(env, jTitle);
    std::wstring artist = toWString(env, jArtist);
    std::wstring album = toWString(env, jAlbum);
    std::wstring cover = toWString(env, jCoverUrl);

    ComPtr<IMusicDisplayProperties> music;
    if (FAILED(g_updater->get_MusicProperties(&music)) || !music) return;

    if (!title.empty()) music->put_Title(HStringWrapper(title).Get());
    if (!artist.empty()) music->put_Artist(HStringWrapper(artist).Get());
    if (!album.empty()) {
        ComPtr<IMusicDisplayProperties2> music2;
        if (SUCCEEDED(music.As(&music2))) {
            music2->put_AlbumTitle(HStringWrapper(album).Get());
        }
    }

    if (!cover.empty()) {
        ComPtr<IRandomAccessStreamReferenceStatics> rasrStatics;
        if (SUCCEEDED(RoGetActivationFactory(
                HStringWrapper(L"Windows.Storage.Streams.RandomAccessStreamReference").Get(),
                IID_PPV_ARGS(&rasrStatics)))) {
            ComPtr<IRandomAccessStreamReference> streamRef;
            HRESULT hr = S_OK;
            if (cover.rfind(L"file://", 0) == 0) {
                // Not straightforward to await StorageFile::GetFileFromPathAsync synchronously
                // from JNI. Best-effort: just try a URI load; Windows accepts file:// for SMTC.
                ComPtr<IUriRuntimeClassFactory> uriFactory;
                if (SUCCEEDED(RoGetActivationFactory(
                        HStringWrapper(L"Windows.Foundation.Uri").Get(),
                        IID_PPV_ARGS(&uriFactory)))) {
                    ComPtr<IUriRuntimeClass> uri;
                    hr = uriFactory->CreateUri(HStringWrapper(cover).Get(), &uri);
                    if (SUCCEEDED(hr)) hr = rasrStatics->CreateFromUri(uri.Get(), &streamRef);
                }
            } else {
                ComPtr<IUriRuntimeClassFactory> uriFactory;
                if (SUCCEEDED(RoGetActivationFactory(
                        HStringWrapper(L"Windows.Foundation.Uri").Get(),
                        IID_PPV_ARGS(&uriFactory)))) {
                    ComPtr<IUriRuntimeClass> uri;
                    hr = uriFactory->CreateUri(HStringWrapper(cover).Get(), &uri);
                    if (SUCCEEDED(hr)) hr = rasrStatics->CreateFromUri(uri.Get(), &streamRef);
                }
            }
            if (SUCCEEDED(hr) && streamRef) {
                g_updater->put_Thumbnail(streamRef.Get());
            }
        }
    }

    if (g_timeline) {
        TimeSpan zero{0};
        g_timeline->put_StartTime(zero);
        g_timeline->put_MinSeekTime(zero);
        long long hns = (durationMs > 0 ? durationMs : 0) * HNS_PER_MS;
        TimeSpan end{hns};
        g_timeline->put_EndTime(end);
        g_timeline->put_MaxSeekTime(end);
        ComPtr<ISystemMediaTransportControls2> smtc2x;
        if (SUCCEEDED(g_smtc.As(&smtc2x))) {
            smtc2x->UpdateTimelineProperties(g_timeline.Get());
        }
    }

    g_updater->Update();
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_windows_NativeWindowsBridge_nativeSetPlaybackState(
    JNIEnv *, jclass, jint status, jlong positionMs
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (FAILED(initSmtc()) || !g_smtc) return;

    // Kotlin enum: STOPPED=0, PAUSED=1, PLAYING=2
    MediaPlaybackStatus mps;
    switch (status) {
        case 2: mps = MediaPlaybackStatus::MediaPlaybackStatus_Playing; break;
        case 1: mps = MediaPlaybackStatus::MediaPlaybackStatus_Paused; break;
        default: mps = MediaPlaybackStatus::MediaPlaybackStatus_Stopped; break;
    }
    g_smtc->put_PlaybackStatus(mps);

    if (g_timeline) {
        TimeSpan pos{ (positionMs > 0 ? positionMs : 0) * HNS_PER_MS };
        g_timeline->put_Position(pos);
        ComPtr<ISystemMediaTransportControls2> smtc2x;
        if (SUCCEEDED(g_smtc.As(&smtc2x))) {
            smtc2x->UpdateTimelineProperties(g_timeline.Get());
        }
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_windows_NativeWindowsBridge_nativeSetVolume(
    JNIEnv *, jclass, jdouble /*volume*/
) {
    // SMTC has no per-app volume channel — no-op.
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_media_control_windows_NativeWindowsBridge_nativeStartListening(
    JNIEnv *, jclass
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (FAILED(initSmtc())) return JNI_FALSE;
    if (g_listening) return JNI_TRUE;
    if (FAILED(hookListeners())) return JNI_FALSE;
    g_listening = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_windows_NativeWindowsBridge_nativeStopListening(
    JNIEnv *, jclass
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_listening) return;
    unhookListeners();
    g_listening = false;
}

} // extern "C"

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(hModule);
    if (reason == DLL_PROCESS_DETACH) {
        if (g_messageThreadId) PostThreadMessageW(g_messageThreadId, WM_QUIT, 0, 0);
    }
    return TRUE;
}
