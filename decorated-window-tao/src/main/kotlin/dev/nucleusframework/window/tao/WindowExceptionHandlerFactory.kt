package dev.nucleusframework.window.tao

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import java.util.logging.Level

/**
 * Factory of window exception handlers, the Tao mirror of Compose Desktop's
 * `androidx.compose.ui.window.WindowExceptionHandlerFactory` — same contract,
 * with [TaoWindow] where the AWT backend takes a `java.awt.Window`.
 *
 * The handlers it produces catch exceptions thrown while rendering frames
 * (composition, layout, draw), dispatching input, running IME callbacks, or
 * walking the accessibility tree of the window they were created for.
 *
 * **Not every failure is survivable.** Layout, draw, input, IME and
 * accessibility failures are caught around the offending pass, so a handler
 * that returns normally really does resume: the frame is dropped, a new one is
 * requested, and later state changes still reach the screen. A failure *inside
 * composition* is different — Compose intercepts it in
 * `Recomposer.processCompositionError` and stops the recomposition loop before
 * the backend ever sees the exception, and that loop cannot be restarted (the
 * recovery path Compose uses for hot reload is internal to
 * `androidx.compose.runtime`). Returning normally cannot revive such a window,
 * so the backend logs the fact at [Level.SEVERE] instead of leaving a window
 * that silently paints its last frame forever. A handler that intends to keep
 * the app running should close and recreate the window in that case.
 */
@ExperimentalComposeUiApi
public fun interface WindowExceptionHandlerFactory {
    /** Creates an exception handler for [window]. Handlers are invoked on the thread the failure occurred on. */
    public fun exceptionHandler(window: TaoWindow): WindowExceptionHandler
}

/**
 * Default [WindowExceptionHandlerFactory]: rethrows, which hands the failure to
 * the app-fatal path — SEVERE log, native error dialog, clean exit (#622). An
 * app that installs nothing therefore gets a diagnosable crash instead of the
 * silent, frozen window this used to leave behind.
 *
 * Deliberately does not log: `TaoApplication.reportFatal` already logs the same
 * throwable at [Level.SEVERE], and logging here too would double every entry.
 *
 * Provide your own factory through [LocalWindowExceptionHandlerFactory] to keep
 * the window alive instead — a handler that returns normally swallows the
 * exception, the offending frame is dropped, and a new one is requested. See
 * [WindowExceptionHandlerFactory] for the one failure class that cannot resume.
 */
@ExperimentalComposeUiApi
public object DefaultWindowExceptionHandlerFactory : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: TaoWindow): WindowExceptionHandler =
        WindowExceptionHandler { throwable -> throw throwable }
}

/**
 * The CompositionLocal that provides the [WindowExceptionHandlerFactory] used
 * by every `DecoratedWindow` / `DecoratedDialog` composed below it — including
 * the separate native windows `nativePopupLayers = true` popups live in.
 */
@ExperimentalComposeUiApi
public val LocalWindowExceptionHandlerFactory: ProvidableCompositionLocal<WindowExceptionHandlerFactory> =
    staticCompositionLocalOf { DefaultWindowExceptionHandlerFactory }
