package dev.nucleusframework.notification.linux

/**
 * Entry point for the freedesktop Desktop Notifications API on Linux.
 *
 * Communicates with `org.freedesktop.Notifications` over D-Bus via JNI (GIO/GDBus).
 * All methods are thread-safe. Signal listener callbacks are dispatched on the Swing EDT.
 *
 * Specification: https://specifications.freedesktop.org/notification/latest-single/
 */
public object LinuxNotificationCenter {
    /**
     * Whether the native library is loaded and the module is functional.
     *
     * Returns `false` on non-Linux platforms or if the native library could not be loaded.
     */
    public val isAvailable: Boolean
        get() = NativeLinuxNotificationBridge.isLoaded

    /**
     * Sends a notification and returns its server-assigned ID.
     *
     * If [Notification.replacesId] is non-zero, the existing notification with that ID
     * is atomically replaced.
     *
     * @return The notification ID (> 0) on success, or 0 on failure.
     */
    public fun notify(notification: Notification): Int {
        if (!isAvailable) return 0

        val actionKeys = notification.actions.map { it.key }.toTypedArray()
        val actionLabels = notification.actions.map { it.label }.toTypedArray()
        val hints = notification.hints

        return NativeLinuxNotificationBridge.nativeNotify(
            appName = notification.appName,
            replacesId = notification.replacesId,
            appIcon = notification.appIcon?.value ?: "",
            summary = notification.summary,
            body = notification.body,
            actionKeys = actionKeys,
            actionLabels = actionLabels,
            urgency = hints.urgency?.value ?: -1,
            category = hints.category,
            desktopEntry = hints.desktopEntry,
            imagePath = hints.imagePath?.value,
            soundFile = hints.soundFile,
            soundName = hints.soundName?.value,
            suppressSound = hints.suppressSound.toNativeFlag(),
            actionIcons = hints.actionIcons.toNativeFlag(),
            resident = hints.resident.toNativeFlag(),
            isTransient = hints.transient.toNativeFlag(),
            posX = hints.x ?: Int.MIN_VALUE,
            posY = hints.y ?: Int.MIN_VALUE,
            hasImageData = hints.imageData != null,
            imageWidth = hints.imageData?.width ?: 0,
            imageHeight = hints.imageData?.height ?: 0,
            imageRowstride = hints.imageData?.rowstride ?: 0,
            imageHasAlpha = hints.imageData?.hasAlpha ?: false,
            imageBitsPerSample = hints.imageData?.bitsPerSample ?: 8,
            imageChannels = hints.imageData?.channels ?: 3,
            imagePixels = hints.imageData?.data,
            expireTimeout = notification.expireTimeout,
        )
    }

    /**
     * Forcefully closes a notification by its ID.
     */
    public fun closeNotification(id: Int) {
        if (!isAvailable) return
        NativeLinuxNotificationBridge.nativeCloseNotification(id)
    }

    /**
     * Queries the notification server for its supported capabilities.
     *
     * Common capabilities include: `"body"`, `"body-markup"`, `"body-hyperlinks"`,
     * `"body-images"`, `"icon-static"`, `"actions"`, `"persistence"`, `"sound"`.
     *
     * @return List of capability strings, or an empty list on failure.
     */
    public fun getCapabilities(): List<String> {
        if (!isAvailable) return emptyList()
        return NativeLinuxNotificationBridge.nativeGetCapabilities()?.toList() ?: emptyList()
    }

    /**
     * Queries the notification server for its identity.
     *
     * @return Server information, or `null` on failure.
     */
    public fun getServerInformation(): ServerInformation? {
        if (!isAvailable) return null
        val info = NativeLinuxNotificationBridge.nativeGetServerInformation() ?: return null
        @Suppress("MagicNumber")
        if (info.size < 4) return null
        return ServerInformation(
            name = info[0],
            vendor = info[1],
            version = info[2],
            specVersion = info[3],
        )
    }

    /**
     * Registers a listener for notification signals.
     *
     * Signal monitoring starts automatically when the first listener is added.
     */
    public fun addListener(listener: LinuxNotificationListener) {
        NativeLinuxNotificationBridge.addListener(listener)
    }

    /**
     * Removes a previously registered listener.
     *
     * Signal monitoring stops automatically when the last listener is removed.
     */
    public fun removeListener(listener: LinuxNotificationListener) {
        NativeLinuxNotificationBridge.removeListener(listener)
    }
}

private fun Boolean?.toNativeFlag(): Int =
    when (this) {
        null -> -1
        true -> 1
        false -> 0
    }
