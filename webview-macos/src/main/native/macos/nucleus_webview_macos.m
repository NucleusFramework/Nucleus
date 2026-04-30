/**
 * nucleus_webview_macos.m
 *
 * Hosts a system WKWebView under a Compose Desktop window, with proper event
 * routing via AWTView hitTest swizzling.
 *
 * Architecture:
 *   borderView (NSNextStepFrame for undecorated transparent windows)
 *     ├─ WKWebView           (subview index 0, behind)
 *     └─ AWTView             (subview index 1, front; transparent MTLLayer)
 *
 * AWTView's `hitTest:` is swizzled to return nil whenever the hit point falls
 * inside the WebView region tracked from Kotlin via [nativeSetWebViewRegion].
 * AppKit then walks to the previous sibling (the WKWebView), which receives
 * the event natively (scroll, click, keyboard, etc.).
 *
 * Required Compose setup: `Window(transparent = true, undecorated = true)` and
 * window setup.
 *
 * Frameworks: Cocoa, WebKit
 */

#import <Cocoa/Cocoa.h>
#import <QuartzCore/CATransaction.h>
#import <WebKit/WebKit.h>
#import <objc/runtime.h>
#include <jni.h>

#define JNI_FN(name) \
    Java_io_github_kdroidfilter_nucleus_webview_macos_NativeWebViewBridge_##name

#define HANDLE_TO_OBJ(h, type) ((__bridge type *)(void *)(h))

static inline jlong retainToHandle(id obj) {
    if (obj == nil) return 0;
    return (jlong)CFBridgingRetain(obj);
}

static inline void releaseHandle(jlong handle) {
    if (handle != 0) CFBridgingRelease((void *)handle);
}

static void runOnMain(dispatch_block_t block) {
    if ([NSThread isMainThread]) block();
    else dispatch_sync(dispatch_get_main_queue(), block);
}

// Async variant — used for operations triggered from the AWT EDT during live
// resize/layout. Using dispatch_sync from EDT while AppKit's main thread holds
// a window-layout lock causes a deadlock (and a visible freeze).
static void runOnMainAsync(dispatch_block_t block) {
    if ([NSThread isMainThread]) block();
    else dispatch_async(dispatch_get_main_queue(), block);
}

// ─── AWTView hitTest swizzle for event passthrough ──────────────────────────────
// Per AWTView (key = NSView pointer), an NSValue-wrapped NSRect that defines
// the passthrough region in AWTView's bounds (top-left origin, points).
static NSMapTable<NSValue *, NSValue *> *gPassthroughRects = nil;
static dispatch_once_t gSwizzleOnce;

@implementation NSView (NucleusWebViewSwizzle)
- (NSView *)nucleus_swizzled_hitTest:(NSPoint)point {
    NSValue *key = [NSValue valueWithPointer:(__bridge const void *)self];
    NSValue *boxed = [gPassthroughRects objectForKey:key];
    if (boxed) {
        NSRect rect = [boxed rectValue];
        // The point passed to hitTest: is in the SUPERVIEW's coordinate space.
        // Convert to self's coordinates first.
        NSPoint local = [self convertPoint:point fromView:self.superview];
        if (NSPointInRect(local, rect)) {
            return nil; // passthrough → AppKit walks to previous sibling (WKWebView)
        }
    }
    return [self nucleus_swizzled_hitTest:point]; // calls original after swap
}
@end

static void ensureHitTestSwizzledOn(Class cls) {
    dispatch_once(&gSwizzleOnce, ^{
        gPassthroughRects = [NSMapTable mapTableWithKeyOptions:NSPointerFunctionsStrongMemory
                                                  valueOptions:NSPointerFunctionsStrongMemory];
    });

    static const char *kSwizzledKey = "nucleus_webview_swizzled";
    if (objc_getAssociatedObject(cls, kSwizzledKey)) return;
    objc_setAssociatedObject(cls, kSwizzledKey, @YES, OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    SEL origSel = @selector(hitTest:);
    SEL newSel  = @selector(nucleus_swizzled_hitTest:);
    Method origMethod = class_getInstanceMethod(cls, origSel);
    Method newMethod  = class_getInstanceMethod(cls, newSel);
    if (!origMethod || !newMethod) return;

    // Try to add the new method; if already present (subclass defined hitTest:),
    // exchange directly.
    BOOL added = class_addMethod(cls, origSel,
                                 method_getImplementation(newMethod),
                                 method_getTypeEncoding(newMethod));
    if (added) {
        class_replaceMethod(cls, newSel,
                            method_getImplementation(origMethod),
                            method_getTypeEncoding(origMethod));
    } else {
        method_exchangeImplementations(origMethod, newMethod);
    }
}

// ─── NSWindow pointer extraction from AWT Window (via Component.peer) ──────────

static jlong getNSWindowPtrFromAWTWindow(JNIEnv *env, jobject awtWindow) {
    if (!awtWindow) return 0;
    jclass componentClass = (*env)->FindClass(env, "java/awt/Component");
    if (!componentClass || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    jfieldID peerField = (*env)->GetFieldID(env, componentClass, "peer", "Ljava/awt/peer/ComponentPeer;");
    (*env)->DeleteLocalRef(env, componentClass);
    if (!peerField || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }

    jobject peer = (*env)->GetObjectField(env, awtWindow, peerField);
    if (!peer) return 0;

    jclass peerClass = (*env)->GetObjectClass(env, peer);
    jmethodID getPlatformWindow = (*env)->GetMethodID(env, peerClass,
        "getPlatformWindow", "()Lsun/lwawt/PlatformWindow;");
    (*env)->DeleteLocalRef(env, peerClass);
    if (!getPlatformWindow || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env); (*env)->DeleteLocalRef(env, peer); return 0;
    }
    jobject platformWindow = (*env)->CallObjectMethod(env, peer, getPlatformWindow);
    (*env)->DeleteLocalRef(env, peer);
    if (!platformWindow || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }

    jfieldID ptrField = NULL;
    jclass cls = (*env)->GetObjectClass(env, platformWindow);
    while (cls) {
        ptrField = (*env)->GetFieldID(env, cls, "ptr", "J");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env); ptrField = NULL;
            jclass parent = (*env)->GetSuperclass(env, cls);
            (*env)->DeleteLocalRef(env, cls); cls = parent;
        } else { (*env)->DeleteLocalRef(env, cls); break; }
    }
    if (!ptrField) { (*env)->DeleteLocalRef(env, platformWindow); return 0; }

    jlong result = (*env)->GetLongField(env, platformWindow, ptrField);
    (*env)->DeleteLocalRef(env, platformWindow);
    return result;
}

JNIEXPORT jlong JNICALL
JNI_FN(nativeGetNSWindowPtr)(JNIEnv *env, jclass clazz, jobject awtWindow) {
    return getNSWindowPtrFromAWTWindow(env, awtWindow);
}

// ─── WKWebView lifecycle ────────────────────────────────────────────────────────

@class NucleusWKNavigationDelegate;

@interface NucleusWebViewHolder : NSObject
@property (nonatomic, strong) WKWebView *webView;
@property (nonatomic, weak)   NSView    *parent;       // borderView
@property (nonatomic, weak)   NSView    *awtView;      // contentView
@property (nonatomic, strong) NucleusWKNavigationDelegate *navDelegate;
@property (nonatomic) jlong handle;
@end
@implementation NucleusWebViewHolder
@end

// ─── Navigation delegate that forwards loading state to Kotlin ─────────────────

static JavaVM *gJVM = NULL;
static jclass  gBridgeClass = NULL;
static jmethodID gOnLoadingChanged = NULL;

static void cacheJVM(JNIEnv *env) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        (*env)->GetJavaVM(env, &gJVM);
        jclass local = (*env)->FindClass(env,
            "io/github/kdroidfilter/nucleus/webview/macos/NativeWebViewBridge");
        if (local) {
            gBridgeClass = (*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
            gOnLoadingChanged = (*env)->GetStaticMethodID(env, gBridgeClass,
                "onLoadingStateChanged", "(JZ)V");
        }
    });
}

static void notifyLoadingChanged(jlong handle, BOOL loading) {
    if (!gJVM || !gBridgeClass || !gOnLoadingChanged) return;
    JNIEnv *env = NULL;
    jint status = (*gJVM)->GetEnv(gJVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*gJVM)->AttachCurrentThreadAsDaemon(gJVM, (void **)&env, NULL) != JNI_OK) return;
    } else if (status != JNI_OK) {
        return;
    }
    (*env)->CallStaticVoidMethod(env, gBridgeClass, gOnLoadingChanged, handle, loading ? JNI_TRUE : JNI_FALSE);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

@interface NucleusWKNavigationDelegate : NSObject<WKNavigationDelegate>
@property (nonatomic) jlong handle;
@end

@implementation NucleusWKNavigationDelegate
- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)navigation {
    notifyLoadingChanged(self.handle, YES);
}
- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    notifyLoadingChanged(self.handle, NO);
}
- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    notifyLoadingChanged(self.handle, NO);
}
- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    notifyLoadingChanged(self.handle, NO);
}
@end

// Configures a decorated AWT window so the AWT-managed metal layer is
// alpha-aware and a sibling WKWebView NSView shows through Compose's
// transparent areas. Idempotent.
//
// Note: we set NSWindow.opaque = NO so the window backing store is alpha-
// aware. The title bar area still appears with its standard look because the
// DecoratedWindow's Compose-rendered title bar paints an opaque background
// there (e.g. MaterialTitleBar's surface color).
JNIEXPORT void JNICALL
JNI_FN(nativeConfigureWindowForOverlay)(JNIEnv *env, jclass clazz, jlong nsWindowPtr) {
    if (nsWindowPtr == 0) return;
    runOnMain(^{
        NSWindow *window = (__bridge NSWindow *)(void *)nsWindowPtr;
        // The window's backing buffer must be alpha-aware so the AWTView's
        // transparent pixels composite against the sibling WKWebView.
        window.opaque = NO;
        window.backgroundColor = [NSColor clearColor];
        NSView *awtView = window.contentView;
        if (awtView && awtView.layer) {
            awtView.layer.opaque = NO;
            awtView.layer.backgroundColor = [NSColor clearColor].CGColor;
        }
    });
}

JNIEXPORT jlong JNICALL
JNI_FN(nativeCreate)(JNIEnv *env, jclass clazz, jlong nsWindowPtr) {
    if (nsWindowPtr == 0) return 0;
    cacheJVM(env);
    __block NucleusWebViewHolder *holder = nil;
    runOnMain(^{
        NSWindow *window = (__bridge NSWindow *)(void *)nsWindowPtr;
        NSView *awtView = window.contentView;
        if (!awtView) return;
        NSView *parent = awtView.superview;
        if (!parent) parent = awtView; // fallback

        WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
        WKWebView *webView = [[WKWebView alloc] initWithFrame:awtView.frame configuration:config];
        webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        webView.wantsLayer = YES;
        // Suppress implicit CALayer animations so live-resize updates apply
        // immediately rather than animating between intermediate frames.
        webView.layer.actions = @{
            @"position": [NSNull null],
            @"bounds":   [NSNull null],
            @"frame":    [NSNull null],
            @"contents": [NSNull null],
        };

        NucleusWKNavigationDelegate *navDelegate = [[NucleusWKNavigationDelegate alloc] init];
        webView.navigationDelegate = navDelegate;

        // Insert below the AWTView so Compose's transparent metal layer renders on top.
        [parent addSubview:webView positioned:NSWindowBelow relativeTo:awtView];

        // Install hitTest swizzle on AWTView's class so events fall through in
        // the WebView region.
        ensureHitTestSwizzledOn([awtView class]);

        holder = [[NucleusWebViewHolder alloc] init];
        holder.webView = webView;
        holder.parent  = parent;
        holder.awtView = awtView;
        holder.navDelegate = navDelegate;
    });
    if (holder) {
        jlong h = retainToHandle(holder);
        holder.handle = h;
        holder.navDelegate.handle = h;
        return h;
    }
    return 0;
}

JNIEXPORT void JNICALL
JNI_FN(nativeDestroy)(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    NucleusWebViewHolder *holder = HANDLE_TO_OBJ(handle, NucleusWebViewHolder);
    runOnMain(^{
        if (holder.awtView) {
            NSValue *key = [NSValue valueWithPointer:(__bridge const void *)holder.awtView];
            [gPassthroughRects removeObjectForKey:key];
        }
        WKWebView *webView = holder.webView;
        [webView stopLoading];
        webView.navigationDelegate = nil;
        webView.UIDelegate = nil;
        [webView removeFromSuperview];
        holder.webView = nil;
        holder.parent = nil;
        holder.awtView = nil;
    });
    releaseHandle(handle);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetFrame)(JNIEnv *env, jclass clazz,
                       jlong handle, jdouble x, jdouble yFromTop, jdouble w, jdouble h) {
    if (handle == 0) return;
    NucleusWebViewHolder *holder = HANDLE_TO_OBJ(handle, NucleusWebViewHolder);
    runOnMainAsync(^{
        WKWebView *webView = holder.webView;
        NSView *parent = webView.superview;
        if (!webView || !parent) return;

        // During a live resize, AppKit drives many fast layout passes and
        // Compose echoes them through onGloballyPositioned at a slower cadence.
        // Applying our async setFrame on top of the autoresizingMask-driven
        // resize causes visible flicker (size mismatch between adjacent frames).
        // Skip the explicit frame update while the window is in live resize —
        // the autoresizingMask handles the sizing synchronously, then we apply
        // the final Compose-computed frame once resize ends.
        if (parent.window != nil && parent.window.inLiveResize) {
            // Still update the passthrough rect so hit-testing tracks the new
            // bounds.
            NSView *awtView = holder.awtView;
            if (awtView) {
                CGFloat awtH = awtView.bounds.size.height;
                CGFloat py = awtView.isFlipped ? (CGFloat)yFromTop : (awtH - (CGFloat)yFromTop - (CGFloat)h);
                NSRect localRect = NSMakeRect((CGFloat)x, py, (CGFloat)w, (CGFloat)h);
                NSValue *key = [NSValue valueWithPointer:(__bridge const void *)awtView];
                [gPassthroughRects setObject:[NSValue valueWithRect:localRect] forKey:key];
            }
            return;
        }

        CGFloat parentH = parent.bounds.size.height;
        CGFloat ay = parent.isFlipped ? (CGFloat)yFromTop : (parentH - (CGFloat)yFromTop - (CGFloat)h);
        NSRect newFrame = NSMakeRect((CGFloat)x, ay, (CGFloat)w, (CGFloat)h);

        NSView *awtView = holder.awtView;

        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        webView.frame = newFrame;
        [CATransaction commit];

        if (awtView) {
            CGFloat awtH = awtView.bounds.size.height;
            CGFloat py = awtView.isFlipped ? (CGFloat)yFromTop : (awtH - (CGFloat)yFromTop - (CGFloat)h);
            NSRect localRect = NSMakeRect((CGFloat)x, py, (CGFloat)w, (CGFloat)h);
            NSValue *key = [NSValue valueWithPointer:(__bridge const void *)awtView];
            [gPassthroughRects setObject:[NSValue valueWithRect:localRect] forKey:key];
        }
    });
}

JNIEXPORT void JNICALL
JNI_FN(nativeLoadUrl)(JNIEnv *env, jclass clazz, jlong handle, jstring jUrl) {
    if (handle == 0 || !jUrl) return;
    const char *cUrl = (*env)->GetStringUTFChars(env, jUrl, NULL);
    if (!cUrl) return;
    NSString *urlString = [NSString stringWithUTF8String:cUrl];
    (*env)->ReleaseStringUTFChars(env, jUrl, cUrl);

    NucleusWebViewHolder *holder = HANDLE_TO_OBJ(handle, NucleusWebViewHolder);
    WKWebView *webView = holder.webView;
    runOnMain(^{
        NSURL *url = [NSURL URLWithString:urlString];
        if (!url) return;
        [webView loadRequest:[NSURLRequest requestWithURL:url]];
    });
}

JNIEXPORT void JNICALL
JNI_FN(nativeLoadHtml)(JNIEnv *env, jclass clazz, jlong handle, jstring jHtml, jstring jBaseUrl) {
    if (handle == 0 || !jHtml) return;
    const char *cHtml = (*env)->GetStringUTFChars(env, jHtml, NULL);
    if (!cHtml) return;
    NSString *html = [NSString stringWithUTF8String:cHtml];
    (*env)->ReleaseStringUTFChars(env, jHtml, cHtml);
    NSURL *baseUrl = nil;
    if (jBaseUrl) {
        const char *cBase = (*env)->GetStringUTFChars(env, jBaseUrl, NULL);
        if (cBase) {
            baseUrl = [NSURL URLWithString:[NSString stringWithUTF8String:cBase]];
            (*env)->ReleaseStringUTFChars(env, jBaseUrl, cBase);
        }
    }
    NucleusWebViewHolder *holder = HANDLE_TO_OBJ(handle, NucleusWebViewHolder);
    WKWebView *webView = holder.webView;
    runOnMain(^{ [webView loadHTMLString:html baseURL:baseUrl]; });
}

JNIEXPORT void JNICALL JNI_FN(nativeReload)(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    WKWebView *webView = ((__bridge NucleusWebViewHolder *)(void *)handle).webView;
    runOnMain(^{ [webView reload]; });
}
JNIEXPORT void JNICALL JNI_FN(nativeGoBack)(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    WKWebView *webView = ((__bridge NucleusWebViewHolder *)(void *)handle).webView;
    runOnMain(^{ if (webView.canGoBack) [webView goBack]; });
}
JNIEXPORT void JNICALL JNI_FN(nativeGoForward)(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    WKWebView *webView = ((__bridge NucleusWebViewHolder *)(void *)handle).webView;
    runOnMain(^{ if (webView.canGoForward) [webView goForward]; });
}
JNIEXPORT void JNICALL JNI_FN(nativeSetHidden)(JNIEnv *env, jclass clazz, jlong handle, jboolean hidden) {
    if (handle == 0) return;
    WKWebView *webView = ((__bridge NucleusWebViewHolder *)(void *)handle).webView;
    runOnMain(^{ webView.hidden = (hidden == JNI_TRUE); });
}
