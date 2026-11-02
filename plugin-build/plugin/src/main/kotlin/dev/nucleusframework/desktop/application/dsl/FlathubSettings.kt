package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import java.io.Serializable
import javax.inject.Inject

/**
 * The Flathub repository of the app: a manifest that repackages its released Linux `tar.gz`
 * (Flathub builds nothing from source, the path a closed-source app takes) and the AppStream
 * metadata, desktop entry, icon and MIME types it installs. `generateFlathubRepository` writes
 * them into [outputDir] (`build/flathub/<appId>/` by default), the content of
 * `github.com/flathub/<appId>`.
 *
 * ```kotlin
 * linux {
 *     flathub {
 *         appId = "com.example.App"
 *         summary = "Edit example documents"
 *         projectLicense = "LicenseRef-proprietary"
 *         archiveUrl = "https://example.com/releases/{version}/app-{version}-linux-{arch}.tar.gz"
 *         screenshot("https://example.com/shots/main.png", "The main window")
 *     }
 * }
 * ```
 *
 * The name, description, vendor, homepage, categories, icon, file associations and URL protocols
 * come from the rest of the DSL; everything here is what only Flathub needs. The defaults target
 * a Nucleus app on Tao, which links GTK 3: the GNOME runtime, Wayland with an X11 fallback, GPU.
 */
abstract class FlathubSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /**
     * Flatpak application id, reverse DNS of a domain or code-hosting account you control
     * (`com.example.App`, `io.github.user.App`): Flathub verifies it. Required.
     */
    var appId: String? = null

    /** Runtime. Default: `org.gnome.Platform`, which ships the GTK 3 that Tao links. */
    var runtime: String = "org.gnome.Platform"

    /** Runtime branch; use one Flathub still supports. Default: `"51"`. */
    var runtimeVersion: String = "51"

    /** SDK matching [runtime]. Default: `org.gnome.Sdk`. */
    var sdk: String = "org.gnome.Sdk"

    /** Sandbox permissions. The default gives a Tao window what it needs and nothing else. */
    var finishArgs: List<String> =
        listOf("--share=ipc", "--socket=wayland", "--socket=fallback-x11", "--device=dri")

    /**
     * URL of the released `tar.gz` (`TargetFormat.Tar`): `{version}` is replaced by the package
     * version, `{arch}` by `x64` / `arm64` ([FlathubArch.archiveArch]). Required.
     */
    var archiveUrl: String? = null

    /** Architectures to publish; Flathub builds both by default. */
    var arches: Set<FlathubArch> = setOf(FlathubArch.X86_64, FlathubArch.AARCH64)

    /**
     * The executable inside the archive, relative to its top directory. Default: the GraalVM image
     * name when `graalvm { isEnabled }`, otherwise `bin/<packageName>` (the jpackage launcher).
     */
    var executable: String? = null

    /** One line under the name in the store, at most 35 characters, no trailing period. Required. */
    var summary: String? = null

    /**
     * The store description: paragraphs separated by blank lines, a `- ` line opens a bullet list.
     * Default: `nativeDistributions.description`.
     */
    var description: String? = null

    /** SPDX license of the app, `LicenseRef-proprietary` for a closed-source one. Required. */
    var projectLicense: String? = null

    /** Developer id (reverse DNS). Default: [appId] without its last component. */
    var developerId: String? = null

    /** Developer name. Default: `nativeDistributions.vendor`. */
    var developerName: String? = null

    /** Store categories (`Development`, `Office`, …). Default: `linux.appCategory`. */
    var categories: List<String> = emptyList()

    /** Search keywords of the store and the desktop entry. */
    var keywords: List<String> = emptyList()

    /** Issue tracker link of the store page. */
    var bugtrackerUrl: String? = null

    /** Source code link of the store page. */
    var vcsBrowserUrl: String? = null

    /** Accent color of the store page in light mode, `#rrggbb`. */
    var brandingLight: String? = null

    /** Accent color of the store page in dark mode, `#rrggbb`. */
    var brandingDark: String? = null

    /** Square PNG of at least 256 px. Default: `linux.iconFile`. */
    val iconFile: RegularFileProperty = objects.fileProperty()

    /**
     * Where `generateFlathubRepository` writes. Default: `build/flathub/<appId>`. Point it at a clone
     * of `github.com/flathub/<appId>` to update it in place: the task only replaces the files it
     * writes (and removes `flathub.json` or the MIME file when they no longer apply), never the
     * rest of the clone.
     */
    val outputDir: DirectoryProperty = objects.directoryProperty()

    internal val screenshots: MutableList<FlathubScreenshot> = mutableListOf()

    /**
     * Adds a store screenshot: a public, stable [url] (the first one is the default). Flathub
     * mirrors it at build time, so it must stay reachable.
     */
    fun screenshot(
        url: String,
        caption: String? = null,
    ) {
        screenshots.add(FlathubScreenshot(url, caption))
    }
}

/** A Flathub architecture: its Flatpak name and the `{arch}` of [FlathubSettings.archiveUrl]. */
enum class FlathubArch(
    val flatpakArch: String,
    val archiveArch: String,
) {
    X86_64("x86_64", "x64"),
    AARCH64("aarch64", "arm64"),
}

/** A store screenshot of [FlathubSettings]. */
data class FlathubScreenshot(
    val url: String,
    val caption: String?,
) : Serializable {
    /** Serialization constants. */
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
