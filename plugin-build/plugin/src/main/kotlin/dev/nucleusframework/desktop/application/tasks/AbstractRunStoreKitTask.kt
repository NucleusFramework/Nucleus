package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Runs the app as a development `.app` bundle served by StoreKit Testing (macOS), for
 * `nucleus.mac-app-store`: in-app purchases against a `.storekit` file, with no App Store Connect
 * listing, Apple account or certificate.
 *
 * StoreKit only answers an app bundle that is "installed for development": signed with
 * `com.apple.security.get-task-allow` (an ad-hoc signature is enough) and known to
 * LaunchServices. The bundle is a jpackage shell made once under [shellDir], carrying the app's
 * bundle id; every run only rewrites its launcher configuration to the current classpath and
 * JVM arguments (those of `run`), re-signs it and launches it, so a rebuild never repackages.
 */
@DisableCachingByDefault(because = "Runs the application, not a cacheable build step")
abstract class AbstractRunStoreKitTask : AbstractNucleusTask() {
    /** The app's runtime classpath, as `run` uses it. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Input
    abstract val appArgs: ListProperty<String>

    /** The JDK whose `jpackage` builds the shell and whose runtime it embeds. */
    @get:Input
    abstract val javaHome: Property<String>

    @get:Input
    abstract val bundleId: Property<String>

    @get:Input
    abstract val launcherName: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val storeKitConfiguration: RegularFileProperty

    /** Where the development `.app` lives between runs. */
    @get:Internal
    abstract val shellDir: DirectoryProperty

    /** Extra environment for the app, e.g. the updater test switches. */
    @get:Input
    abstract val environment: MapProperty<String, String>

    /** Prepares the development bundle if needed, points it at this build and runs it. */
    @TaskAction
    fun run() {
        val configuration =
            storeKitConfiguration.orNull?.asFile
                ?: error(
                    "runStoreKit needs a StoreKit configuration: set " +
                        "nativeDistributions { macOS { storeKitConfiguration.set(file(\"Products.storekit\")) } } " +
                        "(Xcode › File › New › StoreKit Configuration File)",
                )
        val root = shellDir.get().asFile
        val name = launcherName.get()
        val app = root.resolve("$name.app")
        val identity = "javaHome=${javaHome.get()}\nbundleId=${bundleId.get()}\nname=$name\n"
        val marker = root.resolve("shell.properties")
        val fresh = !app.isDirectory || marker.takeIf { it.isFile }?.readText() != identity
        if (fresh) {
            // Leave no registration of the deleted bundle behind in LaunchServices.
            root.listFiles { file -> file.extension == "app" }?.forEach { command(LSREGISTER, "-u", it.absolutePath) }
            root.deleteRecursively()
            createShell(root, name)
            marker.writeText(identity)
        }
        writeLauncherConfiguration(app, name, configuration)
        sign(app, root, deep = fresh)
        command(LSREGISTER, "-f", app.absolutePath)

        logger.lifecycle("Running $name as ${bundleId.get()} with StoreKit Testing (${configuration.name})")
        val contents = app.resolve("Contents")
        execOperations
            .exec { spec ->
                spec.workingDir(contents)
                spec.executable(contents.resolve("MacOS/$name").absolutePath)
                spec.environment(environment.get())
            }.assertNormalExitValue()
    }

    // A jpackage app image around an empty jar: only its launcher, runtime and Info.plist are used.
    private fun createShell(
        root: File,
        name: String,
    ) {
        logger.lifecycle("Creating the StoreKit development app (once): $root")
        val stub = root.resolve("stub").apply { mkdirs() }
        val manifest = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }
        JarOutputStream(stub.resolve("stub.jar").outputStream(), manifest).close()
        command(
            File(javaHome.get(), "bin/jpackage").absolutePath,
            "--type", "app-image",
            "--name", name,
            "--dest", root.absolutePath,
            "--input", stub.absolutePath,
            "--main-jar", "stub.jar",
            "--main-class", mainClass.get(),
            "--mac-package-identifier", bundleId.get(),
            // The whole JDK: the classpath may need any module.
            "--runtime-image", javaHome.get(),
        )
        root.resolve("$name.app/Contents/app/stub.jar").delete()
        stub.deleteRecursively()
    }

    private fun writeLauncherConfiguration(
        app: File,
        name: String,
        configuration: File,
    ) {
        val text =
            buildString {
                appendLine("[Application]")
                appendLine("app.classpath=${classpath.files.joinToString(File.pathSeparator) { it.absolutePath }}")
                appendLine("app.mainclass=${mainClass.get()}")
                appendLine()
                appendLine("[JavaOptions]")
                (jvmArgs.get() + "-D$STOREKIT_CONFIGURATION_PROPERTY=${configuration.absolutePath}")
                    .forEach { appendLine("java-options=$it") }
                if (appArgs.get().isNotEmpty()) {
                    appendLine()
                    appendLine("[ArgOptions]")
                    appArgs.get().forEach { appendLine("arguments=$it") }
                }
            }
        app.resolve("Contents/app/$name.cfg").writeText(text)
    }

    // The first signature covers the embedded runtime; later ones only reseal the rewritten
    // configuration, which takes a second.
    private fun sign(
        app: File,
        root: File,
        deep: Boolean,
    ) {
        val entitlements =
            root.resolve("development.entitlements").apply {
                writeText(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0"><dict><key>com.apple.security.get-task-allow</key><true/></dict></plist>
                    """.trimIndent(),
                )
            }
        val args = listOfNotNull("/usr/bin/codesign", "--force", "--deep".takeIf { deep }, "--sign", "-")
        command(args + listOf("--entitlements", entitlements.absolutePath, app.absolutePath))
    }

    private fun command(vararg args: String) = command(args.toList())

    private fun command(args: List<String>) {
        execOperations.exec { it.commandLine(args) }.assertNormalExitValue()
    }

    private companion object {
        /** Read by `nucleus.mac-app-store` (`DevConfiguration`). */
        const val STOREKIT_CONFIGURATION_PROPERTY = "nucleus.macAppStore.storeKitConfiguration"
        const val LSREGISTER =
            "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
    }
}
