package dev.nucleusframework.lab.probes.shell.widgets

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.LabStartup
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stampedUnjudged
import dev.nucleusframework.widget.windows.WidgetActionInvokedArgs
import dev.nucleusframework.widget.windows.WidgetAnalyticsInfoReportedArgs
import dev.nucleusframework.widget.windows.WidgetContext
import dev.nucleusframework.widget.windows.WidgetContextChangedArgs
import dev.nucleusframework.widget.windows.WidgetCustomizationRequestedArgs
import dev.nucleusframework.widget.windows.WidgetErrorInfoReportedArgs
import dev.nucleusframework.widget.windows.WidgetManager
import dev.nucleusframework.widget.windows.WidgetProvider
import dev.nucleusframework.widget.windows.WidgetProvider2
import dev.nucleusframework.widget.windows.WidgetProviderAnalytics
import dev.nucleusframework.widget.windows.WidgetProviderErrors
import dev.nucleusframework.widget.windows.WidgetProviderServer
import dev.nucleusframework.widget.windows.WidgetSize
import dev.nucleusframework.widget.windows.WidgetUpdateRequestOptions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Port over `widget-windows`: the Lab's widget provider (a counter whose value lives in the
 * widget's custom state) and the [WidgetManager] calls the probe makes.
 */
interface WindowsWidgetsGateway {
    fun managerAvailability(): Availability

    val provider: StateFlow<ProviderStatus>

    /** Host callbacks, stamped on the COM RPC thread they arrived on. */
    val callbacks: SharedFlow<Stamped<String>>

    /** Registers the provider class object; called once, at process start. */
    fun register(args: Array<String>)

    fun list(): Result<List<PinnedWidget>>

    /** Bumps the counter of [widgetId] from the app side, as a background update would. */
    fun push(widgetId: String): CallOutcome

    fun delete(widgetId: String): CallOutcome
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusWindowsWidgetsGateway : WindowsWidgetsGateway {
    private val providerFlow = MutableStateFlow(ProviderStatus())
    override val provider: StateFlow<ProviderStatus> = providerFlow.asStateFlow()

    // Replayed: a COM launch delivers createWidget/activate before the probe is ever opened.
    private val callbackFlow = MutableSharedFlow<Stamped<String>>(replay = 64, extraBufferCapacity = 64)
    override val callbacks: SharedFlow<Stamped<String>> = callbackFlow.asSharedFlow()

    private var registration: AutoCloseable? = null

    override fun managerAvailability(): Availability =
        if (Platform.Current != Platform.Windows) {
            Availability.Unavailable("Windows 11 only")
        } else {
            Availability.catching({ "WidgetManager not activatable: run the packaged Lab (runAppX)" }) {
                WidgetManager.isAvailable
            }
        }

    override fun register(args: Array<String>) {
        if (Platform.Current != Platform.Windows || registration != null) return
        val started =
            ProcessHandle
                .current()
                .info()
                .startInstant()
                .map(Instant::toEpochMilli)
                .orElse(System.currentTimeMillis())
        val availability =
            runCatching { WidgetProviderServer.register(CLSID, LabWidgetProvider()) }.fold(
                onSuccess = {
                    registration = it
                    Availability.Available
                },
                onFailure = { Availability.Unavailable(it.summary) },
            )
        logger.info(
            "Widget provider $CLSID registration: $availability, WidgetManager available: ${managerAvailability()}",
        )
        providerFlow.value =
            ProviderStatus(
                registration = availability,
                launchedByCom = WidgetProviderServer.isComServerLaunch(args),
                processStartMillis = started,
            )
    }

    override fun list(): Result<List<PinnedWidget>> =
        runCatching {
            WidgetManager.getDefault().getWidgetInfos().map { info ->
                PinnedWidget(
                    id = info.widgetContext.id,
                    definitionId = info.widgetContext.definitionId,
                    size = info.widgetContext.size.name,
                    isActive = info.widgetContext.isActive,
                    count = info.customState.toIntOrNull() ?: 0,
                    lastUpdateMillis = info.lastUpdateTime.toEpochMilli(),
                )
            }
        }

    override fun push(widgetId: String): CallOutcome =
        CallOutcome.of(
            runCatching {
                val info = WidgetManager.getDefault().getWidgetInfo(widgetId) ?: error("no widget $widgetId")
                render(
                    widgetId,
                    info.widgetContext.size,
                    (info.customState.toIntOrNull() ?: 0) + 1,
                    "pushed by the app",
                )
            },
        )

    override fun delete(widgetId: String): CallOutcome =
        CallOutcome.of(runCatching { WidgetManager.getDefault().deleteWidget(widgetId) })

    private fun record(what: String) {
        logger.info("Widget host -> $what")
        providerFlow.update { it.copy(firstCallbackMillis = it.firstCallbackMillis ?: System.currentTimeMillis()) }
        // The provider API promises no thread: COM RPC threads deliver every callback.
        callbackFlow.tryEmit(what.stampedUnjudged())
    }

    private fun storedCount(widgetId: String): Int =
        runCatching {
            WidgetManager
                .getDefault()
                .getWidgetInfo(widgetId)
                ?.customState
                ?.toIntOrNull()
        }.getOrNull() ?: 0

    private fun render(
        widgetId: String,
        size: WidgetSize,
        count: Int,
        note: String,
    ) {
        WidgetManager.getDefault().updateWidget(
            WidgetUpdateRequestOptions(widgetId).apply {
                template = CardTemplate
                data = cardData(count, size, note)
                customState = count.toString()
            },
        )
    }

    private inner class LabWidgetProvider :
        WidgetProvider,
        WidgetProvider2,
        WidgetProviderAnalytics,
        WidgetProviderErrors {
        override fun createWidget(widgetContext: WidgetContext) {
            record("createWidget ${widgetContext.describe()}")
            render(widgetContext.id, widgetContext.size, 0, "created")
        }

        override fun deleteWidget(
            widgetId: String,
            customState: String,
        ) = record("deleteWidget $widgetId (count $customState)")

        override fun onActionInvoked(actionInvokedArgs: WidgetActionInvokedArgs) {
            val context = actionInvokedArgs.widgetContext
            record("onActionInvoked '${actionInvokedArgs.verb}' ${context.describe()} data=${actionInvokedArgs.data}")
            val count = actionInvokedArgs.customState.toIntOrNull() ?: 0
            when (actionInvokedArgs.verb) {
                VERB_INCREMENT -> render(context.id, context.size, count + 1, "+1 from the widget")
                VERB_RESET -> render(context.id, context.size, 0, "reset from the widget")
            }
        }

        override fun onWidgetContextChanged(contextChangedArgs: WidgetContextChangedArgs) {
            val context = contextChangedArgs.widgetContext
            record("onWidgetContextChanged ${context.describe()}")
            render(context.id, context.size, storedCount(context.id), "resized")
        }

        override fun activate(widgetContext: WidgetContext) {
            record("activate ${widgetContext.describe()}")
            render(widgetContext.id, widgetContext.size, storedCount(widgetContext.id), "activated")
        }

        override fun deactivate(widgetId: String) = record("deactivate $widgetId")

        override fun onCustomizationRequested(customizationRequestedArgs: WidgetCustomizationRequestedArgs) =
            record("onCustomizationRequested ${customizationRequestedArgs.widgetContext.describe()}")

        override fun onAnalyticsInfoReported(args: WidgetAnalyticsInfoReportedArgs) =
            record("onAnalyticsInfoReported ${args.analyticsJson}")

        override fun onErrorInfoReported(args: WidgetErrorInfoReportedArgs) =
            record("onErrorInfoReported ${args.errorJson}")
    }

    companion object {
        /** Must match `CreateInstance ClassId` and `com:Class Id` in packaging/appx/widget-extensions.xml. */
        val CLSID: UUID = UUID.fromString("439DB1E7-E547-4D7B-A0B4-DB4A0045B335")

        private val logger: Logger = Logger.getLogger(NucleusWindowsWidgetsGateway::class.java.name)

        private const val VERB_INCREMENT = "increment"
        private const val VERB_RESET = "reset"

        private fun WidgetContext.describe() =
            "$id ($definitionId, ${size.name}, ${if (isActive) "active" else "inactive"})"

        private fun cardData(
            count: Int,
            size: WidgetSize,
            note: String,
        ): String = """{"count":"$count","size":"${size.name}","note":"$note"}"""

        // Adaptive Card 1.5 template; ${…} are bound to cardData by the widget host.
        private val CardTemplate =
            """
            {
              "type": "AdaptiveCard",
              "${'$'}schema": "http://adaptivecards.io/schemas/adaptive-card.json",
              "version": "1.5",
              "body": [
                { "type": "TextBlock", "text": "Nucleus Lab", "size": "Small", "isSubtle": true },
                { "type": "TextBlock", "text": "${'$'}{count}", "size": "ExtraLarge", "weight": "Bolder", "horizontalAlignment": "Center" },
                { "type": "TextBlock", "text": "${'$'}{size} · ${'$'}{note}", "size": "Small", "isSubtle": true, "wrap": true, "horizontalAlignment": "Center" }
              ],
              "actions": [
                { "type": "Action.Execute", "title": "+1", "verb": "$VERB_INCREMENT" },
                { "type": "Action.Execute", "title": "Reset", "verb": "$VERB_RESET" },
                { "type": "Action.OpenUrl", "title": "Open probe", "url": "nucleus-lab://probe/shell.windows-widgets" }
              ]
            }
            """.trimIndent()
    }
}

/** Registers the provider before any UI: a COM launch for the widget host waits on it. */
@ContributesIntoSet(AppScope::class)
@Inject
class WindowsWidgetsStartup(
    private val gateway: WindowsWidgetsGateway,
) : LabStartup {
    override fun start(args: Array<String>) = gateway.register(args)
}
