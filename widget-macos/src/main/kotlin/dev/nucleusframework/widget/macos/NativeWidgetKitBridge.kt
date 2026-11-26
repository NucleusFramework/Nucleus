@file:Suppress("TooManyFunctions") // one external per native entry point

package dev.nucleusframework.widget.macos

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.core.runtime.Platform
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

private const val LIBRARY_NAME = "nucleus_widget_macos"

internal object NativeWidgetKitBridge {
    val isLoaded: Boolean by lazy {
        Platform.Current == Platform.MacOS && NativeLibraryLoader.load(LIBRARY_NAME, NativeWidgetKitBridge::class.java)
    }

    private val logger = Logger.getLogger(NativeWidgetKitBridge::class.java.name)
    private val observers = ConcurrentHashMap<Int, () -> Unit>()

    fun requireLoaded() {
        check(isLoaded) { "The $LIBRARY_NAME native library is not loaded (macOS only)" }
    }

    /**
     * Splits a bridge result, "OK\n" + payload or "ERR\n" + message; throws on an error.
     */
    fun payload(result: String?): String {
        val text = result ?: throw WidgetKitException("WidgetKit returned nothing")
        val newline = text.indexOf('\n')
        val status = if (newline < 0) text else text.substring(0, newline)
        val body = if (newline < 0) "" else text.substring(newline + 1)
        if (status != "OK") throw WidgetKitException(body)
        return body
    }

    fun register(
        name: String,
        onNotification: () -> Unit,
    ): Int {
        requireLoaded()
        val token = nativeNotifyRegister(name)
        if (token < 0) throw WidgetKitException("notify_register_dispatch failed for '$name'")
        observers[token] = onNotification
        return token
    }

    fun unregister(token: Int) {
        if (observers.remove(token) != null) nativeNotifyCancel(token)
    }

    /** Called by the native library on its notification queue. */
    @JvmStatic
    fun onDarwinNotification(token: Int) {
        try {
            observers[token]?.invoke()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.log(Level.WARNING, "A Darwin notification observer failed", e)
        }
    }

    // -- WidgetCenter --

    @JvmStatic external fun nativeReloadTimelines(kind: String)

    @JvmStatic external fun nativeReloadAllTimelines()

    @JvmStatic external fun nativeInvalidateConfigurationRecommendations(): Boolean

    @JvmStatic external fun nativeInvalidateRelevance(kind: String): Boolean

    @JvmStatic external fun nativeCurrentConfigurations(timeoutMillis: Int): String?

    @JvmStatic external fun nativeCurrentPushInfo(timeoutMillis: Int): String?

    @JvmStatic external fun nativeUserInfoKey(which: Int): String

    // -- ControlCenter --

    @JvmStatic external fun nativeControlsAvailable(): Boolean

    @JvmStatic external fun nativeReloadControls(kind: String)

    @JvmStatic external fun nativeReloadAllControls()

    @JvmStatic external fun nativeCurrentControls(timeoutMillis: Int): String?

    // -- Darwin notifications --

    @JvmStatic external fun nativeNotifyPost(name: String): Boolean

    @JvmStatic external fun nativeNotifyRegister(name: String): Int

    @JvmStatic external fun nativeNotifyCancel(token: Int)

    // -- Embedded widget extension --

    @JvmStatic external fun nativeExtensionConfig(): Array<String>?
}
