package dev.nucleusframework.darkmodedetector

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.darkmodedetector.linux.LinuxPortalThemeDetector
import dev.nucleusframework.darkmodedetector.mac.MacOSThemeDetector
import dev.nucleusframework.darkmodedetector.windows.WindowsThemeDetector
import java.util.function.Consumer

public interface IDarkModeDetector {
    public fun isDark(): Boolean

    public fun registerListener(listener: Consumer<Boolean>)

    public fun removeListener(listener: Consumer<Boolean>)
}

public object NoopDarkModeDetector : IDarkModeDetector {
    override fun isDark(): Boolean = false

    override fun registerListener(listener: Consumer<Boolean>): Unit = Unit

    override fun removeListener(listener: Consumer<Boolean>): Unit = Unit
}

public fun getPlatformDarkModeDetector(): IDarkModeDetector =
    when (Platform.Current) {
        Platform.MacOS -> MacOSThemeDetector
        Platform.Windows -> WindowsThemeDetector
        Platform.Linux -> LinuxPortalThemeDetector
        else -> NoopDarkModeDetector
    }
