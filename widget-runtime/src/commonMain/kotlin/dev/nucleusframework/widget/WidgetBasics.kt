package dev.nucleusframework.widget

import kotlin.jvm.JvmInline

/**
 * Marks the contract between the widget runtime and its platform hosts (`widget-windows`,
 * `widget-macos`).
 * Apps never need it; it may change without notice.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal contract between the widget runtime and its hosts.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class InternalNucleusWidgetApi

/** Identifies one pinned instance of a widget, as assigned by the widget host. */
@JvmInline
public value class WidgetId(
    public val value: String,
) {
    override fun toString(): String = value
}

/** Size of a pinned widget, as chosen by the user in the widget host. */
public enum class WidgetSize {
    /** The small size. */
    Small,

    /** The medium size. */
    Medium,

    /** The large size. */
    Large,
}
