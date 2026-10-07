package dev.nucleusframework.updater.internal

/** Wraps a value in single quotes for safe interpolation into a generated shell script. */
internal fun String.quoteForShell(): String = "'" + replace("'", "'\\''") + "'"
