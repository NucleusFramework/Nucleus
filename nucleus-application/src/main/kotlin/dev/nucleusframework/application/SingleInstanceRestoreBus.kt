package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import dev.nucleusframework.core.runtime.DeepLinkHandler
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.core.runtime.SingleInstanceManager
import dev.nucleusframework.fswatcher.FsWatchDeliveryMode
import dev.nucleusframework.fswatcher.FsWatchEvent
import dev.nucleusframework.fswatcher.FsWatcherConfig
import dev.nucleusframework.fswatcher.FsWatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
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
    val currentOnRestore = rememberUpdatedState(onRestore)
    // Collected, not read in composition: an app out of sight renders no frame, so it never recomposes.
    LaunchedEffect(Unit) {
        SingleInstanceRestoreBus.signal.collect { if (it != 0) currentOnRestore.value() }
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
    if (Platform.Current == Platform.MacOS) SingleInstanceManager.directoryWatcher = ::watchWithFsEvents
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
    // Collected, not read in composition: a hidden, covered or minimized window renders no frame,
    // so it never recomposes — the very windows a second launch must bring back.
    LaunchedEffect(window) {
        SingleInstanceRestoreBus.signal.collect {
            if (it == 0) return@collect
            window.show()
            window.setMinimized(false)
            window.toFront()
            window.requestFocus()
        }
    }
}

/**
 * FSEvents-backed watcher for the single-instance lock dir: the JDK `WatchService` polls on macOS,
 * waking the app every few seconds for its whole life. Raw delivery: the debounced mode runs a
 * ticking thread. Lives as long as the process (the lock does too).
 */
private fun watchWithFsEvents(
    dir: Path,
    onCreated: (String) -> Unit,
) {
    val config = FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw)
    check(FsWatchers.isSupported(config)) { "fs-watcher is not available" }
    val watcher = FsWatchers.create(config)
    watcher.watch(dir, recursive = false)
    CoroutineScope(Dispatchers.IO).launch {
        watcher.events.collect { event ->
            val path = (event as? FsWatchEvent.Created)?.path ?: (event as? FsWatchEvent.Modified)?.path
            path?.fileName?.let { onCreated(it.toString()) }
        }
    }
}
