package dev.nucleusframework.widget.ios

import dev.nucleusframework.widget.ImageProvider
import dev.nucleusframework.widget.MutableWidgetState
import dev.nucleusframework.widget.NucleusWidgets
import dev.nucleusframework.widget.PinnedWidget
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetHost
import dev.nucleusframework.widget.WidgetHostRegistry
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.WidgetsBuilder
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.writeToFile
import kotlin.concurrent.Volatile

/**
 * Declares the app's widgets and connects them to WidgetKit, in the app and in its widget
 * extension alike (both link the same Kotlin framework, and both must call it before anything
 * else touches the widgets):
 *
 * ```
 * fun startWidgets() = startIosWidgets {
 *     widget("Counter") { CounterWidget() }
 * }
 * ```
 *
 * The two processes share the widget state through the App Group [appGroup], by default the
 * `NucleusWidgetsAppGroup` key of the main bundle's Info.plist (set it in both). Only the first
 * call counts.
 */
public fun startIosWidgets(
    appGroup: String? = null,
    declarations: WidgetsBuilder.() -> Unit,
) {
    if (IosWidgetHost.current != null) return
    val group = appGroup ?: NSBundle.mainBundle.objectForInfoDictionaryKey(APP_GROUP_KEY) as? String
    start(IosWidgetHost(group), declarations)
}

internal fun start(
    host: IosWidgetHost,
    declarations: WidgetsBuilder.() -> Unit,
) {
    IosWidgetHost.current = host
    WidgetHostRegistry.install(host)
    NucleusWidgets.start(declarations)
}

private const val APP_GROUP_KEY = "NucleusWidgetsAppGroup"

/**
 * WidgetKit as a [WidgetHost] on iOS. The widget extension renders the widgets itself (it runs the
 * app's Kotlin, through [WidgetExtensionEntryPoints]), so this host only keeps the state, in the App
 * Group container both processes share, and asks WidgetKit to reload a widget whose state the app
 * changed. A [WidgetId] is `<kind>/<family>`, as on macOS: WidgetKit gives no instance identity
 * without a configuration intent.
 */
internal class IosWidgetHost(
    private val appGroup: String?,
    /** A directory standing for the App Group container (tests). */
    private val storeOverride: String? = null,
) : WidgetHost {
    /** The App Group container, or `null` without the group (or its entitlement). */
    val store: String? by lazy {
        storeOverride ?: appGroup?.let {
            NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(it)?.path
        }
    }

    override val isSupported: Boolean get() = store != null

    override val isLaunchedByHost: Boolean = false

    override fun start() {
        NSFileManager.defaultManager.createDirectoryAtPath(
            "$store/NucleusWidgets/state",
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    override suspend fun pinnedWidgets(): List<PinnedWidget> =
        WidgetCenter
            .currentConfigurations()
            .filter { WidgetEngine.isDeclared(it.kind) && it.family != WidgetFamily.Unknown }
            .map {
                val id = WidgetId("${it.kind}/${it.family.rawValue}")
                PinnedWidget(id, it.kind, sizeOf(it.family.rawValue), isActive = true)
            }.distinctBy { it.id }

    // The extension composes on its next timeline: reloading is all the app does.
    override suspend fun refresh(id: WidgetId) {
        val (kind, _) = split(id) ?: return
        WidgetCenter.reloadTimelines(kind)
    }

    // Never cached: the app and the extension both write the file.
    override suspend fun loadState(id: WidgetId): MutableWidgetState =
        WidgetState.decode(stateFile(id)?.let(::readText))

    override suspend fun saveState(
        id: WidgetId,
        state: WidgetState,
    ) {
        val file = stateFile(id) ?: return
        writeText(file, state.encode())
    }

    private fun stateFile(id: WidgetId): String? {
        val root = store ?: return null
        val (kind, family) = split(id) ?: return null
        return "$root/NucleusWidgets/state/$kind/$family.json"
    }

    /**
     * Where the extension reads an image from: an absolute path. App resources are in the app's
     * bundle (readable from its extension), other images are written into the App Group container.
     */
    fun imagePath(provider: ImageProvider): String? {
        provider.appResource?.let { path ->
            return "${appBundlePath()}/$path".takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
        }
        val root = store ?: return null
        provider.bytes?.let { bytes ->
            val file = "$root/NucleusWidgets/images/${bytes.contentHashCode().toUInt()}-${bytes.size}"
            if (!NSFileManager.defaultManager.fileExistsAtPath(file)) writeBytes(file, bytes)
            return file
        }
        provider.url?.let { url ->
            val file = "$root/NucleusWidgets/images/url-${url.hashCode().toUInt()}"
            if (!NSFileManager.defaultManager.fileExistsAtPath(file)) {
                val data = NSURL.URLWithString(url)?.let { NSData.dataWithContentsOfURL(it) } ?: return null
                createParent(file)
                data.writeToFile(file, atomically = true)
            }
            return file
        }
        return null
    }

    companion object {
        /** The host [startIosWidgets] installed, which the extension's entry points use. */
        @Volatile
        var current: IosWidgetHost? = null

        private val KIND = Regex("[A-Za-z][A-Za-z0-9_]*")
        private val FAMILIES =
            WidgetFamily.entries
                .filter { it != WidgetFamily.Unknown }
                .map { it.rawValue }
                .toSet()

        /** `<kind>/<family>`, both validated: they become paths of the store. */
        fun split(id: WidgetId): Pair<String, String>? =
            id.value
                .split('/', limit = 2)
                .takeIf { it.size == 2 && KIND.matches(it[0]) && it[1] in FAMILIES }
                ?.let { it[0] to it[1] }

        fun sizeOf(family: String): WidgetSize =
            when (family) {
                WidgetFamily.SystemSmall.rawValue, WidgetFamily.AccessoryCircular.rawValue,
                WidgetFamily.AccessoryInline.rawValue,
                -> WidgetSize.Small
                WidgetFamily.SystemMedium.rawValue, WidgetFamily.AccessoryRectangular.rawValue -> WidgetSize.Medium
                else -> WidgetSize.Large
            }

        /** The app's bundle, from the app or from its extension (`<app>.app/PlugIns/<ext>.appex`). */
        fun appBundlePath(): String {
            val main = NSBundle.mainBundle.bundlePath
            return if (main.endsWith(".appex")) parent(parent(main)) else main
        }

        @Suppress("CAST_NEVER_SUCCEEDS")
        private fun parent(path: String): String = (path as NSString).stringByDeletingLastPathComponent

        @OptIn(BetaInteropApi::class)
        fun readText(path: String): String? =
            NSData
                .create(
                    contentsOfFile = path,
                )?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }

        fun writeText(
            path: String,
            text: String,
        ) {
            writeBytes(path, text.encodeToByteArray())
        }

        private fun writeBytes(
            path: String,
            bytes: ByteArray,
        ) {
            createParent(path)
            val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
            // Atomic: the other process never reads half a file.
            data.writeToFile(path, atomically = true)
        }

        private fun createParent(path: String) {
            NSFileManager.defaultManager.createDirectoryAtPath(
                parent(path),
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
    }
}
