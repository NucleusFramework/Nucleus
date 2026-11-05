package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.Action
import java.io.Serializable

/**
 * macOS desktop and Notification Center widgets (WidgetKit) the app provides, rendered at run time
 * by the `widgets { }` of `nucleusApplication` (`nucleus.widget-macos`).
 *
 * Declaring a widget embeds the Nucleus widget extension in the app bundle
 * (`Contents/PlugIns/NucleusWidgets.appex`), taken from the `nucleus.widget-macos` JAR on the
 * runtime classpath and signed through [MacAppExtensionSettings]'s pipeline with its own sandbox
 * entitlements. Nothing changes while no widget is declared. Widgets need macOS 14.
 *
 * ```
 * macOS {
 *     widgets {
 *         widget("Counter") {
 *             displayName = "Counter"
 *             description = "A live counter"
 *             families = listOf(MacWidgetFamily.Small, MacWidgetFamily.Medium)
 *         }
 *     }
 * }
 * ```
 */
@Suppress("SerialVersionUIDInSerializableClass") // Gradle DSL bean, never deserialized across versions
class MacWidgetsSettings : Serializable {
    internal val definitions = mutableListOf<MacWidgetSettings>()

    /**
     * The App Group the app and its widget extension share their files through
     * (`TEAMID.com.example.app`). Required when the app itself is sandboxed (Mac App Store), and
     * then also listed in the app's own `com.apple.security.application-groups` entitlement; the
     * group must belong to the signing team. Left unset, the files live in
     * `~/Library/Application Support/<bundle id>/NucleusWidgets`, which the extension's sandbox
     * reaches through a temporary-exception entitlement (Developer ID and ad-hoc builds).
     */
    var appGroup: String? = null

    /**
     * Declares the widget [kind]: the definition id `widgets { widget(kind) { … } }` registers at
     * run time. Letters, digits and `_`, starting with a letter; at most 16 widgets.
     */
    fun widget(
        kind: String,
        configure: Action<MacWidgetSettings>,
    ) {
        require(WindowsWidgetsSettings.ID.matches(kind)) {
            "Widget kind '$kind' must be letters, digits and '_', starting with a letter"
        }
        require(definitions.none { it.kind == kind }) { "Widget '$kind' is declared twice" }
        require(definitions.size < MAX_WIDGETS) { "macOS widgets: at most $MAX_WIDGETS widgets per app" }
        definitions += MacWidgetSettings(kind).also(configure::execute)
    }

    internal companion object {
        /** The slots of the prebuilt extension (`NucleusWidgetBundles.swift`). */
        const val MAX_WIDGETS = 16
    }
}

/** One widget of [MacWidgetsSettings]. */
@Suppress("SerialVersionUIDInSerializableClass") // Gradle DSL bean, never deserialized across versions
class MacWidgetSettings(
    /** The widget kind, shared with the run-time `widget(kind) { … }`. */
    val kind: String,
) : Serializable {
    /** Name shown in the widget gallery. Defaults to [kind]. */
    var displayName: String? = null

    /** Short description shown in the widget gallery. */
    var description: String? = null

    /** Sizes the user may pick. Defaults to small, medium and large. */
    var families: List<MacWidgetFamily> = listOf(MacWidgetFamily.Small, MacWidgetFamily.Medium, MacWidgetFamily.Large)
}

/** Sizes a macOS widget can be added at (WidgetKit's `WidgetFamily`). */
enum class MacWidgetFamily(
    internal val rawValue: String,
) {
    Small("systemSmall"),
    Medium("systemMedium"),
    Large("systemLarge"),
    ExtraLarge("systemExtraLarge"),
}
