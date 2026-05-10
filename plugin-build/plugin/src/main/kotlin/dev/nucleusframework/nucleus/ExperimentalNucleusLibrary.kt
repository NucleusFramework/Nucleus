package dev.nucleusframework.nucleus

// We write explicitly about OptIn, because IDEA doesn't suggest it.
@RequiresOptIn(
    "This library is experimental and can be unstable. " +
        "Add @OptIn(dev.nucleusframework.nucleus.ExperimentalNucleusLibrary::class) annotation.",
)
annotation class ExperimentalNucleusLibrary
