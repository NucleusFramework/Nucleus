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

#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static jmethodID g_on_drag_enter = NULL; /* (JIIIZ)I */
static jmethodID g_on_drag_over  = NULL; /* (JIIIZ)I */
static jmethodID g_on_drag_leave = NULL; /* (J)V     */
static jmethodID g_on_drag_drop  = NULL; /* (JIII[Ljava/lang/String;)I */

static void cache_dnd_callback_ids(JNIEnv *env, jobject callback) {
    if (g_on_drag_enter != NULL) return;
    jclass cls = (*env)->GetObjectClass(env, callback);
    if (cls == NULL) return;
    g_on_drag_enter = (*env)->GetMethodID(env, cls, "onDragEnter", "(JIIIZ)I");
    g_on_drag_over  = (*env)->GetMethodID(env, cls, "onDragOver",  "(JIIIZ)I");
    g_on_drag_leave = (*env)->GetMethodID(env, cls, "onDragLeave", "(J)V");
    g_on_drag_drop  = (*env)->GetMethodID(env, cls, "onDrop",
                                         "(JIII[Ljava/lang/String;)I");
    (*env)->DeleteLocalRef(env, cls);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

void popup_xdnd_intern_atoms(Display *dpy, Panel *p) {
    p->a_XdndAware      = fn.XInternAtom(dpy, "XdndAware", False);
    p->a_XdndEnter      = fn.XInternAtom(dpy, "XdndEnter", False);
    p->a_XdndPosition   = fn.XInternAtom(dpy, "XdndPosition", False);
    p->a_XdndStatus     = fn.XInternAtom(dpy, "XdndStatus", False);
    p->a_XdndLeave      = fn.XInternAtom(dpy, "XdndLeave", False);
    p->a_XdndDrop       = fn.XInternAtom(dpy, "XdndDrop", False);
    p->a_XdndFinished   = fn.XInternAtom(dpy, "XdndFinished", False);
    p->a_XdndSelection  = fn.XInternAtom(dpy, "XdndSelection", False);
    p->a_XdndTypeList   = fn.XInternAtom(dpy, "XdndTypeList", False);
    p->a_XdndActionCopy = fn.XInternAtom(dpy, "XdndActionCopy", False);
    p->a_text_uri_list  = fn.XInternAtom(dpy, "text/uri-list", False);
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

static int xdnd_type_is_uri_list(Panel *p, Atom type) {
    return type != None && type == p->a_text_uri_list;
}

static int xdnd_source_offers_files(Display *dpy, Panel *p, const XClientMessageEvent *cm) {
    int more = cm->data.l[1] & 1;
    if (!more) {
        return xdnd_type_is_uri_list(p, (Atom) cm->data.l[2]) ||
               xdnd_type_is_uri_list(p, (Atom) cm->data.l[3]) ||
               xdnd_type_is_uri_list(p, (Atom) cm->data.l[4]);
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
        return 0;
    }
    int has = 0;
    Atom *atoms = (Atom *) prop;
    for (unsigned long i = 0; i < nitems; i++) {
        if (xdnd_type_is_uri_list(p, atoms[i])) {
            has = 1;
            break;
        }
    }
    fn.XFree(prop);
    return has;
}

static int hex_nibble(int c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static void percent_decode(const char *src, char *dst, size_t dst_cap) {
    size_t o = 0;
    for (size_t i = 0; src[i] != '\0' && o + 1 < dst_cap; i++) {
        if (src[i] == '%' && src[i + 1] && src[i + 2]) {
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
}

static void uri_to_path(const char *uri, char *out, size_t out_cap) {
    const char *rest = uri;
    if (strncmp(uri, "file://", 7) == 0) {
        rest = uri + 7;
        if (strncmp(rest, "localhost", 9) == 0 &&
            (rest[9] == '/' || rest[9] == '\0')) {
            rest += 9;
        }
    }
    percent_decode(rest, out, out_cap);
}

static int parse_uri_list(const char *data, char paths[][4096], int max_paths) {
    int n = 0;
    const char *p = data;
    while (*p && n < max_paths) {
        while (*p == '\r' || *p == '\n') p++;
        if (*p == '\0') break;
        if (*p == '#') {
            while (*p && *p != '\n') p++;
            continue;
        }
        const char *start = p;
        while (*p && *p != '\r' && *p != '\n') p++;
        size_t len = (size_t) (p - start);
        if (len == 0) continue;
        char uri[4096];
        if (len >= sizeof(uri)) len = sizeof(uri) - 1;
        memcpy(uri, start, len);
        uri[len] = '\0';
        uri_to_path(uri, paths[n], 4096);
        if (paths[n][0] != '\0') n++;
    }
    return n;
}

static jint call_dnd_motion(JNIEnv *env, Panel *p, jmethodID method, int x, int y, int has_files) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->dnd_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || method == NULL) return DROP_EFFECT_NONE;
    jint effect = (*env)->CallIntMethod(env, cb, method, (jlong) (uintptr_t) p,
                                        (jint) x, (jint) y, (jint) 0,
                                        has_files ? JNI_TRUE : JNI_FALSE);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
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
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static jint call_dnd_drop(JNIEnv *env, Panel *p, int x, int y,
                          char paths[][4096], int npaths) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->dnd_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_drag_drop == NULL) return DROP_EFFECT_NONE;

    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    if (str_cls == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return DROP_EFFECT_NONE;
    }
    jobjectArray arr = (*env)->NewObjectArray(env, npaths, str_cls, NULL);
    (*env)->DeleteLocalRef(env, str_cls);
    if (arr == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return DROP_EFFECT_NONE;
    }
    for (int i = 0; i < npaths; i++) {
        jstring s = (*env)->NewStringUTF(env, paths[i]);
        if (s == NULL) {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            continue;
        }
        (*env)->SetObjectArrayElement(env, arr, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    jint effect = (*env)->CallIntMethod(env, cb, g_on_drag_drop,
                                        (jlong) (uintptr_t) p,
                                        (jint) x, (jint) y, (jint) 0, arr);
    (*env)->DeleteLocalRef(env, arr);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return DROP_EFFECT_NONE;
    }
    return effect;
}

static void xdnd_reset_session(Panel *p) {
    p->xdnd_source = None;
    p->xdnd_version = 0;
    p->xdnd_entered = 0;
    p->xdnd_has_files = 0;
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
    p->xdnd_has_files = xdnd_source_offers_files(dpy, p, cm);
}

static void handle_xdnd_position(Display *dpy, JNIEnv *env, Panel *p,
                                 const XClientMessageEvent *cm) {
    Window source = (Window) cm->data.l[0];
    if (p->xdnd_source == None) {
        p->xdnd_source = source;
        p->xdnd_version = XDND_VERSION;
        p->xdnd_has_files = 1;
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

    jint effect;
    if (!p->xdnd_entered) {
        p->xdnd_entered = 1;
        effect = call_dnd_motion(env, p, g_on_drag_enter, local_x, local_y,
                                 p->xdnd_has_files);
    } else {
        effect = call_dnd_motion(env, p, g_on_drag_over, local_x, local_y,
                                 p->xdnd_has_files);
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
    if (!p->xdnd_has_files) {
        xdnd_send_finished(dpy, p, 0);
        if (p->xdnd_entered) call_dnd_leave(env, p);
        xdnd_reset_session(p);
        return;
    }
    fn.XConvertSelection(dpy, p->a_XdndSelection, p->a_text_uri_list,
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
    if (se->property != None) {
        Atom actual_type = None;
        int actual_format = 0;
        unsigned long nitems = 0, bytes_after = 0;
        unsigned char *prop = NULL;
        if (fn.XGetWindowProperty(dpy, p->win, se->property, 0, 65536, False,
                                  AnyPropertyType, &actual_type, &actual_format,
                                  &nitems, &bytes_after, &prop) == Success &&
            prop != NULL && actual_format == 8 && nitems > 0) {
            char *text = (char *) malloc(nitems + 1);
            if (text != NULL) {
                memcpy(text, prop, nitems);
                text[nitems] = '\0';
                char paths[XDND_MAX_FILES][4096];
                int npaths = parse_uri_list(text, paths, XDND_MAX_FILES);
                free(text);
                if (npaths > 0) {
                    jint effect = call_dnd_drop(env, p, p->xdnd_x, p->xdnd_y,
                                                paths, npaths);
                    accepted = effect != DROP_EFFECT_NONE;
                }
            }
        }
        if (prop != NULL) fn.XFree(prop);
        fn.XDeleteProperty(dpy, p->win, se->property);
    }
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

static void percent_encode_append(char *buf, size_t cap, size_t *len, unsigned char b) {
    int safe = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') ||
               (b >= '0' && b <= '9') || b == '-' || b == '_' ||
               b == '.' || b == '~' || b == '/';
    if (*len + 4 >= cap) return;
    if (safe) {
        buf[(*len)++] = (char) b;
    } else {
        static const char hex[] = "0123456789ABCDEF";
        buf[(*len)++] = '%';
        buf[(*len)++] = hex[b >> 4];
        buf[(*len)++] = hex[b & 0xF];
    }
}

static char *build_uri_list(JNIEnv *env, jobjectArray files) {
    if (files == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, files);
    if (n <= 0) return NULL;
    char *buf = (char *) malloc(8192);
    if (buf == NULL) return NULL;
    size_t len = 0;
    buf[0] = '\0';
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, files, i);
        if (js == NULL) continue;
        const char *path = (*env)->GetStringUTFChars(env, js, NULL);
        if (path != NULL) {
            const char *prefix = "file://";
            size_t plen = strlen(prefix);
            if (len + plen + 4 < 8192) {
                memcpy(buf + len, prefix, plen);
                len += plen;
            }
            for (const unsigned char *c = (const unsigned char *) path; *c; c++) {
                percent_encode_append(buf, 8192, &len, *c);
            }
            if (len + 3 < 8192) {
                buf[len++] = '\r';
                buf[len++] = '\n';
            }
            (*env)->ReleaseStringUTFChars(env, js, path);
        }
        (*env)->DeleteLocalRef(env, js);
    }
    buf[len] = '\0';
    if (len == 0) {
        free(buf);
        return NULL;
    }
    return buf;
}

static int serve_selection_request(Display *dpy, const XSelectionRequestEvent *req,
                                   const char *payload, Atom text_uri) {
    Atom property = req->property;
    if (property == None) property = req->target;
    int ok = 0;
    if (payload != NULL &&
        (req->target == text_uri || req->target == XA_STRING)) {
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

static int pump_xdnd_source(Display *dpy, Window src, Atom want, Atom text_uri,
                            const char *payload, int timeout_ms, int *accepted) {
    struct timespec start, now;
    clock_gettime(CLOCK_MONOTONIC, &start);
    struct pollfd fd = { .fd = ConnectionNumber(dpy), .events = POLLIN };
    for (;;) {
        while (fn.XPending(dpy) > 0) {
            XEvent ev;
            fn.XNextEvent(dpy, &ev);
            if (ev.type == SelectionRequest) {
                serve_selection_request(dpy, &ev.xselectionrequest, payload, text_uri);
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

EXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeSmokeXdndDrop(
    JNIEnv *env, jclass clazz, jlong panel, jobjectArray files)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL || p->win == 0) return DROP_EFFECT_NONE;
    char *payload = build_uri_list(env, files);
    if (payload == NULL) return DROP_EFFECT_NONE;

    Display *dpy = fn.XOpenDisplay(NULL);
    if (dpy == NULL) {
        free(payload);
        return DROP_EFFECT_NONE;
    }

    pthread_mutex_lock(&p->lock);
    Window target = p->win;
    int tx = p->x, ty = p->y, tw = p->w, th = p->h;
    Atom text_uri = p->a_text_uri_list;
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
                     (long) text_uri, 0, 0);
    send_xdnd_client(dpy, target, a_pos,
                     (long) src, 0,
                     ((long) drop_x << 16) | ((long) drop_y & 0xFFFF),
                     (long) CurrentTime, (long) a_copy);

    if (!pump_xdnd_source(dpy, src, a_status, text_uri, payload, 1500, NULL)) {
        DBG("smoke XDND: no XdndStatus\n");
        fn.XDestroyWindow(dpy, src);
        fn.XCloseDisplay(dpy);
        free(payload);
        return DROP_EFFECT_NONE;
    }

    send_xdnd_client(dpy, target, a_drop, (long) src, 0, (long) CurrentTime, 0, 0);
    int accepted = 0;
    if (!pump_xdnd_source(dpy, src, a_finished, text_uri, payload, 2000, &accepted)) {
        DBG("smoke XDND: no XdndFinished\n");
        fn.XDestroyWindow(dpy, src);
        fn.XCloseDisplay(dpy);
        free(payload);
        return DROP_EFFECT_NONE;
    }

    fn.XDestroyWindow(dpy, src);
    fn.XCloseDisplay(dpy);
    free(payload);
    return accepted ? DROP_EFFECT_COPY : DROP_EFFECT_NONE;
}
