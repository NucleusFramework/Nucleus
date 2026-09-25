package dev.nucleusframework.screencapture.internal;

public final class NativeScreenCapture {
    public static native int nativeBackend();
    public static native int nativeListDisplays(DisplayCollector sink, String[] message);
    public static native int[] nativeCaptureDisplay(String id, int x, int y, int w, int h, boolean cursor, int[] result, String[] message);
    public static native int[] nativeCaptureWindow(long id, boolean cursor, int[] result, String[] message);
    public static native int nativePermissionStatus();
    public static native int nativeRequestPermission();
    public static native int[] nativePortalScreenshot(boolean interactive, int timeoutMs, int[] result, String[] message);
}
