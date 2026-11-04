package dev.nucleusframework.widget.windows

import java.time.Instant

/**
 * Queries, updates and deletes the widgets of this provider.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetManager`. The class lives in the Windows App SDK
 * framework package: it is only [available][isAvailable] in a process with package identity that
 * declares the Windows App SDK dependency.
 */
public class WidgetManager private constructor() {
    /** Pushes a new template, data and/or custom state to a widget. */
    public fun updateWidget(widgetUpdateRequestOptions: WidgetUpdateRequestOptions) {
        with(widgetUpdateRequestOptions) {
            NativeWidgetBridge.onCom {
                NativeWidgetBridge.nativeUpdateWidget(
                    widgetId,
                    template,
                    data,
                    customState,
                    isPlaceholderContent?.let { if (it) 1 else 0 } ?: -1,
                    rank != null,
                    rank ?: 0,
                )
            }
        }
    }

    /** Returns the ids of every widget of this provider currently pinned in the host. */
    public fun getWidgetIds(): List<String> =
        NativeWidgetBridge.onCom { NativeWidgetBridge.nativeGetWidgetIds().toList() }

    /** Returns the state of the widget [widgetId], or `null` if no such widget exists. */
    public fun getWidgetInfo(widgetId: String): WidgetInfo? =
        NativeWidgetBridge.onCom { WidgetInfo.unpack(NativeWidgetBridge.nativeGetWidgetInfo(widgetId)).firstOrNull() }

    /** Returns the state of every widget of this provider currently pinned in the host. */
    public fun getWidgetInfos(): List<WidgetInfo> =
        NativeWidgetBridge.onCom { WidgetInfo.unpack(NativeWidgetBridge.nativeGetWidgetInfos()) }

    /** Removes the widget [widgetId] from the host. */
    public fun deleteWidget(widgetId: String) {
        NativeWidgetBridge.onCom { NativeWidgetBridge.nativeDeleteWidget(widgetId) }
    }

    /**
     * Sends [message] to the web content of the widget [widgetId], received there through
     * `window.chrome.webview.addEventListener("message", …)`.
     *
     * From `IWidgetManager2` (WidgetContract 6).
     */
    public fun sendMessageToContent(
        widgetId: String,
        message: String,
    ) {
        NativeWidgetBridge.onCom { NativeWidgetBridge.nativeSendMessageToContent(widgetId, message) }
    }

    /** Factory and availability check. */
    public companion object {
        private val instance by lazy { WidgetManager() }

        /**
         * Whether the widget provider API can be used: running on Windows, with the native library
         * loaded and the Windows App SDK `WidgetManager` class activatable.
         */
        public val isAvailable: Boolean
            get() = NativeWidgetBridge.isLoaded && NativeWidgetBridge.onCom { NativeWidgetBridge.nativeIsAvailable() }

        /**
         * Returns the default [WidgetManager].
         *
         * @throws WidgetException if the widget provider API is not [available][isAvailable].
         */
        public fun getDefault(): WidgetManager {
            if (!isAvailable) throw WidgetException("The Windows widget provider API is not available", E_NOT_AVAILABLE)
            return instance
        }
    }
}

/**
 * Options of a [WidgetManager.updateWidget] call. A `null` property leaves the corresponding widget
 * value unchanged; [unsetValue] clears it.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetUpdateRequestOptions`.
 *
 * @property widgetId Id of the widget to update.
 */
public class WidgetUpdateRequestOptions(
    public val widgetId: String,
) {
    /** The Adaptive Card template JSON. */
    public var template: String? = null

    /** The data JSON bound to the template. */
    public var data: String? = null

    /** An opaque string persisted by the host with the widget and handed back in later callbacks. */
    public var customState: String? = null

    /**
     * Whether the content is a placeholder shown until real content is available.
     *
     * From `IWidgetUpdateRequestOptions2` (WidgetContract 9).
     */
    public var isPlaceholderContent: Boolean? = null

    /**
     * Ranking hint of the widget content.
     *
     * From `IWidgetUpdateRequestOptions3` (WidgetContract 11).
     */
    public var rank: Int? = null

    /** Holder of [unsetValue]. */
    public companion object {
        /** Sentinel value that clears a [template], [data] or [customState] previously set on the widget. */
        public val unsetValue: String by lazy { NativeWidgetBridge.onCom { NativeWidgetBridge.nativeGetUnsetValue() } }
    }
}

/**
 * State of a widget as stored by the host.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetInfo`.
 *
 * @property widgetContext The widget context.
 * @property template The last template pushed to the widget.
 * @property data The last data pushed to the widget.
 * @property customState The last custom state pushed to the widget.
 * @property lastUpdateTime When the widget was last updated.
 * @property isPlaceholderContent Whether the content is a placeholder; `null` when the installed
 *   Windows App SDK predates WidgetContract 9.
 * @property rank The content rank; `null` when the installed Windows App SDK predates WidgetContract 11.
 */
public data class WidgetInfo(
    val widgetContext: WidgetContext,
    val template: String,
    val data: String,
    val customState: String,
    val lastUpdateTime: Instant,
    val isPlaceholderContent: Boolean?,
    val rank: Int?,
) {
    internal companion object {
        private const val STRINGS_PER_INFO = 5
        private const val NUMBERS_PER_INFO = 6

        // Layout written by appendInfo() in nucleus_widget_windows.cpp.
        @Suppress("UNCHECKED_CAST", "MagicNumber")
        fun unpack(packed: Array<Any>): List<WidgetInfo> {
            val strings = packed[0] as Array<String>
            val numbers = packed[1] as LongArray
            return List(strings.size / STRINGS_PER_INFO) { i ->
                val s = i * STRINGS_PER_INFO
                val n = i * NUMBERS_PER_INFO
                WidgetInfo(
                    widgetContext =
                        WidgetContext(
                            id = strings[s],
                            definitionId = strings[s + 1],
                            size = WidgetSize.fromRawValue(numbers[n].toInt()),
                            isActive = numbers[n + 1] != 0L,
                        ),
                    template = strings[s + 2],
                    data = strings[s + 3],
                    customState = strings[s + 4],
                    lastUpdateTime = Instant.ofEpochMilli(numbers[n + 2]),
                    isPlaceholderContent = numbers[n + 3].takeIf { it >= 0 }?.let { it == 1L },
                    rank = numbers[n + 5].toInt().takeIf { numbers[n + 4] != 0L },
                )
            }
        }
    }
}
