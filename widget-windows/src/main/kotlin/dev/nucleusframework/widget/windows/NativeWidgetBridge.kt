@file:Suppress("TooManyFunctions", "LongParameterList")

package dev.nucleusframework.widget.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.core.runtime.Platform
import java.lang.ref.Cleaner
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

private const val LIBRARY_NAME = "nucleus_widget_windows"

internal object NativeWidgetBridge {
    val isLoaded: Boolean by lazy {
        Platform.Current == Platform.Windows && NativeLibraryLoader.load(LIBRARY_NAME, NativeWidgetBridge::class.java)
    }

    /**
     * Every WinRT call runs on this dedicated MTA thread. It owns the process MTA for as long as the
     * JVM lives, which keeps `CoRegisterClassObject` registrations alive and lets the COM RPC threads
     * that deliver provider callbacks (and the [Cleaner] thread) use the implicit MTA. It also keeps
     * calls off the Tao event-loop thread, which winit initializes as STA.
     */
    private lateinit var comThread: Thread
    private val comExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nucleus-widget-com").apply {
                isDaemon = true
                comThread = this
            }
        }

    private val apartment by lazy {
        comExecutor.submit<Int> { nativeInitializeApartment() }.get()
    }

    /** Runs [block] on the COM thread and rethrows its exception, if any. */
    fun <T> onCom(block: () -> T): T {
        check(isLoaded) { "The $LIBRARY_NAME native library is not loaded" }
        val hr = apartment
        if (hr < 0) throw WidgetException("RoInitialize failed", hr)
        if (Thread.currentThread() === comThread) return block()
        return try {
            comExecutor.submit<T>(block).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    // -- Lifecycle --

    @JvmStatic external fun nativeInitializeApartment(): Int

    @JvmStatic external fun nativeIsAvailable(): Boolean

    @JvmStatic external fun nativeRegisterProvider(
        clsid: String,
        dispatcher: WidgetProviderDispatcher,
        flags: Int,
    ): Long

    @JvmStatic external fun nativeRevokeProvider(cookie: Long)

    @JvmStatic external fun nativeRelease(handle: Long)

    // -- WidgetManager --

    @JvmStatic external fun nativeUpdateWidget(
        widgetId: String,
        template: String?,
        data: String?,
        customState: String?,
        placeholder: Int,
        hasRank: Boolean,
        rank: Int,
    )

    @JvmStatic external fun nativeGetWidgetIds(): Array<String>

    @JvmStatic external fun nativeGetWidgetInfo(widgetId: String): Array<Any>

    @JvmStatic external fun nativeGetWidgetInfos(): Array<Any>

    @JvmStatic external fun nativeDeleteWidget(widgetId: String)

    @JvmStatic external fun nativeSendMessageToContent(
        widgetId: String,
        message: String,
    )

    @JvmStatic external fun nativeGetUnsetValue(): String

    // -- WidgetResourceRequestedArgs / Deferral --

    @JvmStatic external fun nativeArgsGetRequest(handle: Long): Long

    @JvmStatic external fun nativeArgsGetResponse(handle: Long): Long

    @JvmStatic external fun nativeArgsSetResponse(
        handle: Long,
        response: Long,
    )

    @JvmStatic external fun nativeArgsGetDeferral(handle: Long): Long

    @JvmStatic external fun nativeDeferralComplete(handle: Long)

    // -- WidgetResourceRequest --

    @JvmStatic external fun nativeRequestGetUri(handle: Long): String

    @JvmStatic external fun nativeRequestGetMethod(handle: Long): String

    @JvmStatic external fun nativeRequestSetMethod(
        handle: Long,
        value: String,
    )

    @JvmStatic external fun nativeRequestGetContent(handle: Long): ByteArray?

    @JvmStatic external fun nativeRequestSetContent(
        handle: Long,
        value: ByteArray?,
    )

    @JvmStatic external fun nativeRequestGetHeaders(handle: Long): Long

    // -- WidgetResourceResponse --

    @JvmStatic external fun nativeResponseCreate(
        content: ByteArray?,
        reasonPhrase: String,
        statusCode: Int,
    ): Long

    @JvmStatic external fun nativeResponseGetContent(handle: Long): ByteArray?

    @JvmStatic external fun nativeResponseGetHeaders(handle: Long): Long

    @JvmStatic external fun nativeResponseGetReasonPhrase(handle: Long): String

    @JvmStatic external fun nativeResponseGetStatusCode(handle: Long): Int

    // -- IMap<String, String> --

    @JvmStatic external fun nativeMapLookup(
        handle: Long,
        key: String,
    ): String?

    @JvmStatic external fun nativeMapSize(handle: Long): Int

    @JvmStatic external fun nativeMapHasKey(
        handle: Long,
        key: String,
    ): Boolean

    @JvmStatic external fun nativeMapInsert(
        handle: Long,
        key: String,
        value: String,
    ): Boolean

    @JvmStatic external fun nativeMapRemove(
        handle: Long,
        key: String,
    )

    @JvmStatic external fun nativeMapClear(handle: Long)

    @JvmStatic external fun nativeMapEntries(handle: Long): Array<String>
}

/**
 * An AddRef'd WinRT interface pointer owned by [owner]; released once [owner] is unreachable.
 * Release() needs no apartment hop: the objects are agile and the process MTA is kept alive by the
 * COM thread.
 */
internal class NativeHandle(
    owner: Any,
    val handle: Long,
) {
    init {
        if (handle != 0L) cleaner.register(owner) { NativeWidgetBridge.nativeRelease(handle) }
    }

    private companion object {
        val cleaner: Cleaner = Cleaner.create()
    }
}
