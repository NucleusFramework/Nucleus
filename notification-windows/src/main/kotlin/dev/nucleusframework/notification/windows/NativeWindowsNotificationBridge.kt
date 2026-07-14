@file:Suppress("TooManyFunctions", "LongParameterList")

package dev.nucleusframework.notification.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

private const val LIBRARY_NAME = "nucleus_notification_windows"

internal object NativeWindowsNotificationBridge {
    private val callbackCounter = AtomicLong(0)
    private val callbacks = ConcurrentHashMap<Long, Any>()

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsNotificationBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * All WinRT calls run on this single dedicated thread so that COM is initialized as MTA
     * (a fresh thread with no prior apartment). This is essential under the Tao backend, where
     * Compose event handlers run on the event-loop thread that winit has already initialized as
     * STA via `OleInitialize`. Showing a toast on that STA thread binds the `ClassicCom` event
     * handlers to it, so delivering the activation/dismissal events requires the thread to pump
     * messages — which it cannot while blocked inside the click handler, freezing the whole UI
     * until the user interacts with the toast. On a dedicated MTA thread the handlers are invoked
     * on COM RPC pool threads, so no message pump is needed and the UI thread is never blocked.
     */
    private val comExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nucleus-winrt-notification").apply { isDaemon = true }
        }

    // -- Public API routed onto the COM (MTA) thread --

    fun initialize(
        aumid: String,
        isAppx: Boolean,
        appName: String,
        shortcutPolicy: Int,
    ): Boolean = comExecutor.submit<Boolean> { nativeInitialize(aumid, isAppx, appName, shortcutPolicy) }.get()

    @Suppress("LongParameterList")
    fun showToast(
        xml: String,
        tag: String,
        group: String,
        expiresOnReboot: Boolean,
        expirationTimeMs: Long,
        suppressPopup: Boolean,
        dataKeys: Array<String>,
        dataValues: Array<String>,
        dataSequenceNumber: Int,
        callbackId: Long,
    ) {
        comExecutor.execute {
            nativeShowToast(
                xml,
                tag,
                group,
                expiresOnReboot,
                expirationTimeMs,
                suppressPopup,
                dataKeys,
                dataValues,
                dataSequenceNumber,
                callbackId,
            )
        }
    }

    fun updateToast(
        tag: String,
        group: String,
        sequenceNumber: Int,
        keys: Array<String>,
        values: Array<String>,
        callbackId: Long,
    ) {
        comExecutor.execute { nativeUpdateToast(tag, group, sequenceNumber, keys, values, callbackId) }
    }

    fun removeToast(
        tag: String,
        group: String,
    ) {
        comExecutor.execute { nativeRemoveToast(tag, group) }
    }

    fun removeGroupToasts(group: String) {
        comExecutor.execute { nativeRemoveGroupToasts(group) }
    }

    fun clearAllToasts() {
        comExecutor.execute { nativeClearAllToasts() }
    }

    fun getHistory(callbackId: Long) {
        comExecutor.execute { nativeGetHistory(callbackId) }
    }

    fun uninitialize() {
        comExecutor.submit { nativeUninitialize() }.get()
    }

    // Listeners for toast events
    private val listeners = ConcurrentHashMap.newKeySet<ToastNotificationListener>()

    fun addListener(listener: ToastNotificationListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ToastNotificationListener) {
        listeners.remove(listener)
    }

    // -- Callback management --

    fun <T : Any> registerCallback(callback: T): Long {
        val id = callbackCounter.incrementAndGet()
        callbacks[id] = callback
        return id
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> consumeCallback(id: Long): T? = callbacks.remove(id) as? T

    // =========================================================================
    // Native method declarations
    // =========================================================================

    /**
     * Initialize the Windows notification subsystem.
     * Must be called once with the app's AUMID (Application User Model ID).
     * For packaged apps (MSIX), pass an empty string and isAppx=true.
     */
    @JvmStatic
    private external fun nativeInitialize(
        aumid: String,
        isAppx: Boolean,
        appName: String,
        shortcutPolicy: Int,
    ): Boolean

    /**
     * Show a toast notification from XML content.
     *
     * @param xml The complete toast XML string.
     * @param tag Notification tag for identification (max 16 chars, alphanumeric).
     * @param group Notification group for grouping (max 16 chars, alphanumeric).
     * @param expiresOnReboot Whether to remove the notification on reboot.
     * @param expirationTimeMs Expiration time in ms from now (0 = no expiration).
     * @param suppressPopup If true, the notification goes directly to Action Center.
     * @param callbackId Callback ID for result notification.
     */
    @JvmStatic
    @Suppress("LongParameterList")
    private external fun nativeShowToast(
        xml: String,
        tag: String,
        group: String,
        expiresOnReboot: Boolean,
        expirationTimeMs: Long,
        suppressPopup: Boolean,
        dataKeys: Array<String>,
        dataValues: Array<String>,
        dataSequenceNumber: Int,
        callbackId: Long,
    )

    /**
     * Update the data-bound fields of an existing toast (for progress bars).
     *
     * @param tag Notification tag.
     * @param group Notification group.
     * @param sequenceNumber Sequence number to prevent race conditions.
     * @param keys Data binding keys.
     * @param values Data binding values (parallel to keys).
     * @param callbackId Callback ID for result notification.
     */
    @JvmStatic
    private external fun nativeUpdateToast(
        tag: String,
        group: String,
        sequenceNumber: Int,
        keys: Array<String>,
        values: Array<String>,
        callbackId: Long,
    )

    /**
     * Remove a specific toast from Action Center.
     *
     * @param tag Notification tag.
     * @param group Notification group.
     */
    @JvmStatic
    private external fun nativeRemoveToast(
        tag: String,
        group: String,
    )

    /**
     * Remove all toasts in a specific group from Action Center.
     */
    @JvmStatic
    private external fun nativeRemoveGroupToasts(group: String)

    /**
     * Remove all toasts from Action Center for this app.
     */
    @JvmStatic
    private external fun nativeClearAllToasts()

    /**
     * Get the history of notifications in Action Center.
     * Results are delivered via [onHistoryResult] callback.
     */
    @JvmStatic
    private external fun nativeGetHistory(callbackId: Long)

    /**
     * Clean up native resources. Call on app shutdown.
     */
    @JvmStatic
    private external fun nativeUninitialize()

    // =========================================================================
    // Callbacks from native code
    // =========================================================================

    /** Called when a toast is successfully shown. */
    @JvmStatic
    fun onToastShown(
        callbackId: Long,
        error: String?,
    ) {
        consumeCallback<(String?) -> Unit>(callbackId)?.invoke(error)
    }

    /** Called when toast data is updated. */
    @JvmStatic
    fun onToastUpdated(
        callbackId: Long,
        error: String?,
    ) {
        consumeCallback<(String?) -> Unit>(callbackId)?.invoke(error)
    }

    /** Called when the user activates the toast (click or button). */
    @JvmStatic
    fun onToastActivated(
        tag: String,
        group: String,
        arguments: String,
        inputKeys: Array<String>,
        inputValues: Array<String>,
    ) {
        val inputs = inputKeys.indices.associate { inputKeys[it] to inputValues[it] }
        SwingUtilities.invokeLater {
            for (listener in listeners) {
                listener.onActivated(tag, group, arguments, inputs)
            }
        }
    }

    /** Called when the toast is dismissed. */
    @JvmStatic
    fun onToastDismissed(
        tag: String,
        group: String,
        reason: Int,
    ) {
        val dismissalReason = DismissalReason.fromRawValue(reason)
        SwingUtilities.invokeLater {
            for (listener in listeners) {
                listener.onDismissed(tag, group, dismissalReason)
            }
        }
    }

    /** Called when the toast fails. */
    @JvmStatic
    fun onToastFailed(
        tag: String,
        group: String,
        errorCode: Int,
    ) {
        SwingUtilities.invokeLater {
            for (listener in listeners) {
                listener.onFailed(tag, group, errorCode)
            }
        }
    }

    /** Called with notification history. */
    @JvmStatic
    fun onHistoryResult(
        callbackId: Long,
        tags: Array<String>,
        groups: Array<String>,
        error: String?,
    ) {
        val history = tags.indices.map { HistoryEntry(tags[it], groups[it]) }
        consumeCallback<(List<HistoryEntry>, String?) -> Unit>(callbackId)?.invoke(history, error)
    }
}

/** A notification entry in the Action Center history. */
data class HistoryEntry(
    val tag: String,
    val group: String,
)
