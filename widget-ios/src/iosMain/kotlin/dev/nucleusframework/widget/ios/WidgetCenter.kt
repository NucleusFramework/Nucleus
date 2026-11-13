package dev.nucleusframework.widget.ios

import dev.nucleusframework.widget.ios.objcbridge.nucleus_widgets_call_class

/**
 * Tells WidgetKit about the app's widgets: reloads their timelines and lists the ones the user
 * added. Mirrors WidgetKit's `WidgetCenter` (`WidgetCenter.shared`).
 *
 * WidgetKit's API is Swift-only: calls go through `NucleusWidgetCenter`, a Swift class the app adds
 * to its targets (`widget-ios/src/swift/NucleusWidgetCenter.swift`), found at run time. Without
 * it, [isAvailable] is `false` and every call is a no-op (an empty list for [currentConfigurations]).
 */
public object WidgetCenter {
    /** Whether the Swift bridge (`NucleusWidgetCenter`) is in the app. */
    public val isAvailable: Boolean get() = SwiftBridge.call("available") != null

    /** Asks WidgetKit to reload the timelines of every widget of [ofKind]. */
    public fun reloadTimelines(ofKind: String) {
        SwiftBridge.call("reloadTimelines", ofKind)
    }

    /** Asks WidgetKit to reload the timelines of every widget of the app. */
    public fun reloadAllTimelines() {
        SwiftBridge.call("reloadAllTimelines")
    }

    /**
     * The widgets of the app the user added, one per kind and family. Mirrors
     * `getCurrentConfigurations(_:)`; blocks until WidgetKit answers, so call it off the main thread.
     *
     * @throws WidgetKitException when WidgetKit fails.
     */
    public fun currentConfigurations(): List<WidgetInfo> =
        SwiftBridge
            .payload("currentConfigurations")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map(WidgetInfo::parse)
            .toList()

    /** Asks WidgetKit to fetch the configuration recommendations again (`invalidateConfigurationRecommendations()`). */
    public fun invalidateConfigurationRecommendations() {
        SwiftBridge.call("invalidateConfigurationRecommendations")
    }

    /** Asks WidgetKit to fetch the relevance of the widgets of [ofKind] again; `false` below iOS 18. */
    public fun invalidateRelevance(ofKind: String): Boolean = SwiftBridge.call("invalidateRelevance", ofKind) == "OK\n"
}

/** The controls (Control Center, Lock Screen) of the app. Mirrors WidgetKit's `ControlCenter`, iOS 18. */
public object ControlCenter {
    /** Whether controls exist here: iOS 18 or later, with the Swift bridge in the app. */
    public val isAvailable: Boolean get() = SwiftBridge.call("controlsAvailable") == "OK\n"

    /** Asks WidgetKit to reload the controls of [ofKind]. */
    public fun reloadControls(ofKind: String) {
        SwiftBridge.call("reloadControls", ofKind)
    }

    /** Asks WidgetKit to reload every control of the app. */
    public fun reloadAllControls() {
        SwiftBridge.call("reloadAllControls")
    }

    /**
     * The kinds of the controls the user added (`currentControls()`); empty below iOS 18. Blocks
     * until WidgetKit answers.
     *
     * @throws WidgetKitException when WidgetKit fails.
     */
    public fun currentControls(): List<String> =
        SwiftBridge
            .payload("currentControls")
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
}

/** A widget of the app the user added. Mirrors WidgetKit's `WidgetInfo`, without its intent. */
public data class WidgetInfo(
    /** The kind the widget's configuration declares. */
    val kind: String,
    /** The size the user picked. */
    val family: WidgetFamily,
) {
    internal companion object {
        fun parse(line: String): WidgetInfo {
            val parts = line.split('\t', limit = 2)
            return WidgetInfo(parts[0], WidgetFamily.of(parts.getOrElse(1) { "" }))
        }
    }
}

/** The sizes of an iOS widget. Mirrors WidgetKit's `WidgetFamily` on iOS. */
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

    /** The extra-large widget (iPad). */
    SystemExtraLarge("systemExtraLarge"),

    /** The circular Lock Screen widget. */
    AccessoryCircular("accessoryCircular"),

    /** The rectangular Lock Screen widget. */
    AccessoryRectangular("accessoryRectangular"),

    /** The inline Lock Screen widget (a line of text). */
    AccessoryInline("accessoryInline"),

    /** A family this binding does not know (a newer iOS). */
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

/** `+[NucleusWidgetCenter call:]`: one string command in, "OK\n" + payload or "ERR\n" + message out. */
internal object SwiftBridge {
    private const val CLASS_NAME = "NucleusWidgetCenter"
    private const val SELECTOR = "call:"

    fun call(
        command: String,
        argument: String = "",
    ): String? = nucleus_widgets_call_class(CLASS_NAME, SELECTOR, "$command\n$argument")

    fun payload(command: String): String {
        val result = call(command) ?: return ""
        val newline = result.indexOf('\n')
        val status = if (newline < 0) result else result.substring(0, newline)
        val body = if (newline < 0) "" else result.substring(newline + 1)
        if (status != "OK") throw WidgetKitException(body)
        return body
    }
}
