package dev.nucleusframework.widget.windows.host

import java.io.File
import java.util.logging.Logger

/**
 * Resolves `ImageProvider.appResource` paths for the widget host, which runs in another process:
 * as a `file:///` URI of the installed file, which the widgets board loads. Verified on the board:
 * `ms-appx:///` resolves against the host's own package, and `ms-appx://<family name>/` is not
 * loaded either, so neither reaches the provider's files.
 */
internal object AppResourceImages {
    // Set by the Nucleus plugin for jpackage and GraalVM images alike.
    private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
    private val logger = Logger.getLogger(AppResourceImages::class.java.name)

    private val resourcesDir: File? by lazy {
        System.getProperty(RESOURCES_DIR_PROPERTY)?.let(::File)?.takeIf { it.isDirectory }
    }

    fun resolve(path: String): AdaptiveCardRenderer.ImageSource {
        val dir = resourcesDir
        val file = dir?.resolve(path)
        if (file == null || !file.isFile) {
            logger.warning { "Widget image resource '$path' not found in ${dir ?: "(no app resources directory)"}" }
            return AdaptiveCardRenderer.ImageSource.Missing
        }
        // file:///C:/Program%20Files/WindowsApps/… — the installed package is readable by the host.
        return AdaptiveCardRenderer.ImageSource.Url(
            file.absoluteFile
                .toPath()
                .toUri()
                .toString(),
        )
    }
}
