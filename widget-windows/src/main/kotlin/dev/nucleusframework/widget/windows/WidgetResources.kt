package dev.nucleusframework.widget.windows

import dev.nucleusframework.widget.ExperimentalNucleusWidgetApi

/**
 * Data for [WidgetResourceProvider.onResourceRequested]. Unlike the other event args this object is
 * live: setting [response] or altering [request] is seen by the widget host.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetResourceRequestedArgs` (WidgetContract 6).
 *
 * @property widgetContext Context of the widget whose content made the request.
 */
@ExperimentalNucleusWidgetApi
public class WidgetResourceRequestedArgs internal constructor(
    public val widgetContext: WidgetContext,
    handle: Long,
) {
    private val native = NativeHandle(this, handle)

    /** The request made by the widget content. */
    public val request: WidgetResourceRequest by lazy {
        WidgetResourceRequest(NativeWidgetBridge.onCom { NativeWidgetBridge.nativeArgsGetRequest(native.handle) })
    }

    /** The response returned to the widget content; `null` lets the request proceed to the network. */
    public var response: WidgetResourceResponse?
        get() =
            NativeWidgetBridge
                .onCom { NativeWidgetBridge.nativeArgsGetResponse(native.handle) }
                .takeIf { it != 0L }
                ?.let(::WidgetResourceResponse)
        set(value) {
            NativeWidgetBridge.onCom {
                NativeWidgetBridge.nativeArgsSetResponse(
                    native.handle,
                    value?.native?.handle ?: 0L,
                )
            }
        }

    /** Defers the answer until [Deferral.complete] is called, so [response] can be set asynchronously. */
    public fun getDeferral(): Deferral =
        Deferral(
            NativeWidgetBridge.onCom {
                NativeWidgetBridge.nativeArgsGetDeferral(native.handle)
            },
        )
}

/**
 * A resource request made by a web widget's content.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetResourceRequest` (WidgetContract 6).
 */
@ExperimentalNucleusWidgetApi
public class WidgetResourceRequest internal constructor(
    handle: Long,
) {
    private val native = NativeHandle(this, handle)

    /** The requested URI. */
    public val uri: String
        get() = NativeWidgetBridge.onCom { NativeWidgetBridge.nativeRequestGetUri(native.handle) }

    /** The HTTP method. */
    public var method: String
        get() = NativeWidgetBridge.onCom { NativeWidgetBridge.nativeRequestGetMethod(native.handle) }
        set(value) {
            NativeWidgetBridge.onCom { NativeWidgetBridge.nativeRequestSetMethod(native.handle, value) }
        }

    /** The request body; `null` when the request has none. */
    public var content: ByteArray?
        get() = NativeWidgetBridge.onCom { NativeWidgetBridge.nativeRequestGetContent(native.handle) }
        set(value) {
            NativeWidgetBridge.onCom { NativeWidgetBridge.nativeRequestSetContent(native.handle, value) }
        }

    /** The request headers, live. */
    public val headers: WidgetHeaders by lazy {
        WidgetHeaders(NativeWidgetBridge.onCom { NativeWidgetBridge.nativeRequestGetHeaders(native.handle) })
    }
}

/**
 * A response to a [WidgetResourceRequest].
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetResourceResponse` (WidgetContract 6).
 */
@ExperimentalNucleusWidgetApi
public class WidgetResourceResponse internal constructor(
    handle: Long,
) {
    internal val native = NativeHandle(this, handle)

    /**
     * Creates a response with the given body, reason phrase and HTTP status code.
     *
     * @throws WidgetException if the installed Windows App SDK predates WidgetContract 6.
     */
    public constructor(content: ByteArray?, reasonPhrase: String, statusCode: Int) : this(
        NativeWidgetBridge.onCom { NativeWidgetBridge.nativeResponseCreate(content, reasonPhrase, statusCode) },
    )

    /** The response body; `null` when the response has none. */
    public val content: ByteArray?
        get() = NativeWidgetBridge.onCom { NativeWidgetBridge.nativeResponseGetContent(native.handle) }

    /** The response headers, live. */
    public val headers: WidgetHeaders by lazy {
        WidgetHeaders(NativeWidgetBridge.onCom { NativeWidgetBridge.nativeResponseGetHeaders(native.handle) })
    }

    /** The HTTP reason phrase. */
    public val reasonPhrase: String
        get() = NativeWidgetBridge.onCom { NativeWidgetBridge.nativeResponseGetReasonPhrase(native.handle) }

    /** The HTTP status code. */
    public val statusCode: Int
        get() = NativeWidgetBridge.onCom { NativeWidgetBridge.nativeResponseGetStatusCode(native.handle) }
}

/**
 * Live HTTP header map of a [WidgetResourceRequest] or [WidgetResourceResponse].
 *
 * Mirrors the `IMap<String, String>` returned by their `Headers` property.
 */
@ExperimentalNucleusWidgetApi
public class WidgetHeaders internal constructor(
    handle: Long,
) {
    private val native = NativeHandle(this, handle)

    /** Number of headers. */
    public val size: Int
        get() = NativeWidgetBridge.onCom { NativeWidgetBridge.nativeMapSize(native.handle) }

    /** Returns the value of header [key], or `null` if absent. */
    public fun lookup(key: String): String? =
        NativeWidgetBridge.onCom { NativeWidgetBridge.nativeMapLookup(native.handle, key) }

    /** Whether header [key] is present. */
    public fun hasKey(key: String): Boolean =
        NativeWidgetBridge.onCom { NativeWidgetBridge.nativeMapHasKey(native.handle, key) }

    /** Sets header [key] to [value]; returns `true` if an existing value was replaced. */
    public fun insert(
        key: String,
        value: String,
    ): Boolean = NativeWidgetBridge.onCom { NativeWidgetBridge.nativeMapInsert(native.handle, key, value) }

    /**
     * Removes header [key].
     *
     * @throws WidgetException if the header is absent.
     */
    public fun remove(key: String) {
        NativeWidgetBridge.onCom { NativeWidgetBridge.nativeMapRemove(native.handle, key) }
    }

    /** Removes every header. */
    public fun clear() {
        NativeWidgetBridge.onCom { NativeWidgetBridge.nativeMapClear(native.handle) }
    }

    /** Returns a snapshot of the headers. */
    public fun toMap(): Map<String, String> {
        val flat = NativeWidgetBridge.onCom { NativeWidgetBridge.nativeMapEntries(native.handle) }
        return (flat.indices step 2).associate { flat[it] to flat[it + 1] }
    }
}

/**
 * Defers the completion of a [WidgetResourceProvider.onResourceRequested] callback.
 *
 * Mirrors `Windows.Foundation.Deferral`.
 */
@ExperimentalNucleusWidgetApi
public class Deferral internal constructor(
    handle: Long,
) {
    private val native = NativeHandle(this, handle)

    /** Signals that the deferred work is done. */
    public fun complete() {
        NativeWidgetBridge.onCom { NativeWidgetBridge.nativeDeferralComplete(native.handle) }
    }
}
