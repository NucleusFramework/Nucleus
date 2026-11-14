package dev.nucleusframework.widget.windows

import dev.nucleusframework.widget.ExperimentalNucleusWidgetApi

/**
 * Receives widget lifetime callbacks from the widget host.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.IWidgetProvider`. Register an implementation with
 * [WidgetProviderServer.register]. Implement any of [WidgetProvider2], [WidgetProviderAnalytics],
 * [WidgetProviderErrors], [WidgetProviderMessage] and [WidgetResourceProvider] on the same object to
 * receive the optional callbacks: the COM object only exposes the interfaces the provider implements.
 *
 * Callbacks run on COM RPC threads and must not block for long. A callback that throws is reported
 * to the host as a failure.
 */
@ExperimentalNucleusWidgetApi
public interface WidgetProvider {
    /** A new widget was pinned by the user. Push its initial content with [WidgetManager.updateWidget]. */
    public fun createWidget(widgetContext: WidgetContext)

    /** The widget [widgetId] was removed by the user; [customState] is its last custom state. */
    public fun deleteWidget(
        widgetId: String,
        customState: String,
    )

    /** An action was invoked on a widget, such as the user clicking a button. */
    public fun onActionInvoked(actionInvokedArgs: WidgetActionInvokedArgs)

    /** The context of a widget changed, for example its size. */
    public fun onWidgetContextChanged(contextChangedArgs: WidgetContextChangedArgs)

    /** The widget became visible in the host: it should now be kept up to date. */
    public fun activate(widgetContext: WidgetContext)

    /** The widget [widgetId] is no longer visible: updates can be paused until [activate]. */
    public fun deactivate(widgetId: String)
}

/**
 * Receives widget customization callbacks from the widget host.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.IWidgetProvider2` (WidgetContract 2).
 */
@ExperimentalNucleusWidgetApi
public interface WidgetProvider2 {
    /** The user requested customization of a widget through the host UI. */
    public fun onCustomizationRequested(customizationRequestedArgs: WidgetCustomizationRequestedArgs)
}

/**
 * Receives widget analytics callbacks from the widget host.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.IWidgetProviderAnalytics` (WidgetContract 2).
 */
@ExperimentalNucleusWidgetApi
public interface WidgetProviderAnalytics {
    /** The host reported analytics data about a user interaction with a widget. */
    public fun onAnalyticsInfoReported(args: WidgetAnalyticsInfoReportedArgs)
}

/**
 * Receives widget error callbacks from the widget host.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.IWidgetProviderErrors` (WidgetContract 2).
 */
@ExperimentalNucleusWidgetApi
public interface WidgetProviderErrors {
    /** The host reported a widget error. */
    public fun onErrorInfoReported(args: WidgetErrorInfoReportedArgs)
}

/**
 * Receives messages posted by a web widget's content.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.IWidgetProviderMessage` (WidgetContract 6).
 */
@ExperimentalNucleusWidgetApi
public interface WidgetProviderMessage {
    /** The widget content posted a message with `window.chrome.webview.postMessage`. */
    public fun onMessageReceived(args: WidgetMessageReceivedArgs)
}

/**
 * Monitors, provides or alters the resources requested by a web widget's content.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.IWidgetResourceProvider` (WidgetContract 6). Only
 * requests matching the `WebRequestFilter` of the widget definition in the package manifest are
 * routed here.
 */
@ExperimentalNucleusWidgetApi
public interface WidgetResourceProvider {
    /**
     * The widget content requested a resource. Set [WidgetResourceRequestedArgs.response] to answer
     * it, or alter [WidgetResourceRequestedArgs.request]. Take a [WidgetResourceRequestedArgs.getDeferral]
     * to answer asynchronously.
     */
    public fun onResourceRequested(args: WidgetResourceRequestedArgs)
}
