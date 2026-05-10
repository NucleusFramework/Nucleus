// nucleus_scheduler.cpp — Windows Task Scheduler 2.0 COM API JNI bridge
//
// Replaces the fragile schtasks.exe CLI approach with direct COM calls
// (ITaskService, ITaskFolder, ITaskDefinition, ITrigger, IExecAction, etc.)
//
// Linked libraries: taskschd.lib ole32.lib oleaut32.lib

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <jni.h>
#include <windows.h>
#include <taskschd.h>
#include <wrl/client.h>
#include <string>
#include <vector>

#pragma comment(lib, "taskschd.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")

using Microsoft::WRL::ComPtr;

// ─── Globals ────────────────────────────────────────────────────────────────

static JavaVM *g_jvm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

// ─── BSTR RAII wrapper ──────────────────────────────────────────────────────

class BStr {
    BSTR _b = nullptr;
public:
    explicit BStr(const wchar_t *s) { if (s && *s) _b = SysAllocString(s); }
    explicit BStr(const std::wstring &s) : BStr(s.c_str()) {}
    ~BStr() { if (_b) SysFreeString(_b); }
    operator BSTR() const { return _b; }
    BStr(const BStr &) = delete;
    BStr &operator=(const BStr &) = delete;
};

// ─── Helpers ────────────────────────────────────────────────────────────────

static std::wstring toWString(JNIEnv *env, jstring jstr) {
    if (!jstr) return L"";
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    jsize len = env->GetStringLength(jstr);
    std::wstring result(reinterpret_cast<const wchar_t *>(chars), len);
    env->ReleaseStringChars(jstr, chars);
    return result;
}

static jstring toJString(JNIEnv *env, const wchar_t *wstr) {
    if (!wstr) return env->NewStringUTF("");
    return env->NewString(reinterpret_cast<const jchar *>(wstr),
                          static_cast<jsize>(wcslen(wstr)));
}

static jstring hresultError(JNIEnv *env, const char *ctx, HRESULT hr) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%s: HRESULT 0x%08lX",
             ctx, static_cast<unsigned long>(hr));
    return env->NewStringUTF(buf);
}

static VARIANT emptyVar() {
    VARIANT v;
    VariantInit(&v);
    return v;
}

static VARIANT longVar(LONG val) {
    VARIANT v;
    VariantInit(&v);
    v.vt = VT_I4;
    v.lVal = val;
    return v;
}

// ─── COM scope (per-call init/uninit) ───────────────────────────────────────

struct ComScope {
    bool needsUninit;
    ComScope() {
        HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        needsUninit = (hr == S_OK || hr == S_FALSE);
    }
    ~ComScope() { if (needsUninit) CoUninitialize(); }
};

// ─── Service + Folder ───────────────────────────────────────────────────────

static HRESULT connectService(ComPtr<ITaskService> &svc) {
    HRESULT hr = CoCreateInstance(
        CLSID_TaskScheduler, nullptr, CLSCTX_INPROC_SERVER,
        IID_ITaskService, reinterpret_cast<void **>(svc.GetAddressOf()));
    if (FAILED(hr)) return hr;
    VARIANT e = emptyVar();
    return svc->Connect(e, e, e, e);
}

static HRESULT getOrCreateFolder(ITaskService *svc,
                                  const std::wstring &path,
                                  ComPtr<ITaskFolder> &out) {
    HRESULT hr = svc->GetFolder(BStr(path), out.GetAddressOf());
    if (SUCCEEDED(hr)) return hr;

    // Build path incrementally: \Nucleus\AppId
    ComPtr<ITaskFolder> root;
    hr = svc->GetFolder(BStr(L"\\"), root.GetAddressOf());
    if (FAILED(hr)) return hr;

    ComPtr<ITaskFolder> current = root;
    std::wstring remaining = path;
    if (!remaining.empty() && remaining[0] == L'\\')
        remaining.erase(0, 1);

    size_t pos = 0;
    while (pos < remaining.size()) {
        size_t sep = remaining.find(L'\\', pos);
        std::wstring comp = (sep == std::wstring::npos)
            ? remaining.substr(pos)
            : remaining.substr(pos, sep - pos);
        pos = (sep == std::wstring::npos) ? remaining.size() : sep + 1;
        if (comp.empty()) continue;

        ComPtr<ITaskFolder> child;
        hr = current->GetFolder(BStr(comp), child.GetAddressOf());
        if (FAILED(hr)) {
            VARIANT sd = emptyVar();
            hr = current->CreateFolder(BStr(comp), sd, child.GetAddressOf());
            if (hr == HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)) {
                // Race condition: another process created it
                hr = current->GetFolder(BStr(comp), child.GetAddressOf());
            }
            if (FAILED(hr)) return hr;
        }
        current = child;
    }
    out = current;
    return S_OK;
}

static HRESULT getFolder(ITaskService *svc,
                          const std::wstring &path,
                          ComPtr<ITaskFolder> &out) {
    return svc->GetFolder(BStr(path), out.GetAddressOf());
}

// ─── ISO 8601 start boundary from current time ─────────────────────────────

static std::wstring nowBoundary() {
    SYSTEMTIME st;
    GetLocalTime(&st);
    wchar_t buf[64];
    swprintf_s(buf, 64, L"%04d-%02d-%02dT%02d:%02d:%02d",
               st.wYear, st.wMonth, st.wDay,
               st.wHour, st.wMinute, st.wSecond);
    return buf;
}

static std::wstring nowPlusMinutesBoundary(int minutes) {
    if (minutes <= 0) return nowBoundary();
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    ULARGE_INTEGER uli;
    uli.LowPart = ft.dwLowDateTime;
    uli.HighPart = ft.dwHighDateTime;
    uli.QuadPart += static_cast<ULONGLONG>(minutes) * 60 * 10000000ULL;
    ft.dwLowDateTime = uli.LowPart;
    ft.dwHighDateTime = uli.HighPart;
    SYSTEMTIME stUtc, stLocal;
    FileTimeToSystemTime(&ft, &stUtc);
    SystemTimeToTzSpecificLocalTime(nullptr, &stUtc, &stLocal);
    wchar_t buf[64];
    swprintf_s(buf, 64, L"%04d-%02d-%02dT%02d:%02d:%02d",
               stLocal.wYear, stLocal.wMonth, stLocal.wDay,
               stLocal.wHour, stLocal.wMinute, stLocal.wSecond);
    return buf;
}

static std::wstring todayAtBoundary(int hour, int minute) {
    SYSTEMTIME st;
    GetLocalTime(&st);
    wchar_t buf[64];
    swprintf_s(buf, 64, L"%04d-%02d-%02dT%02d:%02d:00",
               st.wYear, st.wMonth, st.wDay, hour, minute);
    return buf;
}

// ─── DATE (OLE Automation) → epoch ms ───────────────────────────────────────

static jlong dateToEpochMs(DATE date) {
    if (date == 0.0) return 0;
    // VariantTimeToSystemTime returns LOCAL time
    SYSTEMTIME stLocal;
    if (!VariantTimeToSystemTime(date, &stLocal)) return 0;
    // Convert local → UTC before SystemTimeToFileTime (which expects UTC)
    SYSTEMTIME stUtc;
    if (!TzSpecificLocalTimeToSystemTime(nullptr, &stLocal, &stUtc)) return 0;
    FILETIME ft;
    if (!SystemTimeToFileTime(&stUtc, &ft)) return 0;
    ULARGE_INTEGER uli;
    uli.LowPart = ft.dwLowDateTime;
    uli.HighPart = ft.dwHighDateTime;
    // 100-ns intervals from 1601-01-01 to 1970-01-01
    static const ULONGLONG EPOCH_DIFF = 116444736000000000ULL;
    if (uli.QuadPart < EPOCH_DIFF) return 0;
    return static_cast<jlong>((uli.QuadPart - EPOCH_DIFF) / 10000);
}

// ─── Core task creation ─────────────────────────────────────────────────────

enum TriggerKind { TK_PERIODIC, TK_DAILY, TK_WEEKLY, TK_LOGON, TK_ONCE };

struct TriggerConfig {
    TriggerKind kind;
    int intervalMinutes;        // TK_PERIODIC
    int startDelayMinutes;      // TK_PERIODIC: delay before first trigger (0 = immediate)
    int hour;                   // TK_DAILY, TK_WEEKLY
    int minute;                 // TK_DAILY, TK_WEEKLY
    int daysOfWeek;             // TK_WEEKLY (Task Scheduler bitmask)
    std::wstring startBoundary; // TK_ONCE
};

static HRESULT applyTrigger(ITaskDefinition *task, const TriggerConfig &cfg) {
    ComPtr<ITriggerCollection> triggers;
    HRESULT hr = task->get_Triggers(&triggers);
    if (FAILED(hr)) return hr;

    ComPtr<ITrigger> trigger;

    switch (cfg.kind) {
    case TK_PERIODIC: {
        hr = triggers->Create(TASK_TRIGGER_TIME, &trigger);
        if (FAILED(hr)) return hr;

        ComPtr<ITimeTrigger> tt;
        hr = trigger->QueryInterface(IID_PPV_ARGS(&tt));
        if (FAILED(hr)) return hr;

        std::wstring boundary = nowPlusMinutesBoundary(cfg.startDelayMinutes);
        tt->put_StartBoundary(BStr(boundary));

        ComPtr<IRepetitionPattern> rep;
        hr = tt->get_Repetition(&rep);
        if (FAILED(hr)) return hr;

        wchar_t interval[32];
        if (cfg.intervalMinutes >= 60 && cfg.intervalMinutes % 60 == 0)
            swprintf_s(interval, 32, L"PT%dH", cfg.intervalMinutes / 60);
        else
            swprintf_s(interval, 32, L"PT%dM", cfg.intervalMinutes);
        rep->put_Interval(BStr(interval));
        rep->put_Duration(BStr(L"")); // Indefinite
        rep->put_StopAtDurationEnd(VARIANT_FALSE);
        break;
    }

    case TK_DAILY: {
        hr = triggers->Create(TASK_TRIGGER_DAILY, &trigger);
        if (FAILED(hr)) return hr;

        ComPtr<IDailyTrigger> dt;
        hr = trigger->QueryInterface(IID_PPV_ARGS(&dt));
        if (FAILED(hr)) return hr;

        dt->put_DaysInterval(1);
        std::wstring boundary = todayAtBoundary(cfg.hour, cfg.minute);
        dt->put_StartBoundary(BStr(boundary));
        break;
    }

    case TK_WEEKLY: {
        hr = triggers->Create(TASK_TRIGGER_WEEKLY, &trigger);
        if (FAILED(hr)) return hr;

        ComPtr<IWeeklyTrigger> wt;
        hr = trigger->QueryInterface(IID_PPV_ARGS(&wt));
        if (FAILED(hr)) return hr;

        wt->put_DaysOfWeek(static_cast<short>(cfg.daysOfWeek));
        wt->put_WeeksInterval(1);
        std::wstring boundary = todayAtBoundary(cfg.hour, cfg.minute);
        wt->put_StartBoundary(BStr(boundary));
        break;
    }

    case TK_LOGON: {
        hr = triggers->Create(TASK_TRIGGER_LOGON, &trigger);
        if (FAILED(hr)) return hr;
        break;
    }

    case TK_ONCE: {
        hr = triggers->Create(TASK_TRIGGER_TIME, &trigger);
        if (FAILED(hr)) return hr;

        ComPtr<ITimeTrigger> tt;
        hr = trigger->QueryInterface(IID_PPV_ARGS(&tt));
        if (FAILED(hr)) return hr;

        tt->put_StartBoundary(BStr(cfg.startBoundary));
        break;
    }
    }

    return S_OK;
}

static jstring createTaskInternal(
    JNIEnv *env,
    const std::wstring &folderPath,
    const std::wstring &taskName,
    const std::wstring &exePath,
    const std::wstring &arguments,
    const TriggerConfig &triggerCfg)
{
    ComScope com;

    ComPtr<ITaskService> svc;
    HRESULT hr = connectService(svc);
    if (FAILED(hr)) return hresultError(env, "Connect", hr);

    ComPtr<ITaskFolder> folder;
    hr = getOrCreateFolder(svc.Get(), folderPath, folder);
    if (FAILED(hr)) return hresultError(env, "GetOrCreateFolder", hr);

    ComPtr<ITaskDefinition> task;
    hr = svc->NewTask(0, &task);
    if (FAILED(hr)) return hresultError(env, "NewTask", hr);

    // Registration info
    {
        ComPtr<IRegistrationInfo> info;
        if (SUCCEEDED(task->get_RegistrationInfo(&info))) {
            info->put_Author(BStr(L"Nucleus"));
        }
    }

    // Principal: interactive logon, limited privileges
    {
        ComPtr<IPrincipal> principal;
        if (SUCCEEDED(task->get_Principal(&principal))) {
            principal->put_LogonType(TASK_LOGON_INTERACTIVE_TOKEN);
            principal->put_RunLevel(TASK_RUNLEVEL_LUA);
        }
    }

    // Settings
    {
        ComPtr<ITaskSettings> settings;
        if (SUCCEEDED(task->get_Settings(&settings))) {
            settings->put_StartWhenAvailable(VARIANT_TRUE);
            settings->put_DisallowStartIfOnBatteries(VARIANT_FALSE);
            settings->put_StopIfGoingOnBatteries(VARIANT_FALSE);
            settings->put_AllowHardTerminate(VARIANT_TRUE);
            settings->put_ExecutionTimeLimit(BStr(L"PT1H"));
        }
    }

    // Trigger
    hr = applyTrigger(task.Get(), triggerCfg);
    if (FAILED(hr)) return hresultError(env, "ApplyTrigger", hr);

    // Action
    {
        ComPtr<IActionCollection> actions;
        hr = task->get_Actions(&actions);
        if (FAILED(hr)) return hresultError(env, "get_Actions", hr);

        ComPtr<IAction> action;
        hr = actions->Create(TASK_ACTION_EXEC, &action);
        if (FAILED(hr)) return hresultError(env, "CreateAction", hr);

        ComPtr<IExecAction> exec;
        hr = action->QueryInterface(IID_PPV_ARGS(&exec));
        if (FAILED(hr)) return hresultError(env, "QI(IExecAction)", hr);

        exec->put_Path(BStr(exePath));
        if (!arguments.empty()) {
            exec->put_Arguments(BStr(arguments));
        }
    }

    // Register
    VARIANT empty = emptyVar();
    ComPtr<IRegisteredTask> regTask;
    hr = folder->RegisterTaskDefinition(
        BStr(taskName), task.Get(),
        TASK_CREATE_OR_UPDATE,
        empty, empty,
        TASK_LOGON_INTERACTIVE_TOKEN,
        empty,
        regTask.GetAddressOf());
    if (FAILED(hr)) return hresultError(env, "RegisterTaskDefinition", hr);

    return nullptr; // Success
}

// ─── JNI prefix ─────────────────────────────────────────────────────────────
// Package: dev.nucleusframework.nucleus.scheduler.internal
// Class:   WindowsTaskSchedulerJni

// Double indirection so JNI_CLASS is expanded before ## concatenation
#define PASTE_(a, b) a##b
#define PASTE(a, b)  PASTE_(a, b)
#define JNI_CLASS Java_dev_nucleusframework_nucleus_scheduler_internal_WindowsTaskSchedulerJni_
#define JNI_FN(name) JNIEXPORT auto JNICALL PASTE(JNI_CLASS, name)

// ─── Task creation ──────────────────────────────────────────────────────────

extern "C" JNI_FN(nativeCreatePeriodicTask)(
    JNIEnv *env, jclass,
    jstring jFolder, jstring jName,
    jstring jExe, jstring jArgs,
    jint intervalMinutes, jint startDelayMinutes) -> jstring
{
    TriggerConfig cfg{};
    cfg.kind = TK_PERIODIC;
    cfg.intervalMinutes = intervalMinutes;
    cfg.startDelayMinutes = startDelayMinutes;
    return createTaskInternal(env,
        toWString(env, jFolder), toWString(env, jName),
        toWString(env, jExe), toWString(env, jArgs), cfg);
}

extern "C" JNI_FN(nativeCreateDailyTask)(
    JNIEnv *env, jclass,
    jstring jFolder, jstring jName,
    jstring jExe, jstring jArgs,
    jint hour, jint minute) -> jstring
{
    TriggerConfig cfg{};
    cfg.kind = TK_DAILY;
    cfg.hour = hour;
    cfg.minute = minute;
    return createTaskInternal(env,
        toWString(env, jFolder), toWString(env, jName),
        toWString(env, jExe), toWString(env, jArgs), cfg);
}

extern "C" JNI_FN(nativeCreateWeeklyTask)(
    JNIEnv *env, jclass,
    jstring jFolder, jstring jName,
    jstring jExe, jstring jArgs,
    jint daysOfWeek, jint hour, jint minute) -> jstring
{
    TriggerConfig cfg{};
    cfg.kind = TK_WEEKLY;
    cfg.daysOfWeek = daysOfWeek;
    cfg.hour = hour;
    cfg.minute = minute;
    return createTaskInternal(env,
        toWString(env, jFolder), toWString(env, jName),
        toWString(env, jExe), toWString(env, jArgs), cfg);
}

extern "C" JNI_FN(nativeCreateLogonTask)(
    JNIEnv *env, jclass,
    jstring jFolder, jstring jName,
    jstring jExe, jstring jArgs) -> jstring
{
    TriggerConfig cfg{};
    cfg.kind = TK_LOGON;
    return createTaskInternal(env,
        toWString(env, jFolder), toWString(env, jName),
        toWString(env, jExe), toWString(env, jArgs), cfg);
}

extern "C" JNI_FN(nativeCreateOnceTask)(
    JNIEnv *env, jclass,
    jstring jFolder, jstring jName,
    jstring jExe, jstring jArgs,
    jstring jStartBoundary) -> jstring
{
    TriggerConfig cfg{};
    cfg.kind = TK_ONCE;
    cfg.startBoundary = toWString(env, jStartBoundary);
    return createTaskInternal(env,
        toWString(env, jFolder), toWString(env, jName),
        toWString(env, jExe), toWString(env, jArgs), cfg);
}

// ─── Deletion ───────────────────────────────────────────────────────────────

extern "C" JNI_FN(nativeDeleteTask)(
    JNIEnv *env, jclass,
    jstring jFolder, jstring jName) -> jstring
{
    ComScope com;

    ComPtr<ITaskService> svc;
    HRESULT hr = connectService(svc);
    if (FAILED(hr)) return hresultError(env, "Connect", hr);

    ComPtr<ITaskFolder> folder;
    hr = getFolder(svc.Get(), toWString(env, jFolder), folder);
    if (FAILED(hr)) return hresultError(env, "GetFolder", hr);

    hr = folder->DeleteTask(BStr(toWString(env, jName)), 0);
    if (FAILED(hr)) return hresultError(env, "DeleteTask", hr);

    return nullptr;
}

extern "C" JNI_FN(nativeDeleteFolder)(
    JNIEnv *env, jclass,
    jstring jFolder) -> jstring
{
    ComScope com;

    ComPtr<ITaskService> svc;
    HRESULT hr = connectService(svc);
    if (FAILED(hr)) return hresultError(env, "Connect", hr);

    // Get parent folder and delete the subfolder
    std::wstring path = toWString(env, jFolder);

    // Find last backslash to split parent/child
    size_t lastSep = path.rfind(L'\\');
    if (lastSep == std::wstring::npos || lastSep == 0) {
        // Top-level folder: parent is root
        ComPtr<ITaskFolder> root;
        hr = svc->GetFolder(BStr(L"\\"), root.GetAddressOf());
        if (FAILED(hr)) return hresultError(env, "GetFolder(root)", hr);
        hr = root->DeleteFolder(BStr(path.c_str() + (path[0] == L'\\' ? 1 : 0)), 0);
    } else {
        std::wstring parent = path.substr(0, lastSep);
        std::wstring child = path.substr(lastSep + 1);
        ComPtr<ITaskFolder> parentFolder;
        hr = svc->GetFolder(BStr(parent), parentFolder.GetAddressOf());
        if (FAILED(hr)) return hresultError(env, "GetFolder(parent)", hr);
        hr = parentFolder->DeleteFolder(BStr(child), 0);
    }

    if (FAILED(hr)) return hresultError(env, "DeleteFolder", hr);
    return nullptr;
}

// ─── Query ──────────────────────────────────────────────────────────────────

extern "C" JNI_FN(nativeTaskExists)(
    JNIEnv *env, jclass,
    jstring jFolder, jstring jName) -> jboolean
{
    ComScope com;

    ComPtr<ITaskService> svc;
    if (FAILED(connectService(svc))) return JNI_FALSE;

    ComPtr<ITaskFolder> folder;
    if (FAILED(getFolder(svc.Get(), toWString(env, jFolder), folder)))
        return JNI_FALSE;

    ComPtr<IRegisteredTask> task;
    HRESULT hr = folder->GetTask(BStr(toWString(env, jName)), task.GetAddressOf());
    return SUCCEEDED(hr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNI_FN(nativeGetTaskState)(
    JNIEnv *env, jclass,
    jstring jFolder, jstring jName) -> jint
{
    ComScope com;

    ComPtr<ITaskService> svc;
    if (FAILED(connectService(svc))) return -1;

    ComPtr<ITaskFolder> folder;
    if (FAILED(getFolder(svc.Get(), toWString(env, jFolder), folder)))
        return -1;

    ComPtr<IRegisteredTask> task;
    if (FAILED(folder->GetTask(BStr(toWString(env, jName)), task.GetAddressOf())))
        return -1;

    TASK_STATE state;
    if (FAILED(task->get_State(&state))) return -1;

    return static_cast<jint>(state);
}

extern "C" JNI_FN(nativeGetTaskNextRunTime)(
    JNIEnv *env, jclass,
    jstring jFolder, jstring jName) -> jlong
{
    ComScope com;

    ComPtr<ITaskService> svc;
    if (FAILED(connectService(svc))) return 0;

    ComPtr<ITaskFolder> folder;
    if (FAILED(getFolder(svc.Get(), toWString(env, jFolder), folder)))
        return 0;

    ComPtr<IRegisteredTask> task;
    if (FAILED(folder->GetTask(BStr(toWString(env, jName)), task.GetAddressOf())))
        return 0;

    DATE nextRun = 0.0;
    if (FAILED(task->get_NextRunTime(&nextRun))) return 0;

    return dateToEpochMs(nextRun);
}

extern "C" JNI_FN(nativeGetTaskNames)(
    JNIEnv *env, jclass,
    jstring jFolder) -> jobjectArray
{
    ComScope com;

    ComPtr<ITaskService> svc;
    if (FAILED(connectService(svc))) return nullptr;

    ComPtr<ITaskFolder> folder;
    if (FAILED(getFolder(svc.Get(), toWString(env, jFolder), folder)))
        return nullptr;

    ComPtr<IRegisteredTaskCollection> tasks;
    // 0 = TASK_ENUM_HIDDEN excluded
    if (FAILED(folder->GetTasks(0, tasks.GetAddressOf())))
        return nullptr;

    LONG count = 0;
    tasks->get_Count(&count);

    std::vector<std::wstring> names;
    names.reserve(count);

    for (LONG i = 1; i <= count; ++i) {
        ComPtr<IRegisteredTask> task;
        if (FAILED(tasks->get_Item(longVar(i), task.GetAddressOf())))
            continue;
        BSTR bstrName = nullptr;
        if (SUCCEEDED(task->get_Name(&bstrName)) && bstrName) {
            names.emplace_back(bstrName);
            SysFreeString(bstrName);
        }
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(names.size()), stringClass, nullptr);
    for (size_t i = 0; i < names.size(); ++i) {
        env->SetObjectArrayElement(result, static_cast<jsize>(i),
            toJString(env, names[i].c_str()));
    }
    return result;
}
