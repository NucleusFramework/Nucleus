/**
 * nucleus_widget_windows.cpp
 *
 * JNI bridge for the Windows App SDK widget provider API
 * (Microsoft.Windows.Widgets.Providers), bound 1:1:
 *  - an out-of-process COM server (IClassFactory) whose objects implement
 *    IWidgetProvider plus the optional provider interfaces the Kotlin provider
 *    implements, forwarding every callback to a Kotlin dispatcher;
 *  - WidgetManager / WidgetUpdateRequestOptions / WidgetInfo;
 *  - the live WidgetResourceRequest / WidgetResourceResponse / IMap / Deferral
 *    objects, exposed to Kotlin as opaque AddRef'd handles.
 *
 * The WinRT classes live in the Windows App SDK framework package, so they
 * only activate in a process with package identity that declares the
 * framework dependency.
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
#include <roapi.h>
#include <winstring.h>
#include <Shlwapi.h>
#include <shcore.h>

#include <wrl/client.h>
#include <wrl/event.h>
#include <wrl/implements.h>
#include <wrl/wrappers/corewrappers.h>

#include "widgets_abi.h"

#include <jni.h>
#include "../../../../../native-common/nucleus_jni.h"

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "runtimeobject.lib")
#pragma comment(lib, "shlwapi.lib")
#pragma comment(lib, "shcore.lib")

using namespace Microsoft::WRL;
using namespace Microsoft::WRL::Wrappers;
using namespace ABI::Microsoft::Windows::Widgets;
using namespace ABI::Microsoft::Windows::Widgets::Providers;
namespace WF = ABI::Windows::Foundation;
namespace WFC = ABI::Windows::Foundation::Collections;
namespace WSS = ABI::Windows::Storage::Streams;

#define EXCEPTION_CLASS "dev/nucleusframework/widget/windows/WidgetException"

// Optional provider interfaces, mirrored by NativeWidgetBridge.kt.
static const int FLAG_PROVIDER2 = 1 << 0;
static const int FLAG_ANALYTICS = 1 << 1;
static const int FLAG_ERRORS = 1 << 2;
static const int FLAG_MESSAGE = 1 << 3;
static const int FLAG_RESOURCE = 1 << 4;

static JavaVM *g_jvm = nullptr;

// Diagnostics: with NUCLEUS_WIDGET_TRACE=<file>, every class-object request and provider
// QueryInterface is appended to that file, so a host that gives up before CreateWidget is visible.
static void trace(const wchar_t *what, REFIID riid) {
    wchar_t path[MAX_PATH];
    if (GetEnvironmentVariableW(L"NUCLEUS_WIDGET_TRACE", path, MAX_PATH) == 0) return;
    wchar_t iid[64] = L"";
    StringFromGUID2(riid, iid, 64);
    wchar_t line[256];
    swprintf_s(line, L"%lu %ls %ls\r\n", GetCurrentThreadId(), what, iid);
    HANDLE f = CreateFileW(path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return;
    DWORD written = 0;
    char utf8[512];
    int n = WideCharToMultiByte(CP_UTF8, 0, line, -1, utf8, sizeof(utf8), nullptr, nullptr);
    if (n > 1) WriteFile(f, utf8, (DWORD)(n - 1), &written, nullptr);
    CloseHandle(f);
}

// ============================================================================
// JNI helpers
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

// Attaches the calling (COM RPC) thread for the duration of one callback.
class ScopedEnv {
    JNIEnv *_env = nullptr;
    bool _attached = false;
public:
    ScopedEnv() {
        if (!g_jvm) return;
        jint status = g_jvm->GetEnv((void **)&_env, JNI_VERSION_1_8);
        if (status == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThreadAsDaemon((void **)&_env, nullptr) != JNI_OK) {
                _env = nullptr;
                return;
            }
            _attached = true;
        } else if (status != JNI_OK) {
            _env = nullptr;
        }
    }
    ~ScopedEnv() { if (_attached) g_jvm->DetachCurrentThread(); }
    JNIEnv *get() const { return _env; }
    ScopedEnv(const ScopedEnv &) = delete;
    ScopedEnv &operator=(const ScopedEnv &) = delete;
};

static jstring toJString(JNIEnv *env, HSTRING hstr) {
    UINT32 len = 0;
    const wchar_t *raw = WindowsGetStringRawBuffer(hstr, &len);
    return env->NewString((const jchar *)raw, (jsize)len);
}

static jstring toJString(JNIEnv *env, const std::wstring &str) {
    return env->NewString((const jchar *)str.c_str(), (jsize)str.length());
}

static std::wstring toWString(HSTRING hstr) {
    UINT32 len = 0;
    const wchar_t *raw = WindowsGetStringRawBuffer(hstr, &len);
    return std::wstring(raw, len);
}

// Owns an HSTRING built from a Java string (null jstring -> null HSTRING).
class JHString {
    HSTRING _hstr = nullptr;
public:
    JHString(JNIEnv *env, jstring jstr) {
        if (!jstr) return;
        const jchar *chars = env->GetStringChars(jstr, nullptr);
        WindowsCreateString((const wchar_t *)chars, (UINT32)env->GetStringLength(jstr), &_hstr);
        env->ReleaseStringChars(jstr, chars);
    }
    ~JHString() { if (_hstr) WindowsDeleteString(_hstr); }
    HSTRING get() const { return _hstr; }
    JHString(const JHString &) = delete;
    JHString &operator=(const JHString &) = delete;
};

static void throwHr(JNIEnv *env, HRESULT hr, const char *what) {
    jclass cls = env->FindClass(EXCEPTION_CLASS);
    if (!cls) return;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;I)V");
    if (!ctor) return;
    jstring msg = env->NewStringUTF(what);
    jobject ex = env->NewObject(cls, ctor, msg, (jint)hr);
    if (ex) env->Throw((jthrowable)ex);
}

#define THROW_IF_FAILED(env, hr, what, ret) \
    do { HRESULT _hr = (hr); if (FAILED(_hr)) { throwHr((env), _hr, (what)); return ret; } } while (0)

template <typename T>
static T *fromHandle(jlong handle) { return reinterpret_cast<T *>(handle); }

// Hands an AddRef'd interface pointer to Kotlin; released by nativeRelease.
template <typename T>
static jlong toHandle(const ComPtr<T> &ptr) {
    if (!ptr) return 0;
    ptr.Get()->AddRef();
    return reinterpret_cast<jlong>(ptr.Get());
}

// ============================================================================
// Activation factories
// ============================================================================

template <typename T>
static HRESULT getFactory(const wchar_t *runtimeClass, ComPtr<T> &out) {
    return RoGetActivationFactory(HStringReference(runtimeClass).Get(), __uuidof(T), &out);
}

static std::mutex g_managerMutex;
static ComPtr<IWidgetManager> g_manager;

static HRESULT getManager(ComPtr<IWidgetManager> &out) {
    std::lock_guard<std::mutex> lock(g_managerMutex);
    if (!g_manager) {
        ComPtr<IWidgetManagerStatics> statics;
        HRESULT hr = getFactory(RuntimeClass_WidgetManager, statics);
        if (FAILED(hr)) return hr;
        hr = statics->GetDefault(&g_manager);
        if (FAILED(hr)) return hr;
    }
    out = g_manager;
    return S_OK;
}

// The default WidgetManager is a proxy to WidgetService: it dies with the service (Store update,
// crash, user restart) and every call then fails with an RPC disconnection.
static bool isDisconnected(HRESULT hr) {
    return hr == HRESULT_FROM_WIN32(RPC_S_SERVER_UNAVAILABLE) || hr == HRESULT_FROM_WIN32(RPC_S_CALL_FAILED) ||
        hr == RPC_E_DISCONNECTED || hr == CO_E_OBJNOTCONNECTED;
}

// Runs [call] on the cached manager; on a dead proxy, drops it and retries once on a fresh one.
template <typename F>
static HRESULT callManager(F &&call) {
    ComPtr<IWidgetManager> manager;
    HRESULT hr = getManager(manager);
    if (FAILED(hr)) return hr;
    hr = call(manager.Get());
    if (!isDisconnected(hr)) return hr;
    {
        std::lock_guard<std::mutex> lock(g_managerMutex);
        if (g_manager == manager) g_manager.Reset();
    }
    hr = getManager(manager);
    return FAILED(hr) ? hr : call(manager.Get());
}

// ============================================================================
// WidgetContext
// ============================================================================

struct Context {
    std::wstring id;
    std::wstring definitionId;
    int size = 0;
    bool isActive = false;
};

static HRESULT readContext(IWidgetContext *ctx, Context &out) {
    if (!ctx) return E_POINTER;
    HString id, def;
    WidgetSize size = WidgetSize_Small;
    boolean active = false;
    HRESULT hr = ctx->get_Id(id.GetAddressOf());
    if (SUCCEEDED(hr)) hr = ctx->get_DefinitionId(def.GetAddressOf());
    if (SUCCEEDED(hr)) hr = ctx->get_Size(&size);
    if (SUCCEEDED(hr)) hr = ctx->get_IsActive(&active);
    if (FAILED(hr)) return hr;
    out.id = toWString(id.Get());
    out.definitionId = toWString(def.Get());
    out.size = (int)size;
    out.isActive = active != 0;
    return S_OK;
}

// ============================================================================
// Provider COM server
// ============================================================================

// Shared by every provider object a factory creates: the Kotlin dispatcher and
// its method ids. Lives as long as the factory or any provider object.
struct Dispatcher {
    std::atomic<ULONG> refs{1};
    jobject target = nullptr; // global ref
    jmethodID createWidget = nullptr;
    jmethodID deleteWidget = nullptr;
    jmethodID onActionInvoked = nullptr;
    jmethodID onWidgetContextChanged = nullptr;
    jmethodID activate = nullptr;
    jmethodID deactivate = nullptr;
    jmethodID onCustomizationRequested = nullptr;
    jmethodID onAnalyticsInfoReported = nullptr;
    jmethodID onErrorInfoReported = nullptr;
    jmethodID onMessageReceived = nullptr;
    jmethodID onResourceRequested = nullptr;
    int flags = 0;

    void addRef() { refs++; }
    void release() {
        if (--refs == 0) {
            ScopedEnv env;
            if (env.get() && target) env.get()->DeleteGlobalRef(target);
            delete this;
        }
    }
};

// Kotlin returns false when the provider threw; surface that to the host.
static HRESULT finish(JNIEnv *env, jboolean ok) {
    // The dispatcher catches provider exceptions itself; anything left pending is reported, not dropped.
    if (nucleus_jni_clear_exception(env)) return E_FAIL;
    return ok ? S_OK : E_FAIL;
}

class WidgetProviderCom final : public IWidgetProvider,
                                public IWidgetProvider2,
                                public IWidgetProviderAnalytics,
                                public IWidgetProviderErrors,
                                public IWidgetProviderMessage,
                                public IWidgetResourceProvider,
                                public IAgileObject {
    std::atomic<ULONG> _refs{1};
    Dispatcher *_d;

    ~WidgetProviderCom() { _d->release(); }

    bool has(int flag) const { return (_d->flags & flag) != 0; }

    // Calls a (String id, String definitionId, int size, boolean isActive, extra...) dispatcher method.
    template <typename... Extra>
    HRESULT callWithContext(IWidgetContext *ctx, jmethodID method, Extra... extra) {
        Context c;
        HRESULT hr = readContext(ctx, c);
        if (FAILED(hr)) return hr;
        ScopedEnv scoped;
        JNIEnv *env = scoped.get();
        if (!env) return E_UNEXPECTED;
        jstring id = toJString(env, c.id);
        jstring def = toJString(env, c.definitionId);
        jboolean ok = env->CallBooleanMethod(
            _d->target, method, id, def, (jint)c.size, (jboolean)c.isActive, convert(env, extra)...);
        return finish(env, ok);
    }

    static jstring convert(JNIEnv *env, HSTRING s) { return toJString(env, s); }
    static jlong convert(JNIEnv *, jlong v) { return v; }

public:
    explicit WidgetProviderCom(Dispatcher *d) : _d(d) { _d->addRef(); }

    // IUnknown
    IFACEMETHODIMP QueryInterface(REFIID riid, void **ppv) override {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        trace(L"provider QI", riid);
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IInspectable) || riid == __uuidof(IWidgetProvider)) {
            *ppv = static_cast<IWidgetProvider *>(this);
        } else if (riid == __uuidof(IWidgetProvider2) && has(FLAG_PROVIDER2)) {
            *ppv = static_cast<IWidgetProvider2 *>(this);
        } else if (riid == __uuidof(IWidgetProviderAnalytics) && has(FLAG_ANALYTICS)) {
            *ppv = static_cast<IWidgetProviderAnalytics *>(this);
        } else if (riid == __uuidof(IWidgetProviderErrors) && has(FLAG_ERRORS)) {
            *ppv = static_cast<IWidgetProviderErrors *>(this);
        } else if (riid == __uuidof(IWidgetProviderMessage) && has(FLAG_MESSAGE)) {
            *ppv = static_cast<IWidgetProviderMessage *>(this);
        } else if (riid == __uuidof(IWidgetResourceProvider) && has(FLAG_RESOURCE)) {
            *ppv = static_cast<IWidgetResourceProvider *>(this);
        } else if (riid == __uuidof(IAgileObject)) {
            *ppv = static_cast<IAgileObject *>(this);
        } else {
            return E_NOINTERFACE;
        }
        AddRef();
        return S_OK;
    }
    IFACEMETHODIMP_(ULONG) AddRef() override { return ++_refs; }
    IFACEMETHODIMP_(ULONG) Release() override {
        ULONG count = --_refs;
        if (count == 0) delete this;
        return count;
    }

    // IInspectable
    IFACEMETHODIMP GetIids(ULONG *count, IID **iids) override {
        std::vector<IID> list{__uuidof(IWidgetProvider)};
        if (has(FLAG_PROVIDER2)) list.push_back(__uuidof(IWidgetProvider2));
        if (has(FLAG_ANALYTICS)) list.push_back(__uuidof(IWidgetProviderAnalytics));
        if (has(FLAG_ERRORS)) list.push_back(__uuidof(IWidgetProviderErrors));
        if (has(FLAG_MESSAGE)) list.push_back(__uuidof(IWidgetProviderMessage));
        if (has(FLAG_RESOURCE)) list.push_back(__uuidof(IWidgetResourceProvider));
        *iids = (IID *)CoTaskMemAlloc(sizeof(IID) * list.size());
        if (!*iids) return E_OUTOFMEMORY;
        memcpy(*iids, list.data(), sizeof(IID) * list.size());
        *count = (ULONG)list.size();
        return S_OK;
    }
    // Must name a type the WinRT metadata marshaler can resolve: the host's stub looks it up and
    // silently drops a provider whose class name is in no .winmd. Like C++/WinRT's `implements`,
    // report the first WinRT interface implemented.
    IFACEMETHODIMP GetRuntimeClassName(HSTRING *name) override {
        static const wchar_t kName[] = L"Microsoft.Windows.Widgets.Providers.IWidgetProvider";
        return WindowsCreateString(kName, ARRAYSIZE(kName) - 1, name);
    }
    IFACEMETHODIMP GetTrustLevel(TrustLevel *level) override {
        *level = BaseTrust;
        return S_OK;
    }

    // IWidgetProvider
    IFACEMETHODIMP CreateWidget(IWidgetContext *widgetContext) override {
        return callWithContext(widgetContext, _d->createWidget);
    }
    IFACEMETHODIMP DeleteWidget(HSTRING widgetId, HSTRING customState) override {
        ScopedEnv scoped;
        JNIEnv *env = scoped.get();
        if (!env) return E_UNEXPECTED;
        jboolean ok = env->CallBooleanMethod(
            _d->target, _d->deleteWidget, toJString(env, widgetId), toJString(env, customState));
        return finish(env, ok);
    }
    IFACEMETHODIMP OnActionInvoked(IWidgetActionInvokedArgs *args) override {
        ComPtr<IWidgetContext> ctx;
        HString verb, data, state;
        HRESULT hr = args->get_WidgetContext(&ctx);
        if (SUCCEEDED(hr)) hr = args->get_Verb(verb.GetAddressOf());
        if (SUCCEEDED(hr)) hr = args->get_Data(data.GetAddressOf());
        if (SUCCEEDED(hr)) hr = args->get_CustomState(state.GetAddressOf());
        if (FAILED(hr)) return hr;
        return callWithContext(ctx.Get(), _d->onActionInvoked, verb.Get(), data.Get(), state.Get());
    }
    IFACEMETHODIMP OnWidgetContextChanged(IWidgetContextChangedArgs *args) override {
        ComPtr<IWidgetContext> ctx;
        HRESULT hr = args->get_WidgetContext(&ctx);
        if (FAILED(hr)) return hr;
        return callWithContext(ctx.Get(), _d->onWidgetContextChanged);
    }
    IFACEMETHODIMP Activate(IWidgetContext *widgetContext) override {
        return callWithContext(widgetContext, _d->activate);
    }
    IFACEMETHODIMP Deactivate(HSTRING widgetId) override {
        ScopedEnv scoped;
        JNIEnv *env = scoped.get();
        if (!env) return E_UNEXPECTED;
        jboolean ok = env->CallBooleanMethod(_d->target, _d->deactivate, toJString(env, widgetId));
        return finish(env, ok);
    }

    // IWidgetProvider2
    IFACEMETHODIMP OnCustomizationRequested(IWidgetCustomizationRequestedArgs *args) override {
        ComPtr<IWidgetContext> ctx;
        HString state;
        HRESULT hr = args->get_WidgetContext(&ctx);
        if (SUCCEEDED(hr)) hr = args->get_CustomState(state.GetAddressOf());
        if (FAILED(hr)) return hr;
        return callWithContext(ctx.Get(), _d->onCustomizationRequested, state.Get());
    }

    // IWidgetProviderAnalytics
    IFACEMETHODIMP OnAnalyticsInfoReported(IWidgetAnalyticsInfoReportedArgs *args) override {
        ComPtr<IWidgetContext> ctx;
        HString json;
        HRESULT hr = args->get_WidgetContext(&ctx);
        if (SUCCEEDED(hr)) hr = args->get_AnalyticsJson(json.GetAddressOf());
        if (FAILED(hr)) return hr;
        return callWithContext(ctx.Get(), _d->onAnalyticsInfoReported, json.Get());
    }

    // IWidgetProviderErrors
    IFACEMETHODIMP OnErrorInfoReported(IWidgetErrorInfoReportedArgs *args) override {
        ComPtr<IWidgetContext> ctx;
        HString json;
        HRESULT hr = args->get_WidgetContext(&ctx);
        if (SUCCEEDED(hr)) hr = args->get_ErrorJson(json.GetAddressOf());
        if (FAILED(hr)) return hr;
        return callWithContext(ctx.Get(), _d->onErrorInfoReported, json.Get());
    }

    // IWidgetProviderMessage
    IFACEMETHODIMP OnMessageReceived(IWidgetMessageReceivedArgs *args) override {
        ComPtr<IWidgetContext> ctx;
        HString message;
        HRESULT hr = args->get_WidgetContext(&ctx);
        if (SUCCEEDED(hr)) hr = args->get_Message(message.GetAddressOf());
        if (FAILED(hr)) return hr;
        return callWithContext(ctx.Get(), _d->onMessageReceived, message.Get());
    }

    // IWidgetResourceProvider: the args stay live (request/response/deferral),
    // so Kotlin gets an owning handle instead of a snapshot.
    IFACEMETHODIMP OnResourceRequested(IWidgetResourceRequestedArgs *args) override {
        ComPtr<IWidgetContext> ctx;
        HRESULT hr = args->get_WidgetContext(&ctx);
        if (FAILED(hr)) return hr;
        ComPtr<IWidgetResourceRequestedArgs> owned(args);
        return callWithContext(ctx.Get(), _d->onResourceRequested, toHandle(owned));
    }
};

class WidgetProviderFactory final : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IClassFactory> {
    Dispatcher *_d;
public:
    explicit WidgetProviderFactory(Dispatcher *d) : _d(d) { _d->addRef(); }
    ~WidgetProviderFactory() override { _d->release(); }

    IFACEMETHODIMP CreateInstance(IUnknown *outer, REFIID riid, void **ppv) override {
        trace(L"factory CreateInstance", riid);
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        auto *provider = new (std::nothrow) WidgetProviderCom(_d);
        if (!provider) return E_OUTOFMEMORY;
        HRESULT hr = provider->QueryInterface(riid, ppv);
        provider->Release();
        return hr;
    }
    IFACEMETHODIMP LockServer(BOOL) override { return S_OK; }
};

static bool lookupDispatcherMethods(JNIEnv *env, jclass cls, Dispatcher *d) {
    const char *ctx = "(Ljava/lang/String;Ljava/lang/String;IZ";
    auto method = [&](const char *name, const std::string &sig) {
        return env->GetMethodID(cls, name, sig.c_str());
    };
    std::string base(ctx);
    d->createWidget = method("createWidget", base + ")Z");
    d->deleteWidget = method("deleteWidget", "(Ljava/lang/String;Ljava/lang/String;)Z");
    d->onActionInvoked = method("onActionInvoked", base + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    d->onWidgetContextChanged = method("onWidgetContextChanged", base + ")Z");
    d->activate = method("activate", base + ")Z");
    d->deactivate = method("deactivate", "(Ljava/lang/String;)Z");
    d->onCustomizationRequested = method("onCustomizationRequested", base + "Ljava/lang/String;)Z");
    d->onAnalyticsInfoReported = method("onAnalyticsInfoReported", base + "Ljava/lang/String;)Z");
    d->onErrorInfoReported = method("onErrorInfoReported", base + "Ljava/lang/String;)Z");
    d->onMessageReceived = method("onMessageReceived", base + "Ljava/lang/String;)Z");
    d->onResourceRequested = method("onResourceRequested", base + "J)Z");
    return !env->ExceptionCheck();
}

// ============================================================================
// Stream helpers (IRandomAccessStreamReference <-> byte[])
// ============================================================================

using OpenReadOperation = WF::IAsyncOperation<WSS::IRandomAccessStreamWithContentType *>;
using OpenReadHandler = WF::IAsyncOperationCompletedHandler<WSS::IRandomAccessStreamWithContentType *>;

static HRESULT readReference(WSS::IRandomAccessStreamReference *ref, std::vector<BYTE> &out) {
    ComPtr<OpenReadOperation> op;
    HRESULT hr = ref->OpenReadAsync(&op);
    if (FAILED(hr)) return hr;

    Event done(CreateEventEx(nullptr, nullptr, CREATE_EVENT_MANUAL_RESET, EVENT_ALL_ACCESS));
    if (!done.IsValid()) return HRESULT_FROM_WIN32(GetLastError());
    auto handler = Callback<Implements<RuntimeClassFlags<ClassicCom>, OpenReadHandler, FtmBase>>(
        [&done](OpenReadOperation *, AsyncStatus) -> HRESULT {
            SetEvent(done.Get());
            return S_OK;
        });
    hr = op->put_Completed(handler.Get());
    if (FAILED(hr)) return hr;
    WaitForSingleObject(done.Get(), INFINITE);

    ComPtr<WSS::IRandomAccessStreamWithContentType> stream;
    hr = op->GetResults(&stream);
    if (FAILED(hr)) return hr;
    ComPtr<IStream> com;
    hr = CreateStreamOverRandomAccessStream(stream.Get(), IID_PPV_ARGS(&com));
    if (FAILED(hr)) return hr;

    out.clear();
    BYTE buffer[64 * 1024];
    ULONG read = 0;
    do {
        hr = com->Read(buffer, sizeof(buffer), &read);
        if (FAILED(hr)) return hr;
        out.insert(out.end(), buffer, buffer + read);
    } while (hr == S_OK && read > 0);
    return S_OK;
}

static HRESULT createReference(const BYTE *data, UINT size, ComPtr<WSS::IRandomAccessStreamReference> &out) {
    ComPtr<IStream> mem;
    mem.Attach(SHCreateMemStream(data, size));
    if (!mem) return E_OUTOFMEMORY;
    ComPtr<WSS::IRandomAccessStream> stream;
    HRESULT hr = CreateRandomAccessStreamOverStream(mem.Get(), BSOS_DEFAULT, IID_PPV_ARGS(&stream));
    if (FAILED(hr)) return hr;
    ComPtr<WSS::IRandomAccessStreamReferenceStatics> statics;
    hr = getFactory(RuntimeClass_Windows_Storage_Streams_RandomAccessStreamReference, statics);
    if (FAILED(hr)) return hr;
    return statics->CreateFromStream(stream.Get(), &out);
}

static jbyteArray referenceToBytes(JNIEnv *env, WSS::IRandomAccessStreamReference *ref) {
    if (!ref) return nullptr;
    std::vector<BYTE> bytes;
    THROW_IF_FAILED(env, readReference(ref, bytes), "Failed to read resource content", nullptr);
    jbyteArray result = env->NewByteArray((jsize)bytes.size());
    if (result && !bytes.empty()) env->SetByteArrayRegion(result, 0, (jsize)bytes.size(), (const jbyte *)bytes.data());
    return result;
}

// Null array -> null reference (clears the content).
static HRESULT bytesToReference(JNIEnv *env, jbyteArray bytes, ComPtr<WSS::IRandomAccessStreamReference> &out) {
    if (!bytes) {
        out.Reset();
        return S_OK;
    }
    jsize len = env->GetArrayLength(bytes);
    std::vector<BYTE> data(len);
    if (len > 0) env->GetByteArrayRegion(bytes, 0, len, (jbyte *)data.data());
    return createReference(data.data(), (UINT)len, out);
}

// ============================================================================
// JNI: lifecycle
// ============================================================================

#define JNI_FN(name) Java_dev_nucleusframework_widget_windows_NativeWidgetBridge_##name

extern "C" {

JNIEXPORT jint JNICALL JNI_FN(nativeInitializeApartment)(JNIEnv *, jclass) {
    HRESULT hr = RoInitialize(RO_INIT_MULTITHREADED);
    return (hr == S_FALSE || hr == RPC_E_CHANGED_MODE) ? S_OK : hr;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsAvailable)(JNIEnv *, jclass) {
    ComPtr<IWidgetManager> manager;
    return SUCCEEDED(getManager(manager)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeRegisterProvider)(
    JNIEnv *env, jclass, jstring jClsid, jobject dispatcher, jint flags
) {
    CLSID clsid;
    {
        JHString text(env, jClsid);
        std::wstring braced = L"{" + toWString(text.get()) + L"}";
        THROW_IF_FAILED(env, CLSIDFromString(braced.c_str(), &clsid), "Invalid provider CLSID", 0);
    }

    auto *d = new Dispatcher();
    d->flags = flags;
    jclass cls = env->GetObjectClass(dispatcher);
    if (!lookupDispatcherMethods(env, cls, d)) {
        delete d;
        return 0; // NoSuchMethodError already pending
    }
    d->target = env->NewGlobalRef(dispatcher);

    auto factory = Make<WidgetProviderFactory>(d);
    d->release(); // the factory owns it now
    if (!factory) {
        throwHr(env, E_OUTOFMEMORY, "Failed to create the provider class factory");
        return 0;
    }

    DWORD cookie = 0;
    THROW_IF_FAILED(env,
        CoRegisterClassObject(clsid, factory.Get(), CLSCTX_LOCAL_SERVER, REGCLS_MULTIPLEUSE, &cookie),
        "CoRegisterClassObject failed", 0);
    return (jlong)cookie;
}

JNIEXPORT void JNICALL JNI_FN(nativeRevokeProvider)(JNIEnv *env, jclass, jlong cookie) {
    THROW_IF_FAILED(env, CoRevokeClassObject((DWORD)cookie), "CoRevokeClassObject failed", );
}

JNIEXPORT void JNICALL JNI_FN(nativeRelease)(JNIEnv *, jclass, jlong handle) {
    if (handle) fromHandle<IUnknown>(handle)->Release();
}

// ============================================================================
// JNI: WidgetManager
// ============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeUpdateWidget)(
    JNIEnv *env, jclass, jstring jWidgetId, jstring jTemplate, jstring jData, jstring jCustomState,
    jint placeholder, jboolean hasRank, jint rank
) {
    ComPtr<IWidgetUpdateRequestOptionsFactory> factory;
    THROW_IF_FAILED(env, getFactory(RuntimeClass_WidgetUpdateRequestOptions, factory),
        "WidgetUpdateRequestOptions is unavailable", );
    ComPtr<IWidgetUpdateRequestOptions> options;
    {
        JHString id(env, jWidgetId);
        THROW_IF_FAILED(env, factory->CreateInstance(id.get(), &options), "WidgetUpdateRequestOptions creation failed", );
    }
    if (jTemplate) {
        JHString value(env, jTemplate);
        THROW_IF_FAILED(env, options->put_Template(value.get()), "put_Template failed", );
    }
    if (jData) {
        JHString value(env, jData);
        THROW_IF_FAILED(env, options->put_Data(value.get()), "put_Data failed", );
    }
    if (jCustomState) {
        JHString value(env, jCustomState);
        THROW_IF_FAILED(env, options->put_CustomState(value.get()), "put_CustomState failed", );
    }

    if (placeholder >= 0 || hasRank) {
        ComPtr<WF::IPropertyValueStatics> values;
        THROW_IF_FAILED(env, getFactory(RuntimeClass_Windows_Foundation_PropertyValue, values),
            "PropertyValue is unavailable", );
        if (placeholder >= 0) {
            ComPtr<IWidgetUpdateRequestOptions2> options2;
            THROW_IF_FAILED(env, options.As(&options2),
                "IsPlaceholderContent requires WidgetContract 9 (Windows App SDK 1.6+)", );
            ComPtr<IInspectable> boxed;
            ComPtr<WF::IReference<bool>> ref;
            THROW_IF_FAILED(env, values->CreateBoolean(placeholder != 0, &boxed), "CreateBoolean failed", );
            THROW_IF_FAILED(env, boxed.As(&ref), "IReference<bool> cast failed", );
            THROW_IF_FAILED(env, options2->put_IsPlaceholderContent(ref.Get()), "put_IsPlaceholderContent failed", );
        }
        if (hasRank) {
            ComPtr<IWidgetUpdateRequestOptions3> options3;
            THROW_IF_FAILED(env, options.As(&options3),
                "Rank requires WidgetContract 11 (Windows App SDK 1.8+)", );
            ComPtr<IInspectable> boxed;
            ComPtr<WF::IReference<int>> ref;
            THROW_IF_FAILED(env, values->CreateInt32(rank, &boxed), "CreateInt32 failed", );
            THROW_IF_FAILED(env, boxed.As(&ref), "IReference<int> cast failed", );
            THROW_IF_FAILED(env, options3->put_Rank(ref.Get()), "put_Rank failed", );
        }
    }

    THROW_IF_FAILED(env, callManager([&](IWidgetManager *manager) { return manager->UpdateWidget(options.Get()); }),
        "UpdateWidget failed", );
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeGetWidgetIds)(JNIEnv *env, jclass) {
    UINT32 count = 0;
    HSTRING *ids = nullptr;
    THROW_IF_FAILED(env, callManager([&](IWidgetManager *manager) { return manager->GetWidgetIds(&count, &ids); }),
        "GetWidgetIds failed", nullptr);
    jobjectArray result = env->NewObjectArray((jsize)count, env->FindClass("java/lang/String"), nullptr);
    for (UINT32 i = 0; i < count; i++) {
        if (result) env->SetObjectArrayElement(result, (jsize)i, toJString(env, ids[i]));
        WindowsDeleteString(ids[i]);
    }
    CoTaskMemFree(ids);
    return result;
}

// WidgetInfo rows, flattened for a reflection-free transfer:
//   strings: id, definitionId, template, data, customState            (5 per info)
//   numbers: size, isActive, lastUpdateMs, placeholder(-1/0/1),
//            hasRank, rank                                            (6 per info)
static const int INFO_STRINGS = 5;
static const int INFO_NUMBERS = 6;
static const INT64 FILETIME_UNIX_EPOCH = 116444736000000000LL;

static HRESULT appendInfo(JNIEnv *env, IWidgetInfo *info, std::vector<jstring> &strings, std::vector<jlong> &numbers) {
    ComPtr<IWidgetContext> ctx;
    HString tmpl, data, state;
    WF::DateTime updated{};
    HRESULT hr = info->get_WidgetContext(&ctx);
    if (SUCCEEDED(hr)) hr = info->get_Template(tmpl.GetAddressOf());
    if (SUCCEEDED(hr)) hr = info->get_Data(data.GetAddressOf());
    if (SUCCEEDED(hr)) hr = info->get_CustomState(state.GetAddressOf());
    if (SUCCEEDED(hr)) hr = info->get_LastUpdateTime(&updated);
    Context c;
    if (SUCCEEDED(hr)) hr = readContext(ctx.Get(), c);
    if (FAILED(hr)) return hr;

    jlong placeholder = -1;
    ComPtr<IWidgetInfo2> info2;
    boolean isPlaceholder = false;
    if (SUCCEEDED(info->QueryInterface(IID_PPV_ARGS(&info2))) &&
        SUCCEEDED(info2->get_IsPlaceholderContent(&isPlaceholder))) {
        placeholder = isPlaceholder ? 1 : 0;
    }
    jlong hasRank = 0;
    INT32 rank = 0;
    ComPtr<IWidgetInfo3> info3;
    if (SUCCEEDED(info->QueryInterface(IID_PPV_ARGS(&info3))) && SUCCEEDED(info3->get_Rank(&rank))) {
        hasRank = 1;
    }

    strings.push_back(toJString(env, c.id));
    strings.push_back(toJString(env, c.definitionId));
    strings.push_back(toJString(env, tmpl.Get()));
    strings.push_back(toJString(env, data.Get()));
    strings.push_back(toJString(env, state.Get()));
    numbers.push_back(c.size);
    numbers.push_back(c.isActive ? 1 : 0);
    numbers.push_back((updated.UniversalTime - FILETIME_UNIX_EPOCH) / 10000);
    numbers.push_back(placeholder);
    numbers.push_back(hasRank);
    numbers.push_back(rank);
    return S_OK;
}

static jobjectArray packInfos(JNIEnv *env, const std::vector<jstring> &strings, const std::vector<jlong> &numbers) {
    jobjectArray strs = env->NewObjectArray((jsize)strings.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < strings.size(); i++) env->SetObjectArrayElement(strs, (jsize)i, strings[i]);
    jlongArray nums = env->NewLongArray((jsize)numbers.size());
    if (!numbers.empty()) env->SetLongArrayRegion(nums, 0, (jsize)numbers.size(), numbers.data());
    jobjectArray result = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(result, 0, strs);
    env->SetObjectArrayElement(result, 1, nums);
    return result;
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeGetWidgetInfo)(JNIEnv *env, jclass, jstring jWidgetId) {
    ComPtr<IWidgetInfo> info;
    {
        JHString id(env, jWidgetId);
        THROW_IF_FAILED(env, callManager([&](IWidgetManager *manager) { return manager->GetWidgetInfo(id.get(), &info); }),
            "GetWidgetInfo failed", nullptr);
    }
    std::vector<jstring> strings;
    std::vector<jlong> numbers;
    if (info) THROW_IF_FAILED(env, appendInfo(env, info.Get(), strings, numbers), "Reading WidgetInfo failed", nullptr);
    return packInfos(env, strings, numbers);
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeGetWidgetInfos)(JNIEnv *env, jclass) {
    UINT32 count = 0;
    IWidgetInfo **infos = nullptr;
    THROW_IF_FAILED(env, callManager([&](IWidgetManager *manager) { return manager->GetWidgetInfos(&count, &infos); }),
        "GetWidgetInfos failed", nullptr);
    std::vector<jstring> strings;
    std::vector<jlong> numbers;
    HRESULT hr = S_OK;
    for (UINT32 i = 0; i < count; i++) {
        if (SUCCEEDED(hr) && infos[i]) hr = appendInfo(env, infos[i], strings, numbers);
        if (infos[i]) infos[i]->Release();
    }
    CoTaskMemFree(infos);
    THROW_IF_FAILED(env, hr, "Reading WidgetInfo failed", nullptr);
    return packInfos(env, strings, numbers);
}

JNIEXPORT void JNICALL JNI_FN(nativeDeleteWidget)(JNIEnv *env, jclass, jstring jWidgetId) {
    JHString id(env, jWidgetId);
    THROW_IF_FAILED(env, callManager([&](IWidgetManager *manager) { return manager->DeleteWidget(id.get()); }),
        "DeleteWidget failed", );
}

JNIEXPORT void JNICALL JNI_FN(nativeSendMessageToContent)(JNIEnv *env, jclass, jstring jWidgetId, jstring jMessage) {
    JHString id(env, jWidgetId);
    JHString message(env, jMessage);
    HRESULT hr = callManager([&](IWidgetManager *manager) {
        ComPtr<IWidgetManager2> manager2;
        HRESULT qi = manager->QueryInterface(IID_PPV_ARGS(&manager2));
        return FAILED(qi) ? qi : manager2->SendMessageToContent(id.get(), message.get());
    });
    THROW_IF_FAILED(env, hr,
        hr == E_NOINTERFACE ? "SendMessageToContent requires WidgetContract 6 (Windows App SDK 1.5+)"
                            : "SendMessageToContent failed", );
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetUnsetValue)(JNIEnv *env, jclass) {
    ComPtr<IWidgetUpdateRequestOptionsStatics> statics;
    THROW_IF_FAILED(env, getFactory(RuntimeClass_WidgetUpdateRequestOptions, statics),
        "WidgetUpdateRequestOptions is unavailable", nullptr);
    HString value;
    THROW_IF_FAILED(env, statics->get_UnsetValue(value.GetAddressOf()), "get_UnsetValue failed", nullptr);
    return toJString(env, value.Get());
}

// ============================================================================
// JNI: WidgetResourceRequestedArgs
// ============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeArgsGetRequest)(JNIEnv *env, jclass, jlong h) {
    ComPtr<IWidgetResourceRequest> request;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceRequestedArgs>(h)->get_Request(&request), "get_Request failed", 0);
    return toHandle(request);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeArgsGetResponse)(JNIEnv *env, jclass, jlong h) {
    ComPtr<IWidgetResourceResponse> response;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceRequestedArgs>(h)->get_Response(&response), "get_Response failed", 0);
    return toHandle(response);
}

JNIEXPORT void JNICALL JNI_FN(nativeArgsSetResponse)(JNIEnv *env, jclass, jlong h, jlong response) {
    THROW_IF_FAILED(env,
        fromHandle<IWidgetResourceRequestedArgs>(h)->put_Response(fromHandle<IWidgetResourceResponse>(response)),
        "put_Response failed", );
}

JNIEXPORT jlong JNICALL JNI_FN(nativeArgsGetDeferral)(JNIEnv *env, jclass, jlong h) {
    ComPtr<WF::IDeferral> deferral;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceRequestedArgs>(h)->GetDeferral(&deferral), "GetDeferral failed", 0);
    return toHandle(deferral);
}

JNIEXPORT void JNICALL JNI_FN(nativeDeferralComplete)(JNIEnv *env, jclass, jlong h) {
    THROW_IF_FAILED(env, fromHandle<WF::IDeferral>(h)->Complete(), "Deferral.Complete failed", );
}

// ============================================================================
// JNI: WidgetResourceRequest
// ============================================================================

JNIEXPORT jstring JNICALL JNI_FN(nativeRequestGetUri)(JNIEnv *env, jclass, jlong h) {
    HString value;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceRequest>(h)->get_Uri(value.GetAddressOf()), "get_Uri failed", nullptr);
    return toJString(env, value.Get());
}

JNIEXPORT jstring JNICALL JNI_FN(nativeRequestGetMethod)(JNIEnv *env, jclass, jlong h) {
    HString value;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceRequest>(h)->get_Method(value.GetAddressOf()), "get_Method failed", nullptr);
    return toJString(env, value.Get());
}

JNIEXPORT void JNICALL JNI_FN(nativeRequestSetMethod)(JNIEnv *env, jclass, jlong h, jstring jValue) {
    JHString value(env, jValue);
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceRequest>(h)->put_Method(value.get()), "put_Method failed", );
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeRequestGetContent)(JNIEnv *env, jclass, jlong h) {
    ComPtr<WSS::IRandomAccessStreamReference> content;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceRequest>(h)->get_Content(&content), "get_Content failed", nullptr);
    return referenceToBytes(env, content.Get());
}

JNIEXPORT void JNICALL JNI_FN(nativeRequestSetContent)(JNIEnv *env, jclass, jlong h, jbyteArray bytes) {
    ComPtr<WSS::IRandomAccessStreamReference> content;
    THROW_IF_FAILED(env, bytesToReference(env, bytes, content), "Creating the content stream failed", );
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceRequest>(h)->put_Content(content.Get()), "put_Content failed", );
}

JNIEXPORT jlong JNICALL JNI_FN(nativeRequestGetHeaders)(JNIEnv *env, jclass, jlong h) {
    ComPtr<WFC::IMap<HSTRING, HSTRING>> headers;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceRequest>(h)->get_Headers(&headers), "get_Headers failed", 0);
    return toHandle(headers);
}

// ============================================================================
// JNI: WidgetResourceResponse
// ============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeResponseCreate)(
    JNIEnv *env, jclass, jbyteArray bytes, jstring jReasonPhrase, jint statusCode
) {
    ComPtr<IWidgetResourceResponseFactory> factory;
    THROW_IF_FAILED(env, getFactory(RuntimeClass_WidgetResourceResponse, factory),
        "WidgetResourceResponse requires WidgetContract 6 (Windows App SDK 1.5+)", 0);
    ComPtr<WSS::IRandomAccessStreamReference> content;
    THROW_IF_FAILED(env, bytesToReference(env, bytes, content), "Creating the content stream failed", 0);
    JHString reason(env, jReasonPhrase);
    ComPtr<IWidgetResourceResponse> response;
    THROW_IF_FAILED(env, factory->CreateInstance(content.Get(), reason.get(), statusCode, &response),
        "WidgetResourceResponse creation failed", 0);
    return toHandle(response);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeResponseGetContent)(JNIEnv *env, jclass, jlong h) {
    ComPtr<WSS::IRandomAccessStreamReference> content;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceResponse>(h)->get_Content(&content), "get_Content failed", nullptr);
    return referenceToBytes(env, content.Get());
}

JNIEXPORT jlong JNICALL JNI_FN(nativeResponseGetHeaders)(JNIEnv *env, jclass, jlong h) {
    ComPtr<WFC::IMap<HSTRING, HSTRING>> headers;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceResponse>(h)->get_Headers(&headers), "get_Headers failed", 0);
    return toHandle(headers);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeResponseGetReasonPhrase)(JNIEnv *env, jclass, jlong h) {
    HString value;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceResponse>(h)->get_ReasonPhrase(value.GetAddressOf()),
        "get_ReasonPhrase failed", nullptr);
    return toJString(env, value.Get());
}

JNIEXPORT jint JNICALL JNI_FN(nativeResponseGetStatusCode)(JNIEnv *env, jclass, jlong h) {
    INT32 value = 0;
    THROW_IF_FAILED(env, fromHandle<IWidgetResourceResponse>(h)->get_StatusCode(&value), "get_StatusCode failed", 0);
    return value;
}

// ============================================================================
// JNI: IMap<String, String>
// ============================================================================

using StringMap = WFC::IMap<HSTRING, HSTRING>;
using StringPair = WFC::IKeyValuePair<HSTRING, HSTRING>;

JNIEXPORT jstring JNICALL JNI_FN(nativeMapLookup)(JNIEnv *env, jclass, jlong h, jstring jKey) {
    JHString key(env, jKey);
    HString value;
    HRESULT hr = fromHandle<StringMap>(h)->Lookup(key.get(), value.GetAddressOf());
    if (hr == E_BOUNDS) return nullptr;
    THROW_IF_FAILED(env, hr, "IMap.Lookup failed", nullptr);
    return toJString(env, value.Get());
}

JNIEXPORT jint JNICALL JNI_FN(nativeMapSize)(JNIEnv *env, jclass, jlong h) {
    unsigned int size = 0;
    THROW_IF_FAILED(env, fromHandle<StringMap>(h)->get_Size(&size), "IMap.Size failed", 0);
    return (jint)size;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeMapHasKey)(JNIEnv *env, jclass, jlong h, jstring jKey) {
    JHString key(env, jKey);
    boolean found = false;
    THROW_IF_FAILED(env, fromHandle<StringMap>(h)->HasKey(key.get(), &found), "IMap.HasKey failed", JNI_FALSE);
    return found ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeMapInsert)(JNIEnv *env, jclass, jlong h, jstring jKey, jstring jValue) {
    JHString key(env, jKey);
    JHString value(env, jValue);
    boolean replaced = false;
    THROW_IF_FAILED(env, fromHandle<StringMap>(h)->Insert(key.get(), value.get(), &replaced), "IMap.Insert failed", JNI_FALSE);
    return replaced ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeMapRemove)(JNIEnv *env, jclass, jlong h, jstring jKey) {
    JHString key(env, jKey);
    THROW_IF_FAILED(env, fromHandle<StringMap>(h)->Remove(key.get()), "IMap.Remove failed", );
}

JNIEXPORT void JNICALL JNI_FN(nativeMapClear)(JNIEnv *env, jclass, jlong h) {
    THROW_IF_FAILED(env, fromHandle<StringMap>(h)->Clear(), "IMap.Clear failed", );
}

// Flattened [key0, value0, key1, value1, ...].
JNIEXPORT jobjectArray JNICALL JNI_FN(nativeMapEntries)(JNIEnv *env, jclass, jlong h) {
    ComPtr<WFC::IIterable<StringPair *>> iterable;
    THROW_IF_FAILED(env, fromHandle<StringMap>(h)->QueryInterface(IID_PPV_ARGS(&iterable)), "IMap is not iterable", nullptr);
    ComPtr<WFC::IIterator<StringPair *>> it;
    THROW_IF_FAILED(env, iterable->First(&it), "IIterable.First failed", nullptr);
    std::vector<jstring> flat;
    boolean hasCurrent = false;
    THROW_IF_FAILED(env, it->get_HasCurrent(&hasCurrent), "IIterator.HasCurrent failed", nullptr);
    while (hasCurrent) {
        ComPtr<StringPair> pair;
        HString key, value;
        THROW_IF_FAILED(env, it->get_Current(&pair), "IIterator.Current failed", nullptr);
        THROW_IF_FAILED(env, pair->get_Key(key.GetAddressOf()), "IKeyValuePair.Key failed", nullptr);
        THROW_IF_FAILED(env, pair->get_Value(value.GetAddressOf()), "IKeyValuePair.Value failed", nullptr);
        flat.push_back(toJString(env, key.Get()));
        flat.push_back(toJString(env, value.Get()));
        THROW_IF_FAILED(env, it->MoveNext(&hasCurrent), "IIterator.MoveNext failed", nullptr);
    }
    jobjectArray result = env->NewObjectArray((jsize)flat.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < flat.size(); i++) env->SetObjectArrayElement(result, (jsize)i, flat[i]);
    return result;
}

} // extern "C"
