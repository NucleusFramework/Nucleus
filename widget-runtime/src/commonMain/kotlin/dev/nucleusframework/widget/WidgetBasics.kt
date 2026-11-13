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

/**
 * Marks the Nucleus widget API (`widget-runtime`, `widget-windows`, `widget-macos`, `widget-ios`),
 * still experimental: it may change or be removed in a minor release without a deprecation cycle.
 *
 * Opt in with `@OptIn(ExperimentalNucleusWidgetApi::class)`, or module-wide with the
 * `-opt-in=dev.nucleusframework.widget.ExperimentalNucleusWidgetApi` compiler argument.
 */
@RequiresOptIn(
    message =
        "The Nucleus widget API is experimental and may change or be removed without a deprecation cycle. " +
            "Opt in with @OptIn(dev.nucleusframework.widget.ExperimentalNucleusWidgetApi::class).",
)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalNucleusWidgetApi

/** Identifies one pinned instance of a widget, as assigned by the widget host. */
@JvmInline
@ExperimentalNucleusWidgetApi
public value class WidgetId(
    public val value: String,
) {
    override fun toString(): String = value
}

/** Size of a pinned widget, as chosen by the user in the widget host. */
@ExperimentalNucleusWidgetApi
public enum class WidgetSize {
    /** The small size. */
    Small,

    /** The medium size. */
    Medium,

    /** The large size. */
    Large,
}
