package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentOS
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A notarization task follows its package task: off macOS the package task is disabled and leaves
 * `inputDir` uncreated, so a notarization task left enabled there fails validation as soon as the
 * credentials make its `onlyIf` pass, which is what a release matrix sharing one environment does.
 */
class AbstractNotarizationTaskTest {
    @Test
    fun `dmg notarization runs only on macOS`() {
        val task =
            ProjectBuilder
                .builder()
                .build()
                .tasks
                .create("notarizeDmg", AbstractNotarizationTask::class.java, TargetFormat.Dmg)

        assertEquals(currentOS == OS.MacOS, task.enabled)
    }
}
