package dev.nucleusframework.nucleus.window.utils.macos

import java.awt.Component
import java.awt.Window
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.SwingUtilities

@Suppress("TooGenericExceptionCaught")
internal object MacUtil {
    private val logger = Logger.getLogger(MacUtil::class.java.simpleName)

    fun getWindowPtr(w: Window?): Long {
        if (w == null) return 0L
        try {
            val cPlatformWindow = getPlatformWindow(w) ?: return 0L
            val ptr = cPlatformWindow.javaClass.superclass.getDeclaredField("ptr")
            ptr.isAccessible = true
            return ptr.getLong(cPlatformWindow)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to get NSWindow pointer from AWT window.", e)
        }
        return 0L
    }

    private fun getPlatformWindow(w: Window): Any? {
        try {
            val awtAccessor = Class.forName("sun.awt.AWTAccessor")
            val componentAccessor = awtAccessor.getMethod("getComponentAccessor").invoke(null)
            // Resolve getPeer on the interface (sun.awt package, opened via --add-opens)
            // rather than on the anonymous impl class (java.awt package, not opened).
            val accessorInterface = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            val getPeer = accessorInterface.getMethod("getPeer", Component::class.java)
            val peer = getPeer.invoke(componentAccessor, w) ?: return null
            val getPlatformWindowMethod = peer.javaClass.getDeclaredMethod("getPlatformWindow")
            return getPlatformWindowMethod.invoke(peer)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to get cPlatformWindow from AWT window.", e)
        }
        return null
    }

    fun updateColors(w: Window) {
        SwingUtilities.invokeLater {
            val ptr = getWindowPtr(w)
            if (ptr != 0L && NativeMacBridge.isLoaded) {
                NativeMacBridge.nativeUpdateColors(ptr)
            }
        }
    }

    fun updateFullScreenButtons(w: Window) {
        SwingUtilities.invokeLater {
            val ptr = getWindowPtr(w)
            if (ptr != 0L && NativeMacBridge.isLoaded) {
                NativeMacBridge.nativeUpdateFullScreenButtons(ptr)
            }
        }
    }
}
