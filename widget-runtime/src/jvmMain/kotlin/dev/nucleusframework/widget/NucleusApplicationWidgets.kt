package dev.nucleusframework.widget

import dev.nucleusframework.application.NucleusApplicationScope

/**
 * Declares the app's widgets and connects to the platform's widget host:
 *
 * ```
 * fun main(args: Array<String>) = nucleusApplication(args) {
 *     widgets {
 *         widget("Counter") { CounterWidget() }
 *     }
 *     // windows…
 * }
 * ```
 *
 * Call it at the top of the `nucleusApplication` content: it runs on the first composition,
 * well within the time the widget host waits for a process it launched. Only the first call
 * counts, so recompositions are harmless. Check [NucleusWidgets.isLaunchedByWidgetHost] to skip
 * the main window when the host started the app.
 */
@Suppress("UnusedReceiverParameter")
public fun NucleusApplicationScope.widgets(declarations: WidgetsBuilder.() -> Unit): Unit =
    NucleusWidgets.start(declarations)
