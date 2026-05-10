package dev.nucleusframework.nucleus.window.utils.macos

import java.awt.Component
import java.awt.Window
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.RootPaneContainer

@Suppress("TooGenericExceptionCaught")
internal object JniMacWindowUtil {
    private val logger = Logger.getLogger(JniMacWindowUtil::class.java.simpleName)
    private var reflectionFailed = false

    // Extracts the native NSWindow pointer from an AWT window.
    // Prefers the JNI path (bypasses module access checks, works in GraalVM native-image).
    // Falls back to reflection only if the native library is not loaded.
    // Returns 0 if the pointer cannot be obtained (e.g. peer not yet created).
    fun getWindowPtr(w: Window?): Long {
        if (w == null) return 0L

        // JNI path: works in both JVM and native-image.
        // If the native lib is loaded, trust its result (including 0 when the peer is gone)
        // and never fall through to reflection — it is blocked by JPMS in native-image.
        if (JniMacTitleBarBridge.isLoaded) {
            return try {
                JniMacTitleBarBridge.nativeGetNSWindowPtr(w)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "JNI nativeGetNSWindowPtr failed.", e)
                0L
            }
        }

        // Reflection fallback (JVM only, when native library is unavailable)
        if (!reflectionFailed) {
            return getWindowPtrViaReflection(w)
        }
        return 0L
    }

    private fun getWindowPtrViaReflection(w: Window): Long {
        try {
            val awtAccessor = Class.forName("sun.awt.AWTAccessor")
            val componentAccessor = awtAccessor.getMethod("getComponentAccessor").invoke(null)
            val accessorInterface = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            val getPeer = accessorInterface.getMethod("getPeer", Component::class.java)
            val peer = getPeer.invoke(componentAccessor, w) ?: return 0L
            val platformWindow =
                peer.javaClass.getDeclaredMethod("getPlatformWindow").invoke(peer)
                    ?: return 0L
            val ptr = platformWindow.javaClass.superclass.getDeclaredField("ptr")
            ptr.isAccessible = true
            return ptr.getLong(platformWindow)
        } catch (e: IllegalAccessException) {
            reflectionFailed = true
            logger.log(
                Level.WARNING,
                "Module access denied for NSWindow pointer reflection (expected in native-image).",
                e,
            )
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Reflection fallback failed to get NSWindow pointer.", e)
        }
        return 0L
    }

    // Sets the AWT client properties that make the content view extend into the title bar
    // area and make the title bar transparent. Guards against re-firing PropertyChangeEvents
    // on every layout pass, which would cause repeated native style mask updates and jitter.
    fun applyWindowProperties(w: Window) {
        (w as? RootPaneContainer)?.rootPane?.let { rootPane ->
            if (rootPane.getClientProperty("apple.awt.fullWindowContent") != true) {
                rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            }
            if (rootPane.getClientProperty("apple.awt.transparentTitleBar") != true) {
                rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            }
            if (rootPane.getClientProperty("apple.awt.windowTitleVisible") != false) {
                rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
        }
    }
}
