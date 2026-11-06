package dev.nucleusframework.widget.windows

/**
 * Data for [WidgetProvider.onActionInvoked], raised when an action is invoked on a widget,
 * such as the user clicking an `Action.Execute` button.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetActionInvokedArgs`.
 *
 * @property widgetContext Context of the widget the action was invoked on.
 * @property verb The `verb` of the invoked `Action.Execute`.
 * @property data The `data` of the invoked action, merged with the card's input values, as JSON.
 * @property customState The custom state last set on the widget with [WidgetManager.updateWidget].
 */
public data class WidgetActionInvokedArgs(
    val widgetContext: WidgetContext,
    val verb: String,
    val data: String,
    val customState: String,
)

/**
 * Data for [WidgetProvider.onWidgetContextChanged], raised when the widget context changes
 * (for example when the user resizes the widget).
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetContextChangedArgs`.
 *
 * @property widgetContext The updated widget context.
 */
public data class WidgetContextChangedArgs(
    val widgetContext: WidgetContext,
)

/**
 * Data for [WidgetProvider2.onCustomizationRequested], raised when the user requested widget
 * customization through the widget host UI.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetCustomizationRequestedArgs`.
 *
 * @property widgetContext Context of the widget to customize.
 * @property customState The custom state last set on the widget.
 */
public data class WidgetCustomizationRequestedArgs(
    val widgetContext: WidgetContext,
    val customState: String,
)

/**
 * Data for [WidgetProviderAnalytics.onAnalyticsInfoReported], raised when the widget host reports
 * analytics data about a user interaction with a widget.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetAnalyticsInfoReportedArgs`.
 *
 * @property widgetContext Context of the widget the analytics data relates to.
 * @property analyticsJson The analytics payload, as JSON.
 */
public data class WidgetAnalyticsInfoReportedArgs(
    val widgetContext: WidgetContext,
    val analyticsJson: String,
)

/**
 * Data for [WidgetProviderErrors.onErrorInfoReported], raised when the widget host reports a widget
 * error (for example an invalid template).
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetErrorInfoReportedArgs`.
 *
 * @property widgetContext Context of the widget the error relates to.
 * @property errorJson The error payload, as JSON.
 */
public data class WidgetErrorInfoReportedArgs(
    val widgetContext: WidgetContext,
    val errorJson: String,
)

/**
 * Data for [WidgetProviderMessage.onMessageReceived], raised when the widget's web content posts a
 * message with `window.chrome.webview.postMessage`.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetMessageReceivedArgs`.
 *
 * @property widgetContext Context of the widget that posted the message.
 * @property message The posted message.
 */
public data class WidgetMessageReceivedArgs(
    val widgetContext: WidgetContext,
    val message: String,
)
