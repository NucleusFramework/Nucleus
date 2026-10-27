// Network connectivity, metered status and change monitoring on Windows.
//
// Uses the Network List Manager, the API behind the taskbar's network icon:
//   - INetworkListManager::GetConnectivity: NLM_CONNECTIVITY flags (NCSI-probed)
//   - the per-connection property bag (NA_InternetConnectivityV4/V6): captive portal
//   - INetworkCostManager::GetCost: metered status
//
// Level mapping, following the NLM_CONNECTIVITY documentation:
//   *_INTERNET                 -> FULL
//   web hijack (captive portal)-> CAPTIVE_PORTAL
//   *_LOCALNETWORK (routed)    -> LIMITED
//   *_SUBNET                   -> LOCAL
//   *_NOTRAFFIC / DISCONNECTED -> NONE
// "available" means a routed network (LIMITED and up), like Linux's default route.
//
// The change monitor runs its own STA thread with a message pump: NLM delivers the
// INetworkListManagerEvents / INetworkConnectionEvents / INetworkCostManagerEvents
// callbacks there. A callback only marks the state dirty; the pump re-queries
// everything once the pending messages are drained and calls back into Java only
// when the state actually changed (NLM fires bursts of identical events).

#include "nucleus_system_info_common.h"
#include "../../../../../native-common/nucleus_jni.h"

#include <objbase.h>
#include <ocidl.h>
#include <netlistmgr.h>

// Codes mirrored in ConnectivityMonitor.kt (connectivityInfoOf).
#define METERED_UNKNOWN 0
#define METERED_NO 1
#define METERED_YES 2

#define LEVEL_UNKNOWN 0
#define LEVEL_NONE 1
#define LEVEL_LOCAL 2
#define LEVEL_LIMITED 3
#define LEVEL_PORTAL 4
#define LEVEL_FULL 5

// NLM_INTERNET_CONNECTIVITY_WEBHIJACK (Windows 10 SDK), redefined to build against older headers.
#define INTERNET_CONNECTIVITY_WEBHIJACK 0x1

#define CONNECTIVITY_INTERNET (NLM_CONNECTIVITY_IPV4_INTERNET | NLM_CONNECTIVITY_IPV6_INTERNET)
#define CONNECTIVITY_ROUTED (NLM_CONNECTIVITY_IPV4_LOCALNETWORK | NLM_CONNECTIVITY_IPV6_LOCALNETWORK)
#define CONNECTIVITY_SUBNET (NLM_CONNECTIVITY_IPV4_SUBNET | NLM_CONNECTIVITY_IPV6_SUBNET)

typedef struct {
    int available;
    int metered;
    int level;
} NetState;

static const NetState UNKNOWN_STATE = { 0, METERED_UNKNOWN, LEVEL_UNKNOWN };

static int has_flag(IPropertyBag *bag, LPCOLESTR name, DWORD flag) {
    VARIANT value;
    VariantInit(&value);
    int found = SUCCEEDED(bag->lpVtbl->Read(bag, name, &value, NULL)) &&
                value.vt == VT_UI4 && (value.ulVal & flag);
    VariantClear(&value);
    return found;
}

// NCSI flags a connection whose probe was redirected to a sign-in page.
static int behind_captive_portal(INetworkListManager *nlm) {
    IEnumNetworkConnections *connections = NULL;
    if (FAILED(nlm->lpVtbl->GetNetworkConnections(nlm, &connections)) || !connections) return 0;

    int hijacked = 0;
    INetworkConnection *connection = NULL;
    ULONG fetched = 0;
    while (!hijacked && connections->lpVtbl->Next(connections, 1, &connection, &fetched) == S_OK) {
        IPropertyBag *bag = NULL;
        if (SUCCEEDED(connection->lpVtbl->QueryInterface(connection, &IID_IPropertyBag, (void **)&bag)) && bag) {
            hijacked = has_flag(bag, L"NA_InternetConnectivityV4", INTERNET_CONNECTIVITY_WEBHIJACK) ||
                       has_flag(bag, L"NA_InternetConnectivityV6", INTERNET_CONNECTIVITY_WEBHIJACK);
            bag->lpVtbl->Release(bag);
        }
        connection->lpVtbl->Release(connection);
    }
    connections->lpVtbl->Release(connections);
    return hijacked;
}

static int metered_status(INetworkListManager *nlm) {
    INetworkCostManager *costs = NULL;
    if (FAILED(nlm->lpVtbl->QueryInterface(nlm, &IID_INetworkCostManager, (void **)&costs)) || !costs)
        return METERED_UNKNOWN;
    DWORD cost = 0;
    HRESULT hr = costs->lpVtbl->GetCost(costs, &cost, NULL);
    costs->lpVtbl->Release(costs);
    if (FAILED(hr)) return METERED_UNKNOWN;

    if (cost & (NLM_CONNECTION_COST_FIXED | NLM_CONNECTION_COST_VARIABLE |
                NLM_CONNECTION_COST_OVERDATALIMIT | NLM_CONNECTION_COST_ROAMING))
        return METERED_YES;
    if (cost & NLM_CONNECTION_COST_UNRESTRICTED) return METERED_NO;
    return METERED_UNKNOWN;
}

// Returns 0 when NLM could not be queried, leaving *out untouched.
static int query_state(INetworkListManager *nlm, NetState *out) {
    NLM_CONNECTIVITY connectivity = NLM_CONNECTIVITY_DISCONNECTED;
    if (FAILED(nlm->lpVtbl->GetConnectivity(nlm, &connectivity))) return 0;

    NetState state = { 0, METERED_UNKNOWN, LEVEL_NONE };
    if (connectivity & CONNECTIVITY_INTERNET) {
        state.level = LEVEL_FULL;
    } else if (connectivity & CONNECTIVITY_ROUTED) {
        state.level = behind_captive_portal(nlm) ? LEVEL_PORTAL : LEVEL_LIMITED;
    } else if (connectivity & CONNECTIVITY_SUBNET) {
        state.level = LEVEL_LOCAL;
    }
    state.available = state.level >= LEVEL_LIMITED;
    if (state.available) state.metered = metered_status(nlm);
    *out = state;
    return 1;
}

static jintArray to_java(JNIEnv *env, const NetState *state) {
    jintArray result = (*env)->NewIntArray(env, 3);
    if (!result) return NULL;
    jint values[3] = { state->available, state->metered, state->level };
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}

// ---------------------------------------------------------------------------
// One-shot query
// ---------------------------------------------------------------------------

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeQueryConnectivity(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    NetState state = UNKNOWN_STATE;

    // The calling thread may already live in an STA (RPC_E_CHANGED_MODE): use it as is.
    HRESULT hrInit = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hrInit) && hrInit != RPC_E_CHANGED_MODE) return to_java(env, &state);

    INetworkListManager *nlm = NULL;
    if (SUCCEEDED(CoCreateInstance(&CLSID_NetworkListManager, NULL, CLSCTX_ALL,
                                   &IID_INetworkListManager, (void **)&nlm)) && nlm) {
        query_state(nlm, &state);
        nlm->lpVtbl->Release(nlm);
    }

    if (SUCCEEDED(hrInit)) CoUninitialize();
    return to_java(env, &state);
}

// ---------------------------------------------------------------------------
// Change monitor
// ---------------------------------------------------------------------------

typedef struct Monitor Monitor;

// One COM sink per event interface. Their lifetime is the monitor's: AddRef/Release
// are no-ops, and the apartment is torn down (disconnecting every stub) before the
// monitor is freed.
typedef struct {
    INetworkListManagerEvents iface;
    Monitor *monitor;
} ListSink;

typedef struct {
    INetworkConnectionEvents iface;
    Monitor *monitor;
} ConnectionSink;

typedef struct {
    INetworkCostManagerEvents iface;
    Monitor *monitor;
} CostSink;

typedef struct {
    IConnectionPoint *point;
    DWORD cookie;
} Subscription;

struct Monitor {
    JavaVM *jvm;
    jclass bridge;            // global ref
    jmethodID callback;       // static void onConnectivityChanged(boolean, int, int)
    HANDLE thread;
    HANDLE ready;             // set once the thread subscribed (or failed to)
    HANDLE stop;              // set by nativeStopConnectivityMonitor
    int started;              // written before ready is set

    // Only touched on the monitor thread.
    JNIEnv *env;
    INetworkListManager *nlm;
    int dirty;
    NetState last;
    int has_last;
    ListSink list_sink;
    ConnectionSink connection_sink;
    CostSink cost_sink;
    Subscription subscriptions[3];
};

static Monitor *monitor;      // guarded by the Kotlin side (start/stop are serialized)

static ULONG STDMETHODCALLTYPE sink_add_ref(void *self) { (void)self; return 2; }
static ULONG STDMETHODCALLTYPE sink_release(void *self) { (void)self; return 1; }

static HRESULT sink_query(void *self, REFIID riid, REFIID own, void **out) {
    if (!out) return E_POINTER;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, own)) {
        *out = self;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT STDMETHODCALLTYPE list_query(INetworkListManagerEvents *self, REFIID riid, void **out) {
    return sink_query(self, riid, &IID_INetworkListManagerEvents, out);
}
static ULONG STDMETHODCALLTYPE list_add_ref(INetworkListManagerEvents *self) { return sink_add_ref(self); }
static ULONG STDMETHODCALLTYPE list_release(INetworkListManagerEvents *self) { return sink_release(self); }
static HRESULT STDMETHODCALLTYPE list_connectivity_changed(INetworkListManagerEvents *self, NLM_CONNECTIVITY value) {
    (void)value;
    ((ListSink *)self)->monitor->dirty = 1;
    return S_OK;
}
static INetworkListManagerEventsVtbl list_vtbl = {
    list_query, list_add_ref, list_release, list_connectivity_changed,
};

static HRESULT STDMETHODCALLTYPE connection_query(INetworkConnectionEvents *self, REFIID riid, void **out) {
    return sink_query(self, riid, &IID_INetworkConnectionEvents, out);
}
static ULONG STDMETHODCALLTYPE connection_add_ref(INetworkConnectionEvents *self) { return sink_add_ref(self); }
static ULONG STDMETHODCALLTYPE connection_release(INetworkConnectionEvents *self) { return sink_release(self); }
static HRESULT STDMETHODCALLTYPE connection_connectivity_changed(
    INetworkConnectionEvents *self, GUID id, NLM_CONNECTIVITY value) {
    (void)id; (void)value;
    ((ConnectionSink *)self)->monitor->dirty = 1;
    return S_OK;
}
// Catches the captive-portal flag, which NCSI can set after the connectivity change.
static HRESULT STDMETHODCALLTYPE connection_property_changed(
    INetworkConnectionEvents *self, GUID id, NLM_CONNECTION_PROPERTY_CHANGE flags) {
    (void)id; (void)flags;
    ((ConnectionSink *)self)->monitor->dirty = 1;
    return S_OK;
}
static INetworkConnectionEventsVtbl connection_vtbl = {
    connection_query, connection_add_ref, connection_release,
    connection_connectivity_changed, connection_property_changed,
};

static HRESULT STDMETHODCALLTYPE cost_query(INetworkCostManagerEvents *self, REFIID riid, void **out) {
    return sink_query(self, riid, &IID_INetworkCostManagerEvents, out);
}
static ULONG STDMETHODCALLTYPE cost_add_ref(INetworkCostManagerEvents *self) { return sink_add_ref(self); }
static ULONG STDMETHODCALLTYPE cost_release(INetworkCostManagerEvents *self) { return sink_release(self); }
static HRESULT STDMETHODCALLTYPE cost_changed(INetworkCostManagerEvents *self, DWORD cost, NLM_SOCKADDR *dest) {
    (void)cost; (void)dest;
    ((CostSink *)self)->monitor->dirty = 1;
    return S_OK;
}
static HRESULT STDMETHODCALLTYPE cost_data_plan_changed(INetworkCostManagerEvents *self, NLM_SOCKADDR *dest) {
    (void)dest;
    ((CostSink *)self)->monitor->dirty = 1;
    return S_OK;
}
static INetworkCostManagerEventsVtbl cost_vtbl = {
    cost_query, cost_add_ref, cost_release, cost_changed, cost_data_plan_changed,
};

static int subscribe(IConnectionPointContainer *container, REFIID iid, IUnknown *sink, Subscription *out) {
    if (FAILED(container->lpVtbl->FindConnectionPoint(container, iid, &out->point)) || !out->point) {
        out->point = NULL;
        return 0;
    }
    if (FAILED(out->point->lpVtbl->Advise(out->point, sink, &out->cookie))) {
        out->point->lpVtbl->Release(out->point);
        out->point = NULL;
        return 0;
    }
    return 1;
}

static void publish(Monitor *m) {
    NetState state;
    // A failed query mid-stream keeps the last state rather than reporting a disconnection.
    if (!query_state(m->nlm, &state)) {
        if (m->has_last) return;
        state = UNKNOWN_STATE;
    }
    if (m->has_last && memcmp(&m->last, &state, sizeof(NetState)) == 0) return;
    m->last = state;
    m->has_last = 1;

    (*m->env)->CallStaticVoidMethod(m->env, m->bridge, m->callback,
                                    (jboolean)(state.available ? JNI_TRUE : JNI_FALSE),
                                    (jint)state.metered, (jint)state.level);
    nucleus_jni_clear_exception(m->env);
}

// Subscribes and publishes the initial state; returns 0 when NLM is unusable.
static int open_monitor(Monitor *m) {
    if (FAILED(CoCreateInstance(&CLSID_NetworkListManager, NULL, CLSCTX_ALL,
                                &IID_INetworkListManager, (void **)&m->nlm)) || !m->nlm) {
        m->nlm = NULL;
        return 0;
    }
    IConnectionPointContainer *container = NULL;
    if (FAILED(m->nlm->lpVtbl->QueryInterface(m->nlm, &IID_IConnectionPointContainer, (void **)&container)) ||
        !container)
        return 0;

    // Connectivity events are required; connection and cost events only refine the state.
    int ok = subscribe(container, &IID_INetworkListManagerEvents, (IUnknown *)&m->list_sink, &m->subscriptions[0]);
    if (ok) {
        subscribe(container, &IID_INetworkConnectionEvents, (IUnknown *)&m->connection_sink, &m->subscriptions[1]);
        subscribe(container, &IID_INetworkCostManagerEvents, (IUnknown *)&m->cost_sink, &m->subscriptions[2]);
    }
    container->lpVtbl->Release(container);
    // Subscribe first: a change landing between the query and Advise is never lost.
    if (ok) publish(m);
    return ok;
}

static void close_monitor(Monitor *m) {
    for (int i = 0; i < 3; i++) {
        Subscription *s = &m->subscriptions[i];
        if (!s->point) continue;
        s->point->lpVtbl->Unadvise(s->point, s->cookie);
        s->point->lpVtbl->Release(s->point);
        s->point = NULL;
    }
    CoDisconnectObject((IUnknown *)&m->list_sink, 0);
    CoDisconnectObject((IUnknown *)&m->connection_sink, 0);
    CoDisconnectObject((IUnknown *)&m->cost_sink, 0);
    if (m->nlm) {
        m->nlm->lpVtbl->Release(m->nlm);
        m->nlm = NULL;
    }
}

static DWORD WINAPI monitor_thread(LPVOID arg) {
    Monitor *m = (Monitor *)arg;

    JavaVMAttachArgs args = { JNI_VERSION_1_6, "nucleus-connectivity-monitor", NULL };
    if ((*m->jvm)->AttachCurrentThreadAsDaemon(m->jvm, (void **)&m->env, &args) != JNI_OK) {
        SetEvent(m->ready);
        return 0;
    }

    // An STA, so NLM delivers its events on this thread through the pump below.
    HRESULT hrInit = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
    m->started = SUCCEEDED(hrInit) && open_monitor(m);
    SetEvent(m->ready);

    while (m->started) {
        DWORD woken = MsgWaitForMultipleObjectsEx(1, &m->stop, INFINITE, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
        if (woken != WAIT_OBJECT_0 + 1) break; // stop requested, or the wait failed
        MSG msg;
        while (PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        // Coalesces a burst of events into one query.
        if (m->dirty) {
            m->dirty = 0;
            publish(m);
        }
    }

    close_monitor(m);
    if (SUCCEEDED(hrInit)) CoUninitialize();
    (*m->jvm)->DetachCurrentThread(m->jvm);
    return 0;
}

static void free_monitor(JNIEnv *env, Monitor *m) {
    if (m->thread) CloseHandle(m->thread);
    if (m->ready) CloseHandle(m->ready);
    if (m->stop) CloseHandle(m->stop);
    if (m->bridge) (*env)->DeleteGlobalRef(env, m->bridge);
    free(m);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeStartConnectivityMonitor(
    JNIEnv *env, jclass clazz) {
    if (monitor) return JNI_TRUE;

    Monitor *m = calloc(1, sizeof(Monitor));
    if (!m) return JNI_FALSE;
    (*env)->GetJavaVM(env, &m->jvm);
    m->callback = (*env)->GetStaticMethodID(env, clazz, "onConnectivityChanged", "(ZII)V");
    if (!m->callback) {
        nucleus_jni_clear_exception(env);
        free(m);
        return JNI_FALSE;
    }
    m->bridge = (*env)->NewGlobalRef(env, clazz);
    m->list_sink.iface.lpVtbl = &list_vtbl;
    m->list_sink.monitor = m;
    m->connection_sink.iface.lpVtbl = &connection_vtbl;
    m->connection_sink.monitor = m;
    m->cost_sink.iface.lpVtbl = &cost_vtbl;
    m->cost_sink.monitor = m;
    m->ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    m->stop = CreateEventW(NULL, TRUE, FALSE, NULL);
    m->thread = m->ready && m->stop ? CreateThread(NULL, 0, monitor_thread, m, 0, NULL) : NULL;
    if (!m->thread) {
        free_monitor(env, m);
        return JNI_FALSE;
    }

    // The thread publishes the initial state before it reports ready.
    WaitForSingleObject(m->ready, INFINITE);
    if (!m->started) {
        WaitForSingleObject(m->thread, INFINITE);
        free_monitor(env, m);
        return JNI_FALSE;
    }
    monitor = m;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeStopConnectivityMonitor(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    Monitor *m = monitor;
    if (!m) return;
    monitor = NULL;

    // The thread unsubscribes and tears its apartment down before exiting, so once
    // it is gone no callback can touch the monitor any more.
    SetEvent(m->stop);
    WaitForSingleObject(m->thread, INFINITE);
    free_monitor(env, m);
}
