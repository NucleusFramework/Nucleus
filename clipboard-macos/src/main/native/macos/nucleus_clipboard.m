/**
 * JNI bridge for macOS NSPasteboard.
 *
 * Exposes the general pasteboard as a flat read/write/watch API. Writes are
 * atomic multi-representation — a single NSPasteboardItem carries every
 * non-null format supplied from Kotlin, so consumers can pick the richest
 * representation they understand.
 *
 * Frameworks: AppKit (NSPasteboard, NSImage, NSBitmapImageRep).
 */

#import <AppKit/AppKit.h>
#include <jni.h>

// ============================================================================
// JNI helpers
// ============================================================================

static JavaVM *g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

static NSString *toNSString(JNIEnv *env, jstring jstr) {
    if (jstr == NULL) return nil;
    const char *utf = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (utf == NULL) return nil;
    NSString *str = [NSString stringWithUTF8String:utf];
    (*env)->ReleaseStringUTFChars(env, jstr, utf);
    return str;
}

static jstring toJStringOrNull(JNIEnv *env, NSString *str) {
    if (str == nil) return NULL;
    const char *utf = [str UTF8String];
    return (*env)->NewStringUTF(env, utf ? utf : "");
}

static jbyteArray toJByteArray(JNIEnv *env, NSData *data) {
    if (data == nil) return NULL;
    NSUInteger len = [data length];
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    if (arr == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)[data bytes]);
    return arr;
}

static jobjectArray toJStringArray(JNIEnv *env, NSArray<NSString *> *items) {
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) return NULL;
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)items.count, stringClass, NULL);
    if (arr == NULL) return NULL;
    for (NSUInteger i = 0; i < items.count; i++) {
        jstring js = toJStringOrNull(env, items[i]);
        (*env)->SetObjectArrayElement(env, arr, (jsize)i, js);
        if (js != NULL) (*env)->DeleteLocalRef(env, js);
    }
    return arr;
}

// ============================================================================
// Read helpers
// ============================================================================

// Resolves the first available UTI among `candidates` on the given pasteboard.
static NSString *firstAvailableType(NSPasteboard *pb, NSArray<NSString *> *candidates) {
    NSString *match = [pb availableTypeFromArray:candidates];
    return match;
}

static NSData *readPngFromPasteboard(NSPasteboard *pb) {
    // Prefer a native PNG representation when present.
    NSData *png = [pb dataForType:@"public.png"];
    if (png != nil) return png;

    // Fallback: transcode TIFF → PNG via NSBitmapImageRep.
    NSData *tiff = [pb dataForType:@"public.tiff"];
    if (tiff == nil) {
        tiff = [pb dataForType:NSPasteboardTypeTIFF];
    }
    if (tiff == nil) return nil;

    NSBitmapImageRep *rep = [NSBitmapImageRep imageRepWithData:tiff];
    if (rep == nil) return nil;
    return [rep representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
}

static NSArray<NSString *> *readFilePathsFromPasteboard(NSPasteboard *pb) {
    NSMutableArray<NSString *> *out = [NSMutableArray array];

    // Modern path: NSURL objects with the file-URL filter.
    NSArray<Class> *classes = @[[NSURL class]];
    NSDictionary *options = @{NSPasteboardURLReadingFileURLsOnlyKey: @YES};
    NSArray *urls = [pb readObjectsForClasses:classes options:options];
    if (urls != nil) {
        for (NSURL *u in urls) {
            if (u.isFileURL && u.path != nil) {
                [out addObject:u.path];
            }
        }
    }

    // Legacy fallback for apps that still write NSFilenamesPboardType.
    if (out.count == 0) {
        id legacy = [pb propertyListForType:@"NSFilenamesPboardType"];
        if ([legacy isKindOfClass:[NSArray class]]) {
            for (id item in (NSArray *)legacy) {
                if ([item isKindOfClass:[NSString class]]) {
                    [out addObject:(NSString *)item];
                }
            }
        }
    }

    return out;
}

// ============================================================================
// JNI: change count + available types
// ============================================================================

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeChangeCount(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    @autoreleasepool {
        return (jlong)[[NSPasteboard generalPasteboard] changeCount];
    }
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeAvailableTypes(
    JNIEnv *env, jclass cls) {
    (void)cls;
    @autoreleasepool {
        NSArray<NSPasteboardType> *types = [[NSPasteboard generalPasteboard] types];
        if (types == nil) types = @[];
        return toJStringArray(env, types);
    }
}

// ============================================================================
// JNI: reads
// ============================================================================

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeReadText(
    JNIEnv *env, jclass cls) {
    (void)cls;
    @autoreleasepool {
        NSString *s = [[NSPasteboard generalPasteboard]
            stringForType:@"public.utf8-plain-text"];
        if (s == nil) {
            s = [[NSPasteboard generalPasteboard] stringForType:NSPasteboardTypeString];
        }
        return toJStringOrNull(env, s);
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeReadHtml(
    JNIEnv *env, jclass cls) {
    (void)cls;
    @autoreleasepool {
        NSString *s = [[NSPasteboard generalPasteboard] stringForType:@"public.html"];
        return toJStringOrNull(env, s);
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeReadRtf(
    JNIEnv *env, jclass cls) {
    (void)cls;
    @autoreleasepool {
        NSData *data = [[NSPasteboard generalPasteboard] dataForType:@"public.rtf"];
        if (data == nil) return NULL;
        NSString *s = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
        if (s == nil) {
            s = [[NSString alloc] initWithData:data encoding:NSASCIIStringEncoding];
        }
        return toJStringOrNull(env, s);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeReadImagePng(
    JNIEnv *env, jclass cls) {
    (void)cls;
    @autoreleasepool {
        NSData *png = readPngFromPasteboard([NSPasteboard generalPasteboard]);
        return toJByteArray(env, png);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeReadFilePaths(
    JNIEnv *env, jclass cls) {
    (void)cls;
    @autoreleasepool {
        NSArray<NSString *> *paths = readFilePathsFromPasteboard([NSPasteboard generalPasteboard]);
        return toJStringArray(env, paths);
    }
}

// ============================================================================
// JNI: writes
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeWrite(
    JNIEnv *env, jclass cls,
    jstring jText, jstring jHtml, jstring jRtf, jbyteArray jPng, jobjectArray jPaths) {
    (void)cls;
    @autoreleasepool {
        NSPasteboard *pb = [NSPasteboard generalPasteboard];
        [pb clearContents];

        NSMutableArray<id<NSPasteboardWriting>> *objects = [NSMutableArray array];

        NSPasteboardItem *item = [[NSPasteboardItem alloc] init];
        BOOL itemHasContent = NO;

        NSString *text = toNSString(env, jText);
        NSString *html = toNSString(env, jHtml);
        NSString *rtf = toNSString(env, jRtf);

        if (html != nil) {
            [item setString:html forType:@"public.html"];
            itemHasContent = YES;
        }
        if (rtf != nil) {
            NSData *rtfData = [rtf dataUsingEncoding:NSUTF8StringEncoding];
            if (rtfData != nil) {
                [item setData:rtfData forType:@"public.rtf"];
                itemHasContent = YES;
            }
        }
        if (text != nil) {
            [item setString:text forType:@"public.utf8-plain-text"];
            itemHasContent = YES;
        }

        if (jPng != NULL) {
            jsize len = (*env)->GetArrayLength(env, jPng);
            jbyte *bytes = (*env)->GetByteArrayElements(env, jPng, NULL);
            if (bytes != NULL) {
                NSData *pngData = [NSData dataWithBytes:bytes length:(NSUInteger)len];
                (*env)->ReleaseByteArrayElements(env, jPng, bytes, JNI_ABORT);

                // Write PNG directly for web/Chromium consumers, plus a transcoded
                // TIFF for AppKit-native pasters.
                [item setData:pngData forType:@"public.png"];
                NSBitmapImageRep *rep = [NSBitmapImageRep imageRepWithData:pngData];
                if (rep != nil) {
                    NSData *tiff = [rep representationUsingType:NSBitmapImageFileTypeTIFF properties:@{}];
                    if (tiff != nil) {
                        [item setData:tiff forType:@"public.tiff"];
                    }
                }
                itemHasContent = YES;
            }
        }

        if (itemHasContent) {
            [objects addObject:item];
        }

        // File URLs go as separate NSURL pasteboard items — they round-trip
        // cleanly through readObjectsForClasses: on the reader side.
        if (jPaths != NULL) {
            jsize pc = (*env)->GetArrayLength(env, jPaths);
            for (jsize i = 0; i < pc; i++) {
                jstring jp = (jstring)(*env)->GetObjectArrayElement(env, jPaths, i);
                NSString *p = toNSString(env, jp);
                if (jp != NULL) (*env)->DeleteLocalRef(env, jp);
                if (p == nil || p.length == 0) continue;
                NSURL *url = [NSURL fileURLWithPath:p];
                if (url != nil) {
                    [objects addObject:url];
                }
            }
        }

        if (objects.count == 0) return JNI_FALSE;

        BOOL ok = [pb writeObjects:objects];
        return ok ? JNI_TRUE : JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeClear(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    @autoreleasepool {
        [[NSPasteboard generalPasteboard] clearContents];
        return JNI_TRUE;
    }
}

// ============================================================================
// JNI: access behavior (macOS 15.4+)
// ============================================================================

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_macos_NativeMacClipboardBridge_nativeSetAccessBehavior(
    JNIEnv *env, jclass cls, jint value) {
    (void)env; (void)cls;
    @autoreleasepool {
        NSPasteboard *pb = [NSPasteboard generalPasteboard];
        SEL sel = NSSelectorFromString(@"setAccessBehavior:");
        if (![pb respondsToSelector:sel]) return;
        // macOS 15.4 enum layout: 0 = alwaysAllow, 1 = askEveryTime, 2 = alwaysDeny.
        NSInteger mapped = (NSInteger)value;
        NSMethodSignature *sig = [pb methodSignatureForSelector:sel];
        NSInvocation *inv = [NSInvocation invocationWithMethodSignature:sig];
        [inv setSelector:sel];
        [inv setTarget:pb];
        [inv setArgument:&mapped atIndex:2];
        [inv invoke];
    }
}
