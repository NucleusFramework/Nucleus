/*
 * nucleus_tao_dnd.c — Inbound OS drag-and-drop for the Tao Windows backend.
 *
 * Implements an IDropTarget COM object registered on the Tao-owned HWND via
 * RegisterDragDrop, and forwards the four DnD callbacks (DragEnter, DragOver,
 * DragLeave, Drop) to a Java object passed at registration time.
 *
 * Linked libraries: kernel32.lib user32.lib ole32.lib oleaut32.lib uuid.lib shell32.lib
 *
 * Threading: RegisterDragDrop / RevokeDragDrop must run on the thread that
 * owns the HWND, which is the Tao event-loop thread (= the Compose dispatcher
 * thread). OleInitialize is called per registration; reference-counted, safe
 * to nest with anything Tao does internally.
 */

#define COBJMACROS
#define CINTERFACE
#define INITGUID

#include <jni.h>
#include <windows.h>
#include <objbase.h>
#include <ole2.h>
#include <oleidl.h>
#include <shlobj.h>
#include <stdint.h>
#include <stddef.h>

/* /NODEFAULTLIB support */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

#pragma function(memcpy)
void *memcpy(void *dest, const void *src, size_t count) {
    unsigned char *d = (unsigned char *)dest;
    const unsigned char *s = (const unsigned char *)src;
    while (count--) *d++ = *s++;
    return dest;
}

int memcmp(const void *a, const void *b, size_t count) {
    const unsigned char *p = (const unsigned char *)a;
    const unsigned char *q = (const unsigned char *)b;
    while (count--) {
        if (*p != *q) return (int)*p - (int)*q;
        p++; q++;
    }
    return 0;
}

/* DllMain stub for /NODEFAULTLIB linking. */
BOOL WINAPI DllMain(HINSTANCE h, DWORD r, LPVOID v) { (void)h; (void)r; (void)v; return TRUE; }

/* ---------------------------------------------------------------------- */

static JavaVM *g_vm = NULL;

/* Cached Kotlin callback class + methods (resolved on first register). */
static jclass    g_callback_class       = NULL; /* GlobalRef */
static jmethodID g_method_on_enter      = NULL; /* (JIIIZ)I  hwnd, x, y, keyState, hasFiles → effect */
static jmethodID g_method_on_over       = NULL; /* (JIIIZ)I */
static jmethodID g_method_on_leave      = NULL; /* (J)V */
static jmethodID g_method_on_drop       = NULL; /* (JIII[Ljava/lang/String;)I  hwnd, x, y, keyState, files → effect */

#define DROPEFFECT_NONE_LOCAL 0
#define DROPEFFECT_COPY_LOCAL 1

/* ---------------------------------------------------------------------- */
/* IDropTarget implementation                                              */
/* ---------------------------------------------------------------------- */

typedef struct NucleusDropTarget {
    IDropTargetVtbl *lpVtbl;
    LONG     refCount;
    HWND     hwnd;
    jobject  callbackRef; /* GlobalRef on the Kotlin callback object */
    BOOL     hasAcceptableData; /* set in DragEnter, used by DragOver */
} NucleusDropTarget;

static JNIEnv *attach_thread(BOOL *attachedHere) {
    JNIEnv *env = NULL;
    if (!g_vm) { *attachedHere = FALSE; return NULL; }
    jint rc = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_8);
    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != 0) {
            *attachedHere = FALSE;
            return NULL;
        }
        *attachedHere = TRUE;
    } else {
        *attachedHere = FALSE;
    }
    return env;
}

static void detach_if_needed(BOOL attachedHere) {
    if (attachedHere && g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

/* Walks the IDataObject's formats and returns TRUE if CF_HDROP is present. */
static BOOL data_has_files(IDataObject *pDataObj) {
    if (!pDataObj) return FALSE;
    FORMATETC fmt = { CF_HDROP, NULL, DVASPECT_CONTENT, -1, TYMED_HGLOBAL };
    return IDataObject_QueryGetData(pDataObj, &fmt) == S_OK;
}

/* Extracts file paths from a CF_HDROP IDataObject. Returns a Java String[]
 * (NewObjectArray) or NULL on failure. Caller owns the local ref. */
static jobjectArray extract_files(JNIEnv *env, IDataObject *pDataObj) {
    if (!pDataObj) return NULL;
    FORMATETC fmt = { CF_HDROP, NULL, DVASPECT_CONTENT, -1, TYMED_HGLOBAL };
    STGMEDIUM stg = { 0 };
    if (IDataObject_GetData(pDataObj, &fmt, &stg) != S_OK) return NULL;

    jobjectArray result = NULL;
    HDROP hdrop = (HDROP)GlobalLock(stg.hGlobal);
    if (hdrop) {
        UINT count = DragQueryFileW(hdrop, 0xFFFFFFFF, NULL, 0);
        jclass strClass = (*env)->FindClass(env, "java/lang/String");
        if (strClass && count > 0) {
            result = (*env)->NewObjectArray(env, (jsize)count, strClass, NULL);
            if (result) {
                for (UINT i = 0; i < count; ++i) {
                    UINT len = DragQueryFileW(hdrop, i, NULL, 0);
                    if (len == 0) continue;
                    WCHAR *buf = (WCHAR *)HeapAlloc(GetProcessHeap(), 0, (len + 1) * sizeof(WCHAR));
                    if (!buf) continue;
                    DragQueryFileW(hdrop, i, buf, len + 1);
                    jstring js = (*env)->NewString(env, (const jchar *)buf, (jsize)len);
                    if (js) {
                        (*env)->SetObjectArrayElement(env, result, (jsize)i, js);
                        (*env)->DeleteLocalRef(env, js);
                    }
                    HeapFree(GetProcessHeap(), 0, buf);
                }
            }
        }
        GlobalUnlock(stg.hGlobal);
    }
    ReleaseStgMedium(&stg);
    return result;
}

/* ---- IUnknown ---- */

static HRESULT STDMETHODCALLTYPE NDT_QueryInterface(IDropTarget *self, REFIID riid, void **ppv) {
    if (!ppv) return E_POINTER;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IDropTarget)) {
        *ppv = self;
        IDropTarget_AddRef(self);
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE NDT_AddRef(IDropTarget *self) {
    NucleusDropTarget *t = (NucleusDropTarget *)self;
    return (ULONG)InterlockedIncrement(&t->refCount);
}

static ULONG STDMETHODCALLTYPE NDT_Release(IDropTarget *self) {
    NucleusDropTarget *t = (NucleusDropTarget *)self;
    LONG n = InterlockedDecrement(&t->refCount);
    if (n == 0) {
        if (t->callbackRef && g_vm) {
            BOOL attached = FALSE;
            JNIEnv *env = attach_thread(&attached);
            if (env) (*env)->DeleteGlobalRef(env, t->callbackRef);
            detach_if_needed(attached);
        }
        HeapFree(GetProcessHeap(), 0, t);
    }
    return (ULONG)n;
}

/* ---- IDropTarget ---- */

/* Converts a screen-space POINTL to client (logical-relative) coords for the
 * given HWND. Returns physical pixels with origin = client-area top-left. */
static void to_client(HWND hwnd, POINTL ptl, jint *outX, jint *outY) {
    POINT p = { (LONG)ptl.x, (LONG)ptl.y };
    ScreenToClient(hwnd, &p);
    *outX = (jint)p.x;
    *outY = (jint)p.y;
}

static HRESULT STDMETHODCALLTYPE NDT_DragEnter(
    IDropTarget *self, IDataObject *pDataObj, DWORD grfKeyState, POINTL pt, DWORD *pdwEffect)
{
    NucleusDropTarget *t = (NucleusDropTarget *)self;
    t->hasAcceptableData = data_has_files(pDataObj);
    if (!t->hasAcceptableData) {
        *pdwEffect = DROPEFFECT_NONE;
        return S_OK;
    }

    BOOL attached = FALSE;
    JNIEnv *env = attach_thread(&attached);
    if (!env) {
        *pdwEffect = DROPEFFECT_NONE;
        return S_OK;
    }

    jint x, y;
    to_client(t->hwnd, pt, &x, &y);

    jint effect = DROPEFFECT_COPY_LOCAL;
    if (g_method_on_enter) {
        effect = (*env)->CallIntMethod(
            env, t->callbackRef, g_method_on_enter,
            (jlong)(intptr_t)t->hwnd, x, y, (jint)grfKeyState, JNI_TRUE);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            effect = DROPEFFECT_NONE_LOCAL;
        }
    }

    detach_if_needed(attached);
    *pdwEffect = (effect == DROPEFFECT_COPY_LOCAL) ? DROPEFFECT_COPY : DROPEFFECT_NONE;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE NDT_DragOver(
    IDropTarget *self, DWORD grfKeyState, POINTL pt, DWORD *pdwEffect)
{
    NucleusDropTarget *t = (NucleusDropTarget *)self;
    if (!t->hasAcceptableData) {
        *pdwEffect = DROPEFFECT_NONE;
        return S_OK;
    }

    BOOL attached = FALSE;
    JNIEnv *env = attach_thread(&attached);
    if (!env) {
        *pdwEffect = DROPEFFECT_NONE;
        return S_OK;
    }

    jint x, y;
    to_client(t->hwnd, pt, &x, &y);

    jint effect = DROPEFFECT_COPY_LOCAL;
    if (g_method_on_over) {
        effect = (*env)->CallIntMethod(
            env, t->callbackRef, g_method_on_over,
            (jlong)(intptr_t)t->hwnd, x, y, (jint)grfKeyState, JNI_TRUE);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            effect = DROPEFFECT_NONE_LOCAL;
        }
    }

    detach_if_needed(attached);
    *pdwEffect = (effect == DROPEFFECT_COPY_LOCAL) ? DROPEFFECT_COPY : DROPEFFECT_NONE;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE NDT_DragLeave(IDropTarget *self) {
    NucleusDropTarget *t = (NucleusDropTarget *)self;
    BOOL attached = FALSE;
    JNIEnv *env = attach_thread(&attached);
    if (env && g_method_on_leave) {
        (*env)->CallVoidMethod(env, t->callbackRef, g_method_on_leave,
                               (jlong)(intptr_t)t->hwnd);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
    }
    detach_if_needed(attached);
    t->hasAcceptableData = FALSE;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE NDT_Drop(
    IDropTarget *self, IDataObject *pDataObj, DWORD grfKeyState, POINTL pt, DWORD *pdwEffect)
{
    NucleusDropTarget *t = (NucleusDropTarget *)self;
    BOOL attached = FALSE;
    JNIEnv *env = attach_thread(&attached);
    if (!env) {
        *pdwEffect = DROPEFFECT_NONE;
        t->hasAcceptableData = FALSE;
        return S_OK;
    }

    jobjectArray files = extract_files(env, pDataObj);
    jint x, y;
    to_client(t->hwnd, pt, &x, &y);

    jint effect = DROPEFFECT_NONE_LOCAL;
    if (g_method_on_drop) {
        effect = (*env)->CallIntMethod(
            env, t->callbackRef, g_method_on_drop,
            (jlong)(intptr_t)t->hwnd, x, y, (jint)grfKeyState, files);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            effect = DROPEFFECT_NONE_LOCAL;
        }
    }
    if (files) (*env)->DeleteLocalRef(env, files);

    detach_if_needed(attached);
    t->hasAcceptableData = FALSE;
    *pdwEffect = (effect == DROPEFFECT_COPY_LOCAL) ? DROPEFFECT_COPY : DROPEFFECT_NONE;
    return S_OK;
}

/* Static vtable shared by every NucleusDropTarget instance. */
static IDropTargetVtbl g_drop_target_vtbl = {
    NDT_QueryInterface,
    NDT_AddRef,
    NDT_Release,
    NDT_DragEnter,
    NDT_DragOver,
    NDT_DragLeave,
    NDT_Drop,
};

/* ---------------------------------------------------------------------- */
/* JNI exports                                                             */
/* ---------------------------------------------------------------------- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_8;
}

/* Resolves the Kotlin callback class & method IDs. Idempotent. */
static BOOL ensure_callback_methods(JNIEnv *env, jobject callback) {
    if (g_callback_class && g_method_on_enter && g_method_on_over &&
        g_method_on_leave && g_method_on_drop) return TRUE;

    jclass local = (*env)->GetObjectClass(env, callback);
    if (!local) return FALSE;
    g_callback_class = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!g_callback_class) return FALSE;

    g_method_on_enter = (*env)->GetMethodID(env, g_callback_class, "onDragEnter", "(JIIIZ)I");
    g_method_on_over  = (*env)->GetMethodID(env, g_callback_class, "onDragOver",  "(JIIIZ)I");
    g_method_on_leave = (*env)->GetMethodID(env, g_callback_class, "onDragLeave", "(J)V");
    g_method_on_drop  = (*env)->GetMethodID(env, g_callback_class, "onDrop",
        "(JIII[Ljava/lang/String;)I");

    return g_method_on_enter && g_method_on_over && g_method_on_leave && g_method_on_drop;
}

/*
 * nativeRegister(hwnd, callback): allocates a NucleusDropTarget, calls
 * OleInitialize on the calling thread (= Tao event-loop thread), then
 * RegisterDragDrop. Returns 0 on success, negative on failure.
 */
JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDndBridge_nativeRegister(
    JNIEnv *env, jclass cls, jlong hwnd, jobject callback)
{
    (void)cls;
    if (!hwnd || !callback) return -1;
    if (!ensure_callback_methods(env, callback)) return -2;

    HRESULT hr = OleInitialize(NULL);
    /* OleInitialize returns S_FALSE if already initialized — both are OK. */
    if (FAILED(hr)) return -3;

    NucleusDropTarget *t = (NucleusDropTarget *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(NucleusDropTarget));
    if (!t) { OleUninitialize(); return -4; }

    t->lpVtbl = &g_drop_target_vtbl;
    t->refCount = 1;
    t->hwnd = (HWND)(intptr_t)hwnd;
    t->callbackRef = (*env)->NewGlobalRef(env, callback);
    if (!t->callbackRef) {
        HeapFree(GetProcessHeap(), 0, t);
        OleUninitialize();
        return -5;
    }

    /* Tao 0.35 unconditionally calls RegisterDragDrop on every window so it
     * can emit its own WindowEvent::FileDropped. Win32 only allows one
     * IDropTarget per HWND, so revoke whatever is currently bound before
     * registering ours. RevokeDragDrop fails harmlessly if nothing is bound. */
    RevokeDragDrop(t->hwnd);

    hr = RegisterDragDrop(t->hwnd, (IDropTarget *)t);
    if (FAILED(hr)) {
        IDropTarget_Release((IDropTarget *)t);
        OleUninitialize();
        /* Return the raw HRESULT so the caller can diagnose. */
        return (jint)hr;
    }

    /* RegisterDragDrop holds its own AddRef on the target; release the one
     * the caller (us) had. The COM target survives until RevokeDragDrop. */
    IDropTarget_Release((IDropTarget *)t);
    return 0;
}

/*
 * nativeRevoke(hwnd): RevokeDragDrop + OleUninitialize. Pairs 1:1 with
 * nativeRegister calls.
 */
JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDndBridge_nativeRevoke(
    JNIEnv *env, jclass cls, jlong hwnd)
{
    (void)env; (void)cls;
    if (!hwnd) return -1;
    HRESULT hr = RevokeDragDrop((HWND)(intptr_t)hwnd);
    OleUninitialize();
    return SUCCEEDED(hr) ? 0 : -2;
}

/* ====================================================================== */
/* Outbound DnD — DoDragDrop session driven from Compose                   */
/* ====================================================================== */

/* ---- Helpers: build HGLOBAL payloads -------------------------------- */

/* Builds a CF_HDROP HGLOBAL from a list of WCHAR paths. The destination
 * receiving CF_HDROP via STGMEDIUM (TYMED_HGLOBAL) takes ownership of the
 * HGLOBAL and frees it via ReleaseStgMedium. We must therefore allocate a
 * fresh one for every GetData call. */
static HGLOBAL build_hdrop(JNIEnv *env, jobjectArray files) {
    if (!files) return NULL;
    jsize n = (*env)->GetArrayLength(env, files);
    if (n <= 0) return NULL;

    /* First pass: total WCHAR count incl. per-string null + final extra null. */
    SIZE_T total_wchars = 0;
    for (jsize i = 0; i < n; ++i) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, files, i);
        if (!s) continue;
        jsize len = (*env)->GetStringLength(env, s);
        total_wchars += (SIZE_T)len + 1; /* +1 for null terminator */
        (*env)->DeleteLocalRef(env, s);
    }
    total_wchars += 1; /* final extra null = double-null termination */

    SIZE_T cb = sizeof(DROPFILES) + total_wchars * sizeof(WCHAR);
    HGLOBAL hg = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, cb);
    if (!hg) return NULL;

    DROPFILES *df = (DROPFILES *)GlobalLock(hg);
    if (!df) { GlobalFree(hg); return NULL; }
    df->pFiles = sizeof(DROPFILES);
    df->fWide = TRUE;

    WCHAR *cursor = (WCHAR *)((BYTE *)df + sizeof(DROPFILES));
    for (jsize i = 0; i < n; ++i) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, files, i);
        if (!s) continue;
        jsize len = (*env)->GetStringLength(env, s);
        const jchar *src = (*env)->GetStringChars(env, s, NULL);
        if (src) {
            for (jsize k = 0; k < len; ++k) cursor[k] = (WCHAR)src[k];
            (*env)->ReleaseStringChars(env, s, src);
            cursor[len] = L'\0';
            cursor += len + 1;
        }
        (*env)->DeleteLocalRef(env, s);
    }
    *cursor = L'\0';

    GlobalUnlock(hg);
    return hg;
}

/* Builds a CF_UNICODETEXT HGLOBAL from a Java string. */
static HGLOBAL build_unicode_text(JNIEnv *env, jstring text) {
    if (!text) return NULL;
    jsize len = (*env)->GetStringLength(env, text);
    SIZE_T cb = ((SIZE_T)len + 1) * sizeof(WCHAR);
    HGLOBAL hg = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, cb);
    if (!hg) return NULL;

    WCHAR *p = (WCHAR *)GlobalLock(hg);
    if (!p) { GlobalFree(hg); return NULL; }
    const jchar *src = (*env)->GetStringChars(env, text, NULL);
    if (src) {
        for (jsize k = 0; k < len; ++k) p[k] = (WCHAR)src[k];
        (*env)->ReleaseStringChars(env, text, src);
    }
    p[len] = L'\0';
    GlobalUnlock(hg);
    return hg;
}

/* ---- IEnumFORMATETC ------------------------------------------------- */

typedef struct NucleusEnumFormatEtc {
    IEnumFORMATETCVtbl *lpVtbl;
    LONG     refCount;
    UINT     count;
    UINT     cursor;
    FORMATETC formats[3]; /* CF_HDROP, CF_UNICODETEXT, … */
} NucleusEnumFormatEtc;

static HRESULT STDMETHODCALLTYPE NEF_QueryInterface(IEnumFORMATETC *self, REFIID riid, void **ppv) {
    if (!ppv) return E_POINTER;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IEnumFORMATETC)) {
        *ppv = self; IEnumFORMATETC_AddRef(self); return S_OK;
    }
    *ppv = NULL; return E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE NEF_AddRef(IEnumFORMATETC *self) {
    return (ULONG)InterlockedIncrement(&((NucleusEnumFormatEtc *)self)->refCount);
}
static ULONG STDMETHODCALLTYPE NEF_Release(IEnumFORMATETC *self) {
    NucleusEnumFormatEtc *e = (NucleusEnumFormatEtc *)self;
    LONG n = InterlockedDecrement(&e->refCount);
    if (n == 0) HeapFree(GetProcessHeap(), 0, e);
    return (ULONG)n;
}
static HRESULT STDMETHODCALLTYPE NEF_Next(IEnumFORMATETC *self, ULONG celt, FORMATETC *rgelt, ULONG *pceltFetched) {
    NucleusEnumFormatEtc *e = (NucleusEnumFormatEtc *)self;
    ULONG fetched = 0;
    while (fetched < celt && e->cursor < e->count) {
        rgelt[fetched] = e->formats[e->cursor];
        e->cursor++; fetched++;
    }
    if (pceltFetched) *pceltFetched = fetched;
    return (fetched == celt) ? S_OK : S_FALSE;
}
static HRESULT STDMETHODCALLTYPE NEF_Skip(IEnumFORMATETC *self, ULONG celt) {
    NucleusEnumFormatEtc *e = (NucleusEnumFormatEtc *)self;
    if (e->cursor + celt > e->count) { e->cursor = e->count; return S_FALSE; }
    e->cursor += celt; return S_OK;
}
static HRESULT STDMETHODCALLTYPE NEF_Reset(IEnumFORMATETC *self) {
    ((NucleusEnumFormatEtc *)self)->cursor = 0; return S_OK;
}
static HRESULT STDMETHODCALLTYPE NEF_Clone(IEnumFORMATETC *self, IEnumFORMATETC **ppenum) { (void)self; (void)ppenum; return E_NOTIMPL; }

static IEnumFORMATETCVtbl g_enum_vtbl = {
    NEF_QueryInterface, NEF_AddRef, NEF_Release,
    NEF_Next, NEF_Skip, NEF_Reset, NEF_Clone,
};

static IEnumFORMATETC *make_enum(const FORMATETC *src, UINT count) {
    NucleusEnumFormatEtc *e = (NucleusEnumFormatEtc *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(NucleusEnumFormatEtc));
    if (!e) return NULL;
    e->lpVtbl = &g_enum_vtbl;
    e->refCount = 1;
    e->count = count;
    for (UINT i = 0; i < count && i < 3; ++i) e->formats[i] = src[i];
    return (IEnumFORMATETC *)e;
}

/* ---- IDataObject ---------------------------------------------------- */

typedef struct NucleusDataObject {
    IDataObjectVtbl *lpVtbl;
    LONG     refCount;
    JavaVM  *vm;
    /* Pre-baked source data, materialized once at session start.
     * GetData clones from these into fresh HGLOBALs as the destination
     * takes ownership via ReleaseStgMedium. */
    HGLOBAL  src_hdrop;        /* may be NULL */
    HGLOBAL  src_text;         /* may be NULL */
    UINT     n_formats;
    FORMATETC formats[2];
} NucleusDataObject;

static HGLOBAL clone_hglobal(HGLOBAL src) {
    if (!src) return NULL;
    SIZE_T cb = GlobalSize(src);
    HGLOBAL dst = GlobalAlloc(GMEM_MOVEABLE, cb);
    if (!dst) return NULL;
    void *psrc = GlobalLock(src);
    void *pdst = GlobalLock(dst);
    if (psrc && pdst) memcpy(pdst, psrc, cb);
    if (psrc) GlobalUnlock(src);
    if (pdst) GlobalUnlock(dst);
    return dst;
}

static HRESULT STDMETHODCALLTYPE NDO_QueryInterface(IDataObject *self, REFIID riid, void **ppv) {
    if (!ppv) return E_POINTER;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IDataObject)) {
        *ppv = self; IDataObject_AddRef(self); return S_OK;
    }
    *ppv = NULL; return E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE NDO_AddRef(IDataObject *self) {
    return (ULONG)InterlockedIncrement(&((NucleusDataObject *)self)->refCount);
}
static ULONG STDMETHODCALLTYPE NDO_Release(IDataObject *self) {
    NucleusDataObject *o = (NucleusDataObject *)self;
    LONG n = InterlockedDecrement(&o->refCount);
    if (n == 0) {
        if (o->src_hdrop) GlobalFree(o->src_hdrop);
        if (o->src_text)  GlobalFree(o->src_text);
        HeapFree(GetProcessHeap(), 0, o);
    }
    return (ULONG)n;
}
static HRESULT STDMETHODCALLTYPE NDO_GetData(IDataObject *self, FORMATETC *pfe, STGMEDIUM *psm) {
    NucleusDataObject *o = (NucleusDataObject *)self;
    if (!pfe || !psm) return E_INVALIDARG;
    if (!(pfe->tymed & TYMED_HGLOBAL)) return DV_E_TYMED;
    if (pfe->dwAspect != DVASPECT_CONTENT) return DV_E_DVASPECT;

    HGLOBAL src = NULL;
    if (pfe->cfFormat == CF_HDROP) src = o->src_hdrop;
    else if (pfe->cfFormat == CF_UNICODETEXT) src = o->src_text;
    else return DV_E_FORMATETC;
    if (!src) return DV_E_FORMATETC;

    HGLOBAL clone = clone_hglobal(src);
    if (!clone) return E_OUTOFMEMORY;

    psm->tymed = TYMED_HGLOBAL;
    psm->hGlobal = clone;
    psm->pUnkForRelease = NULL;
    return S_OK;
}
static HRESULT STDMETHODCALLTYPE NDO_GetDataHere(IDataObject *self, FORMATETC *pfe, STGMEDIUM *psm) {
    (void)self; (void)pfe; (void)psm; return E_NOTIMPL;
}
static HRESULT STDMETHODCALLTYPE NDO_QueryGetData(IDataObject *self, FORMATETC *pfe) {
    NucleusDataObject *o = (NucleusDataObject *)self;
    if (!pfe) return E_INVALIDARG;
    if (!(pfe->tymed & TYMED_HGLOBAL)) return DV_E_TYMED;
    if (pfe->dwAspect != DVASPECT_CONTENT) return DV_E_DVASPECT;
    if (pfe->cfFormat == CF_HDROP && o->src_hdrop) return S_OK;
    if (pfe->cfFormat == CF_UNICODETEXT && o->src_text) return S_OK;
    return DV_E_FORMATETC;
}
static HRESULT STDMETHODCALLTYPE NDO_GetCanonicalFormatEtc(IDataObject *self, FORMATETC *in, FORMATETC *out) {
    (void)self;
    if (in && out) { *out = *in; out->ptd = NULL; }
    return DATA_S_SAMEFORMATETC;
}
static HRESULT STDMETHODCALLTYPE NDO_SetData(IDataObject *self, FORMATETC *pfe, STGMEDIUM *psm, BOOL fRelease) {
    (void)self; (void)pfe; (void)psm; (void)fRelease; return E_NOTIMPL;
}
static HRESULT STDMETHODCALLTYPE NDO_EnumFormatEtc(IDataObject *self, DWORD direction, IEnumFORMATETC **ppenum) {
    NucleusDataObject *o = (NucleusDataObject *)self;
    if (!ppenum) return E_POINTER;
    if (direction != DATADIR_GET) { *ppenum = NULL; return E_NOTIMPL; }
    *ppenum = make_enum(o->formats, o->n_formats);
    return *ppenum ? S_OK : E_OUTOFMEMORY;
}
static HRESULT STDMETHODCALLTYPE NDO_DAdvise(IDataObject *self, FORMATETC *pfe, DWORD a, IAdviseSink *s, DWORD *p) {
    (void)self;(void)pfe;(void)a;(void)s;(void)p; return OLE_E_ADVISENOTSUPPORTED;
}
static HRESULT STDMETHODCALLTYPE NDO_DUnadvise(IDataObject *self, DWORD c) { (void)self;(void)c; return OLE_E_ADVISENOTSUPPORTED; }
static HRESULT STDMETHODCALLTYPE NDO_EnumDAdvise(IDataObject *self, IEnumSTATDATA **p) { (void)self;(void)p; return OLE_E_ADVISENOTSUPPORTED; }

static IDataObjectVtbl g_data_object_vtbl = {
    NDO_QueryInterface, NDO_AddRef, NDO_Release,
    NDO_GetData, NDO_GetDataHere, NDO_QueryGetData,
    NDO_GetCanonicalFormatEtc, NDO_SetData, NDO_EnumFormatEtc,
    NDO_DAdvise, NDO_DUnadvise, NDO_EnumDAdvise,
};

/* ---- IDropSource ---------------------------------------------------- */

typedef struct NucleusDropSource {
    IDropSourceVtbl *lpVtbl;
    LONG refCount;
} NucleusDropSource;

static HRESULT STDMETHODCALLTYPE NDS_QueryInterface(IDropSource *self, REFIID riid, void **ppv) {
    if (!ppv) return E_POINTER;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IDropSource)) {
        *ppv = self; IDropSource_AddRef(self); return S_OK;
    }
    *ppv = NULL; return E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE NDS_AddRef(IDropSource *self) {
    return (ULONG)InterlockedIncrement(&((NucleusDropSource *)self)->refCount);
}
static ULONG STDMETHODCALLTYPE NDS_Release(IDropSource *self) {
    NucleusDropSource *s = (NucleusDropSource *)self;
    LONG n = InterlockedDecrement(&s->refCount);
    if (n == 0) HeapFree(GetProcessHeap(), 0, s);
    return (ULONG)n;
}
static HRESULT STDMETHODCALLTYPE NDS_QueryContinueDrag(IDropSource *self, BOOL fEscape, DWORD grfKeyState) {
    (void)self;
    if (fEscape) return DRAGDROP_S_CANCEL;
    /* Drop on left-button release. MK_LBUTTON is bit 0x01 in grfKeyState. */
    if (!(grfKeyState & MK_LBUTTON)) return DRAGDROP_S_DROP;
    return S_OK;
}
static HRESULT STDMETHODCALLTYPE NDS_GiveFeedback(IDropSource *self, DWORD dwEffect) {
    (void)self; (void)dwEffect;
    /* Use OS-default cursors. */
    return DRAGDROP_S_USEDEFAULTCURSORS;
}

static IDropSourceVtbl g_drop_source_vtbl = {
    NDS_QueryInterface, NDS_AddRef, NDS_Release,
    NDS_QueryContinueDrag, NDS_GiveFeedback,
};

/* ---- nativeStartDrag JNI export ------------------------------------- */

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDndBridge_nativeStartDrag(
    JNIEnv *env, jclass cls, jlong hwnd, jobjectArray files, jstring text, jint allowedEffects)
{
    (void)cls; (void)hwnd;

    /* Materialize source data once. */
    HGLOBAL hdrop = build_hdrop(env, files);
    HGLOBAL htext = build_unicode_text(env, text);
    if (!hdrop && !htext) return 0; /* DROPEFFECT_NONE — nothing to drag */

    /* Allocate IDataObject. */
    NucleusDataObject *obj = (NucleusDataObject *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(NucleusDataObject));
    if (!obj) {
        if (hdrop) GlobalFree(hdrop);
        if (htext) GlobalFree(htext);
        return 0;
    }
    obj->lpVtbl = &g_data_object_vtbl;
    obj->refCount = 1;
    obj->vm = g_vm;
    obj->src_hdrop = hdrop;
    obj->src_text = htext;

    UINT idx = 0;
    if (hdrop) {
        obj->formats[idx].cfFormat = CF_HDROP;
        obj->formats[idx].ptd = NULL;
        obj->formats[idx].dwAspect = DVASPECT_CONTENT;
        obj->formats[idx].lindex = -1;
        obj->formats[idx].tymed = TYMED_HGLOBAL;
        idx++;
    }
    if (htext) {
        obj->formats[idx].cfFormat = CF_UNICODETEXT;
        obj->formats[idx].ptd = NULL;
        obj->formats[idx].dwAspect = DVASPECT_CONTENT;
        obj->formats[idx].lindex = -1;
        obj->formats[idx].tymed = TYMED_HGLOBAL;
        idx++;
    }
    obj->n_formats = idx;

    /* Allocate IDropSource. */
    NucleusDropSource *src = (NucleusDropSource *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(NucleusDropSource));
    if (!src) {
        IDataObject_Release((IDataObject *)obj);
        return 0;
    }
    src->lpVtbl = &g_drop_source_vtbl;
    src->refCount = 1;

    /* DoDragDrop pumps its own modal loop until the user releases or escapes.
     * The thread must be STA — guaranteed if nativeRegister was called earlier
     * (which OleInitializes), but call it defensively here in case outbound
     * drag is the very first DnD operation. */
    HRESULT ole_hr = OleInitialize(NULL);
    DWORD result_effect = DROPEFFECT_NONE;
    HRESULT hr = DoDragDrop(
        (IDataObject *)obj,
        (IDropSource *)src,
        (DWORD)allowedEffects,
        &result_effect);

    IDropSource_Release((IDropSource *)src);
    IDataObject_Release((IDataObject *)obj);
    if (SUCCEEDED(ole_hr)) OleUninitialize();

    if (hr == DRAGDROP_S_DROP) {
        /* Map DROPEFFECT_* to our public bitmask values. */
        if (result_effect & DROPEFFECT_COPY) return 1;
        if (result_effect & DROPEFFECT_MOVE) return 2;
        if (result_effect & DROPEFFECT_LINK) return 4;
        return 0;
    }
    return 0;
}
