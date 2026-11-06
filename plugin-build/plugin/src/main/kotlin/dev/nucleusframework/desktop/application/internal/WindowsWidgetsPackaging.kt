package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.WindowsWidgetSettings
import dev.nucleusframework.desktop.application.dsl.WindowsWidgetsSettings
import org.gradle.api.GradleException
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Turns `windows { widgets { } }` into what the AppX needs for the Windows 11 widgets board: the
 * images, the manifest template and the `<Extensions>` XML. Everything the widget host trips over
 * silently is handled here: plain-file images (it does not resolve `.scale-*` assets), the
 * `PublicFolder` makeappx requires, the Windows App SDK dependency, and one CLSID shared by the
 * manifest and the app (`NucleusApp.windowsWidgetsClsid`).
 */
// One cohesive generator; the many small functions are the manifest's elements.
@Suppress("TooManyFunctions")
internal object WindowsWidgetsPackaging {
    /** Package-relative folder the images are staged in, under the app image's root (`app\`). */
    private const val IMAGES_DIR = "widgets"
    private const val MANIFEST_TEMPLATE = "/nucleus/appx/AppxManifest-widgets.xml"
    private const val PREVIEW_WIDTH = 300
    private const val PREVIEW_HEIGHT = 304

    // The widget provider API arrived in the Windows App SDK 1.2.
    private const val MIN_WIDGETS_MINOR = 2

    /** The identity the CLSID derives from: the AppX identity, else the package or app name. */
    fun identity(distributions: JvmApplicationDistributions): String =
        distributions.windows.appx.identityName
            ?: distributions.packageName
            ?: distributions.appName
            ?: "app"

    /** The provider name the widget picker lists: the AppX display name, else the app name. */
    fun displayName(distributions: JvmApplicationDistributions): String =
        distributions.windows.appx.displayName
            ?: distributions.appName
            ?: distributions.packageName
            ?: "Widgets"

    /** The COM CLSID of the app's widget provider: stable for a package identity, distinct across apps. */
    fun clsid(identity: String): UUID = UUID.nameUUIDFromBytes("nucleus-windows-widgets:$identity".toByteArray())

    /** The files the electron-builder config points at. */
    class Staged(
        val manifestTemplate: File,
        val extensions: File,
    )

    /**
     * Copies the widget images into [appImageDir] and writes the manifest template and the
     * extensions XML (followed by the user's own `customExtensions`, if any) into [workDir].
     */
    @Suppress("LongParameterList") // the app's packaging settings, all passed from one call site
    fun stage(
        widgets: WindowsWidgetsSettings,
        identity: String,
        displayName: String,
        executableName: String,
        appImageDir: File,
        workDir: File,
        userExtensions: File?,
        warn: (String) -> Unit,
    ): Staged {
        val definitions = widgets.definitions
        val imagesDir = appImageDir.resolve(IMAGES_DIR).apply { deleteRecursively() }
        val paths =
            definitions.associate { widget ->
                widget.id to stageImages(widget, imagesDir.resolve(widget.id), warn)
            }

        workDir.mkdirs()
        val runtime = windowsAppRuntime(widgets.windowsAppSdkVersion)
        val template =
            workDir.resolve("AppxManifest.xml").apply {
                writeText(
                    (
                        WindowsWidgetsPackaging::class.java.getResource(MANIFEST_TEMPLATE)?.readText()
                            ?: error("Missing plugin resource $MANIFEST_TEMPLATE")
                    ).replace("@WINDOWS_APP_RUNTIME_NAME@", runtime.name)
                        .replace("@WINDOWS_APP_RUNTIME_MIN_VERSION@", runtime.minVersion),
                )
            }
        val extensions =
            workDir.resolve("widget-extensions.xml").apply {
                writeText(
                    extensionsXml(clsid(identity), displayName, "app\\$executableName.exe", definitions, paths) +
                        (userExtensions?.readText().orEmpty()),
                )
            }
        return Staged(template, extensions)
    }

    /** The framework package of a Windows App SDK release, as an AppX `PackageDependency` names it. */
    data class WindowsAppRuntime(
        val name: String,
        val minVersion: String,
    )

    /**
     * Maps a Windows App SDK `major.minor` to its runtime package: 1.x ships one package per minor
     * (`Microsoft.WindowsAppRuntime.1.8`, versions `8000.*`), 2.x one per major
     * (`Microsoft.WindowsAppRuntime.2`, versions `2.<minor>.*`).
     */
    fun windowsAppRuntime(version: String): WindowsAppRuntime {
        val parts = version.trim().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull()
        val minor = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != 2 || major == null || minor == null) {
            throw GradleException(
                "windowsAppSdkVersion must be major.minor (\"1.8\", \"2.6\"), was \"$version\"",
            )
        }
        if (major < 1 || (major == 1 && minor < MIN_WIDGETS_MINOR)) {
            throw GradleException(
                "Windows widgets need the Windows App SDK 1.$MIN_WIDGETS_MINOR or later, was $version",
            )
        }
        return if (major == 1) {
            WindowsAppRuntime("Microsoft.WindowsAppRuntime.1.$minor", "${minor}000.0.0.0")
        } else {
            WindowsAppRuntime("Microsoft.WindowsAppRuntime.$major", "$major.$minor.0.0")
        }
    }

    /** One widget's images, as package-relative paths. */
    class Images(
        val icon: String,
        val preview: String,
        val previewDark: String?,
        val previewLight: String?,
    )

    private fun stageImages(
        widget: WindowsWidgetSettings,
        dir: File,
        warn: (String) -> Unit,
    ): Images {
        fun stage(
            file: File,
            name: String,
        ): String {
            if (!file.isFile) throw GradleException("Windows widget '${widget.id}': $name not found: $file")
            dir.mkdirs()
            file.copyTo(dir.resolve("$name.${file.extension}"), overwrite = true)
            return "app\\$IMAGES_DIR\\${widget.id}\\$name.${file.extension}"
        }

        fun required(
            file: File?,
            name: String,
        ): String = stage(file ?: throw GradleException("Windows widget '${widget.id}' needs its $name image"), name)

        listOf(widget.preview, widget.previewDark, widget.previewLight).forEach { property ->
            property.orNull?.asFile?.let { checkPreviewSize(widget.id, it, warn) }
        }
        return Images(
            icon = required(widget.icon.orNull?.asFile, "icon"),
            preview = required(widget.preview.orNull?.asFile, "preview"),
            previewDark = widget.previewDark.orNull?.asFile?.let { stage(it, "preview-dark") },
            previewLight = widget.previewLight.orNull?.asFile?.let { stage(it, "preview-light") },
        )
    }

    private fun checkPreviewSize(
        id: String,
        file: File,
        warn: (String) -> Unit,
    ) {
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return
        if (image.width != PREVIEW_WIDTH || image.height != PREVIEW_HEIGHT) {
            warn(
                "Windows widget '$id': ${file.name} is ${image.width}x${image.height}; the widgets board " +
                    "expects a ${PREVIEW_WIDTH}x$PREVIEW_HEIGHT preview of the medium widget.",
            )
        }
    }

    // The numbers are the XML indentation of each line inside the manifest's <Extensions>.
    @Suppress("MagicNumber")
    internal fun extensionsXml(
        clsid: UUID,
        displayName: String,
        executable: String,
        definitions: List<WindowsWidgetSettings>,
        images: Map<String, Images>,
    ): String {
        val id = clsid.toString().uppercase()
        val providerIcon = images.getValue(definitions.first().id).icon
        return buildString {
            line(8, "<!-- Windows 11 widget provider, generated by the Nucleus plugin (windows { widgets { } }). -->")
            line(8, "<com:Extension Category=\"windows.comServer\">")
            line(10, "<com:ComServer>")
            line(
                12,
                "<com:ExeServer Executable=\"${xml(executable)}\" Arguments=\"-RegisterProcessAsComServer\" " +
                    "DisplayName=\"Widget provider\">",
            )
            line(14, "<com:Class Id=\"$id\" DisplayName=\"Widget provider\" />")
            line(12, "</com:ExeServer>")
            line(10, "</com:ComServer>")
            line(8, "</com:Extension>")
            line(8, "<uap3:Extension Category=\"windows.appExtension\">")
            // makeappx requires PublicFolder below MinVersion 10.0.18307: any existing root folder
            // does, the widget host does not read it.
            line(
                10,
                "<uap3:AppExtension Name=\"com.microsoft.windows.widgets\" DisplayName=\"${xml(displayName)}\" " +
                    "Id=\"NucleusWidgets\" PublicFolder=\"assets\">",
            )
            line(12, "<uap3:Properties>")
            line(14, "<WidgetProvider>")
            line(16, "<ProviderIcons><Icon Path=\"${xml(providerIcon)}\" /></ProviderIcons>")
            line(16, "<Activation><CreateInstance ClassId=\"$id\" /></Activation>")
            line(16, "<Definitions>")
            for (widget in definitions) appendDefinition(widget, images.getValue(widget.id))
            line(16, "</Definitions>")
            line(14, "</WidgetProvider>")
            line(12, "</uap3:Properties>")
            line(10, "</uap3:AppExtension>")
            line(8, "</uap3:Extension>")
        }
    }

    private fun StringBuilder.line(
        indent: Int,
        text: String,
    ) {
        append(" ".repeat(indent)).appendLine(text)
    }

    // The numbers are the XML indentation of each line inside the manifest's <Extensions>.
    @Suppress("MagicNumber")
    private fun StringBuilder.appendDefinition(
        widget: WindowsWidgetSettings,
        images: Images,
    ) {
        val name = xml(widget.displayName ?: widget.id)
        val description = xml(widget.description ?: widget.displayName ?: widget.id)
        val attributes =
            buildList {
                add("Id=\"${widget.id}\"")
                add("DisplayName=\"$name\"")
                add("Description=\"$description\"")
                add("AllowMultiple=\"${widget.allowMultiple}\"")
                add("IsCustomizable=\"${widget.customizable}\"")
                widget.webRequestFilter?.let { add("WebRequestFilter=\"${xml(it)}\"") }
            }
        line(18, "<Definition ${attributes.joinToString(" ")}>")
        line(20, "<Capabilities>")
        for (size in widget.sizes.distinct()) {
            line(22, "<Capability><Size Name=\"${size.name.lowercase()}\" /></Capability>")
        }
        line(20, "</Capabilities>")
        line(20, "<ThemeResources>")
        line(22, "<Icons><Icon Path=\"${xml(images.icon)}\" /></Icons>")
        line(22, "<Screenshots>${screenshot(images.preview, name)}</Screenshots>")
        images.previewDark?.let { line(22, themed("DarkMode", screenshot(it, name))) }
        images.previewLight?.let { line(22, themed("LightMode", screenshot(it, name))) }
        line(20, "</ThemeResources>")
        line(18, "</Definition>")
    }

    private fun themed(
        mode: String,
        screenshot: String,
    ) = "<$mode><Screenshots>$screenshot</Screenshots></$mode>"

    private fun screenshot(
        path: String,
        altText: String,
    ) = "<Screenshot Path=\"${xml(path)}\" DisplayAltText=\"$altText\" />"

    /** What the package task fingerprints: every setting the generated XML is built from. */
    fun describe(widgets: WindowsWidgetsSettings): String =
        buildString {
            appendLine("windowsAppSdkVersion=${widgets.windowsAppSdkVersion}")
            for (widget in widgets.definitions) {
                appendLine(
                    "widget ${widget.id}: name=${widget.displayName} description=${widget.description} " +
                        "sizes=${widget.sizes} customizable=${widget.customizable} " +
                        "allowMultiple=${widget.allowMultiple} webRequestFilter=${widget.webRequestFilter}",
                )
            }
        }

    /** The image files, for the package task's input files. */
    fun files(widgets: WindowsWidgetsSettings): List<File> =
        widgets.definitions.flatMap { widget ->
            listOf(widget.icon, widget.preview, widget.previewDark, widget.previewLight)
                .mapNotNull { it.orNull?.asFile }
        }

    private fun xml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
