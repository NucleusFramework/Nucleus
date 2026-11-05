package dev.nucleusframework.widget.macos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tells WidgetKit about the app's widgets: reloads their timelines and lists the ones the user
 * added. Mirrors WidgetKit's `WidgetCenter` (`WidgetCenter.shared`), which only reaches the widget
 * extensions embedded in the running app bundle: in a process that is not a bundled app, every call
 * is a silent no-op and [currentConfigurations] returns an empty list.
 *
 * WidgetKit draws widgets in a separate, sandboxed extension process: this API does not render
 * anything. `nucleus.widget-macos` ships that extension too (assembled by the Gradle plugin's
 * `macOS { widgets { } }`), driven by the Glance-like API of `nucleus.widget-runtime`.
 */
public object WidgetCenter {
    private val defaultTimeout = 10.seconds

    /** Whether WidgetKit can be called here: macOS 12 or later with the native library loaded. */
    public val isAvailable: Boolean get() = NativeWidgetKitBridge.isLoaded

    /** Asks WidgetKit to reload the timelines of every widget of [kind]. */
    public fun reloadTimelines(ofKind: String) {
        NativeWidgetKitBridge.requireLoaded()
        NativeWidgetKitBridge.nativeReloadTimelines(ofKind)
    }

    /** Asks WidgetKit to reload the timelines of every widget of the app. */
    public fun reloadAllTimelines() {
        NativeWidgetKitBridge.requireLoaded()
        NativeWidgetKitBridge.nativeReloadAllTimelines()
    }

    /**
     * The widgets of the app the user added, one per kind and family (WidgetKit tells two
     * instances of the same kind and family apart only by their configuration intent). Mirrors
     * `getCurrentConfigurations(_:)`; suspends on [Dispatchers.IO] for at most [timeout].
     *
     * @throws WidgetKitException when WidgetKit fails or does not answer in time.
     */
    public suspend fun currentConfigurations(timeout: Duration = defaultTimeout): List<WidgetInfo> {
        NativeWidgetKitBridge.requireLoaded()
        val payload =
            withContext(Dispatchers.IO) {
                NativeWidgetKitBridge.payload(NativeWidgetKitBridge.nativeCurrentConfigurations(timeout.millis))
            }
        return payload
            .lineSequence()
            .filter { it.isNotBlank() }
            .map(WidgetInfo::parse)
            .toList()
    }

    /**
     * Asks WidgetKit to fetch the app's configuration recommendations again. Mirrors
     * `invalidateConfigurationRecommendations()`; returns `false` below macOS 13, where it does
     * not exist.
     */
    public fun invalidateConfigurationRecommendations(): Boolean {
        NativeWidgetKitBridge.requireLoaded()
        return NativeWidgetKitBridge.nativeInvalidateConfigurationRecommendations()
    }

    /**
     * Asks WidgetKit to fetch the relevance of the widgets of [kind] again. Mirrors
     * `invalidateRelevance(ofKind:)`; returns `false` below macOS 15.
     */
    public fun invalidateRelevance(ofKind: String): Boolean {
        NativeWidgetKitBridge.requireLoaded()
        return NativeWidgetKitBridge.nativeInvalidateRelevance(ofKind)
    }

    /**
     * The token a server pushes widget updates with, or `null` (below macOS 26, or when the
     * extension declares no `WidgetPushHandler`). Mirrors `currentPushInfo`.
     *
     * @throws WidgetKitException when WidgetKit does not answer in time.
     */
    public suspend fun currentPushInfo(timeout: Duration = defaultTimeout): WidgetPushInfo? {
        NativeWidgetKitBridge.requireLoaded()
        val token =
            withContext(Dispatchers.IO) {
                NativeWidgetKitBridge.payload(NativeWidgetKitBridge.nativeCurrentPushInfo(timeout.millis))
            }
        return token.takeIf { it.isNotEmpty() }?.let { WidgetPushInfo(it.hexToBytes()) }
    }

    /**
     * Keys of the `userInfo` WidgetKit attaches to the user activity a widget opens the app with.
     * Mirrors `WidgetCenter.UserInfoKey`.
     */
    public object UserInfoKey {
        /** The kind of the widget the user clicked. */
        public val kind: String by lazy { key(0) }

        /** The family of the widget the user clicked. */
        public val family: String by lazy { key(1) }

        /** The identifier of the activity the user clicked. */
        public val activityID: String by lazy { key(2) }

        private fun key(which: Int): String {
            NativeWidgetKitBridge.requireLoaded()
            return NativeWidgetKitBridge.nativeUserInfoKey(which)
        }
    }
}

/**
 * The controls (Control Center, menu bar) WidgetKit offers for the app. Mirrors WidgetKit's
 * `ControlCenter` (`ControlCenter.shared`), macOS 26 and later: see [isAvailable].
 */
public object ControlCenter {
    private val defaultTimeout = 10.seconds

    /** Whether controls exist here: macOS 26 or later with the native library loaded. */
    public val isAvailable: Boolean get() =
        NativeWidgetKitBridge.isLoaded &&
            NativeWidgetKitBridge.nativeControlsAvailable()

    /** Asks WidgetKit to reload the controls of [kind]. No-op below macOS 26. */
    public fun reloadControls(ofKind: String) {
        NativeWidgetKitBridge.requireLoaded()
        NativeWidgetKitBridge.nativeReloadControls(ofKind)
    }

    /** Asks WidgetKit to reload every control of the app. No-op below macOS 26. */
    public fun reloadAllControls() {
        NativeWidgetKitBridge.requireLoaded()
        NativeWidgetKitBridge.nativeReloadAllControls()
    }

    /**
     * The controls of the app the user added; empty below macOS 26. Mirrors `currentControls()`.
     *
     * @throws WidgetKitException when WidgetKit fails or does not answer in time.
     */
    public suspend fun currentControls(timeout: Duration = defaultTimeout): List<ControlInfo> {
        NativeWidgetKitBridge.requireLoaded()
        val payload =
            withContext(Dispatchers.IO) {
                NativeWidgetKitBridge.payload(NativeWidgetKitBridge.nativeCurrentControls(timeout.millis))
            }
        return payload
            .lineSequence()
            .filter { it.isNotBlank() }
            .map(ControlInfo::parse)
            .toList()
    }
}

/** A widget of the app the user added. Mirrors WidgetKit's `WidgetInfo`, without its intents. */
public data class WidgetInfo(
    /** The kind the widget's configuration declares. */
    val kind: String,
    /** The size the user picked. */
    val family: WidgetFamily,
) {
    internal companion object {
        fun parse(line: String): WidgetInfo {
            val (kind, family) = line.split('\t', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return WidgetInfo(kind, WidgetFamily.of(family))
        }
    }
}

/** A control of the app the user added. Mirrors WidgetKit's `ControlInfo`, without its intent. */
public data class ControlInfo(
    /** The kind the control's configuration declares. */
    val kind: String,
    /** The control's push token, when its extension declares a push handler. */
    val pushInfo: ControlPushInfo?,
) {
    internal companion object {
        fun parse(line: String): ControlInfo {
            val parts = line.split('\t', limit = 2)
            val token = parts.getOrElse(1) { "" }
            return ControlInfo(parts[0], token.takeIf { it.isNotEmpty() }?.let { ControlPushInfo(it.hexToBytes()) })
        }
    }
}

/** The token a server pushes widget updates with. Mirrors WidgetKit's `WidgetPushInfo`. */
public class WidgetPushInfo(
    /** The APNs token. */
    public val token: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is WidgetPushInfo && other.token.contentEquals(token)

    override fun hashCode(): Int = token.contentHashCode()

    override fun toString(): String = "WidgetPushInfo(${token.toHex()})"
}

/** The token a server pushes control updates with. Mirrors WidgetKit's `ControlPushInfo`. */
public class ControlPushInfo(
    /** The APNs token. */
    public val token: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is ControlPushInfo && other.token.contentEquals(token)

    override fun hashCode(): Int = token.contentHashCode()

    override fun toString(): String = "ControlPushInfo(${token.toHex()})"
}

/** The sizes of a macOS widget. Mirrors WidgetKit's `WidgetFamily` on macOS. */
public enum class WidgetFamily(
    /** WidgetKit's name for the family (`systemSmall`, …). */
    public val rawValue: String,
) {
    /** The small square widget. */
    SystemSmall("systemSmall"),

    /** The medium, wide widget. */
    SystemMedium("systemMedium"),

    /** The large square widget. */
    SystemLarge("systemLarge"),

    /** The extra-large, wide widget (macOS 14). */
    SystemExtraLarge("systemExtraLarge"),

    /** The extra-large, tall widget (macOS 27). */
    SystemExtraLargePortrait("systemExtraLargePortrait"),

    /** A family this binding does not know (a newer macOS). */
    Unknown("unknown"),
    ;

    /** Factories. */
    public companion object {
        /** The family named [rawValue] by WidgetKit, or [Unknown]. */
        public fun of(rawValue: String): WidgetFamily = entries.firstOrNull { it.rawValue == rawValue } ?: Unknown
    }
}

/** A WidgetKit call failed. */
public class WidgetKitException(
    message: String,
) : Exception(message)

private val Duration.millis: Int get() = inWholeMilliseconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private const val HEX_RADIX = 16

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
