package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.nucleusframework.core.runtime.DeepLinkHandler
import dev.nucleusframework.core.runtime.SingleInstanceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.system.exitProcess

/**
 * Internal bus used by [nucleusApplication] to relay a "second instance tried
 * to launch" signal to the live `DecoratedWindow`, which reacts by bringing
 * itself back to the foreground.
 *
 * Held as a process-wide singleton because the [SingleInstanceManager] watcher
 * thread runs outside of any composition.
 */
internal object SingleInstanceRestoreBus {
    private val _signal = MutableStateFlow(0)
    val signal: StateFlow<Int> = _signal.asStateFlow()

    fun fire() {
        _signal.value = _signal.value + 1
    }
}

/**
 * Runs [onRestore] whenever a second instance of the application attempts to
 * launch while this process holds the single-instance lock (requires
 * `nucleusApplication(enableSingleInstance = true)`).
 *
 * Windows created through [DecoratedWindow] already restore themselves
 * (shown, un-minimized, focused); use this hook when the app's main surface
 * is something else — typically a tray application re-opening its popup.
 */
@Composable
public fun SingleInstanceRestoreEffect(onRestore: () -> Unit) {
    val signal by SingleInstanceRestoreBus.signal.collectAsState()
    val currentOnRestore = rememberUpdatedState(onRestore)
    LaunchedEffect(signal) {
        if (signal != 0) currentOnRestore.value()
    }
}

/**
 * Acquires the single-instance lock for this process.
 *
 * - First instance: registers a watcher that fires [SingleInstanceRestoreBus]
 *   whenever a second instance tries to launch, and routes any deep link the
 *   secondary wrote into [DeepLinkHandler].
 * - Second instance: writes its CLI deep link (if any) for the primary to pick
 *   up, then terminates the process with exit code 0 — Compose never starts.
 */
internal fun acquireSingleInstanceLock(args: Array<String>) {
    // Capture the deep link from this launch's CLI args up front: a secondary
    // instance exits inside this function (before the composition's
    // onDeepLink { } would parse them), so writeUriTo must already see the URI.
    DeepLinkHandler.captureFromArgs(args)
    val isFirst =
        SingleInstanceManager.isSingleInstance(
            onRestoreFileCreated = { DeepLinkHandler.writeUriTo(this) },
            onRestoreRequest = {
                DeepLinkHandler.readUriFrom(this)
                SingleInstanceRestoreBus.fire()
            },
        )
    if (!isFirst) {
        exitProcess(0)
    }
}

/**
 * Wires automatic window restoration when a second instance attempts to
 * launch: the [NucleusWindow] is shown, un-minimized, brought to the front and
 * given focus. Invoked once per [DecoratedWindow] composition.
 */
@Composable
internal fun ObserveSingleInstanceRestore(window: NucleusWindow) {
    val signal by SingleInstanceRestoreBus.signal.collectAsState()
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        window.show()
        window.setMinimized(false)
        window.toFront()
        window.requestFocus()
    }
}
