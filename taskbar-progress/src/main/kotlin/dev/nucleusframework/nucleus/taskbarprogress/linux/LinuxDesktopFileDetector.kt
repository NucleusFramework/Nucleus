package dev.nucleusframework.nucleus.taskbarprogress.linux

import dev.nucleusframework.nucleus.core.runtime.tools.LinuxDesktopFileDetector as CoreDetector

/** Delegates to [CoreDetector] in core-runtime. */
internal object LinuxDesktopFileDetector {
    val desktopFilename: String? get() = CoreDetector.desktopFilename
}
