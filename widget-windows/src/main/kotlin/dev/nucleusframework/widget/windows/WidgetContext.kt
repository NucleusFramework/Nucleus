package dev.nucleusframework.widget.windows

import dev.nucleusframework.widget.ExperimentalNucleusWidgetApi

/**
 * Size of a widget in the widget host.
 *
 * Mirrors `Microsoft.Windows.Widgets.WidgetSize`.
 */
@ExperimentalNucleusWidgetApi
public enum class WidgetSize {
    /** The small widget size. */
    Small,

    /** The medium widget size. */
    Medium,

    /** The large widget size. */
    Large,
    ;

    internal companion object {
        fun fromRawValue(value: Int): WidgetSize = entries.getOrElse(value) { Small }
    }
}

/**
 * Information about the configuration of a widget within the widget host.
 *
 * Mirrors `Microsoft.Windows.Widgets.Providers.WidgetContext`.
 *
 * @property id Unique identifier of this widget instance.
 * @property definitionId Id of the widget definition, as declared in the package manifest.
 * @property size Current size of the widget.
 * @property isActive Whether the widget is currently visible in the host and should be kept up to date.
 */
@ExperimentalNucleusWidgetApi
public data class WidgetContext(
    val id: String,
    val definitionId: String,
    val size: WidgetSize,
    val isActive: Boolean,
)
