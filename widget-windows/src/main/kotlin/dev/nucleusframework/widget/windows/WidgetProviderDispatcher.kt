package dev.nucleusframework.widget.windows

import java.util.logging.Level
import java.util.logging.Logger

/**
 * JNI target of the native provider COM object: rebuilds the event args and forwards them to the
 * user's [WidgetProvider]. Every method returns `false` when the provider threw, which the native side
 * reports to the host as `E_FAIL`.
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal class WidgetProviderDispatcher(
    private val provider: WidgetProvider,
) {
    /** Optional interfaces the COM object exposes; bit layout mirrors FLAG_* in the native code. */
    val flags: Int =
        (if (provider is WidgetProvider2) FLAG_PROVIDER2 else 0) or
            (if (provider is WidgetProviderAnalytics) FLAG_ANALYTICS else 0) or
            (if (provider is WidgetProviderErrors) FLAG_ERRORS else 0) or
            (if (provider is WidgetProviderMessage) FLAG_MESSAGE else 0) or
            (if (provider is WidgetResourceProvider) FLAG_RESOURCE else 0)

    fun createWidget(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
    ): Boolean = dispatch("createWidget") { provider.createWidget(context(id, definitionId, size, isActive)) }

    fun deleteWidget(
        widgetId: String,
        customState: String,
    ): Boolean = dispatch("deleteWidget") { provider.deleteWidget(widgetId, customState) }

    fun onActionInvoked(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
        verb: String,
        data: String,
        customState: String,
    ): Boolean =
        dispatch("onActionInvoked") {
            provider.onActionInvoked(
                WidgetActionInvokedArgs(context(id, definitionId, size, isActive), verb, data, customState),
            )
        }

    fun onWidgetContextChanged(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
    ): Boolean =
        dispatch("onWidgetContextChanged") {
            provider.onWidgetContextChanged(WidgetContextChangedArgs(context(id, definitionId, size, isActive)))
        }

    fun activate(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
    ): Boolean = dispatch("activate") { provider.activate(context(id, definitionId, size, isActive)) }

    fun deactivate(widgetId: String): Boolean = dispatch("deactivate") { provider.deactivate(widgetId) }

    fun onCustomizationRequested(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
        customState: String,
    ): Boolean =
        dispatch("onCustomizationRequested") {
            (provider as WidgetProvider2).onCustomizationRequested(
                WidgetCustomizationRequestedArgs(context(id, definitionId, size, isActive), customState),
            )
        }

    fun onAnalyticsInfoReported(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
        analyticsJson: String,
    ): Boolean =
        dispatch("onAnalyticsInfoReported") {
            (provider as WidgetProviderAnalytics).onAnalyticsInfoReported(
                WidgetAnalyticsInfoReportedArgs(context(id, definitionId, size, isActive), analyticsJson),
            )
        }

    fun onErrorInfoReported(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
        errorJson: String,
    ): Boolean =
        dispatch("onErrorInfoReported") {
            (provider as WidgetProviderErrors).onErrorInfoReported(
                WidgetErrorInfoReportedArgs(context(id, definitionId, size, isActive), errorJson),
            )
        }

    fun onMessageReceived(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
        message: String,
    ): Boolean =
        dispatch("onMessageReceived") {
            (provider as WidgetProviderMessage).onMessageReceived(
                WidgetMessageReceivedArgs(context(id, definitionId, size, isActive), message),
            )
        }

    /** [argsHandle] is an AddRef'd `IWidgetResourceRequestedArgs*`, owned from here on. */
    fun onResourceRequested(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
        argsHandle: Long,
    ): Boolean {
        val args = WidgetResourceRequestedArgs(context(id, definitionId, size, isActive), argsHandle)
        return dispatch("onResourceRequested") { (provider as WidgetResourceProvider).onResourceRequested(args) }
    }

    private fun context(
        id: String,
        definitionId: String,
        size: Int,
        isActive: Boolean,
    ) = WidgetContext(id, definitionId, WidgetSize.fromRawValue(size), isActive)

    @Suppress("TooGenericExceptionCaught")
    private inline fun dispatch(
        callback: String,
        block: () -> Unit,
    ): Boolean =
        try {
            block()
            true
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "Widget provider $callback threw", t)
            false
        }

    private companion object {
        const val FLAG_PROVIDER2 = 1 shl 0
        const val FLAG_ANALYTICS = 1 shl 1
        const val FLAG_ERRORS = 1 shl 2
        const val FLAG_MESSAGE = 1 shl 3
        const val FLAG_RESOURCE = 1 shl 4

        val logger: Logger = Logger.getLogger(WidgetProviderDispatcher::class.java.name)
    }
}
