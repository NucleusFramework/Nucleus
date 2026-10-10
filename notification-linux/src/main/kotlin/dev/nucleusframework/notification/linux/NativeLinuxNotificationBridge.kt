package dev.nucleusframework.notification.linux

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.core.runtime.NucleusUiThread
import java.util.concurrent.ConcurrentHashMap

private const val LIBRARY_NAME = "nucleus_notification_linux"

internal object NativeLinuxNotificationBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxNotificationBridge::class.java)
    val isLoaded: Boolean get() = loaded

    private val listeners: MutableSet<LinuxNotificationListener> = ConcurrentHashMap.newKeySet()

    fun addListener(listener: LinuxNotificationListener) {
        val wasEmpty = listeners.isEmpty()
        listeners.add(listener)
        if (wasEmpty && listeners.isNotEmpty() && isLoaded) {
            nativeStartListening()
        }
    }

    fun removeListener(listener: LinuxNotificationListener) {
        listeners.remove(listener)
        if (listeners.isEmpty() && isLoaded) {
            nativeStopListening()
        }
    }

    // ---- Native methods ------------------------------------------------

    @JvmStatic
    external fun nativeNotify(
        appName: String,
        replacesId: Int,
        appIcon: String,
        summary: String,
        body: String,
        actionKeys: Array<String>,
        actionLabels: Array<String>,
        urgency: Int,
        category: String?,
        desktopEntry: String?,
        imagePath: String?,
        soundFile: String?,
        soundName: String?,
        suppressSound: Int,
        actionIcons: Int,
        resident: Int,
        isTransient: Int,
        posX: Int,
        posY: Int,
        hasImageData: Boolean,
        imageWidth: Int,
        imageHeight: Int,
        imageRowstride: Int,
        imageHasAlpha: Boolean,
        imageBitsPerSample: Int,
        imageChannels: Int,
        imagePixels: ByteArray?,
        expireTimeout: Int,
    ): Int

    @JvmStatic
    external fun nativeCloseNotification(id: Int)

    @JvmStatic
    external fun nativeGetCapabilities(): Array<String>?

    @JvmStatic
    external fun nativeGetServerInformation(): Array<String>?

    @JvmStatic
    external fun nativeStartListening(): Boolean

    @JvmStatic
    external fun nativeStopListening()

    // ---- Callbacks from native (signal handlers) -----------------------

    @JvmStatic
    fun onNotificationClosed(
        id: Int,
        reason: Int,
    ) {
        val closeReason = CloseReason.fromValue(reason)
        NucleusUiThread.post {
            listeners.forEach { it.onClosed(id, closeReason) }
        }
    }

    @JvmStatic
    fun onActionInvoked(
        id: Int,
        actionKey: String,
    ) {
        NucleusUiThread.post {
            listeners.forEach { it.onActionInvoked(id, actionKey) }
        }
    }

    @JvmStatic
    fun onActivationToken(
        id: Int,
        token: String,
    ) {
        NucleusUiThread.post {
            listeners.forEach { it.onActivationToken(id, token) }
        }
    }
}
