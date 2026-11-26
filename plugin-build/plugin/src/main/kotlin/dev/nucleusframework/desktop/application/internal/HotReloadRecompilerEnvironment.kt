package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentOS
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec

/**
 * Makes Compose Hot Reload's auto mode (`hotRun --auto`) recompile on Windows machines that set
 * `NoDefaultCurrentDirectoryInExePath` (a documented hardening that keeps `cmd` from resolving a
 * bare program name in the current directory). The devtools recompiler, a child of the app
 * process, starts the continuous build as a bare `gradlew.bat` from the build root; with the
 * variable set, `cmd` answers "'gradlew.bat' is not recognized" and nothing is ever recompiled.
 * The variable is dropped from the hot-reload run's environment only, which the recompiler
 * inherits; packaged apps and every other task keep it.
 */
internal fun letHotReloadRecompilerFindGradlew(project: Project) {
    if (currentOS != OS.Windows) return
    project.tasks.withType(JavaExec::class.java).configureEach { task ->
        if (!task.isHotReloadJavaExec()) return@configureEach
        // Environment keys are case-insensitive on Windows.
        task.environment.keys
            .filter { it.equals(NO_DEFAULT_CURRENT_DIRECTORY_IN_EXE_PATH, ignoreCase = true) }
            .forEach { task.environment.remove(it) }
    }
}

private const val NO_DEFAULT_CURRENT_DIRECTORY_IN_EXE_PATH = "NoDefaultCurrentDirectoryInExePath"
