package dev.nucleusframework.widget.windows.host

import java.io.File
import java.net.URLConnection
import java.util.logging.Logger

/**
 * Resolves `ImageProvider.appResource` paths for the widget host, which runs in another process:
 * inside a package, as an `ms-appx:///` URI the host reads from the installed files; outside one
 * (a dev run, where the widgets board cannot show the app anyway), as the file's bytes.
 */
internal object AppResourceImages {
    // Set by the Nucleus plugin for jpackage and GraalVM images alike.
    private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
    private val logger = Logger.getLogger(AppResourceImages::class.java.name)

    private val resourcesDir: File? by lazy {
        System.getProperty(RESOURCES_DIR_PROPERTY)?.let(::File)?.takeIf { it.isDirectory }
    }

    // The package root is the first ancestor holding the AppxManifest.xml.
    private val packageRoot: File? by lazy {
        generateSequence(resourcesDir?.absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "AppxManifest.xml").isFile }
    }

    fun resolve(path: String): AdaptiveCardRenderer.ImageSource {
        val dir = resourcesDir
        val file = dir?.resolve(path)
        if (file == null || !file.isFile) {
            logger.warning { "Widget image resource '$path' not found in ${dir ?: "(no app resources directory)"}" }
            return AdaptiveCardRenderer.ImageSource.Missing
        }
        val root = packageRoot
        if (root != null) {
            val relative = file.absoluteFile.relativeTo(root).invariantSeparatorsPath
            return AdaptiveCardRenderer.ImageSource.Url("ms-appx:///$relative")
        }
        val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: "image/png"
        return AdaptiveCardRenderer.ImageSource.Bytes(file.readBytes(), mimeType)
    }
}
