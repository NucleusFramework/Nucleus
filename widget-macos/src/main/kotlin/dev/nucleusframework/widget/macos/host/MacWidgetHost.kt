package dev.nucleusframework.widget.macos.host

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.widget.MutableWidgetState
import dev.nucleusframework.widget.PinnedWidget
import dev.nucleusframework.widget.WidgetActionSignatures
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetEvent
import dev.nucleusframework.widget.WidgetHost
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetInstance
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.macos.DarwinNotificationCenter
import dev.nucleusframework.widget.macos.NativeWidgetKitBridge
import dev.nucleusframework.widget.macos.WidgetCenter
import dev.nucleusframework.widget.macos.WidgetFamily
import dev.nucleusframework.widget.macos.WidgetInfo
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * WidgetKit as a [WidgetHost]. The widgets are drawn by the Nucleus widget extension the Gradle
 * plugin embeds in the app bundle (`macOS { widgets { } }`); this host composes them into the store
 * the two processes share ([WidgetStore]), answers the extension's requests (a first render, a
 * click) and reloads the timelines. Found by `NucleusWidgets` through `ServiceLoader`; apps never
 * use it directly.
 *
 * WidgetKit has no instance identity for a widget without a configuration intent: a [WidgetId] is
 * `<kind>/<family>`, and two widgets of the same kind and size show the same content and state.
 */
public class MacWidgetHost internal constructor(
    private val loadEnvironment: () -> Environment?,
) : WidgetHost {
    /** For `ServiceLoader`. */
    public constructor() : this({ NativeEnvironment.load() })

    private val environment: Environment? by lazy { loadEnvironment() }

    override val isSupported: Boolean get() = environment != null

    @Volatile
    private var launchedByHost = false

    override val isLaunchedByHost: Boolean get() = launchedByHost

    private lateinit var store: WidgetStore
    private lateinit var images: StoreImages
    private val states = ConcurrentHashMap<String, MutableWidgetState>()

    /** Ids known to be on screen: a new one is reported as pinned, a vanished one as unpinned. */
    private val known = ConcurrentHashMap.newKeySet<String>()
    private var observer: AutoCloseable? = null

    override fun start() {
        val env = environment ?: error("No Nucleus widget extension in this app bundle")
        store = WidgetStore(env.store).apply { create() }
        images = StoreImages(store, env.resourcesDir)
        launchedByHost = store.consumeLaunchMarker(env.nowMillis(), LAUNCH_MARKER_MAX_AGE)
        observer = env.observe(env.requestNotification) { dispatch(null, "requests") { drainRequests() } }
        dispatch(null, "startup") {
            // Everything WidgetKit shows now was composed by an earlier run: compose it again.
            pinned().forEach { known += it.id.value }
            drainRequests()
            writePreviews()
            for (widget in pinned()) push(widget.definitionId, widget.family, loadState(widget.id))
        }
        logger.fine { "Widget host started on ${env.store}" }
    }

    override suspend fun pinnedWidgets(): List<PinnedWidget> {
        val pinned = pinned()
        val current = pinned.map { it.id.value }.toSet()
        for (id in known.toList()) {
            if (id !in current) unpinned(id)
        }
        pinned.forEach { seen(it.id.value) }
        return pinned.map { PinnedWidget(it.id, it.definitionId, it.size, isActive = true) }
    }

    override suspend fun refresh(id: WidgetId) {
        val (kind, family) = split(id) ?: return
        if (!WidgetEngine.isDeclared(kind)) return
        push(kind, family, loadState(id))
    }

    override suspend fun loadState(id: WidgetId): MutableWidgetState =
        states.computeIfAbsent(id.value) {
            val file = split(id)?.let { (kind, family) -> store.state(kind, family) }
            WidgetState.decode(file?.takeIf { it.isFile }?.readText())
        }

    override suspend fun saveState(
        id: WidgetId,
        state: WidgetState,
    ) {
        val (kind, family) = split(id) ?: return
        WidgetStore.writeAtomically(store.state(kind, family), state.encode())
    }

    /** Composes [kind] at [family] from [state], writes it for the extension and reloads its timelines. */
    private suspend fun push(
        kind: String,
        family: String,
        state: MutableWidgetState,
    ) {
        val id = WidgetId("$kind/$family")
        val instance = WidgetInstance(id, kind, sizeOf(family), state)
        val render = WidgetEngine.render(instance)
        WidgetStore.writeAtomically(store.render(kind, family), WidgetTreeEncoder.encode(render.root, images::resolve))
        saveState(id, state)
        environment?.reload(kind)
    }

    /** The gallery shows each declared widget before it is added: from an empty state. */
    private suspend fun writePreviews() {
        val env = environment ?: return
        for ((kind, families) in env.widgets) {
            if (!WidgetEngine.isDeclared(kind)) continue
            for (family in families) {
                val instance = WidgetInstance(WidgetId("$kind/$family"), kind, sizeOf(family), WidgetState.decode(null))
                val tree = WidgetTreeEncoder.encode(WidgetEngine.render(instance).root, images::resolve)
                WidgetStore.writeAtomically(store.preview(kind, family), tree)
            }
        }
    }

    private suspend fun drainRequests() {
        val env = environment ?: return
        while (true) {
            val files = store.pendingRequests()
            if (files.isEmpty()) break
            for (file in files) handle(file, env.nowMillis())
        }
        store.sweep(env.nowMillis(), STALE_REQUEST_AGE)
    }

    private suspend fun handle(
        file: File,
        nowMillis: Long,
    ) {
        val written = file.lastModified()
        val request =
            try {
                WidgetEngine.parseInputs(file.readText())
            } catch (e: IOException) {
                logger.log(Level.FINE, "Widget request ${file.name} vanished", e)
                return
            } finally {
                file.delete()
            }
        val requestId = request["id"] ?: file.nameWithoutExtension
        try {
            // A click from long ago (the app could not start then) must not run now.
            if (nowMillis - written > STALE_REQUEST_AGE && request["type"] == "action") return
            val id = request["widget"]?.let(::WidgetId) ?: return
            val (kind, family) = split(id) ?: return
            if (!WidgetEngine.isDeclared(kind)) return
            seen(id.value)
            when (request["type"]) {
                "render" -> push(kind, family, loadState(id))
                "timeline" -> if (!store.render(kind, family).isFile) push(kind, family, loadState(id))
                "action" -> {
                    val state = loadState(id)
                    act(WidgetInstance(id, kind, sizeOf(family), state), request)
                    push(kind, family, state)
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.log(Level.WARNING, "Widget request ${request["type"]} failed", e)
            WidgetEngine.emit(
                WidgetEvent.Failed(request["widget"]?.let(::WidgetId), "Widget request failed: ${e.message}", e),
            )
        } finally {
            // Always answered: the extension waits for it before WidgetKit reloads the timeline.
            store.acknowledge(requestId)
        }
    }

    /**
     * Runs a click: the inputs it carries (a toggle, a choice) are written, then its action, unless
     * the render it came from is outdated and its key now names another element.
     */
    private suspend fun act(
        instance: WidgetInstance,
        request: Map<String, String>,
    ) {
        WidgetEngine.applyInputs(instance, WidgetEngine.parseInputs(request["inputs"].orEmpty()))
        val key = request["key"].orEmpty()
        if (key.isEmpty()) return
        val clicked = request["signature"].orEmpty()
        if (clicked.isNotEmpty() && WidgetActionSignatures.of(WidgetEngine.render(instance).root)[key] != clicked) {
            WidgetEngine.emit(WidgetEvent.ActionDropped(instance.id, key))
            return
        }
        if (!WidgetEngine.invokeAction(instance, key)) logger.fine { "Stale action '$key' on ${instance.id}" }
    }

    private fun seen(id: String) {
        if (!known.add(id)) return
        val (kind, family) = split(WidgetId(id)) ?: return
        WidgetEngine.emit(WidgetEvent.Pinned(WidgetId(id), kind, sizeOf(family)))
    }

    // Like Windows, a removed widget takes its state with it: adding it again starts afresh.
    private fun unpinned(id: String) {
        known -= id
        states.remove(id)
        split(WidgetId(id))?.let { (kind, family) ->
            store.render(kind, family).delete()
            store.state(kind, family).delete()
        }
        WidgetEngine.emit(WidgetEvent.Unpinned(WidgetId(id)))
    }

    private suspend fun pinned(): List<Pinned> =
        environment
            ?.pinned()
            .orEmpty()
            .filter { WidgetEngine.isDeclared(it.kind) && it.family != WidgetFamily.Unknown }
            .map {
                Pinned(
                    WidgetId("${it.kind}/${it.family.rawValue}"),
                    it.kind,
                    it.family.rawValue,
                    sizeOf(it.family.rawValue),
                )
            }.distinctBy { it.id }

    private class Pinned(
        val id: WidgetId,
        val definitionId: String,
        val family: String,
        val size: WidgetSize,
    )

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

    /** What the host needs from macOS, replaced in tests. */
    internal interface Environment {
        val store: File
        val requestNotification: String

        /** Declared widget kinds and the families each supports, as the extension's Info.plist lists them. */
        val widgets: Map<String, List<String>>
        val resourcesDir: File?

        fun nowMillis(): Long = System.currentTimeMillis()

        suspend fun pinned(): List<WidgetInfo>

        fun reload(kind: String)

        fun observe(
            name: String,
            onNotification: () -> Unit,
        ): AutoCloseable
    }

    private class NativeEnvironment(
        override val store: File,
        appBundleIdentifier: String,
        override val widgets: Map<String, List<String>>,
    ) : Environment {
        override val requestNotification = "$appBundleIdentifier.nucleus-widgets.request"

        // Set by the Nucleus plugin for jpackage and GraalVM images alike.
        override val resourcesDir: File? =
            System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory }

        override suspend fun pinned(): List<WidgetInfo> = WidgetCenter.currentConfigurations()

        override fun reload(kind: String) = WidgetCenter.reloadTimelines(kind)

        override fun observe(
            name: String,
            onNotification: () -> Unit,
        ): AutoCloseable = DarwinNotificationCenter.addObserver(name, onNotification)

        companion object {
            /** The app's embedded widget extension, on macOS 14 or later (interactive widgets). */
            fun load(): NativeEnvironment? {
                if (Platform.Current != Platform.MacOS ||
                    macOsMajor() < MIN_MACOS ||
                    !NativeWidgetKitBridge.isLoaded
                ) {
                    return null
                }
                val config = NativeWidgetKitBridge.nativeExtensionConfig() ?: return null
                if (config.size < CONFIG_HEADER) return null
                val widgets =
                    config.drop(CONFIG_HEADER).associate { line ->
                        val (kind, families) = line.split('\t', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                        kind to families.split(',').filter { it.isNotBlank() }
                    }
                return NativeEnvironment(File(config[2]), config[1], widgets)
            }

            private fun macOsMajor(): Int =
                System.getProperty("os.version", "0").substringBefore('.').toIntOrNull() ?: 0
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger(MacWidgetHost::class.java.name)
        const val MIN_MACOS = 14
        const val CONFIG_HEADER = 3
        const val LAUNCH_MARKER_MAX_AGE = 60_000L
        const val STALE_REQUEST_AGE = 120_000L

        fun split(id: WidgetId): Pair<String, String>? =
            id.value
                .split('/', limit = 2)
                .takeIf { it.size == 2 && it[0].isNotEmpty() }
                ?.let { it[0] to it[1] }

        fun sizeOf(family: String): WidgetSize =
            when (family) {
                WidgetFamily.SystemSmall.rawValue -> WidgetSize.Small
                WidgetFamily.SystemMedium.rawValue -> WidgetSize.Medium
                else -> WidgetSize.Large
            }
    }
}
