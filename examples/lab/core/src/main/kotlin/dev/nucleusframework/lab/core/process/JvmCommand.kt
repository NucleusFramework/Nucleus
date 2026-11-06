package dev.nucleusframework.lab.core.process

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.fixture.FIXTURE_PROPERTY
import java.io.File
import java.lang.management.ManagementFactory

/** Program argument that runs a fixture where only arguments reach the app (installed launchers, launchd, Task Scheduler). */
const val FIXTURE_ARGUMENT: String = "--lab-fixture="

/**
 * How to start this very app again: the same JVM, flags and classpath in a dev run, the
 * packaged launcher otherwise. Every relaunch of the Lab (fixtures, second instances,
 * scheduled tasks, launch agents) builds its command here.
 */
object JvmCommand {
    /** The main class, from `-Dlab.mainClass` (set by the app build) or the JVM's own command line. */
    val mainClass: String?
        get() =
            System.getProperty("lab.mainClass")
                ?: System.getProperty("sun.java.command")?.substringBefore(' ')?.takeIf { it.isNotBlank() }

    val javaExecutable: String
        get() =
            File(
                System.getProperty("java.home"),
                "bin/" + if (Platform.Current == Platform.Windows) "java.exe" else "java",
            ).path

    /** The packaged launcher (or native image) this process was started from, if any. */
    val launcher: String?
        get() =
            if (ExecutableRuntime.isDev() && !ExecutableRuntime.isGraalVmNativeImage) {
                null
            } else {
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null)
            }

    fun availability(): Availability =
        when {
            ExecutableRuntime.isGraalVmNativeImage ->
                Availability.of(launcher != null) { "the native executable cannot tell its own path" }
            mainClass == null -> Availability.Unavailable("cannot tell the main class; set -Dlab.mainClass")
            !File(javaExecutable).canExecute() -> Availability.Unavailable("no java executable at $javaExecutable")
            else -> Availability.Available
        }

    /** This JVM's flags minus debugger agents and the Lab's own one-shot switches. */
    fun inheritedJvmArgs(): List<String> =
        ManagementFactory.getRuntimeMXBean().inputArguments.filterNot { arg ->
            arg.startsWith("-agentlib") ||
                arg.startsWith("-javaagent") ||
                arg.startsWith("-D$FIXTURE_PROPERTY=") ||
                arg.startsWith("-Dlab.probe=")
        }

    /** Same JVM, same classpath; [extraJvmArgs] come last so they win over inherited ones. */
    fun dev(
        extraJvmArgs: List<String> = emptyList(),
        args: List<String> = emptyList(),
    ): List<String> =
        listOf(javaExecutable) + inheritedJvmArgs() + extraJvmArgs +
            listOf("-cp", System.getProperty("java.class.path"), checkNotNull(mainClass) { "unknown main class" }) +
            args

    /** The packaged launcher when there is one, the dev command otherwise (no extra JVM flags possible there). */
    fun self(args: List<String> = emptyList()): List<String> = launcher?.let { listOf(it) + args } ?: dev(args = args)

    /**
     * A fixture: by system property on a JVM (variant flags apply), by argument from a native image
     * (only its `-D` flags carry over).
     */
    fun fixture(
        id: String,
        extraJvmArgs: List<String> = emptyList(),
        args: List<String> = emptyList(),
    ): List<String> =
        if (ExecutableRuntime.isGraalVmNativeImage) {
            // No JVM to relaunch: the image itself, which reads -D options at run time but no other JVM flag.
            listOf(checkNotNull(launcher)) + extraJvmArgs.filter { it.startsWith("-D") } + fixtureArguments(id) + args
        } else {
            dev(extraJvmArgs + "-D$FIXTURE_PROPERTY=$id", args)
        }

    /** The arguments a packaged launcher needs to run fixture [id]. */
    fun fixtureArguments(id: String): List<String> = listOf("$FIXTURE_ARGUMENT$id")
}
