package dev.nucleusframework.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.SingleInstanceRestoreEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Declares the app's widgets and connects to the platform's widget host:
 *
 * ```
 * fun main(args: Array<String>) = nucleusApplication(args) {
 *     widgets {
 *         widget("Counter") { CounterWidget() }
 *     }
 *     if (!isWidgetHostOnly()) {
 *         DecoratedWindow(onCloseRequest = ::exitApplication) { … }
 *     }
 * }
 * ```
 *
 * Call it at the top of the `nucleusApplication` content: it runs on the first composition,
 * well within the time the widget host waits for a process it launched. Only the first call
 * counts, so recompositions are harmless.
 */
@Suppress("UnusedReceiverParameter")
public fun NucleusApplicationScope.widgets(declarations: WidgetsBuilder.() -> Unit): Unit =
    NucleusWidgets.start(declarations)

/**
 * Whether the app runs only for the widget host: the host launched it (a widget needed app code,
 * e.g. a button click, while the app was closed) and nothing has asked for a window since.
 * Skip the main window while it is `true`.
 *
 * It turns `false` for good when the user starts the app again or opens one of its deep links
 * (both reach this instance through the single-instance lock). While it is `true`, the app exits
 * once no widget has been visible for [idleExit], so it does not linger in the background; pass
 * `null` to keep it running.
 */
@Composable
public fun NucleusApplicationScope.isWidgetHostOnly(idleExit: Duration? = 30.seconds): Boolean {
    var hostOnly by remember { mutableStateOf(NucleusWidgets.isLaunchedByWidgetHost) }
    SingleInstanceRestoreEffect { hostOnly = false }
    if (hostOnly && idleExit != null) {
        LaunchedEffect(idleExit) {
            val visible = mutableSetOf<WidgetId>()
            @Suppress("USELESS_CAST") // widens the flow so onStart can seed the first countdown
            (NucleusWidgets.events as Flow<WidgetEvent?>)
                .onStart { emit(null) }
                .collectLatest { event ->
                    when (event) {
                        is WidgetEvent.Activated -> visible += event.id
                        is WidgetEvent.Deactivated -> visible -= event.id
                        is WidgetEvent.Unpinned -> visible -= event.id
                        else -> Unit
                    }
                    // Any later event cancels this countdown (collectLatest) and starts a new one.
                    if (visible.isEmpty()) {
                        delay(idleExit)
                        exitApplication()
                    }
                }
        }
    }
    return hostOnly
}
