/**
 * JNI bridge for Linux native window move via _NET_WM_MOVERESIZE.
 *
 * Replicates the JBR's XNETProtocol logic:
 *   1. Acquire AWT lock (SunToolkit.awtLock())
 *   2. Ungrab pointer and keyboard
 *   3. Send _NET_WM_MOVERESIZE ClientMessage to the root window
 *   4. XFlush + release AWT lock
 *
 * X11 handles are obtained via JNI reflection into AWT internals
 * (bypasses JPMS restrictions, same pattern as the Windows nativeGetHwnd).
 *
 * Linked libraries: -lX11
 */

#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <X11/Xlib.h>
#include <X11/Xatom.h>

#define _NET_WM_MOVERESIZE_MOVE     8
#define _NET_WM_MOVERESIZE_CANCEL  11

/* ------------------------------------------------------------------ */
/*  Helper: get X11 Display* from AWT (XToolkit.getDisplay())          */
/* ------------------------------------------------------------------ */
static Display *getAwtDisplay(JNIEnv *env) {
    jclass xToolkitClass = (*env)->FindClass(env, "sun/awt/X11/XToolkit");
    if (!xToolkitClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    jmethodID getDisplay = (*env)->GetStaticMethodID(env, xToolkitClass, "getDisplay", "()J");
    if (!getDisplay || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, xToolkitClass);
        return NULL;
    }

    jlong displayPtr = (*env)->CallStaticLongMethod(env, xToolkitClass, getDisplay);
    (*env)->DeleteLocalRef(env, xToolkitClass);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    return (Display *)(uintptr_t)displayPtr;
}

/* ------------------------------------------------------------------ */
/*  Helper: get X11 Window from AWT peer                               */
/*  AWTAccessor → getComponentAccessor() → getPeer(window) →           */
/*  XBaseWindow.getWindow() (returns the shell window ID)              */
/* ------------------------------------------------------------------ */
static Window getAwtX11Window(JNIEnv *env, jobject awtWindow) {
    if (!awtWindow) return 0;

    /* AWTAccessor.getComponentAccessor() */
    jclass awtAccessorClass = (*env)->FindClass(env, "sun/awt/AWTAccessor");
    if (!awtAccessorClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    jmethodID getCompAccessor = (*env)->GetStaticMethodID(env, awtAccessorClass,
        "getComponentAccessor", "()Lsun/awt/AWTAccessor$ComponentAccessor;");
    if (!getCompAccessor || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, awtAccessorClass);
        return 0;
    }

    jobject compAccessor = (*env)->CallStaticObjectMethod(env, awtAccessorClass, getCompAccessor);
    (*env)->DeleteLocalRef(env, awtAccessorClass);
    if (!compAccessor || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    /* componentAccessor.getPeer(window) */
    jclass compAccessorClass = (*env)->FindClass(env, "sun/awt/AWTAccessor$ComponentAccessor");
    if (!compAccessorClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, compAccessor);
        return 0;
    }

    jmethodID getPeer = (*env)->GetMethodID(env, compAccessorClass,
        "getPeer", "(Ljava/awt/Component;)Ljava/awt/peer/ComponentPeer;");
    (*env)->DeleteLocalRef(env, compAccessorClass);
    if (!getPeer || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, compAccessor);
        return 0;
    }

    jobject peer = (*env)->CallObjectMethod(env, compAccessor, getPeer, awtWindow);
    (*env)->DeleteLocalRef(env, compAccessor);
    if (!peer || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    /* peer.getWindow() — XBaseWindow.getWindow() returns the X11 window ID */
    jclass xBaseWindowClass = (*env)->FindClass(env, "sun/awt/X11/XBaseWindow");
    if (!xBaseWindowClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, peer);
        return 0;
    }

    jmethodID getWindow = (*env)->GetMethodID(env, xBaseWindowClass, "getWindow", "()J");
    (*env)->DeleteLocalRef(env, xBaseWindowClass);
    if (!getWindow || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, peer);
        return 0;
    }

    jlong windowId = (*env)->CallLongMethod(env, peer, getWindow);
    (*env)->DeleteLocalRef(env, peer);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    return (Window)windowId;
}

/* ------------------------------------------------------------------ */
/*  Helper: acquire/release AWT lock via SunToolkit                    */
/* ------------------------------------------------------------------ */
static jboolean awtLock(JNIEnv *env) {
    jclass sunToolkitClass = (*env)->FindClass(env, "sun/awt/SunToolkit");
    if (!sunToolkitClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }
    jmethodID lockMethod = (*env)->GetStaticMethodID(env, sunToolkitClass, "awtLock", "()V");
    if (!lockMethod || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, sunToolkitClass);
        return JNI_FALSE;
    }
    (*env)->CallStaticVoidMethod(env, sunToolkitClass, lockMethod);
    (*env)->DeleteLocalRef(env, sunToolkitClass);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static void awtUnlock(JNIEnv *env) {
    jclass sunToolkitClass = (*env)->FindClass(env, "sun/awt/SunToolkit");
    if (!sunToolkitClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return;
    }
    jmethodID unlockMethod = (*env)->GetStaticMethodID(env, sunToolkitClass, "awtUnlock", "()V");
    if (!unlockMethod || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, sunToolkitClass);
        return;
    }
    (*env)->CallStaticVoidMethod(env, sunToolkitClass, unlockMethod);
    (*env)->DeleteLocalRef(env, sunToolkitClass);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

/* ------------------------------------------------------------------ */
/*  nativeStartWindowMove                                              */
/*  Sends _NET_WM_MOVERESIZE ClientMessage to initiate a native WM    */
/*  move. This gives us snap/tile support and native drag feel.        */
/* ------------------------------------------------------------------ */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_window_utils_linux_JniLinuxWindowBridge_nativeStartWindowMove(
    JNIEnv *env, jclass clazz, jobject awtWindow, jint rootX, jint rootY, jint button)
{
    Display *display = getAwtDisplay(env);
    if (!display) return JNI_FALSE;

    Window xWindow = getAwtX11Window(env, awtWindow);
    if (!xWindow) return JNI_FALSE;

    /* Acquire AWT lock — required before any direct Xlib call on AWT's Display */
    if (!awtLock(env)) return JNI_FALSE;

    /* Determine the root window */
    Window rootWindow = XDefaultRootWindow(display);

    /*
     * Query the REAL root coordinates via XQueryPointer.
     * Java's MouseInfo.getPointerInfo().location returns logical (scaled)
     * coordinates on HiDPI screens, but _NET_WM_MOVERESIZE requires
     * physical X11 root-window coordinates. XQueryPointer always returns
     * unscaled physical pixels, which is exactly what the WM expects.
     */
    Window queryRoot, queryChild;
    int physRootX, physRootY, winX, winY;
    unsigned int mask;
    Bool queryOk = XQueryPointer(display, rootWindow,
                                 &queryRoot, &queryChild,
                                 &physRootX, &physRootY,
                                 &winX, &winY, &mask);

    if (!queryOk) {
        /* Fallback to the (possibly scaled) coordinates from Java */
        physRootX = rootX;
        physRootY = rootY;
    }

    /* Release AWT's pointer and keyboard grabs so the WM can take over */
    XUngrabPointer(display, CurrentTime);
    XUngrabKeyboard(display, CurrentTime);

    /* Intern the atom */
    Atom wmMoveResize = XInternAtom(display, "_NET_WM_MOVERESIZE", False);

    /* Build and send the ClientMessage */
    XEvent event;
    memset(&event, 0, sizeof(event));
    event.xclient.type = ClientMessage;
    event.xclient.window = xWindow;
    event.xclient.message_type = wmMoveResize;
    event.xclient.format = 32;
    event.xclient.data.l[0] = physRootX;                    /* x_root (physical) */
    event.xclient.data.l[1] = physRootY;                    /* y_root (physical) */
    event.xclient.data.l[2] = _NET_WM_MOVERESIZE_MOVE;     /* direction */
    event.xclient.data.l[3] = button;                       /* X11 button (1=left) */
    event.xclient.data.l[4] = 1;                            /* source indication: application */

    XSendEvent(display, rootWindow, False,
               SubstructureRedirectMask | SubstructureNotifyMask,
               &event);

    XFlush(display);

    awtUnlock(env);

    return JNI_TRUE;
}

/* ------------------------------------------------------------------ */
/*  nativeSetFullscreen                                                 */
/*  Toggles _NET_WM_STATE_FULLSCREEN on the window via a               */
/*  _NET_WM_STATE ClientMessage to the root window.                    */
/* ------------------------------------------------------------------ */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_window_utils_linux_JniLinuxWindowBridge_nativeSetFullscreen(
    JNIEnv *env, jclass clazz, jobject awtWindow, jboolean fullscreen)
{
    Display *display = getAwtDisplay(env);
    if (!display) return JNI_FALSE;

    Window xWindow = getAwtX11Window(env, awtWindow);
    if (!xWindow) return JNI_FALSE;

    if (!awtLock(env)) return JNI_FALSE;

    Window rootWindow = XDefaultRootWindow(display);
    Atom wmState = XInternAtom(display, "_NET_WM_STATE", False);
    Atom wmStateFullscreen = XInternAtom(display, "_NET_WM_STATE_FULLSCREEN", False);

    XEvent event;
    memset(&event, 0, sizeof(event));
    event.xclient.type = ClientMessage;
    event.xclient.window = xWindow;
    event.xclient.message_type = wmState;
    event.xclient.format = 32;
    event.xclient.data.l[0] = fullscreen ? 1 : 0;  /* _NET_WM_STATE_ADD or _REMOVE */
    event.xclient.data.l[1] = (long)wmStateFullscreen;
    event.xclient.data.l[2] = 0;
    event.xclient.data.l[3] = 1;  /* source indication: application */
    event.xclient.data.l[4] = 0;

    XSendEvent(display, rootWindow, False,
               SubstructureRedirectMask | SubstructureNotifyMask,
               &event);

    XFlush(display);

    awtUnlock(env);

    return JNI_TRUE;
}

/* ------------------------------------------------------------------ */
/*  nativeIsFullscreen                                                  */
/*  Checks if _NET_WM_STATE_FULLSCREEN is set on the window.          */
/* ------------------------------------------------------------------ */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_window_utils_linux_JniLinuxWindowBridge_nativeIsFullscreen(
    JNIEnv *env, jclass clazz, jobject awtWindow)
{
    Display *display = getAwtDisplay(env);
    if (!display) return JNI_FALSE;

    Window xWindow = getAwtX11Window(env, awtWindow);
    if (!xWindow) return JNI_FALSE;

    if (!awtLock(env)) return JNI_FALSE;

    Atom wmState = XInternAtom(display, "_NET_WM_STATE", False);
    Atom wmStateFullscreen = XInternAtom(display, "_NET_WM_STATE_FULLSCREEN", False);

    Atom actualType;
    int actualFormat;
    unsigned long nItems, bytesAfter;
    unsigned char *data = NULL;

    jboolean isFullscreen = JNI_FALSE;

    int result = XGetWindowProperty(display, xWindow, wmState,
                                    0, 1024, False, XA_ATOM,
                                    &actualType, &actualFormat,
                                    &nItems, &bytesAfter, &data);

    if (result == Success && data && actualType == XA_ATOM && actualFormat == 32) {
        Atom *atoms = (Atom *)data;
        for (unsigned long i = 0; i < nItems; i++) {
            if (atoms[i] == wmStateFullscreen) {
                isFullscreen = JNI_TRUE;
                break;
            }
        }
    }

    if (data) XFree(data);

    awtUnlock(env);

    return isFullscreen;
}

/* ------------------------------------------------------------------ */
/*  nativeIsWmMoveResizeSupported                                      */
/*  Checks if the WM advertises _NET_WM_MOVERESIZE in _NET_SUPPORTED. */
/* ------------------------------------------------------------------ */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_window_utils_linux_JniLinuxWindowBridge_nativeIsWmMoveResizeSupported(
    JNIEnv *env, jclass clazz, jobject awtWindow)
{
    Display *display = getAwtDisplay(env);
    if (!display) return JNI_FALSE;

    if (!awtLock(env)) return JNI_FALSE;

    Window rootWindow = XDefaultRootWindow(display);

    Atom netSupported = XInternAtom(display, "_NET_SUPPORTED", False);
    Atom wmMoveResize = XInternAtom(display, "_NET_WM_MOVERESIZE", False);

    Atom actualType;
    int actualFormat;
    unsigned long nItems, bytesAfter;
    unsigned char *data = NULL;

    jboolean supported = JNI_FALSE;

    int result = XGetWindowProperty(display, rootWindow, netSupported,
                                    0, 1024, False, XA_ATOM,
                                    &actualType, &actualFormat,
                                    &nItems, &bytesAfter, &data);

    if (result == Success && data && actualType == XA_ATOM && actualFormat == 32) {
        Atom *atoms = (Atom *)data;
        for (unsigned long i = 0; i < nItems; i++) {
            if (atoms[i] == wmMoveResize) {
                supported = JNI_TRUE;
                break;
            }
        }
    }

    if (data) XFree(data);

    awtUnlock(env);

    return supported;
}
