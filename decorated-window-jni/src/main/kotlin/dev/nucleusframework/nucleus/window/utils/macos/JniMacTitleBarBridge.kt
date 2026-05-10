package dev.nucleusframework.nucleus.window.utils.macos

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

private const val LIBRARY_NAME = "nucleus_macos_jni"

@Suppress("TooManyFunctions")
internal object JniMacTitleBarBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, JniMacTitleBarBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // Register a shutdown hook to disable native → JVM callbacks before the
    // JVM tears down. Without this, the NSEvent menu bar monitor can fire
    // notifyMenuBarOffsetChanged during JVM_Halt, calling CallStaticVoidMethod
    // on a freed sBridgeClass global ref → EXC_BAD_ACCESS → abort.
    init {
        if (loaded) {
            Runtime.getRuntime().addShutdownHook(Thread({ nativeShutdown() }, "nucleus-native-shutdown"))
        }
    }

    // ── Menu bar offset (event-driven via native NSEvent monitor) ──

    private val menuBarOffsetFlows = ConcurrentHashMap<Long, MutableStateFlow<Float>>()
    private val emptyFlow = MutableStateFlow(0f)

    // Returns a StateFlow that emits the current menu bar offset for
    // the given window. Updated by the native event monitor callback.
    fun menuBarOffsetFlow(nsWindowPtr: Long): StateFlow<Float> {
        if (nsWindowPtr == 0L) return emptyFlow
        return menuBarOffsetFlows.getOrPut(nsWindowPtr) { MutableStateFlow(0f) }
    }

    fun removeMenuBarOffsetFlow(nsWindowPtr: Long) {
        menuBarOffsetFlows.remove(nsWindowPtr)
    }

    // Called from native (macOS main thread) when the menu bar offset
    // changes. MutableStateFlow.value is thread-safe.
    @JvmStatic
    fun onMenuBarOffsetChanged(
        nsWindowPtr: Long,
        offset: Float,
    ) {
        menuBarOffsetFlows.getOrPut(nsWindowPtr) { MutableStateFlow(0f) }.value = offset
    }

    // ── JNI methods ──

    // Sets up (or updates) the custom title bar and repositions traffic light buttons.
    // heightPt: title bar height in NSPoints (= dp on macOS).
    // Returns the left inset in points to reserve space for the traffic lights.
    @JvmStatic
    external fun nativeApplyTitleBar(
        nsWindowPtr: Long,
        heightPt: Float,
    ): Float

    // Removes all custom constraints, fullscreen observer, and restores AppKit defaults.
    @JvmStatic
    external fun nativeResetTitleBar(nsWindowPtr: Long)

    // Updates the position of the replacement fullscreen buttons (called on layout passes).
    @JvmStatic
    external fun nativeUpdateFullScreenButtons(nsWindowPtr: Long)

    // Performs the macOS title bar double-click action (zoom or minimize)
    // respecting the user's AppleActionOnDoubleClick system preference.
    @JvmStatic
    external fun nativePerformTitleBarDoubleClickAction(nsWindowPtr: Long)

    // Initiates a native window drag using the saved mouseDown event.
    // Called from Compose when an unconsumed drag is detected in the title bar.
    @JvmStatic
    external fun nativeStartWindowDrag(nsWindowPtr: Long)

    // Extracts the native NSWindow pointer from an AWT Window via JNI.
    // JNI bypasses module access checks, so this works in GraalVM native-image
    // where Kotlin reflection cannot access sun.awt.AWTAccessor.
    @JvmStatic
    external fun nativeGetNSWindowPtr(awtWindow: java.awt.Window): Long

    // Stores the newFullscreenControls flag on the NSWindow.
    // When enabled, the title bar and traffic-light buttons are pushed down
    // by the menu bar height when the auto-hidden menu bar appears in fullscreen.
    @JvmStatic
    external fun nativeSetNewFullscreenControls(
        nsWindowPtr: Long,
        enabled: Boolean,
    )

    // Returns the current menu bar offset in points (reads the stored value).
    @JvmStatic
    external fun nativeGetMenuBarOffset(nsWindowPtr: Long): Float

    // Stores the current menu bar offset (in points) and repositions
    // the native traffic-light buttons to match the Compose title bar.
    @JvmStatic
    external fun nativeSetMenuBarOffset(
        nsWindowPtr: Long,
        offsetPt: Float,
    )

    // Installs a native NSEvent local monitor that detects menu bar
    // visibility changes and calls onMenuBarOffsetChanged via JNI.
    @JvmStatic
    external fun nativeInstallMenuBarMonitor(nsWindowPtr: Long)

    // Removes the native event monitor installed by nativeInstallMenuBarMonitor.
    @JvmStatic
    external fun nativeRemoveMenuBarMonitor(nsWindowPtr: Long)

    // Installs or removes an invisible NSToolbar to trigger the macOS 26pt
    // corner radius. When disabled, the window uses the standard ~10pt radius.
    @JvmStatic
    external fun nativeSetLargeCornerRadius(
        nsWindowPtr: Long,
        enabled: Boolean,
    )

    // Sets the RTL (right-to-left) flag on the NSWindow.
    // When enabled, traffic-light buttons are positioned on the right side.
    // Re-applies constraints immediately so the change is visible live.
    @JvmStatic
    external fun nativeSetRTL(
        nsWindowPtr: Long,
        rtl: Boolean,
    )

    // Disables native → JVM callbacks and removes all menu bar monitors.
    // Called from the shutdown hook before the JVM starts tearing down.
    @JvmStatic
    private external fun nativeShutdown()
}
