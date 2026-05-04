// dnd.m
//
// Inbound + outbound OS drag-and-drop for the Tao macOS backend.
//
// Mirrors `windows/nucleus_tao_dnd.c` semantically:
//   - JNI exports `nativeRegister`, `nativeRevoke`, `nativeStartDrag` reachable
//     from `NativeTaoMacOsDndBridge` on the Kotlin side.
//   - Inbound: swizzles the Tao NSView's <NSDraggingDestination> protocol
//     methods (draggingEntered/Updated/Exited, prepareForDragOperation,
//     performDragOperation) to forward into a per-view Java callback.
//   - Outbound: spins up a `<NSDraggingSource>` carrying file URLs and/or
//     plain text, then drives `beginDraggingSessionWithItems:event:source:`
//     and pumps the AppKit run loop until the session ends so the call is
//     synchronous (matches the contract of Win32's `DoDragDrop`).
//
// Shipped as a separate `libnucleus_tao_dnd.dylib` so the JNI exports survive
// the Rust `cdylib`'s release-mode symbol stripping (see Cargo.toml `strip =
// "symbols"`). Loaded by `NativeLibraryLoader.load("nucleus_tao_dnd")`.
//
// Threading: every entry point runs on the macOS main thread (= Tao event-loop
// thread = Compose dispatcher thread). AttachCurrentThread is defensive — the
// main thread is already attached after `JNI_OnLoad`.

#import <Cocoa/Cocoa.h>
#import <objc/runtime.h>
#import <objc/message.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>

// ── JNI plumbing ────────────────────────────────────────────────────────────

static JavaVM *g_vm = NULL;

// Cached Kotlin callback class + method IDs (resolved on first register).
static jclass    g_callback_class       = NULL; // GlobalRef
static jmethodID g_method_on_enter      = NULL; // (JIIIZ)I  hwnd, x, y, modState, hasFiles → effect
static jmethodID g_method_on_over       = NULL; // (JIIIZ)I
static jmethodID g_method_on_leave      = NULL; // (J)V
static jmethodID g_method_on_drop       = NULL; // (JIII[Ljava/lang/String;)I

#define DROP_EFFECT_NONE 0
#define DROP_EFFECT_COPY 1
#define DROP_EFFECT_MOVE 2
#define DROP_EFFECT_LINK 4

static JNIEnv *attach_thread(BOOL *attachedHere) {
    JNIEnv *env = NULL;
    if (!g_vm) { *attachedHere = NO; return NULL; }
    jint rc = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_8);
    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != 0) {
            *attachedHere = NO;
            return NULL;
        }
        *attachedHere = YES;
    } else {
        *attachedHere = NO;
    }
    return env;
}

static void detach_if_needed(BOOL attachedHere) {
    if (attachedHere && g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

// Resolves the Kotlin callback class & methods. Idempotent.
static BOOL ensure_callback_methods(JNIEnv *env, jobject callback) {
    if (g_callback_class && g_method_on_enter && g_method_on_over &&
        g_method_on_leave && g_method_on_drop) return YES;

    jclass local = (*env)->GetObjectClass(env, callback);
    if (!local) return NO;
    g_callback_class = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!g_callback_class) return NO;

    g_method_on_enter = (*env)->GetMethodID(env, g_callback_class, "onDragEnter", "(JIIIZ)I");
    g_method_on_over  = (*env)->GetMethodID(env, g_callback_class, "onDragOver",  "(JIIIZ)I");
    g_method_on_leave = (*env)->GetMethodID(env, g_callback_class, "onDragLeave", "(J)V");
    g_method_on_drop  = (*env)->GetMethodID(env, g_callback_class, "onDrop",
        "(JIII[Ljava/lang/String;)I");

    return g_method_on_enter && g_method_on_over &&
           g_method_on_leave && g_method_on_drop;
}

// ── Per-view associated state ──────────────────────────────────────────────

// Stored on the NSView via objc_setAssociatedObject so the swizzled methods
// can find the Java callback to invoke. One instance per registered view.
@interface NucleusDndState : NSObject
@property(nonatomic, assign) jobject callbackRef; // GlobalRef
@property(nonatomic, assign) BOOL    hasAcceptableData;
@end

@implementation NucleusDndState
- (void)dealloc {
    if (_callbackRef && g_vm) {
        BOOL attached = NO;
        JNIEnv *env = attach_thread(&attached);
        if (env) (*env)->DeleteGlobalRef(env, _callbackRef);
        detach_if_needed(attached);
    }
}
@end

static const void *kNucleusDndStateKey = &kNucleusDndStateKey;

static NucleusDndState *state_for_view(NSView *view) {
    return objc_getAssociatedObject(view, kNucleusDndStateKey);
}

// ── Pasteboard helpers ─────────────────────────────────────────────────────

// Returns YES if the pasteboard carries at least one file URL (Cocoa's
// dragging API uses `NSPasteboardTypeFileURL`; legacy `NSFilenamesPboardType`
// is tolerated for older drag sources).
static BOOL pasteboard_has_files(NSPasteboard *pb) {
    if (!pb) return NO;
    NSArray<NSString *> *types = [pb types];
    if ([types containsObject:NSPasteboardTypeFileURL]) return YES;
    if ([types containsObject:(NSString *)kUTTypeFileURL]) return YES;
    if ([types containsObject:@"NSFilenamesPboardType"]) return YES;
    return NO;
}

// Materializes a Java String[] of file paths from the dragging pasteboard.
// Returns NULL when no file is present.
static jobjectArray extract_files(JNIEnv *env, NSPasteboard *pb) {
    if (!pb) return NULL;

    NSArray<NSURL *> *urls = [pb readObjectsForClasses:@[[NSURL class]]
                                               options:@{ NSPasteboardURLReadingFileURLsOnlyKey : @YES }];
    NSMutableArray<NSString *> *paths = [NSMutableArray arrayWithCapacity:urls.count];
    for (NSURL *u in urls) {
        if (u.isFileURL && u.path) [paths addObject:u.path];
    }
    if (paths.count == 0) {
        // Fallback: legacy NSFilenamesPboardType (string-array property list).
        NSArray *legacy = [pb propertyListForType:@"NSFilenamesPboardType"];
        if ([legacy isKindOfClass:[NSArray class]]) {
            for (id p in legacy) {
                if ([p isKindOfClass:[NSString class]]) [paths addObject:(NSString *)p];
            }
        }
    }
    if (paths.count == 0) return NULL;

    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    if (!strClass) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)paths.count, strClass, NULL);
    if (!result) return NULL;

    for (NSUInteger i = 0; i < paths.count; ++i) {
        NSString *p = paths[i];
        const char *utf8 = [p UTF8String];
        jstring js = (*env)->NewStringUTF(env, utf8 ? utf8 : "");
        if (js) {
            (*env)->SetObjectArrayElement(env, result, (jsize)i, js);
            (*env)->DeleteLocalRef(env, js);
        }
    }
    return result;
}

// Converts a window-coordinate point (returned by `draggingLocation`) to
// physical pixels with a top-left origin so it matches what Compose receives
// from the Tao mouse pipeline.
static void window_point_to_root_pixels(NSView *view, NSPoint windowPt, jint *outX, jint *outY) {
    NSPoint local = [view convertPoint:windowPt fromView:nil];
    if (![view isFlipped]) {
        // AppKit default: bottom-left origin → flip to top-left.
        local.y = view.bounds.size.height - local.y;
    }
    NSPoint backing = [view convertPointToBacking:local];
    // `convertPointToBacking:` may return negative Y on flipped views — guard.
    *outX = (jint)lround(backing.x);
    *outY = (jint)lround(backing.y < 0 ? -backing.y : backing.y);
}

// ── NSDraggingDestination override (swizzled onto the Tao view class) ──────

// Tao 0.35 registers `NSPasteboardTypeFileURL` and implements the dragging
// destination methods so it can emit `WindowEvent::FileDropped`. We override
// the four hook methods on the view's class via `class_replaceMethod` so all
// callbacks come straight to us. The Tao file-drop event no longer fires for
// that view — intentional, we now own inbound DnD end-to-end.

static NSDragOperation nucleus_draggingEntered(id self, SEL _cmd, id<NSDraggingInfo> sender) {
    (void)_cmd;
    NSView *view = (NSView *)self;
    NucleusDndState *st = state_for_view(view);
    if (!st || !st.callbackRef) return NSDragOperationNone;

    NSPasteboard *pb = [sender draggingPasteboard];
    st.hasAcceptableData = pasteboard_has_files(pb);
    if (!st.hasAcceptableData) return NSDragOperationNone;

    BOOL attached = NO;
    JNIEnv *env = attach_thread(&attached);
    if (!env) return NSDragOperationNone;

    jint x, y;
    window_point_to_root_pixels(view, [sender draggingLocation], &x, &y);

    jint effect = DROP_EFFECT_COPY;
    if (g_method_on_enter) {
        effect = (*env)->CallIntMethod(env, st.callbackRef, g_method_on_enter,
                                       (jlong)(intptr_t)view, x, y, (jint)0, JNI_TRUE);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            effect = DROP_EFFECT_NONE;
        }
    }
    detach_if_needed(attached);
    return (effect == DROP_EFFECT_COPY) ? NSDragOperationCopy : NSDragOperationNone;
}

static NSDragOperation nucleus_draggingUpdated(id self, SEL _cmd, id<NSDraggingInfo> sender) {
    (void)_cmd;
    NSView *view = (NSView *)self;
    NucleusDndState *st = state_for_view(view);
    if (!st || !st.callbackRef || !st.hasAcceptableData) return NSDragOperationNone;

    BOOL attached = NO;
    JNIEnv *env = attach_thread(&attached);
    if (!env) return NSDragOperationNone;

    jint x, y;
    window_point_to_root_pixels(view, [sender draggingLocation], &x, &y);

    jint effect = DROP_EFFECT_COPY;
    if (g_method_on_over) {
        effect = (*env)->CallIntMethod(env, st.callbackRef, g_method_on_over,
                                       (jlong)(intptr_t)view, x, y, (jint)0, JNI_TRUE);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            effect = DROP_EFFECT_NONE;
        }
    }
    detach_if_needed(attached);
    return (effect == DROP_EFFECT_COPY) ? NSDragOperationCopy : NSDragOperationNone;
}

static void nucleus_draggingExited(id self, SEL _cmd, id<NSDraggingInfo> sender) {
    (void)_cmd; (void)sender;
    NSView *view = (NSView *)self;
    NucleusDndState *st = state_for_view(view);
    if (!st || !st.callbackRef) return;
    st.hasAcceptableData = NO;

    BOOL attached = NO;
    JNIEnv *env = attach_thread(&attached);
    if (env && g_method_on_leave) {
        (*env)->CallVoidMethod(env, st.callbackRef, g_method_on_leave,
                               (jlong)(intptr_t)view);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
    }
    detach_if_needed(attached);
}

static BOOL nucleus_prepareForDragOperation(id self, SEL _cmd, id<NSDraggingInfo> sender) {
    (void)_cmd; (void)sender;
    NSView *view = (NSView *)self;
    NucleusDndState *st = state_for_view(view);
    return st && st.callbackRef && st.hasAcceptableData;
}

static BOOL nucleus_performDragOperation(id self, SEL _cmd, id<NSDraggingInfo> sender) {
    (void)_cmd;
    NSView *view = (NSView *)self;
    NucleusDndState *st = state_for_view(view);
    if (!st || !st.callbackRef) return NO;

    BOOL attached = NO;
    JNIEnv *env = attach_thread(&attached);
    if (!env) { st.hasAcceptableData = NO; return NO; }

    jobjectArray files = extract_files(env, [sender draggingPasteboard]);
    jint x, y;
    window_point_to_root_pixels(view, [sender draggingLocation], &x, &y);

    jint effect = DROP_EFFECT_NONE;
    if (g_method_on_drop) {
        effect = (*env)->CallIntMethod(env, st.callbackRef, g_method_on_drop,
                                       (jlong)(intptr_t)view, x, y, (jint)0, files);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            effect = DROP_EFFECT_NONE;
        }
    }
    if (files) (*env)->DeleteLocalRef(env, files);
    detach_if_needed(attached);
    st.hasAcceptableData = NO;
    return effect != DROP_EFFECT_NONE;
}

// Swaps the four NSDraggingDestination methods on the view's class. Idempotent
// per class via an associated marker — repeated `class_replaceMethod` calls are
// safe but we avoid the redundant work.
static void install_dragging_destination_overrides(NSView *view) {
    Class cls = object_getClass(view);
    static const void *kInstalledKey = &kInstalledKey;
    if (objc_getAssociatedObject(cls, kInstalledKey)) return;

    class_replaceMethod(cls, @selector(draggingEntered:),
                        (IMP)nucleus_draggingEntered, "L@:@");
    class_replaceMethod(cls, @selector(draggingUpdated:),
                        (IMP)nucleus_draggingUpdated, "L@:@");
    class_replaceMethod(cls, @selector(draggingExited:),
                        (IMP)nucleus_draggingExited, "v@:@");
    class_replaceMethod(cls, @selector(prepareForDragOperation:),
                        (IMP)nucleus_prepareForDragOperation, "B@:@");
    class_replaceMethod(cls, @selector(performDragOperation:),
                        (IMP)nucleus_performDragOperation, "B@:@");

    objc_setAssociatedObject(cls, kInstalledKey, @YES, OBJC_ASSOCIATION_RETAIN);
}

// ── Outbound: NSDraggingSource ─────────────────────────────────────────────

@interface NucleusDraggingSource : NSObject <NSDraggingSource> {
    @public
    NSDragOperation _allowed;
    NSDragOperation _resultOperation;
    BOOL            _done;
}
@end

@implementation NucleusDraggingSource
- (NSDragOperation)draggingSession:(NSDraggingSession *)session
    sourceOperationMaskForDraggingContext:(NSDraggingContext)context {
    (void)session; (void)context;
    return _allowed;
}

- (void)draggingSession:(NSDraggingSession *)session
           endedAtPoint:(NSPoint)screenPoint
              operation:(NSDragOperation)operation {
    (void)session; (void)screenPoint;
    _resultOperation = operation;
    _done = YES;
}
@end

// Captures the most recent NSLeftMouseDown so beginDraggingSession can be
// driven with the originating event (see window_drag.m for the rationale).
static id sMouseDownMonitor = nil;
static NSEvent *sLastMouseDownEvent = nil;
static NSLock *sMouseDownLock = nil;

static void ensure_mouse_monitor(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        sMouseDownLock = [[NSLock alloc] init];
    });
    if (sMouseDownMonitor != nil) return;
    if (![NSThread isMainThread]) {
        dispatch_sync(dispatch_get_main_queue(), ^{ ensure_mouse_monitor(); });
        return;
    }
    if (sMouseDownMonitor != nil) return;
    sMouseDownMonitor = [NSEvent
        addLocalMonitorForEventsMatchingMask:NSEventMaskLeftMouseDown
        handler:^NSEvent * _Nullable(NSEvent * _Nonnull event) {
            [sMouseDownLock lock];
            sLastMouseDownEvent = event;
            [sMouseDownLock unlock];
            return event;
        }];
}

static NSDragOperation map_allowed(jint allowed_mask) {
    NSDragOperation op = NSDragOperationNone;
    if (allowed_mask & DROP_EFFECT_COPY) op |= NSDragOperationCopy;
    if (allowed_mask & DROP_EFFECT_MOVE) op |= NSDragOperationMove;
    if (allowed_mask & DROP_EFFECT_LINK) op |= NSDragOperationLink;
    if (op == NSDragOperationNone) op = NSDragOperationCopy;
    return op;
}

static jint map_result(NSDragOperation op) {
    if (op & NSDragOperationCopy) return DROP_EFFECT_COPY;
    if (op & NSDragOperationMove) return DROP_EFFECT_MOVE;
    if (op & NSDragOperationLink) return DROP_EFFECT_LINK;
    return DROP_EFFECT_NONE;
}

// ── JNI exports ────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_8;
}

/*
 * nativeRegister(nsView, callback): retain a global ref on the callback,
 * swizzle the view's <NSDraggingDestination> methods, and register the
 * pasteboard types we accept. Returns 0 on success, negative on failure.
 */
JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsDndBridge_nativeRegister(
    JNIEnv *env, jclass cls, jlong nsView, jobject callback)
{
    (void)cls;
    if (!nsView || !callback) return -1;
    if (!ensure_callback_methods(env, callback)) return -2;

    NSView *view = (__bridge NSView *)(void *)(intptr_t)nsView;
    if (!view) return -3;

    jobject globalRef = (*env)->NewGlobalRef(env, callback);
    if (!globalRef) return -4;

    NucleusDndState *st = [[NucleusDndState alloc] init];
    st.callbackRef = globalRef;
    objc_setAssociatedObject(view, kNucleusDndStateKey, st, OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    install_dragging_destination_overrides(view);

    NSArray *types = @[NSPasteboardTypeFileURL,
                       NSPasteboardTypeString,
                       NSPasteboardTypeURL];
    [view registerForDraggedTypes:types];

    ensure_mouse_monitor();
    return 0;
}

/*
 * nativeRevoke(nsView): unregister pasteboard types and drop the per-view
 * Java callback ref. The swizzled methods stay on the view's class — they
 * fast-path on a missing state and become inert.
 */
JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsDndBridge_nativeRevoke(
    JNIEnv *env, jclass cls, jlong nsView)
{
    (void)env; (void)cls;
    if (!nsView) return -1;
    NSView *view = (__bridge NSView *)(void *)(intptr_t)nsView;
    if (!view) return -2;
    [view unregisterDraggedTypes];
    objc_setAssociatedObject(view, kNucleusDndStateKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    return 0;
}

/*
 * nativeStartDrag(nsView, files, text, allowedEffects): synchronously runs an
 * outbound drag session. Returns the negotiated drop effect or 0 if cancelled.
 *
 * Must be called on the macOS main thread (= Tao event-loop thread). Pumps
 * AppKit events while the drag is in flight so the call appears blocking from
 * Kotlin's perspective — matches the Win32 `DoDragDrop` contract.
 */
JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsDndBridge_nativeStartDrag(
    JNIEnv *env, jclass cls, jlong nsView, jobjectArray files, jstring text, jint allowedEffects)
{
    (void)cls;
    if (!nsView) return DROP_EFFECT_NONE;
    NSView *view = (__bridge NSView *)(void *)(intptr_t)nsView;
    if (!view) return DROP_EFFECT_NONE;

    @autoreleasepool {
        NSMutableArray<NSDraggingItem *> *items = [NSMutableArray array];

        // ── Files → one NSDraggingItem per URL ─────────────────────────────
        if (files) {
            jsize n = (*env)->GetArrayLength(env, files);
            for (jsize i = 0; i < n; ++i) {
                jstring js = (jstring)(*env)->GetObjectArrayElement(env, files, i);
                if (!js) continue;
                const char *utf8 = (*env)->GetStringUTFChars(env, js, NULL);
                if (utf8) {
                    NSString *path = [NSString stringWithUTF8String:utf8];
                    (*env)->ReleaseStringUTFChars(env, js, utf8);
                    if (path.length > 0) {
                        NSURL *url = [NSURL fileURLWithPath:path];
                        if (url) {
                            NSDraggingItem *item = [[NSDraggingItem alloc] initWithPasteboardWriter:url];
                            // Anchor the drag preview at the drag origin so AppKit
                            // can render a default file icon there.
                            NSImage *icon = [[NSWorkspace sharedWorkspace] iconForFile:path];
                            NSRect frame;
                            if (icon) {
                                frame = NSMakeRect(0, 0, icon.size.width, icon.size.height);
                                [item setDraggingFrame:frame contents:icon];
                            } else {
                                frame = NSMakeRect(0, 0, 32, 32);
                                [item setDraggingFrame:frame contents:nil];
                            }
                            [items addObject:item];
                        }
                    }
                }
                (*env)->DeleteLocalRef(env, js);
            }
        }

        // ── Text → single NSDraggingItem ──────────────────────────────────
        if (text) {
            const char *utf8 = (*env)->GetStringUTFChars(env, text, NULL);
            if (utf8) {
                NSString *str = [NSString stringWithUTF8String:utf8];
                (*env)->ReleaseStringUTFChars(env, text, utf8);
                if (str.length > 0) {
                    NSDraggingItem *item = [[NSDraggingItem alloc] initWithPasteboardWriter:str];
                    NSRect frame = NSMakeRect(0, 0, 200, 24);
                    [item setDraggingFrame:frame contents:nil];
                    [items addObject:item];
                }
            }
        }

        if (items.count == 0) return DROP_EFFECT_NONE;

        // beginDraggingSessionWithItems: needs an event of type LeftMouseDown
        // or LeftMouseDragged. Prefer the latched mouseDown captured by the
        // global monitor; fall back to NSApp.currentEvent if none seen yet.
        NSEvent *event = nil;
        if (sMouseDownLock) {
            [sMouseDownLock lock];
            event = sLastMouseDownEvent;
            [sMouseDownLock unlock];
        }
        if (!event) event = [NSApp currentEvent];
        if (!event) return DROP_EFFECT_NONE;

        NucleusDraggingSource *source = [[NucleusDraggingSource alloc] init];
        source->_allowed = map_allowed(allowedEffects);
        source->_resultOperation = NSDragOperationNone;
        source->_done = NO;

        NSDraggingSession *session __unused = [view beginDraggingSessionWithItems:items
                                                                            event:event
                                                                           source:source];

        // Pump the AppKit run loop until the session ends. We're on the main
        // thread so simply waiting would freeze the UI; manually dispatching
        // events keeps everything responsive (mirrors what Win32 DoDragDrop
        // does internally with its modal message loop).
        while (!source->_done) {
            @autoreleasepool {
                NSEvent *ev = [NSApp nextEventMatchingMask:NSEventMaskAny
                                                 untilDate:[NSDate dateWithTimeIntervalSinceNow:0.05]
                                                    inMode:NSDefaultRunLoopMode
                                                   dequeue:YES];
                if (ev) [NSApp sendEvent:ev];
            }
        }
        return map_result(source->_resultOperation);
    }
}
