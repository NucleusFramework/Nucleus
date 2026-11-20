package dev.nucleusframework.window

/**
 * Marks a Nucleus API that is still experimental: it may change or be removed
 * in a minor release without a deprecation cycle.
 *
 * Opt in with `@OptIn(ExperimentalNucleusApi::class)`, or module-wide with the
 * `-opt-in=dev.nucleusframework.window.ExperimentalNucleusApi` compiler argument.
 */
@RequiresOptIn(
    message =
        "This Nucleus API is experimental and may change or be removed without a deprecation cycle. " +
            "Opt in with @OptIn(dev.nucleusframework.window.ExperimentalNucleusApi::class).",
)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalNucleusApi
