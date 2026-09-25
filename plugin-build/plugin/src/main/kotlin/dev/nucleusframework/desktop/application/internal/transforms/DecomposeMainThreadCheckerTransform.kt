package dev.nucleusframework.desktop.application.internal.transforms

import org.gradle.api.Project
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Removes Decompose's Swing `MainThreadChecker` provider from `extensions-compose` (#513).
 *
 * Decompose resolves its checker with `ServiceLoader.load(MainThreadChecker).firstOrNull()`, and
 * `extensions-compose` registers `SwingMainThreadChecker`, which only accepts the AWT EDT — never
 * the UI thread under Tao, so every `childStack` / `childSlot` built on `Dispatchers.Main` reported
 * `NotOnMainThreadException`. `decorated-window-tao` ships a Tao-aware provider, but only the first
 * provider on the classpath counts: with the Swing one removed it is the only candidate, whatever
 * the dependency order. No runtime reflection, so it holds under ProGuard and native-image too.
 */
@CacheableTransform
internal abstract class DecomposeMainThreadCheckerTransform : TransformAction<TransformParameters.None> {
    /** The jar being transformed; only `extensions-compose-*.jar` can be rewritten. */
    @get:Classpath
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val stripped =
            if (input.name.startsWith(EXTENSIONS_COMPOSE_PREFIX) && input.extension == "jar") {
                JarFile(input).use { jar ->
                    jar.getJarEntry(MAIN_THREAD_CHECKER_SERVICE)?.let { entry ->
                        stripSwingMainThreadChecker(jar.getInputStream(entry).use { it.readBytes() })
                    }
                }
            } else {
                null
            }
        if (stripped == null) {
            // Identity: hand the original artifact through without copying.
            outputs.file(inputArtifact)
            return
        }
        rewriteJar(input, outputs.file("${input.nameWithoutExtension}$PATCHED_JAR_SUFFIX.jar"), stripped)
    }

    private fun rewriteJar(
        input: File,
        output: File,
        serviceFile: ByteArray,
    ) {
        JarFile(input).use { jar ->
            JarOutputStream(output.outputStream().buffered()).use { out ->
                val entries = jar.entries().asSequence()
                // An emptied service file is dropped: no provider is left in it.
                val kept = entries.filterNot { it.name == MAIN_THREAD_CHECKER_SERVICE && serviceFile.isEmpty() }
                for (entry in kept) {
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(
                        if (entry.name == MAIN_THREAD_CHECKER_SERVICE) {
                            serviceFile
                        } else {
                            jar.getInputStream(entry).use { it.readBytes() }
                        },
                    )
                    out.closeEntry()
                }
            }
        }
    }
}

private const val EXTENSIONS_COMPOSE_PREFIX = "extensions-compose"
private const val PATCHED_JAR_SUFFIX = "-nucleus-tao"
private const val MAIN_THREAD_CHECKER_SERVICE =
    "META-INF/services/com.arkivanov.decompose.mainthread.MainThreadChecker"
private const val SWING_MAIN_THREAD_CHECKER =
    "com.arkivanov.decompose.extensions.compose.mainthread.SwingMainThreadChecker"

/**
 * The `MainThreadChecker` service file [content] without the Swing provider, or `null` when it
 * does not list it. An empty result means no provider is left and the entry is dropped. Comments
 * and every other provider are kept as they are.
 */
internal fun stripSwingMainThreadChecker(content: ByteArray): ByteArray? {
    val lines = content.toString(Charsets.UTF_8).lines()
    val kept = lines.filterNot { it.substringBefore('#').trim() == SWING_MAIN_THREAD_CHECKER }
    if (kept.size == lines.size) return null
    if (kept.none { it.substringBefore('#').isNotBlank() }) return ByteArray(0)
    return kept.joinToString("\n").toByteArray(Charsets.UTF_8)
}

/**
 * Marks jars that went through [DecomposeMainThreadCheckerTransform]. Runtime classpaths request
 * `true`, plain jars default to `false`, and the transform bridges the two.
 */
private val DECOMPOSE_CHECKER_STRIPPED: Attribute<Boolean> =
    Attribute.of("dev.nucleusframework.decompose-main-thread-checker", Boolean::class.javaObjectType)

/**
 * Registers [DecomposeMainThreadCheckerTransform] on every non-test runtime classpath of
 * [project] — what `run`, packaging, ProGuard and the GraalVM native-image classpath resolve.
 * Exclusions and KMP jar pinning are those of [configureLcdTextDefaultTransform], for the same
 * reasons. Compose Hot Reload runs are therefore not covered: they keep the Swing checker.
 */
internal fun configureDecomposeMainThreadCheckerTransform(project: Project) {
    project.dependencies.registerTransform(DecomposeMainThreadCheckerTransform::class.java) { spec ->
        spec.from.attribute(DECOMPOSE_CHECKER_STRIPPED, false)
        spec.to.attribute(DECOMPOSE_CHECKER_STRIPPED, true)
    }

    val isMultiplatform = project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
    val jarLibraryElements =
        project.objects.named(LibraryElements::class.java, LibraryElements.JAR)

    project.configurations.configureEach { configuration ->
        val name = configuration.name
        if (name.endsWith("RuntimeClasspath", ignoreCase = true) && !name.contains("Test", ignoreCase = true)) {
            val isAndroid = configuration.attributes.keySet().any { it.name.startsWith("com.android") }
            val isHotReload = name.contains("HotReload", ignoreCase = true)
            if (!isAndroid && !isHotReload) {
                configuration.attributes.attribute(DECOMPOSE_CHECKER_STRIPPED, true)
                if (isMultiplatform) {
                    configuration.attributes.attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        jarLibraryElements,
                    )
                }
            }
        }
    }

    project.dependencies.artifactTypes.configureEach { artifactType ->
        if (artifactType.name == "jar") {
            artifactType.attributes.attribute(DECOMPOSE_CHECKER_STRIPPED, false)
        }
    }
}
