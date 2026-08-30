// error_dialog.m
//
// Fatal-error dialog (issue #622) — the no-AWT replacement for Compose
// Desktop's Swing default. Shown AFTER the Tao event loop has exited:
// showing a modal from inside a tao callback frame deadlocks (tao's
// Handler.callback std::Mutex is not re-entrant, and a Dock-reopen or
// deep-link delivered by the modal's event pump re-locks it on the same
// thread), and post-loop NSApp is left in a stopped state (tao latches
// [NSApp stop:] on exit, which makes a subsequent -[NSAlert runModal]
// session return immediately).
//
// CFUserNotificationDisplayAlert is the one AppKit-free primitive built for
// exactly this: rendered out of process by the user notification server,
// callable from any thread, no run loop / NSApp / main-thread dependency,
// and it blocks until the user dismisses the alert.
//
// Caveat: not available to App-Sandboxed processes; Nucleus-packaged apps
// (jpackage / GraalVM native, Developer ID) are not sandboxed. The JVM-side
// SEVERE log remains the durable record either way.

#include <CoreFoundation/CoreFoundation.h>

void nucleus_tao_show_error_dialog(const char *title, const char *message) {
    CFStringRef header = CFStringCreateWithCString(
        NULL, title != NULL ? title : "Error", kCFStringEncodingUTF8);
    CFStringRef body = CFStringCreateWithCString(
        NULL, message != NULL ? message : "", kCFStringEncodingUTF8);
    CFOptionFlags response = 0;
    // Timeout 0 = no timeout: returns when the user dismisses the alert.
    CFUserNotificationDisplayAlert(
        0, kCFUserNotificationStopAlertLevel,
        NULL, NULL, NULL,
        header != NULL ? header : CFSTR("Error"),
        body,
        NULL /* default button — localized "OK" */, NULL, NULL,
        &response);
    if (header != NULL) {
        CFRelease(header);
    }
    if (body != NULL) {
        CFRelease(body);
    }
}
