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
