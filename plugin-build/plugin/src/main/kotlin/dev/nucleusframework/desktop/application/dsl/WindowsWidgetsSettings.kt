package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * Windows 11 widgets the app provides to the widgets board, rendered at run time by the
 * `widgets { }` of `nucleusApplication` (`nucleus.widget-windows`).
 *
 * For an AppX package, each declared widget becomes a `Definition` of the manifest's
 * `com.microsoft.windows.widgets` extension, next to the COM server the board activates (under a
 * CLSID derived from the package identity and baked into `NucleusApp.windowsWidgetsClsid`) and
 * the Windows App SDK framework dependency. Nothing changes while no widget is declared.
 *
 * ```
 * windows {
 *     widgets {
 *         widget("Counter") {
 *             displayName = "Counter"
 *             description = "A live counter"
 *             icon.set(file("widgets/icon.png"))
 *             preview.set(file("widgets/preview.png"))
 *         }
 *     }
 * }
 * ```
 */
@Suppress("UnnecessaryAbstractClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class WindowsWidgetsSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    internal val definitions = mutableListOf<WindowsWidgetSettings>()

    /**
     * Declares the widget [id]: the definition id `widgets { widget(id) { … } }` registers at run
     * time. Letters, digits and `_`, starting with a letter.
     */
    fun widget(
        id: String,
        configure: Action<WindowsWidgetSettings>,
    ) {
        require(ID.matches(id)) { "Widget id '$id' must be letters, digits and '_', starting with a letter" }
        require(definitions.none { it.id == id }) { "Widget '$id' is declared twice" }
        definitions += objects.newInstance(WindowsWidgetSettings::class.java, id).also(configure::execute)
    }

    internal companion object {
        val ID = Regex("[A-Za-z][A-Za-z0-9_]*")
    }
}

/** One widget of [WindowsWidgetsSettings]. */
@Suppress("UnnecessaryAbstractClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class WindowsWidgetSettings
    @Inject
    constructor(
        /** The definition id, shared with the run-time `widget(id) { … }`. */
        val id: String,
    ) {
        @get:Inject
        internal abstract val objects: ObjectFactory

        /** Name shown in the widgets board's picker. Defaults to [id]. */
        var displayName: String? = null

        /** Short description shown in the picker. Defaults to [displayName]. */
        var description: String? = null

        /** Sizes the user may pick. Defaults to all three. */
        var sizes: List<WindowsWidgetSize> = WindowsWidgetSize.entries

        /** Whether the widget's menu offers *Customize widget* (`WidgetProvider2`). */
        var customizable: Boolean = false

        /** Whether the user may pin several instances. */
        var allowMultiple: Boolean = true

        /** Icon shown in the widget's header and in the picker. Required. */
        val icon: RegularFileProperty = objects.fileProperty()

        /**
         * Preview shown in the picker: the medium widget, 300 x 304 px, transparent rounded
         * corners. Required.
         */
        val preview: RegularFileProperty = objects.fileProperty()

        /** Optional [preview] variant for a dark Windows theme. */
        val previewDark: RegularFileProperty = objects.fileProperty()

        /** Optional [preview] variant for a light Windows theme. */
        val previewLight: RegularFileProperty = objects.fileProperty()
    }

/** Sizes a Windows widget can be pinned at. */
enum class WindowsWidgetSize {
    Small,
    Medium,
    Large,
}
