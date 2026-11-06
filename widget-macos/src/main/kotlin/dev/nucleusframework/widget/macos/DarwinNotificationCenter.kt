package dev.nucleusframework.widget.macos

/**
 * System-wide Darwin notifications (`notify(3)`): a name and no payload, delivered to every process
 * observing it, sandboxed ones included. This is how the app and its widget extension, two
 * processes, wake each other; the data travels through files.
 */
public object DarwinNotificationCenter {
    /** Whether Darwin notifications can be used here (macOS with the native library loaded). */
    public val isAvailable: Boolean get() = NativeWidgetKitBridge.isLoaded

    /** Posts [name]; returns `false` when `notify_post` fails. */
    public fun post(name: String): Boolean {
        NativeWidgetKitBridge.requireLoaded()
        return NativeWidgetKitBridge.nativeNotifyPost(name)
    }

    /**
     * Calls [onNotification] whenever [name] is posted, on one native queue (in posting order;
     * several posts in a row may coalesce into one call). Close the result to stop observing.
     *
     * @throws WidgetKitException when the name cannot be registered.
     */
    public fun addObserver(
        name: String,
        onNotification: () -> Unit,
    ): AutoCloseable {
        val token = NativeWidgetKitBridge.register(name, onNotification)
        return AutoCloseable { NativeWidgetKitBridge.unregister(token) }
    }
}
