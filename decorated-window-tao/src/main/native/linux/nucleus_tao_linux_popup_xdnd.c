/**
 * Inbound XDND for the Linux standalone popup panel.
 *
 * Lives in its own translation unit so the panel file stays window/input/EGL.
 * Runs on the panel's event thread: XDND ClientMessages are sent with
 * `event_mask = NoEventMask`, which the X server delivers only to the
 * creating client — that is this thread, which created the window.
 *
 * JNI shape matches the Windows IDropTarget / GTK bridges so TaoSceneDnD
 * stays shared. `nativeSmokeXdndDrop` is a second-client XDND source used
 * only by StandalonePanelLinuxNativeSmokeTest.
 */

#include "nucleus_tao_linux_popup.h"
#include "../../../../../native-common/nucleus_jni.h"

#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <time.h>

static jmethodID g_on_drag_enter = NULL; /* (JIIIZ)I */
static jmethodID g_on_drag_over  = NULL; /* (JIIIZ)I */
static jmethodID g_on_drag_leave = NULL; /* (J)V     */
static jmethodID g_on_drag_drop  = NULL; /* (JIII[String;String;[String;)I */

/* Upper bound on what one drop reads, in 32-bit units (16 MiB). INCR
 * transfers (a source splitting larger data into chunks) are not supported. */
#define XDND_MAX_PROPERTY_LONGS (1L << 22)

static void cache_dnd_callback_ids(JNIEnv *env, jobject callback) {
    if (g_on_drag_enter != NULL) return;
    jclass cls = (*env)->GetObjectClass(env, callback);
    if (cls == NULL) return;
    g_on_drag_enter = (*env)->GetMethodID(env, cls, "onDragEnter", "(JIIIZ)I");
    g_on_drag_over  = (*env)->GetMethodID(env, cls, "onDragOver",  "(JIIIZ)I");
    g_on_drag_leave = (*env)->GetMethodID(env, cls, "onDragLeave", "(J)V");
    g_on_drag_drop  = (*env)->GetMethodID(
        env, cls, "onDrop",
        "(JIII[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)I");
    (*env)->DeleteLocalRef(env, cls);
    nucleus_jni_clear_exception(env);
}

void popup_xdnd_intern_atoms(Display *dpy, Panel *p) {
    p->a_XdndAware       = fn.XInternAtom(dpy, "XdndAware", False);
    p->a_XdndEnter       = fn.XInternAtom(dpy, "XdndEnter", False);
    p->a_XdndPosition    = fn.XInternAtom(dpy, "XdndPosition", False);
    p->a_XdndStatus      = fn.XInternAtom(dpy, "XdndStatus", False);
    p->a_XdndLeave       = fn.XInternAtom(dpy, "XdndLeave", False);
    p->a_XdndDrop        = fn.XInternAtom(dpy, "XdndDrop", False);
    p->a_XdndFinished    = fn.XInternAtom(dpy, "XdndFinished", False);
    p->a_XdndSelection   = fn.XInternAtom(dpy, "XdndSelection", False);
    p->a_XdndTypeList    = fn.XInternAtom(dpy, "XdndTypeList", False);
    p->a_XdndActionCopy  = fn.XInternAtom(dpy, "XdndActionCopy", False);
    p->a_text_uri_list   = fn.XInternAtom(dpy, "text/uri-list", False);
    p->a_text_plain_utf8 = fn.XInternAtom(dpy, "text/plain;charset=utf-8", False);
    p->a_UTF8_STRING     = fn.XInternAtom(dpy, "UTF8_STRING", False);
    p->a_text_plain      = fn.XInternAtom(dpy, "text/plain", False);
}

void popup_xdnd_set_aware(Display *dpy, Panel *p) {
    unsigned long version = XDND_VERSION;
    fn.XChangeProperty(dpy, p->win, p->a_XdndAware, XA_ATOM, 32,
                       PropModeReplace, (unsigned char *) &version, 1);
}

static void send_xdnd_client(Display *dpy, Window dest, Atom type,
                             long l0, long l1, long l2, long l3, long l4) {
    XEvent ev;
    memset(&ev, 0, sizeof(ev));
    ev.xclient.type = ClientMessage;
    ev.xclient.display = dpy;
    ev.xclient.window = dest;
    ev.xclient.message_type = type;
    ev.xclient.format = 32;
    ev.xclient.data.l[0] = l0;
    ev.xclient.data.l[1] = l1;
    ev.xclient.data.l[2] = l2;
    ev.xclient.data.l[3] = l3;
    ev.xclient.data.l[4] = l4;
    fn.XSendEvent(dpy, dest, False, NoEventMask, &ev);
    fn.XFlush(dpy);
}

/* Preference rank of a target this panel can read, lower is better; -1 when
 * unreadable. Same order as the GtkWindow bridge (dnd.rs): a URI list first,
 * so a browser link arrives as a URI rather than as its text, then UTF-8
 * text, then Latin-1 STRING, then text/plain of unknown charset. */
static int xdnd_target_rank(const Panel *p, Atom type) {
    if (type == None) return -1;
    if (type == p->a_text_uri_list) return 0;
    if (type == p->a_text_plain_utf8) return 1;
    if (type == p->a_UTF8_STRING) return 2;
    if (type == XA_STRING) return 3;
    if (type == p->a_text_plain) return 4;
    return -1;
}

static Atom xdnd_best_target(const Panel *p, const Atom *types, unsigned long n) {
    Atom best = None;
    int best_rank = -1;
    for (unsigned long i = 0; i < n; i++) {
        int rank = xdnd_target_rank(p, types[i]);
        if (rank >= 0 && (best_rank < 0 || rank < best_rank)) {
            best = types[i];
            best_rank = rank;
        }
    }
    return best;
}

static Atom xdnd_source_target(Display *dpy, Panel *p, const XClientMessageEvent *cm) {
    int more = cm->data.l[1] & 1;
    if (!more) {
        Atom types[3] = { (Atom) cm->data.l[2], (Atom) cm->data.l[3], (Atom) cm->data.l[4] };
        return xdnd_best_target(p, types, 3);
    }
    Window source = (Window) cm->data.l[0];
    Atom actual_type = None;
    int actual_format = 0;
    unsigned long nitems = 0, bytes_after = 0;
    unsigned char *prop = NULL;
    if (fn.XGetWindowProperty(dpy, source, p->a_XdndTypeList, 0, 64, False,
                              XA_ATOM, &actual_type, &actual_format, &nitems,
                              &bytes_after, &prop) != Success ||
        prop == NULL) {
        return None;
    }
    Atom best = xdnd_best_target(p, (const Atom *) prop, nitems);
    fn.XFree(prop);
    return best;
}

static int hex_nibble(int c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Decodes %XX escapes of `len` bytes of `src`; the result is never longer. */
static char *percent_decode(const char *src, size_t len) {
    char *dst = (char *) malloc(len + 1);
    if (dst == NULL) return NULL;
    size_t o = 0;
    for (size_t i = 0; i < len; i++) {
        if (src[i] == '%' && i + 2 < len) {
            int hi = hex_nibble((unsigned char) src[i + 1]);
            int lo = hex_nibble((unsigned char) src[i + 2]);
            if (hi >= 0 && lo >= 0) {
                dst[o++] = (char) ((hi << 4) | lo);
                i += 2;
                continue;
            }
        }
        dst[o++] = src[i];
    }
    dst[o] = '\0';
    return dst;
}

static char *dup_range(const char *src, size_t len) {
    char *out = (char *) malloc(len + 1);
    if (out == NULL) return NULL;
    memcpy(out, src, len);
    out[len] = '\0';
    return out;
}

static int is_file_uri(const char *uri, size_t len) {
    return len >= 5 && strncasecmp(uri, "file:", 5) == 0;
}

/* `file://[localhost]/p%20q` → `/p q`. A remote `file://host/…` URI is kept
 * as is: it is not a local path, and not a link the app could open either. */
static char *file_uri_to_path(const char *uri, size_t len) {
    const char *rest = uri + 5;
    size_t rest_len = len - 5;
    if (rest_len >= 2 && rest[0] == '/' && rest[1] == '/') {
        rest += 2;
        rest_len -= 2;
        if (rest_len >= 9 && strncasecmp(rest, "localhost", 9) == 0) {
            rest += 9;
            rest_len -= 9;
        }
        if (rest_len > 0 && rest[0] != '/') return dup_range(uri, len);
    }
    return percent_decode(rest, rest_len);
}

typedef struct {
    char *files[XDND_MAX_FILES];
    int nfiles;
    char *uris[XDND_MAX_FILES];
    int nuris;
    char *text; /* UTF-8, NULL when the drop carried none */
} XdndPayload;

static void xdnd_payload_free(XdndPayload *payload) {
    for (int i = 0; i < payload->nfiles; i++) free(payload->files[i]);
    for (int i = 0; i < payload->nuris; i++) free(payload->uris[i]);
    free(payload->text);
    memset(payload, 0, sizeof(*payload));
}

static int xdnd_payload_is_empty(const XdndPayload *payload) {
    return payload->nfiles == 0 && payload->nuris == 0 && payload->text == NULL;
}

/* RFC 2483: CRLF-separated URIs, `#` comment lines. File URIs become paths,
 * every other URI is kept verbatim. */
static void parse_uri_list(const char *data, size_t len, XdndPayload *out) {
    size_t i = 0;
    while (i < len) {
        size_t start = i;
        while (i < len && data[i] != '\r' && data[i] != '\n') i++;
        size_t end = i;
        while (i < len && (data[i] == '\r' || data[i] == '\n')) i++;
        while (start < end && (data[start] == ' ' || data[start] == '\t')) start++;
        while (end > start && (data[end - 1] == ' ' || data[end - 1] == '\t')) end--;
        if (end == start || data[start] == '#') continue;
        const char *uri = data + start;
        size_t uri_len = end - start;
        if (is_file_uri(uri, uri_len)) {
            if (out->nfiles >= XDND_MAX_FILES) continue;
            char *path = file_uri_to_path(uri, uri_len);
            if (path != NULL && path[0] != '\0') {
                out->files[out->nfiles++] = path;
            } else {
                free(path);
            }
        } else if (out->nuris < XDND_MAX_FILES) {
            char *copy = dup_range(uri, uri_len);
            if (copy != NULL) out->uris[out->nuris++] = copy;
        }
    }
}

/* STRING is ISO-8859-1 (ICCCM); every other text target is read as UTF-8. */
static char *text_to_utf8(const char *data, size_t len, int latin1) {
    char *out = (char *) malloc(latin1 ? len * 2 + 1 : len + 1);
    if (out == NULL) return NULL;
    size_t o = 0;
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char) data[i];
        if (c == '\0') break; /* some sources NUL-terminate inside the length */
        if (latin1 && c >= 0x80) {
            out[o++] = (char) (0xC0 | (c >> 6));
            out[o++] = (char) (0x80 | (c & 0x3F));
        } else {
            out[o++] = (char) c;
        }
    }
    out[o] = '\0';
    if (o == 0) {
        free(out);
        return NULL;
    }
    return out;
}

/* jstring from UTF-8 through UTF-16: NewStringUTF expects *modified* UTF-8
 * and mangles characters outside the BMP (emoji) and embedded NULs. Invalid
 * sequences become U+FFFD. */
static jstring new_jstring_utf8(JNIEnv *env, const char *s) {
    size_t len = strlen(s);
    jchar *buf = (jchar *) malloc((len + 1) * sizeof(jchar) * 2);
    if (buf == NULL) return NULL;
    size_t o = 0;
    for (size_t i = 0; i < len;) {
        unsigned char c = (unsigned char) s[i];
        uint32_t cp;
        int extra;
        if (c < 0x80) { cp = c; extra = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; extra = 3; }
        else { buf[o++] = 0xFFFD; i++; continue; }
        int ok = 1;
        for (int k = 1; k <= extra; k++) {
            if (i + k >= len || ((unsigned char) s[i + k] & 0xC0) != 0x80) { ok = 0; break; }
            cp = (cp << 6) | ((unsigned char) s[i + k] & 0x3F);
        }
        if (!ok) { buf[o++] = 0xFFFD; i++; continue; }
        i += (size_t) extra + 1;
        if (cp >= 0x10000 && cp <= 0x10FFFF) {
            cp -= 0x10000;
            buf[o++] = (jchar) (0xD800 | (cp >> 10));
            buf[o++] = (jchar) (0xDC00 | (cp & 0x3FF));
        } else if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            buf[o++] = 0xFFFD;
        } else {
            buf[o++] = (jchar) cp;
        }
    }
    jstring result = (*env)->NewString(env, buf, (jsize) o);
    free(buf);
    return result;
}

static jint call_dnd_motion(JNIEnv *env, Panel *p, jmethodID method, int x, int y, int has_data) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->dnd_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || method == NULL) return DROP_EFFECT_NONE;
    jint effect = (*env)->CallIntMethod(env, cb, method, (jlong) (uintptr_t) p,
                                        (jint) x, (jint) y, (jint) 0,
                                        has_data ? JNI_TRUE : JNI_FALSE);
    if (nucleus_jni_clear_exception(env)) {
        return DROP_EFFECT_NONE;
    }
    return effect;
}

static void call_dnd_leave(JNIEnv *env, Panel *p) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->dnd_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_drag_leave == NULL) return;
    (*env)->CallVoidMethod(env, cb, g_on_drag_leave, (jlong) (uintptr_t) p);
    nucleus_jni_clear_exception(env);
}

/* String[] for a non-empty list, NULL otherwise ("none" on the Kotlin side).
 * Sets *failed when the JVM could not build it. */
static jobjectArray new_string_array(JNIEnv *env, char **items, int n, int *failed) {
    if (n <= 0) return NULL;
    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    if (str_cls == NULL) {
        nucleus_jni_clear_exception(env);
        *failed = 1;
        return NULL;
    }
    jobjectArray arr = (*env)->NewObjectArray(env, n, str_cls, NULL);
    (*env)->DeleteLocalRef(env, str_cls);
    if (arr == NULL) {
        nucleus_jni_clear_exception(env);
        *failed = 1;
        return NULL;
    }
    for (int i = 0; i < n; i++) {
        jstring s = new_jstring_utf8(env, items[i]);
        if (s == NULL) {
            nucleus_jni_clear_exception(env);
            continue;
        }
        (*env)->SetObjectArrayElement(env, arr, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return arr;
}

static jint call_dnd_drop(JNIEnv *env, Panel *p, int x, int y, XdndPayload *payload) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->dnd_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_drag_drop == NULL) return DROP_EFFECT_NONE;

    int failed = 0;
    jobjectArray files = new_string_array(env, payload->files, payload->nfiles, &failed);
    jobjectArray uris = new_string_array(env, payload->uris, payload->nuris, &failed);
    jstring text = NULL;
    if (payload->text != NULL) {
        text = new_jstring_utf8(env, payload->text);
        if (text == NULL) {
            nucleus_jni_clear_exception(env);
            failed = 1;
        }
    }
    jint effect = DROP_EFFECT_NONE;
    if (!failed) {
        effect = (*env)->CallIntMethod(env, cb, g_on_drag_drop,
                                       (jlong) (uintptr_t) p,
                                       (jint) x, (jint) y, (jint) 0,
                                       files, text, uris);
        if (nucleus_jni_clear_exception(env)) effect = DROP_EFFECT_NONE;
    }
    if (files != NULL) (*env)->DeleteLocalRef(env, files);
    if (uris != NULL) (*env)->DeleteLocalRef(env, uris);
    if (text != NULL) (*env)->DeleteLocalRef(env, text);
    return effect;
}

static void xdnd_reset_session(Panel *p) {
    p->xdnd_source = None;
    p->xdnd_version = 0;
    p->xdnd_entered = 0;
    p->xdnd_target = None;
    p->xdnd_x = 0;
    p->xdnd_y = 0;
    p->xdnd_awaiting_sel = 0;
    p->xdnd_time = CurrentTime;
}

static void xdnd_send_status(Display *dpy, Panel *p, int accept) {
    if (p->xdnd_source == None) return;
    long flags = accept ? 1 : 0;
    flags |= 2;
    send_xdnd_client(dpy, p->xdnd_source, p->a_XdndStatus,
                     (long) p->win, flags, 0, 0,
                     accept ? (long) p->a_XdndActionCopy : 0);
}

static void xdnd_send_finished(Display *dpy, Panel *p, int accept) {
    if (p->xdnd_source == None) return;
    long flags = 0;
    long action = 0;
    if (p->xdnd_version >= 5) {
        flags = accept ? 1 : 0;
        action = accept ? (long) p->a_XdndActionCopy : 0;
    }
    send_xdnd_client(dpy, p->xdnd_source, p->a_XdndFinished,
                     (long) p->win, flags, action, 0, 0);
}

static void handle_xdnd_enter(Display *dpy, Panel *p, const XClientMessageEvent *cm) {
    xdnd_reset_session(p);
    p->xdnd_source = (Window) cm->data.l[0];
    p->xdnd_version = (int) ((cm->data.l[1] >> 24) & 0xFF);
    if (p->xdnd_version > XDND_VERSION) p->xdnd_version = XDND_VERSION;
    p->xdnd_target = xdnd_source_target(dpy, p, cm);
}

static void handle_xdnd_position(Display *dpy, JNIEnv *env, Panel *p,
                                 const XClientMessageEvent *cm) {
    Window source = (Window) cm->data.l[0];
    if (p->xdnd_source == None) {
        /* Position without Enter: assume the historical file drop. */
        p->xdnd_source = source;
        p->xdnd_version = XDND_VERSION;
        p->xdnd_target = p->a_text_uri_list;
    }
    if (source != p->xdnd_source) return;

    int root_x = (int) ((cm->data.l[2] >> 16) & 0xFFFF);
    int root_y = (int) (cm->data.l[2] & 0xFFFF);
    pthread_mutex_lock(&p->lock);
    int local_x = root_x - p->x;
    int local_y = root_y - p->y;
    pthread_mutex_unlock(&p->lock);
    p->xdnd_x = local_x;
    p->xdnd_y = local_y;
    if (cm->data.l[3]) p->xdnd_time = (Time) cm->data.l[3];

    int has_data = p->xdnd_target != None;
    jint effect;
    if (!p->xdnd_entered) {
        p->xdnd_entered = 1;
        effect = call_dnd_motion(env, p, g_on_drag_enter, local_x, local_y, has_data);
    } else {
        effect = call_dnd_motion(env, p, g_on_drag_over, local_x, local_y, has_data);
    }
    xdnd_send_status(dpy, p, effect != DROP_EFFECT_NONE);
}

static void handle_xdnd_leave(JNIEnv *env, Panel *p, const XClientMessageEvent *cm) {
    if (p->xdnd_source != None && (Window) cm->data.l[0] != p->xdnd_source) return;
    if (p->xdnd_entered) call_dnd_leave(env, p);
    xdnd_reset_session(p);
}

static void handle_xdnd_drop(Display *dpy, JNIEnv *env, Panel *p,
                             const XClientMessageEvent *cm) {
    if (p->xdnd_source != None && (Window) cm->data.l[0] != p->xdnd_source) return;
    Time time = cm->data.l[2] ? (Time) cm->data.l[2] : CurrentTime;
    p->xdnd_time = time;
    if (p->xdnd_target == None) {
        xdnd_send_finished(dpy, p, 0);
        if (p->xdnd_entered) call_dnd_leave(env, p);
        xdnd_reset_session(p);
        return;
    }
    fn.XConvertSelection(dpy, p->a_XdndSelection, p->xdnd_target,
                         p->a_XdndSelection, p->win, time);
    p->xdnd_awaiting_sel = 1;
}

void popup_xdnd_on_client_message(Display *dpy, JNIEnv *env, Panel *p,
                                  const XClientMessageEvent *cm) {
    Atom mtype = cm->message_type;
    if (mtype == p->a_XdndEnter) {
        handle_xdnd_enter(dpy, p, cm);
    } else if (mtype == p->a_XdndPosition) {
        handle_xdnd_position(dpy, env, p, cm);
    } else if (mtype == p->a_XdndLeave) {
        handle_xdnd_leave(env, p, cm);
    } else if (mtype == p->a_XdndDrop) {
        handle_xdnd_drop(dpy, env, p, cm);
    }
}

void popup_xdnd_on_selection_notify(Display *dpy, JNIEnv *env, Panel *p,
                                    const XSelectionEvent *se) {
    if (!p->xdnd_awaiting_sel) return;
    p->xdnd_awaiting_sel = 0;
    int accepted = 0;
    int dropped = 0;
    if (se->property != None) {
        Atom actual_type = None;
        int actual_format = 0;
        unsigned long nitems = 0, bytes_after = 0;
        unsigned char *prop = NULL;
        if (fn.XGetWindowProperty(dpy, p->win, se->property, 0, XDND_MAX_PROPERTY_LONGS,
                                  False, AnyPropertyType, &actual_type, &actual_format,
                                  &nitems, &bytes_after, &prop) == Success &&
            prop != NULL && actual_format == 8 && nitems > 0) {
            XdndPayload payload;
            memset(&payload, 0, sizeof(payload));
            if (se->target == p->a_text_uri_list) {
                parse_uri_list((const char *) prop, nitems, &payload);
            } else {
                payload.text = text_to_utf8((const char *) prop, nitems,
                                            se->target == XA_STRING);
            }
            if (!xdnd_payload_is_empty(&payload)) {
                dropped = 1;
                jint effect = call_dnd_drop(env, p, p->xdnd_x, p->xdnd_y, &payload);
                accepted = effect != DROP_EFFECT_NONE;
            }
            xdnd_payload_free(&payload);
        }
        if (prop != NULL) fn.XFree(prop);
        fn.XDeleteProperty(dpy, p->win, se->property);
    }
    /* onDrop ends the scene's session itself; without it, close it here. */
    if (!dropped && p->xdnd_entered) call_dnd_leave(env, p);
    xdnd_send_finished(dpy, p, accepted);
    xdnd_reset_session(p);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeSetDnDCallback(
    JNIEnv *env, jclass clazz, jlong panel, jobject callback)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL) return;
    if (g_jvm == NULL) (*env)->GetJavaVM(env, &g_jvm);
    jobject global = NULL;
    if (callback != NULL) {
        cache_dnd_callback_ids(env, callback);
        global = (*env)->NewGlobalRef(env, callback);
    }
    pthread_mutex_lock(&p->lock);
    jobject prev = p->dnd_cb;
    p->dnd_cb = global;
    pthread_mutex_unlock(&p->lock);
    if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
}

static int serve_selection_request(Display *dpy, const XSelectionRequestEvent *req,
                                   const char *payload, Atom offered) {
    Atom property = req->property;
    if (property == None) property = req->target;
    int ok = 0;
    if (payload != NULL && req->target == offered) {
        fn.XChangeProperty(dpy, req->requestor, property, req->target, 8,
                           PropModeReplace, (const unsigned char *) payload,
                           (int) strlen(payload));
        ok = 1;
    }
    XEvent notify;
    memset(&notify, 0, sizeof(notify));
    notify.xselection.type = SelectionNotify;
    notify.xselection.display = dpy;
    notify.xselection.requestor = req->requestor;
    notify.xselection.selection = req->selection;
    notify.xselection.target = req->target;
    notify.xselection.property = ok ? property : None;
    notify.xselection.time = req->time;
    fn.XSendEvent(dpy, req->requestor, False, NoEventMask, &notify);
    fn.XFlush(dpy);
    return ok;
}

static int pump_xdnd_source(Display *dpy, Window src, Atom want, Atom offered,
                            const char *payload, int timeout_ms, int *accepted) {
    struct timespec start, now;
    clock_gettime(CLOCK_MONOTONIC, &start);
    struct pollfd fd = { .fd = ConnectionNumber(dpy), .events = POLLIN };
    for (;;) {
        while (fn.XPending(dpy) > 0) {
            XEvent ev;
            fn.XNextEvent(dpy, &ev);
            if (ev.type == SelectionRequest) {
                serve_selection_request(dpy, &ev.xselectionrequest, payload, offered);
            } else if (ev.type == ClientMessage &&
                       ev.xclient.window == src &&
                       ev.xclient.message_type == want) {
                if (accepted != NULL) {
                    *accepted = (ev.xclient.data.l[1] & 1) ? 1 : 0;
                }
                return 1;
            }
        }
        clock_gettime(CLOCK_MONOTONIC, &now);
        long elapsed_ms = (long) ((now.tv_sec - start.tv_sec) * 1000 +
                                  (now.tv_nsec - start.tv_nsec) / 1000000);
        if (elapsed_ms >= timeout_ms) return 0;
        int wait = (int) (timeout_ms - elapsed_ms);
        if (wait < 1) wait = 1;
        poll(&fd, 1, wait);
    }
}

/* Test-only second X client: drags `data` offered as the single `target`
 * (`text/uri-list`, `UTF8_STRING`, …) onto the panel and drops it. */
EXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeSmokeXdndDrop(
    JNIEnv *env, jclass clazz, jlong panel, jstring target_name, jbyteArray data)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL || p->win == 0 || target_name == NULL || data == NULL) {
        return DROP_EFFECT_NONE;
    }
    jsize data_len = (*env)->GetArrayLength(env, data);
    char *payload = (char *) malloc((size_t) data_len + 1);
    if (payload == NULL) return DROP_EFFECT_NONE;
    (*env)->GetByteArrayRegion(env, data, 0, data_len, (jbyte *) payload);
    payload[data_len] = '\0';
    const char *name = (*env)->GetStringUTFChars(env, target_name, NULL);
    if (name == NULL) {
        free(payload);
        return DROP_EFFECT_NONE;
    }

    Display *dpy = fn.XOpenDisplay(NULL);
    if (dpy == NULL) {
        (*env)->ReleaseStringUTFChars(env, target_name, name);
        free(payload);
        return DROP_EFFECT_NONE;
    }
    Atom offered = fn.XInternAtom(dpy, name, False);
    (*env)->ReleaseStringUTFChars(env, target_name, name);

    pthread_mutex_lock(&p->lock);
    Window target = p->win;
    int tx = p->x, ty = p->y, tw = p->w, th = p->h;
    Atom a_enter = p->a_XdndEnter;
    Atom a_pos = p->a_XdndPosition;
    Atom a_drop = p->a_XdndDrop;
    Atom a_status = p->a_XdndStatus;
    Atom a_finished = p->a_XdndFinished;
    Atom a_sel = p->a_XdndSelection;
    Atom a_copy = p->a_XdndActionCopy;
    pthread_mutex_unlock(&p->lock);

    Window root = DefaultRootWindow(dpy);
    XSetWindowAttributes swa;
    memset(&swa, 0, sizeof(swa));
    swa.override_redirect = True;
    Window src = fn.XCreateWindow(dpy, root, 0, 0, 1, 1, 0, CopyFromParent,
                                  InputOutput, CopyFromParent,
                                  CWOverrideRedirect, &swa);
    if (src == 0) {
        fn.XCloseDisplay(dpy);
        free(payload);
        return DROP_EFFECT_NONE;
    }
    fn.XSelectInput(dpy, src, StructureNotifyMask);
    fn.XSetSelectionOwner(dpy, a_sel, src, CurrentTime);
    fn.XFlush(dpy);

    int drop_x = tx + (tw > 20 ? 10 : tw / 2);
    int drop_y = ty + (th > 20 ? 10 : th / 2);
    if (drop_x < 0) drop_x = 0;
    if (drop_y < 0) drop_y = 0;

    send_xdnd_client(dpy, target, a_enter,
                     (long) src, (long) (XDND_VERSION << 24),
                     (long) offered, 0, 0);
    send_xdnd_client(dpy, target, a_pos,
                     (long) src, 0,
                     ((long) drop_x << 16) | ((long) drop_y & 0xFFFF),
                     (long) CurrentTime, (long) a_copy);

    jint result = DROP_EFFECT_NONE;
    if (!pump_xdnd_source(dpy, src, a_status, offered, payload, 1500, NULL)) {
        DBG("smoke XDND: no XdndStatus\n");
    } else {
        send_xdnd_client(dpy, target, a_drop, (long) src, 0, (long) CurrentTime, 0, 0);
        int accepted = 0;
        if (!pump_xdnd_source(dpy, src, a_finished, offered, payload, 2000, &accepted)) {
            DBG("smoke XDND: no XdndFinished\n");
        } else if (accepted) {
            result = DROP_EFFECT_COPY;
        }
    }

    fn.XDestroyWindow(dpy, src);
    fn.XCloseDisplay(dpy);
    free(payload);
    return result;
}
