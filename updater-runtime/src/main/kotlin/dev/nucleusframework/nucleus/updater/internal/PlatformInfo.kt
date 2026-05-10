package dev.nucleusframework.nucleus.updater.internal

import dev.nucleusframework.nucleus.core.runtime.Platform

internal enum class Arch {
    X64,
    ARM64,
}

internal object PlatformInfo {
    fun currentPlatform(): Platform = Platform.Current

    fun currentArch(): Arch {
        val osArch = System.getProperty("os.arch").lowercase()
        return when {
            osArch.contains("aarch64") || osArch.contains("arm64") -> Arch.ARM64
            else -> Arch.X64
        }
    }

    fun ymlSuffix(): String =
        when (currentPlatform()) {
            Platform.Windows -> ""
            Platform.MacOS -> "mac"
            Platform.Linux -> "linux"
            Platform.Unknown -> ""
        }

    fun ymlFileName(channel: String): String {
        val suffix = ymlSuffix()
        return if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
    }
}
