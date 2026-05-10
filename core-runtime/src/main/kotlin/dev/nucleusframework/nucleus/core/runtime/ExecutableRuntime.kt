package dev.nucleusframework.nucleus.core.runtime

import java.io.File

public enum class ExecutableType {
    // Windows
    EXE,
    MSI,
    NSIS,
    NSIS_WEB,
    PORTABLE,
    APPX,

    // macOS
    DMG,
    PKG,

    // Linux
    DEB,
    RPM,
    SNAP,
    FLATPAK,
    APPIMAGE,

    // Archives
    ZIP,
    TAR,
    SEVEN_Z,

    // Dev
    DEV,
}

@Suppress("TooManyFunctions")
public object ExecutableRuntime {
    public const val TYPE_PROPERTY: String = "nucleus.executable.type"
    private const val TYPE_MARKER_FILE: String = ".nucleus-executable-type"

    @JvmStatic
    public fun type(): ExecutableType {
        val fromProperty = System.getProperty(TYPE_PROPERTY)
        if (fromProperty != null) return parseType(fromProperty)
        return parseType(markerData?.type)
    }

    @JvmStatic
    public fun type(propertyName: String): ExecutableType = parseType(System.getProperty(propertyName))

    @JvmStatic
    public fun isExe(): Boolean = type() == ExecutableType.EXE

    @JvmStatic
    public fun isMsi(): Boolean = type() == ExecutableType.MSI

    @JvmStatic
    public fun isNsis(): Boolean = type() == ExecutableType.NSIS

    @JvmStatic
    public fun isNsisWeb(): Boolean = type() == ExecutableType.NSIS_WEB

    @JvmStatic
    public fun isPortable(): Boolean = type() == ExecutableType.PORTABLE

    @JvmStatic
    public fun isAppX(): Boolean = type() == ExecutableType.APPX

    @JvmStatic
    public fun isDmg(): Boolean = type() == ExecutableType.DMG

    @JvmStatic
    public fun isPkg(): Boolean = type() == ExecutableType.PKG

    @JvmStatic
    public fun isDeb(): Boolean = type() == ExecutableType.DEB

    @JvmStatic
    public fun isRpm(): Boolean = type() == ExecutableType.RPM

    @JvmStatic
    public fun isSnap(): Boolean = type() == ExecutableType.SNAP

    @JvmStatic
    public fun isFlatpak(): Boolean = type() == ExecutableType.FLATPAK

    @JvmStatic
    public fun isAppImage(): Boolean = type() == ExecutableType.APPIMAGE

    @JvmStatic
    public fun isZip(): Boolean = type() == ExecutableType.ZIP

    @JvmStatic
    public fun isTar(): Boolean = type() == ExecutableType.TAR

    @JvmStatic
    public fun isSevenZ(): Boolean = type() == ExecutableType.SEVEN_Z

    @JvmStatic
    public fun isDev(): Boolean = type() == ExecutableType.DEV

    @JvmStatic
    public val isGraalVmNativeImage: Boolean =
        System.getProperty("org.graalvm.nativeimage.imagecode") != null

    public fun parseType(rawValue: String?): ExecutableType =
        when (rawValue?.trim()?.lowercase()) {
            // Windows
            "exe", ".exe" -> ExecutableType.EXE
            "msi", ".msi" -> ExecutableType.MSI
            "nsis" -> ExecutableType.NSIS
            "nsis-web" -> ExecutableType.NSIS_WEB
            "portable" -> ExecutableType.PORTABLE
            "appx", ".appx" -> ExecutableType.APPX
            // macOS
            "dmg", ".dmg" -> ExecutableType.DMG
            "pkg", ".pkg" -> ExecutableType.PKG
            // Linux
            "deb", ".deb" -> ExecutableType.DEB
            "rpm", ".rpm" -> ExecutableType.RPM
            "snap", ".snap" -> ExecutableType.SNAP
            "flatpak", ".flatpak" -> ExecutableType.FLATPAK
            "appimage", ".appimage" -> ExecutableType.APPIMAGE
            // Archives
            "zip", ".zip" -> ExecutableType.ZIP
            "tar", "tar.gz", ".tar.gz" -> ExecutableType.TAR
            "7z", ".7z" -> ExecutableType.SEVEN_Z
            // Dev
            "dev", "development", "app-image" -> ExecutableType.DEV
            else -> ExecutableType.DEV
        }

    private data class MarkerData(
        val type: String,
        val version: String?,
    )

    private val markerData: MarkerData? by lazy { readMarkerFile() }

    /**
     * Reads the app version from the marker file written by the Gradle plugin
     * for GraalVM native-image builds (where jpackage.app-version is unavailable).
     */
    @JvmStatic
    public fun markerVersion(): String? = markerData?.version

    @Suppress("TooGenericExceptionCaught")
    private fun readMarkerFile(): MarkerData? =
        try {
            val execPath =
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null) ?: return null
            val marker = File(execPath).parentFile?.resolve(TYPE_MARKER_FILE) ?: return null
            if (!marker.isFile) return null
            val lines = marker.readLines()
            MarkerData(
                type = lines.getOrNull(0)?.trim() ?: return null,
                version = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
            )
        } catch (_: Exception) {
            null
        }
}
