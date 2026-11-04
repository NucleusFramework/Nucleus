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
internal object WindowsWidgetsPackaging {
    /** Package-relative folder the images are staged in, under the app image's root (`app\`). */
    private const val IMAGES_DIR = "widgets"
    private const val MANIFEST_TEMPLATE = "/nucleus/appx/AppxManifest-widgets.xml"
    private const val PREVIEW_WIDTH = 300
    private const val PREVIEW_HEIGHT = 304

    /** The identity the CLSID derives from: the AppX identity, else the package or app name. */
    fun identity(distributions: JvmApplicationDistributions): String =
        distributions.windows.appx.identityName
            ?: distributions.packageName
            ?: distributions.appName
            ?: "app"

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
    fun stage(
        widgets: WindowsWidgetsSettings,
        identity: String,
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
        val template =
            workDir.resolve("AppxManifest.xml").apply {
                writeText(
                    WindowsWidgetsPackaging::class.java.getResource(MANIFEST_TEMPLATE)?.readText()
                        ?: error("Missing plugin resource $MANIFEST_TEMPLATE"),
                )
            }
        val extensions =
            workDir.resolve("widget-extensions.xml").apply {
                writeText(
                    extensionsXml(clsid(identity), "app\\$executableName.exe", definitions, paths) +
                        (userExtensions?.readText().orEmpty()),
                )
            }
        return Staged(template, extensions)
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
            file: File?,
            name: String,
            required: Boolean,
        ): String? {
            if (file == null) {
                if (required) throw GradleException("Windows widget '${widget.id}' needs its $name image")
                return null
            }
            if (!file.isFile) throw GradleException("Windows widget '${widget.id}': $name not found: $file")
            dir.mkdirs()
            file.copyTo(dir.resolve("$name.${file.extension}"), overwrite = true)
            return "app\\$IMAGES_DIR\\${widget.id}\\$name.${file.extension}"
        }
        listOf(widget.preview, widget.previewDark, widget.previewLight).forEach { property ->
            property.orNull?.asFile?.let { checkPreviewSize(widget.id, it, warn) }
        }
        return Images(
            icon = stage(widget.icon.orNull?.asFile, "icon", required = true)!!,
            preview = stage(widget.preview.orNull?.asFile, "preview", required = true)!!,
            previewDark = stage(widget.previewDark.orNull?.asFile, "preview-dark", required = false),
            previewLight = stage(widget.previewLight.orNull?.asFile, "preview-light", required = false),
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

    internal fun extensionsXml(
        clsid: UUID,
        executable: String,
        definitions: List<WindowsWidgetSettings>,
        images: Map<String, Images>,
    ): String {
        val id = clsid.toString().uppercase()
        val providerIcon = images.getValue(definitions.first().id).icon
        return buildString {
            appendLine("        <!-- Windows 11 widget provider, generated by the Nucleus plugin from windows { widgets { } }. -->")
            appendLine("        <com:Extension Category=\"windows.comServer\">")
            appendLine("          <com:ComServer>")
            appendLine(
                "            <com:ExeServer Executable=\"${xml(executable)}\" Arguments=\"-RegisterProcessAsComServer\" " +
                    "DisplayName=\"Widget provider\">",
            )
            appendLine("              <com:Class Id=\"$id\" DisplayName=\"Widget provider\" />")
            appendLine("            </com:ExeServer>")
            appendLine("          </com:ComServer>")
            appendLine("        </com:Extension>")
            appendLine("        <uap3:Extension Category=\"windows.appExtension\">")
            // makeappx requires PublicFolder below MinVersion 10.0.18307: any existing root folder
            // does, the widget host does not read it.
            appendLine(
                "          <uap3:AppExtension Name=\"com.microsoft.windows.widgets\" DisplayName=\"Widgets\" " +
                    "Id=\"NucleusWidgets\" PublicFolder=\"assets\">",
            )
            appendLine("            <uap3:Properties>")
            appendLine("              <WidgetProvider>")
            appendLine("                <ProviderIcons><Icon Path=\"${xml(providerIcon)}\" /></ProviderIcons>")
            appendLine("                <Activation><CreateInstance ClassId=\"$id\" /></Activation>")
            appendLine("                <Definitions>")
            for (widget in definitions) appendDefinition(widget, images.getValue(widget.id))
            appendLine("                </Definitions>")
            appendLine("              </WidgetProvider>")
            appendLine("            </uap3:Properties>")
            appendLine("          </uap3:AppExtension>")
            appendLine("        </uap3:Extension>")
        }
    }

    private fun StringBuilder.appendDefinition(
        widget: WindowsWidgetSettings,
        images: Images,
    ) {
        val name = widget.displayName ?: widget.id
        val description = widget.description ?: name
        appendLine(
            "                  <Definition Id=\"${widget.id}\" DisplayName=\"${xml(name)}\" Description=\"${xml(description)}\" " +
                "AllowMultiple=\"${widget.allowMultiple}\" IsCustomizable=\"${widget.customizable}\">",
        )
        appendLine("                    <Capabilities>")
        for (size in widget.sizes.distinct()) {
            appendLine("                      <Capability><Size Name=\"${size.name.lowercase()}\" /></Capability>")
        }
        appendLine("                    </Capabilities>")
        appendLine("                    <ThemeResources>")
        appendLine("                      <Icons><Icon Path=\"${xml(images.icon)}\" /></Icons>")
        appendLine("                      <Screenshots><Screenshot Path=\"${xml(images.preview)}\" DisplayAltText=\"${xml(name)}\" /></Screenshots>")
        images.previewDark?.let {
            appendLine("                      <DarkMode><Screenshots><Screenshot Path=\"${xml(it)}\" DisplayAltText=\"${xml(name)}\" /></Screenshots></DarkMode>")
        }
        images.previewLight?.let {
            appendLine("                      <LightMode><Screenshots><Screenshot Path=\"${xml(it)}\" DisplayAltText=\"${xml(name)}\" /></Screenshots></LightMode>")
        }
        appendLine("                    </ThemeResources>")
        appendLine("                  </Definition>")
    }

    /** What the package task fingerprints: every setting the generated XML is built from. */
    fun describe(widgets: WindowsWidgetsSettings): String =
        widgets.definitions.joinToString("\n") { widget ->
            "widget ${widget.id}: name=${widget.displayName} description=${widget.description} sizes=${widget.sizes} " +
                "customizable=${widget.customizable} allowMultiple=${widget.allowMultiple}"
        }

    /** The image files, for the package task's input files. */
    fun files(widgets: WindowsWidgetsSettings): List<File> =
        widgets.definitions.flatMap { widget ->
            listOf(widget.icon, widget.preview, widget.previewDark, widget.previewLight).mapNotNull { it.orNull?.asFile }
        }

    private fun xml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
