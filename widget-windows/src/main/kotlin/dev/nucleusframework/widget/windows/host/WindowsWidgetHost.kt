package dev.nucleusframework.widget.windows.host

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.widget.MutableWidgetState
import dev.nucleusframework.widget.PinnedWidget
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetEvent
import dev.nucleusframework.widget.WidgetHost
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetInstance
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.windows.WebWidgetRequest
import dev.nucleusframework.widget.windows.WidgetActionInvokedArgs
import dev.nucleusframework.widget.windows.WidgetContext
import dev.nucleusframework.widget.windows.WidgetContextChangedArgs
import dev.nucleusframework.widget.windows.WidgetCustomizationRequestedArgs
import dev.nucleusframework.widget.windows.WidgetErrorInfoReportedArgs
import dev.nucleusframework.widget.windows.WidgetManager
import dev.nucleusframework.widget.windows.WidgetMessageReceivedArgs
import dev.nucleusframework.widget.windows.WidgetProvider
import dev.nucleusframework.widget.windows.WidgetProvider2
import dev.nucleusframework.widget.windows.WidgetProviderErrors
import dev.nucleusframework.widget.windows.WidgetProviderMessage
import dev.nucleusframework.widget.windows.WidgetProviderServer
import dev.nucleusframework.widget.windows.WidgetResourceProvider
import dev.nucleusframework.widget.windows.WidgetResourceRequestedArgs
import dev.nucleusframework.widget.windows.WidgetResourceResponse
import dev.nucleusframework.widget.windows.WidgetSize
import dev.nucleusframework.widget.windows.WidgetUpdateRequestOptions
import dev.nucleusframework.widget.windows.WindowsWebWidget
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import dev.nucleusframework.widget.WidgetSize as RuntimeWidgetSize

/**
 * The Windows 11 widgets board as a [WidgetHost]: registers the COM provider under the CLSID the
 * Gradle plugin wrote into the AppX manifest, turns each host callback into a render, and keeps
 * each widget's [WidgetState] in its custom state. Found by `NucleusWidgets` through
 * `ServiceLoader`; apps never use it directly.
 */
public class WindowsWidgetHost : WidgetHost {
    // WidgetManager is only activatable with package identity and the Windows App SDK framework.
    override val isSupported: Boolean by lazy {
        Platform.Current == Platform.Windows && runCatching { WidgetManager.isAvailable }.getOrDefault(false)
    }

    override val isLaunchedByHost: Boolean get() = WidgetProviderServer.isComServerLaunch

    /** Live state per widget id: renders read it, click handlers change it, every push persists it. */
    private val states = ConcurrentHashMap<String, MutableWidgetState>()

    // Ids the host deleted: WidgetService still lists a widget for a moment after telling the
    // provider, so a pinnedWidgets() run on the Unpinned event would bring it back. Ids are GUIDs,
    // never reused.
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    private var registration: AutoCloseable? = null

    override fun start() {
        val clsid =
            NucleusApp.windowsWidgetsClsid
                ?: error("No widget provider CLSID: declare the widgets in the Gradle plugin's windows { widgets { } }")
        registration = WidgetProviderServer.register(UUID.fromString(clsid), Provider())
        logger.fine { "Widget provider registered under $clsid" }
    }

    override suspend fun pinnedWidgets(): List<PinnedWidget> =
        manager()
            .getWidgetInfos()
            .filter { WidgetEngine.isDeclared(it.widgetContext.definitionId) && it.widgetContext.id !in deleted }
            .map {
                PinnedWidget(
                    WidgetId(it.widgetContext.id),
                    it.widgetContext.definitionId,
                    it.widgetContext.size.toRuntime(),
                    it.widgetContext.isActive,
                )
            }

    override suspend fun refresh(id: WidgetId) {
        val info = manager().getWidgetInfo(id.value) ?: return
        push(info.widgetContext, stateOf(id.value, info.customState))
    }

    override suspend fun loadState(id: WidgetId): MutableWidgetState =
        stateOf(id.value, manager().getWidgetInfo(id.value)?.customState)

    override suspend fun saveState(
        id: WidgetId,
        state: WidgetState,
    ) {
        manager().updateWidget(WidgetUpdateRequestOptions(id.value).apply { customState = state.encode() })
    }

    private fun stateOf(
        id: String,
        persisted: String?,
    ): MutableWidgetState = states.computeIfAbsent(id) { WidgetState.decode(persisted) }

    /** Composes the widget from [state] and hands the card and the state to the host. */
    private suspend fun push(
        context: WidgetContext,
        state: MutableWidgetState,
    ) {
        val instance = WidgetInstance(WidgetId(context.id), context.definitionId, context.size.toRuntime(), state)
        val render = WidgetEngine.render(instance)
        manager().updateWidget(
            WidgetUpdateRequestOptions(context.id).apply {
                template =
                    AdaptiveCardRenderer.render(
                        render.root,
                        AppResourceImages::resolve,
                        webWidget(context.definitionId)?.webUrl(instance.id, state),
                    )
                data = "{}"
                customState = state.encode()
            },
        )
    }

    /** Runs [block] on the engine's single thread, so renders never interleave; reports failures. */
    private fun dispatch(
        id: String?,
        what: String,
        block: suspend () -> Unit,
    ) {
        WidgetEngine.scope.launch {
            try {
                block()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.log(Level.WARNING, "Widget $what failed", e)
                WidgetEngine.emit(WidgetEvent.Failed(id?.let(::WidgetId), "$what failed: ${e.message}", e))
            }
        }
    }

    private inner class Provider :
        WidgetProvider,
        WidgetProvider2,
        WidgetProviderErrors,
        WidgetProviderMessage,
        WidgetResourceProvider {
        override fun createWidget(widgetContext: WidgetContext) {
            WidgetEngine.emit(
                WidgetEvent.Pinned(
                    WidgetId(widgetContext.id),
                    widgetContext.definitionId,
                    widgetContext.size.toRuntime(),
                ),
            )
            states[widgetContext.id] = WidgetState.decode(null)
            dispatch(widgetContext.id, "createWidget") { push(widgetContext, stateOf(widgetContext.id, null)) }
        }

        override fun deleteWidget(
            widgetId: String,
            customState: String,
        ) {
            deleted += widgetId
            states.remove(widgetId)
            WidgetEngine.emit(WidgetEvent.Unpinned(WidgetId(widgetId)))
        }

        override fun onActionInvoked(actionInvokedArgs: WidgetActionInvokedArgs) {
            val context = actionInvokedArgs.widgetContext
            dispatch(context.id, "action '${actionInvokedArgs.verb}'") {
                // The custom state the host sends with the action is the persisted truth.
                val state = WidgetState.decode(actionInvokedArgs.customState)
                states[context.id] = state
                val instance =
                    WidgetInstance(WidgetId(context.id), context.definitionId, context.size.toRuntime(), state)
                // Input values come back in the action data, keyed by input id.
                val inputs = WidgetEngine.parseInputs(actionInvokedArgs.data)
                if (!WidgetEngine.invokeAction(instance, actionInvokedArgs.verb, inputs)) {
                    logger.fine { "Stale action '${actionInvokedArgs.verb}' on ${context.id}: re-rendering" }
                }
                push(context, state)
            }
        }

        override fun onWidgetContextChanged(contextChangedArgs: WidgetContextChangedArgs) {
            val context = contextChangedArgs.widgetContext
            WidgetEngine.emit(WidgetEvent.Resized(WidgetId(context.id), context.size.toRuntime()))
            dispatch(context.id, "resize") { push(context, loadState(WidgetId(context.id))) }
        }

        override fun activate(widgetContext: WidgetContext) {
            WidgetEngine.emit(WidgetEvent.Activated(WidgetId(widgetContext.id)))
            dispatch(widgetContext.id, "activate") { push(widgetContext, loadState(WidgetId(widgetContext.id))) }
        }

        override fun deactivate(widgetId: String) {
            WidgetEngine.emit(WidgetEvent.Deactivated(WidgetId(widgetId)))
        }

        override fun onCustomizationRequested(customizationRequestedArgs: WidgetCustomizationRequestedArgs) {
            val context = customizationRequestedArgs.widgetContext
            dispatch(context.id, "customization") {
                val state = loadState(WidgetId(context.id))
                WidgetEngine.requestCustomization(
                    WidgetInstance(WidgetId(context.id), context.definitionId, context.size.toRuntime(), state),
                )
                push(context, state)
            }
        }

        override fun onMessageReceived(args: WidgetMessageReceivedArgs) {
            val context = args.widgetContext
            dispatch(context.id, "message") {
                webWidget(context.definitionId)?.onWebMessage(WidgetId(context.id), args.message)
            }
        }

        override fun onResourceRequested(args: WidgetResourceRequestedArgs) {
            val context = args.widgetContext
            // The host waits for the response until the deferral completes, so the widget may suspend.
            val deferral = args.getDeferral()
            dispatch(context.id, "web request") {
                try {
                    val request = args.request
                    val answer =
                        webWidget(context.definitionId)?.onWebRequest(
                            WidgetId(context.id),
                            WebWidgetRequest(request.uri, request.method, request.headers.toMap(), request.content),
                        )
                    if (answer != null) {
                        args.response =
                            WidgetResourceResponse(answer.body, answer.reasonPhrase, answer.statusCode).apply {
                                answer.headers.forEach { (name, value) -> headers.insert(name, value) }
                            }
                    }
                } finally {
                    deferral.complete()
                }
            }
        }

        override fun onErrorInfoReported(args: WidgetErrorInfoReportedArgs) {
            logger.warning { "Widget host error for ${args.widgetContext.id}: ${args.errorJson}" }
            WidgetEngine.emit(WidgetEvent.HostError(WidgetId(args.widgetContext.id), args.errorJson))
        }
    }

    private fun manager() = WidgetManager.getDefault()

    /** The web side of the widget [definitionId], when it implements [WindowsWebWidget]. */
    private fun webWidget(definitionId: String): WindowsWebWidget? =
        WidgetEngine.newWidget(definitionId) as? WindowsWebWidget

    private companion object {
        val logger: Logger = Logger.getLogger(WindowsWidgetHost::class.java.name)

        fun WidgetSize.toRuntime(): RuntimeWidgetSize =
            when (this) {
                WidgetSize.Small -> RuntimeWidgetSize.Small
                WidgetSize.Medium -> RuntimeWidgetSize.Medium
                WidgetSize.Large -> RuntimeWidgetSize.Large
            }
    }
}
