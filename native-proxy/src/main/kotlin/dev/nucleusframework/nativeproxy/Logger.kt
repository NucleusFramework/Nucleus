package dev.nucleusframework.nativeproxy

import dev.nucleusframework.core.runtime.tools.allowNucleusRuntimeLogging

internal fun debugln(
    tag: String,
    message: () -> String,
) {
    if (allowNucleusRuntimeLogging) {
        println("[$tag] ${message()}")
    }
}

internal fun errorln(
    tag: String,
    message: () -> String,
) {
    if (allowNucleusRuntimeLogging) {
        System.err.println("[$tag] ${message()}")
    }
}
