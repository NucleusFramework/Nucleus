package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.NucleusPlatformView
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxWidgetBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real, focusable native text widget for a headful case to embed through
 * `NativeView`: a `GtkEntry`, an `NSTextField` or an `EDIT` control.
 *
 * The focus and cursor races between Compose and an embed only happen
 * against a widget that *takes* keyboard focus on click and *shows* an
 * I-beam — an empty overlay view has neither, so it cannot lose a keystroke
 * or leave a stale cursor behind. The bridges hand these out for the suite
 * (`nativeDiag*`); nothing in `NativeView` itself uses them.
 *
 * [platformView] is what `NativeView`'s factory returns. Its `dispose()` — the
 * one `NativeView` calls when it leaves composition — destroys the widget and
 * marks the probe [isDisposed], so a fixture can count probes created against
 * probes disposed and know whether an unmount leaked one.
 */
internal class NativeProbe private constructor(
    /** The widget as a handle, for reports. */
    val handle: Long,
    private val focusQuery: () -> Boolean,
    private val textQuery: () -> String?,
    private val frameQuery: () -> IntArray?,
    private val destroy: () -> Unit,
) {
    @Volatile
    var isDisposed: Boolean = false
        private set

    /** Whether the OS routes keystrokes to the widget right now. */
    fun hasNativeFocus(): Boolean = !isDisposed && focusQuery()

    /** What has been typed into the widget so far. */
    fun text(): String = if (isDisposed) "" else textQuery().orEmpty()

    /**
     * Where the platform actually put the widget, in the window's content
     * space and physical px as `[x, y, w, h]` — null while it is not mapped.
     * Compared against the Compose slot to see how far the embed trails the
     * layout through a resize.
     */
    fun framePx(): IntArray? = if (isDisposed) null else frameQuery()

    val platformView: NucleusPlatformView =
        when (Platform.Current) {
            Platform.Linux ->
                object : NucleusPlatformView.GtkWidget {
                    override val gtkWidgetHandle: Long get() = handle

                    override fun dispose() = disposeOnce()
                }
            Platform.MacOS ->
                object : NucleusPlatformView.NsView {
                    override val nsViewHandle: Long get() = handle

                    override fun dispose() = disposeOnce()
                }
            else ->
                object : NucleusPlatformView.HWnd {
                    override val hwndHandle: Long get() = handle

                    override fun dispose() = disposeOnce()
                }
        }

    private fun disposeOnce() {
        if (isDisposed) return
        isDisposed = true
        disposedCount.incrementAndGet()
        destroy()
    }

    companion object {
        /** Probes created so far in this process. */
        val createdCount = AtomicInteger()

        /** Probes whose `dispose()` ran so far in this process. */
        val disposedCount = AtomicInteger()

        /** Why no probe can be made on this host, or null when one can. */
        fun skipReason(): String? =
            when (Platform.Current) {
                Platform.Linux ->
                    if (!NativeTaoLinuxWidgetBridge.isLoaded) {
                        "libnucleus_tao_linux_widget is not loaded"
                    } else if (NativeTaoLinuxWidgetBridge.nativeGtkVersion() == null) {
                        "GTK 3 is not available"
                    } else {
                        null
                    }
                Platform.MacOS ->
                    if (NativeTaoMacOsNativeViewBridge.isLoaded) {
                        null
                    } else {
                        "libnucleus_tao_macos_native_view is not loaded"
                    }
                Platform.Windows ->
                    if (NativeTaoWindowsNativeViewBridge.isLoaded) {
                        null
                    } else {
                        "nucleus_tao_windows_native_view is not loaded"
                    }
                else -> "no native view backend on ${Platform.Current}"
            }

        /**
         * Makes a probe for [window]. Runs on the loop thread (GTK / AppKit
         * demand it), typically from a `NativeView` factory. Null when the
         * platform refused — see [skipReason] for the reasons known upfront.
         */
        fun create(window: TaoWindow): NativeProbe? {
            val probe =
                when (Platform.Current) {
                    Platform.Linux -> createGtkEntry(window)
                    Platform.MacOS -> createNsTextField()
                    Platform.Windows -> createWin32Edit()
                    else -> null
                } ?: return null
            createdCount.incrementAndGet()
            return probe.also { probes[window.handle] = it }
        }

        /** Whether Compose — not an embed — owns the keyboard in [window], as far as the OS can tell. */
        fun composeOwnsNativeFocus(window: TaoWindow): Boolean? =
            when (Platform.Current) {
                Platform.Linux -> {
                    val gtkWindow = NativeTaoBridge.nativeLinuxGtkWindow(window.handle)
                    if (gtkWindow == 0L) {
                        null
                    } else {
                        // Tao's own key handler sits on the toplevel: focus on
                        // nothing, or on one of the suite's input boxes, is what
                        // "Compose has the keyboard" looks like. Only the embed
                        // itself steals it.
                        val focus = NativeTaoLinuxWidgetBridge.nativeDiagFocusWidget(gtkWindow)
                        probes[window.handle]?.handle != focus
                    }
                }
                Platform.MacOS -> {
                    val content = NativeTaoBridge.nativeNsViewHandle(window.handle)
                    if (content == 0L) null else NativeTaoMacOsNativeViewBridge.nativeDiagViewIsFirstResponder(content)
                }
                Platform.Windows -> NativeTaoWindowsNativeViewBridge.nativeDiagFocusedHwnd() == window.nativeHandle
                else -> null
            }

        /** The last probe created per window, for [composeOwnsNativeFocus]. */
        private val probes = java.util.concurrent.ConcurrentHashMap<Long, NativeProbe>()

        private fun createGtkEntry(window: TaoWindow): NativeProbe? {
            val entry = NativeTaoLinuxWidgetBridge.nativeDiagCreateEntry()
            if (entry == 0L) return null
            return NativeProbe(
                handle = entry,
                focusQuery = { NativeTaoLinuxWidgetBridge.nativeDiagWidgetHasFocus(entry) },
                textQuery = { NativeTaoLinuxWidgetBridge.nativeDiagEntryText(entry) },
                frameQuery = {
                    // GTK lays out in logical px; Compose measures in physical.
                    val gtkWindow = NativeTaoBridge.nativeLinuxGtkWindow(window.handle)
                    val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
                    NativeTaoLinuxWidgetBridge
                        .nativeDiagWidgetFrame(gtkWindow, entry)
                        ?.map { (it * scale).toInt() }
                        ?.toIntArray()
                },
                destroy = { NativeTaoLinuxWidgetBridge.nativeDiagDestroyWidget(entry) },
            )
        }

        private fun createNsTextField(): NativeProbe? {
            val field = NativeTaoMacOsNativeViewBridge.nativeDiagCreateTextField()
            if (field == 0L) return null
            return NativeProbe(
                handle = field,
                focusQuery = { NativeTaoMacOsNativeViewBridge.nativeDiagViewIsEditing(field) },
                textQuery = { NativeTaoMacOsNativeViewBridge.nativeDiagTextFieldString(field) },
                frameQuery = { NativeTaoMacOsNativeViewBridge.nativeDiagViewFrame(field) },
                destroy = { NativeTaoMacOsNativeViewBridge.nativeDiagReleaseView(field) },
            )
        }

        private fun createWin32Edit(): NativeProbe? {
            val edit = NativeTaoWindowsNativeViewBridge.nativeDiagCreateEdit()
            if (edit == 0L) return null
            return NativeProbe(
                handle = edit,
                focusQuery = { NativeTaoWindowsNativeViewBridge.nativeDiagFocusedHwnd() == edit },
                textQuery = { NativeTaoWindowsNativeViewBridge.nativeDiagWindowText(edit) },
                frameQuery = { NativeTaoWindowsNativeViewBridge.nativeDiagWindowFrame(edit) },
                destroy = { NativeTaoWindowsNativeViewBridge.nativeDiagDestroyWindow(edit) },
            )
        }
    }
}
