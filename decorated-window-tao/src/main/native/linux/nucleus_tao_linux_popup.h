/**
 * Shared types for the Linux standalone popup .so:
 *   nucleus_tao_linux_popup.c      — window, event thread, input
 *   nucleus_tao_linux_popup_xdnd.c — inbound XDND + test source
 *
 * XDND ClientMessages are delivered to the *creating* client (event_mask=0),
 * so the protocol has to run on the event thread. The split keeps the panel
 * file from owning the protocol.
 */
#ifndef NUCLEUS_TAO_LINUX_POPUP_H
#define NUCLEUS_TAO_LINUX_POPUP_H

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>

#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <X11/extensions/XInput2.h>
#include <X11/extensions/Xrandr.h>

#define NUCLEUS_TAO_POPUP_DEBUG 0
#if NUCLEUS_TAO_POPUP_DEBUG
#define DBG(...) fprintf(stderr, "[nucleus_tao_linux_popup] " __VA_ARGS__)
#else
#define DBG(...) ((void)0)
#endif

#define EXPORT JNIEXPORT __attribute__((visibility("default")))

#define DROP_EFFECT_NONE 0
#define DROP_EFFECT_COPY 1
#define XDND_VERSION     5
#define XDND_MAX_FILES   64

typedef struct {
    int initialized;

    Display *(*XOpenDisplay)(const char *);
    int (*XCloseDisplay)(Display *);
    Window (*XCreateWindow)(Display *, Window, int, int, unsigned, unsigned,
                            unsigned, int, unsigned, Visual *, unsigned long,
                            XSetWindowAttributes *);
    int (*XDestroyWindow)(Display *, Window);
    int (*XMapRaised)(Display *, Window);
    int (*XUnmapWindow)(Display *, Window);
    int (*XRaiseWindow)(Display *, Window);
    int (*XMoveResizeWindow)(Display *, Window, int, int, unsigned, unsigned);
    int (*XFlush)(Display *);
    int (*XSync)(Display *, Bool);
    int (*XSelectInput)(Display *, Window, long);
    int (*XNextEvent)(Display *, XEvent *);
    int (*XPending)(Display *);
    Colormap (*XCreateColormap)(Display *, Window, Visual *, int);
    int (*XFreeColormap)(Display *, Colormap);
    XVisualInfo *(*XGetVisualInfo)(Display *, long, XVisualInfo *, int *);
    int (*XFree)(void *);
    int (*XSetInputFocus)(Display *, Window, int, Time);
    Cursor (*XCreateFontCursor)(Display *, unsigned int);
    int (*XDefineCursor)(Display *, Window, Cursor);
    int (*XFreeCursor)(Display *, Cursor);
    int (*XStoreName)(Display *, Window, const char *);
    char *(*XResourceManagerString)(Display *);
    int (*XLookupString)(XKeyEvent *, char *, int, KeySym *, XComposeStatus *);
    KeySym (*XkbKeycodeToKeysym)(Display *, KeyCode, unsigned, unsigned);
    Bool (*XQueryExtension)(Display *, const char *, int *, int *, int *);
    Bool (*XGetEventData)(Display *, XGenericEventCookie *);
    void (*XFreeEventData)(Display *, XGenericEventCookie *);
    Bool (*XQueryPointer)(Display *, Window, Window *, Window *, int *, int *,
                          int *, int *, unsigned *);

    Atom (*XInternAtom)(Display *, const char *, Bool);
    int (*XGetWindowProperty)(Display *, Window, Atom, long, long, Bool, Atom,
                              Atom *, int *, unsigned long *, unsigned long *,
                              unsigned char **);
    int (*XChangeProperty)(Display *, Window, Atom, Atom, int, int,
                           const unsigned char *, int);
    int (*XDeleteProperty)(Display *, Window, Atom);
    Status (*XSendEvent)(Display *, Window, Bool, long, XEvent *);
    int (*XConvertSelection)(Display *, Atom, Atom, Atom, Window, Time);
    int (*XSetSelectionOwner)(Display *, Atom, Window, Time);

    int (*XISelectEvents)(Display *, Window, XIEventMask *, int);
    int (*XIQueryVersion)(Display *, int *, int *);

    XRRMonitorInfo *(*XRRGetMonitors)(Display *, Window, Bool, int *);
    void (*XRRFreeMonitors)(XRRMonitorInfo *);

    uint32_t (*xkb_keysym_to_utf32)(uint32_t keysym);

    void *(*eglGetPlatformDisplay)(unsigned, void *, const intptr_t *);
    void *(*eglGetDisplay)(void *);
    int (*eglInitialize)(void *, int *, int *);
    int (*eglBindAPI)(unsigned);
    int (*eglChooseConfig)(void *, const int *, void **, int, int *);
    int (*eglGetConfigAttrib)(void *, void *, int, int *);
} PopupX11;

extern PopupX11 fn;
extern JavaVM *g_jvm;

typedef struct {
    Window   win;
    Colormap cmap;
    Cursor   cursor;
    VisualID visual_id;
    int      depth;

    pthread_mutex_t lock;
    pthread_cond_t  ready_cond;
    int x, y, w, h;
    int visible;
    int focusable;
    int ready;

    pthread_t evt_thread;
    int       evt_thread_started;
    int       quit_pipe[2];

    jobject event_cb;
    jobject outside_cb;
    jobject dnd_cb;

    Atom a_XdndAware;
    Atom a_XdndEnter;
    Atom a_XdndPosition;
    Atom a_XdndStatus;
    Atom a_XdndLeave;
    Atom a_XdndDrop;
    Atom a_XdndFinished;
    Atom a_XdndSelection;
    Atom a_XdndTypeList;
    Atom a_XdndActionCopy;
    Atom a_text_uri_list;

    Window xdnd_source;
    int    xdnd_version;
    int    xdnd_entered;
    int    xdnd_has_files;
    int    xdnd_x;
    int    xdnd_y;
    int    xdnd_awaiting_sel;
    Time   xdnd_time;
} Panel;

void popup_xdnd_intern_atoms(Display *dpy, Panel *p);
void popup_xdnd_set_aware(Display *dpy, Panel *p);
void popup_xdnd_on_client_message(Display *dpy, JNIEnv *env, Panel *p,
                                  const XClientMessageEvent *cm);
void popup_xdnd_on_selection_notify(Display *dpy, JNIEnv *env, Panel *p,
                                    const XSelectionEvent *se);

#endif /* NUCLEUS_TAO_LINUX_POPUP_H */
