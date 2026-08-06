package dev.nucleusframework.launcher.linux

/**
 * Entry point for the Unity Launcher API on Linux.
 *
 * Allows applications to control their launcher icon appearance: badge count,
 * progress bar, urgency, quicklist, and update status.
 *
 * Communicates with `com.canonical.Unity.LauncherEntry` over D-Bus via JNI (GIO/GDBus).
 * All methods are thread-safe.
 *
 * Specification: https://wiki.ubuntu.com/Unity/LauncherAPI
 *
 * Supported desktops: Unity, Plank, KDE, budgie-panel, and others that
 * implement the `com.canonical.Unity.LauncherEntry` D-Bus interface.
 */
public object LinuxLauncherEntry {
    /**
     * Whether the native library is loaded and the module is functional.
     *
     * Returns `false` on non-Linux platforms or if the native library could not be loaded.
     */
    public val isAvailable: Boolean
        get() = NativeLinuxLauncherBridge.isLoaded

    /**
     * Builds the `application://` URI from a `.desktop` file ID.
     *
     * Example: `appUri("firefox.desktop")` returns `"application://firefox.desktop"`.
     */
    public fun appUri(desktopFileId: String): String = "application://$desktopFileId"

    /**
     * Emits a `com.canonical.Unity.LauncherEntry.Update` signal with the given properties.
     *
     * Only non-null properties in [properties] are included in the signal.
     * Per the spec, only changed properties should be sent.
     *
     * @param appUri Application URI in the form `application://<desktop-file-id>`,
     *   e.g. `"application://firefox.desktop"`. Use [appUri] to build it.
     * @param properties The launcher entry properties to update.
     * @return `true` if the signal was emitted successfully.
     */
    public fun update(
        appUri: String,
        properties: LauncherProperties,
    ): Boolean {
        if (!isAvailable) return false
        return NativeLinuxLauncherBridge.nativeUpdate(
            appUri = appUri,
            hasCount = properties.count != null,
            count = properties.count ?: 0L,
            countVisible = properties.countVisible.toNativeFlag(),
            hasProgress = properties.progress != null,
            progress = properties.progress ?: 0.0,
            progressVisible = properties.progressVisible.toNativeFlag(),
            urgent = properties.urgent.toNativeFlag(),
            quicklist = properties.quicklist,
            updating = properties.updating.toNativeFlag(),
        )
    }

    /**
     * Registers a D-Bus object to handle `Query` method calls from the launcher.
     *
     * Call this once at startup if you want the launcher to be able to query
     * the current entry state (primarily used for debugging).
     *
     * @param appUri Application URI matching the one used in [update].
     * @return `true` if registration succeeded.
     */
    public fun registerQueryHandler(appUri: String): Boolean {
        if (!isAvailable) return false
        return NativeLinuxLauncherBridge.nativeRegisterQueryHandler(appUri)
    }

    /**
     * Unregisters the Query handler and releases D-Bus resources.
     *
     * Call this on application shutdown if [registerQueryHandler] was used.
     */
    public fun unregister() {
        if (!isAvailable) return
        NativeLinuxLauncherBridge.nativeUnregister()
    }

    // -- Convenience methods -----------------------------------------------

    /**
     * Sets the badge count on the launcher icon.
     *
     * @param appUri Application URI.
     * @param count The count value to display.
     * @param visible Whether the count badge is visible. Defaults to `true`.
     */
    public fun setCount(
        appUri: String,
        count: Long,
        visible: Boolean = true,
    ): Boolean = update(appUri, LauncherProperties(count = count, countVisible = visible))

    /**
     * Clears the badge count.
     */
    public fun clearCount(appUri: String): Boolean = update(appUri, LauncherProperties(count = 0L, countVisible = false))

    /**
     * Sets the progress bar on the launcher icon.
     *
     * @param appUri Application URI.
     * @param progress Progress value in the range `0.0..1.0`.
     * @param visible Whether the progress bar is visible. Defaults to `true`.
     */
    public fun setProgress(
        appUri: String,
        progress: Double,
        visible: Boolean = true,
    ): Boolean = update(appUri, LauncherProperties(progress = progress, progressVisible = visible))

    /**
     * Clears the progress bar.
     */
    @Suppress("MaxLineLength")
    public fun clearProgress(appUri: String): Boolean =
        update(appUri, LauncherProperties(progress = 0.0, progressVisible = false))

    /**
     * Sets or clears the urgency flag on the launcher icon.
     */
    public fun setUrgent(
        appUri: String,
        urgent: Boolean,
    ): Boolean = update(appUri, LauncherProperties(urgent = urgent))

    /**
     * Sets or clears the updating flag on the launcher icon.
     */
    public fun setUpdating(
        appUri: String,
        updating: Boolean,
    ): Boolean = update(appUri, LauncherProperties(updating = updating))
}

private fun Boolean?.toNativeFlag(): Int =
    when (this) {
        null -> -1
        true -> 1
        false -> 0
    }
