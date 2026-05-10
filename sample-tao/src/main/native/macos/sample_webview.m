// sample_webview.m
//
// Minimal WKWebView factory for the sample-tao "WebView" tab demo. NOT a
// production-grade WebView wrapper — only "create + load URL + navigate +
// release", just enough to exercise the `NativeView` interop and prove
// that Compose `Popup`s appear above a real native subview.
//
// Builds into `libsample_tao_webview.dylib`. Loaded from
// `NativeLibraryLoader.load("sample_tao_webview")`.
//
// Threading: every entry point runs on the macOS main thread (= Tao
// event-loop thread = Compose dispatcher thread).

#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#include <jni.h>

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeCreate(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
    WKWebView *webview = [[WKWebView alloc] initWithFrame:NSZeroRect
                                            configuration:config];
    void *retained = (__bridge_retained void *)webview;
    return (jlong)(uintptr_t)retained;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeLoadUrl(
    JNIEnv *env, jclass clazz, jlong viewPtr, jstring urlStr)
{
    (void)clazz;
    if (viewPtr == 0 || urlStr == NULL) return;
    WKWebView *webview = (__bridge WKWebView *)(void *)(uintptr_t)viewPtr;

    const char *cUrl = (*env)->GetStringUTFChars(env, urlStr, NULL);
    if (cUrl == NULL) return;
    NSString *str = [NSString stringWithUTF8String:cUrl];
    (*env)->ReleaseStringUTFChars(env, urlStr, cUrl);

    NSURL *url = [NSURL URLWithString:str];
    if (url == nil) return;
    [webview loadRequest:[NSURLRequest requestWithURL:url]];
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeGoBack(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)env; (void)clazz;
    if (viewPtr == 0) return;
    WKWebView *webview = (__bridge WKWebView *)(void *)(uintptr_t)viewPtr;
    [webview goBack];
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeGoForward(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)env; (void)clazz;
    if (viewPtr == 0) return;
    WKWebView *webview = (__bridge WKWebView *)(void *)(uintptr_t)viewPtr;
    [webview goForward];
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeReload(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)env; (void)clazz;
    if (viewPtr == 0) return;
    WKWebView *webview = (__bridge WKWebView *)(void *)(uintptr_t)viewPtr;
    [webview reload];
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeCanGoBack(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)env; (void)clazz;
    if (viewPtr == 0) return JNI_FALSE;
    WKWebView *webview = (__bridge WKWebView *)(void *)(uintptr_t)viewPtr;
    return webview.canGoBack ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeCanGoForward(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)env; (void)clazz;
    if (viewPtr == 0) return JNI_FALSE;
    WKWebView *webview = (__bridge WKWebView *)(void *)(uintptr_t)viewPtr;
    return webview.canGoForward ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeIsLoading(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)env; (void)clazz;
    if (viewPtr == 0) return JNI_FALSE;
    WKWebView *webview = (__bridge WKWebView *)(void *)(uintptr_t)viewPtr;
    return webview.isLoading ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeCurrentUrl(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)clazz;
    if (viewPtr == 0) return NULL;
    WKWebView *webview = (__bridge WKWebView *)(void *)(uintptr_t)viewPtr;
    NSString *urlStr = webview.URL.absoluteString;
    if (urlStr == nil) return NULL;
    return (*env)->NewStringUTF(env, [urlStr UTF8String]);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewBridge_nativeRelease(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)env; (void)clazz;
    if (viewPtr == 0) return;
    WKWebView *webview = (__bridge_transfer WKWebView *)(void *)(uintptr_t)viewPtr;
    (void)webview;
}
