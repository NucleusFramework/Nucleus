package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import javax.inject.Inject

/**
 * Represents an additional launcher configuration for the application.
 *
 * This allows defining multiple entry points or distinct configurations
 * (e.g., with different arguments, main classes, or icons) for the same application.
 *
 * @property name The name of the additional launcher. This will be used to generate the launcher executable.
 */
abstract class AdditionalLauncher @Inject constructor(
    @get:Input
    val name: String
) {
    /**
     * The version of the application for this specific launcher.
     */
    @get:Input
    @get:Optional
    abstract var appVersion: String?

    /**
     * The Java module name if the application uses JPMS.
     */
    @get:Input
    @get:Optional
    abstract var module: String?

    /**
     * The fully qualified name of the main class to be executed by this launcher.
     */
    @get:Input
    @get:Optional
    abstract var mainClass: String?

    /**
     * The main JAR file to be executed.
     */
    @get:Input
    @get:Optional
    abstract var mainJar: String?

    /**
     * The JVM arguments to be passed when launching the application.
     */
    // Public DSL property: a `var` of a mutable list is kept for source compatibility.
    @Suppress("DoubleMutabilityForCollection")
    @get:Input
    @get:Optional
    abstract var jvmArgs: MutableList<String>?

    /**
     * Appends the given options to the JVM arguments.
     *
     * @param options The JVM options to append.
     */
    fun jvmArgs(vararg options: String) {
        val list = jvmArgs ?: mutableListOf<String>().also { jvmArgs = it }
        list.addAll(options)
    }

    /**
     * The application arguments to be passed to the main class when launching.
     */
    // Public DSL property: a `var` of a mutable list is kept for source compatibility.
    @Suppress("DoubleMutabilityForCollection")
    @get:Input
    @get:Optional
    abstract var args: MutableList<String>?

    /**
     * Appends the given arguments to the application arguments.
     *
     * @param args The arguments to append.
     */
    fun args(vararg args: String) {
        val list = this@AdditionalLauncher.args ?: mutableListOf<String>().also { this@AdditionalLauncher.args = it }
        list.addAll(args)
    }

    /**
     * The path to the icon file for this launcher.
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val icon: RegularFileProperty

    /**
     * Specifies whether a Windows console should be created when launching the application.
     * Only applicable on Windows platform.
     */
    @get:Input
    @get:Optional
    abstract var winConsole: Boolean?
}
