package dev.nucleusframework

/**
 * Marks dependency shortcuts that point to experimental Compose libraries; using them requires an explicit opt-in.
 */
// We write explicitly about OptIn, because IDEA doesn't suggest it.
@RequiresOptIn(
    "This library is experimental and can be unstable. " +
        "Add @OptIn(dev.nucleusframework.ExperimentalNucleusLibrary::class) annotation.",
)
annotation class ExperimentalNucleusLibrary
