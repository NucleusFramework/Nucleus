package dev.nucleusframework.lab.app.shell

import androidx.compose.runtime.staticCompositionLocalOf

/** The shell, for the built-in probes that report on the Lab itself. */
val LocalShell = staticCompositionLocalOf<ShellViewModel> { error("LocalShell is provided by ProbeHost") }
