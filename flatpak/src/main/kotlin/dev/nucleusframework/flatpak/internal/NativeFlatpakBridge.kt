package dev.nucleusframework.flatpak.internal

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.core.runtime.Platform
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** One `Progress` signal of an update monitor, as the portal sends it. */
internal data class PortalProgress(
    val operationCount: Int,
    val operation: Int,
    val percent: Int,
    val status: Int,
    val error: String?,
    val errorMessage: String?,
)

/** Receives one update monitor's signals, on the monitor's reader thread. */
internal interface MonitorListener {
    fun onUpdateAvailable(
        running: String,
        local: String,
        remote: String,
    )

    fun onProgress(progress: PortalProgress)

    /** The portal or the bus went away; the monitor is gone. */
    fun onClosed(reason: String)
}

/**
 * The portal calls of [NativeFlatpakBridge]. Each returns `null` on success or
 * `"<D-Bus error name>\n<message>"` (see [parsePortalError]).
 */
internal interface FlatpakBridge {
    val isAvailable: Boolean

    /** `version` of the Flatpak portal, `0` when it is not reachable. */
    fun portalVersion(): Int

    /** Creates update monitor [id] (an id of the caller's); its signals go to [listener]. */
    fun startMonitor(
        id: Long,
        listener: MonitorListener,
    ): String?

    fun update(
        id: Long,
        parentWindow: String,
    ): String?

    fun closeMonitor(id: Long)

    fun spawnLatest(
        cwd: String,
        argv: List<String>,
    ): String?

    fun openUri(
        parentWindow: String,
        uri: String,
    ): String?
}

private const val LIBRARY_NAME = "nucleus_flatpak"

/** JNI binding of `nucleus_flatpak`, the Rust Flatpak / OpenURI portal bridge. */
@Suppress("TooManyFunctions") // six externals and three native callbacks
internal object NativeFlatpakBridge : FlatpakBridge {
    private val logger = Logger.getLogger(NativeFlatpakBridge::class.java.name)

    private val loaded: Boolean by lazy {
        when (Platform.Current) {
            Platform.Linux ->
                runCatching { NativeLibraryLoader.load(LIBRARY_NAME, NativeFlatpakBridge::class.java) }
                    .onFailure { logger.log(Level.WARNING, "Failed to load $LIBRARY_NAME", it) }
                    .getOrDefault(false)
            else -> false
        }
    }

    private val listeners = ConcurrentHashMap<Long, MonitorListener>()

    override val isAvailable: Boolean
        get() = loaded

    override fun portalVersion(): Int = nativePortalVersion()

    override fun startMonitor(
        id: Long,
        listener: MonitorListener,
    ): String? {
        listeners[id] = listener
        return nativeStartMonitor(id)?.also { listeners.remove(id) }
    }

    override fun update(
        id: Long,
        parentWindow: String,
    ): String? = nativeUpdate(id, parentWindow)

    override fun closeMonitor(id: Long) {
        listeners.remove(id)
        nativeCloseMonitor(id)
    }

    override fun spawnLatest(
        cwd: String,
        argv: List<String>,
    ): String? = nativeSpawnLatest(cwd, argv.toTypedArray())

    override fun openUri(
        parentWindow: String,
        uri: String,
    ): String? = nativeOpenUri(parentWindow, uri)

    @JvmStatic
    private external fun nativePortalVersion(): Int

    @JvmStatic
    private external fun nativeStartMonitor(id: Long): String?

    @JvmStatic
    private external fun nativeUpdate(
        id: Long,
        parentWindow: String,
    ): String?

    @JvmStatic
    private external fun nativeCloseMonitor(id: Long)

    @JvmStatic
    private external fun nativeSpawnLatest(
        cwd: String,
        argv: Array<String>,
    ): String?

    @JvmStatic
    private external fun nativeOpenUri(
        parentWindow: String,
        uri: String,
    ): String?

    /** Called by native code, on the monitor's reader thread. */
    @JvmStatic
    @Suppress("unused")
    fun onUpdateAvailable(
        id: Long,
        running: String,
        local: String,
        remote: String,
    ) {
        listeners[id]?.onUpdateAvailable(running, local, remote)
    }

    /** Called by native code, on the monitor's reader thread. */
    @JvmStatic
    @Suppress("unused", "LongParameterList")
    fun onProgress(
        id: Long,
        operationCount: Int,
        operation: Int,
        percent: Int,
        status: Int,
        error: String?,
        errorMessage: String?,
    ) {
        listeners[id]?.onProgress(PortalProgress(operationCount, operation, percent, status, error, errorMessage))
    }

    /** Called by native code, on the monitor's reader thread. */
    @JvmStatic
    @Suppress("unused")
    fun onMonitorClosed(
        id: Long,
        reason: String,
    ) {
        listeners.remove(id)?.onClosed(reason)
    }
}
