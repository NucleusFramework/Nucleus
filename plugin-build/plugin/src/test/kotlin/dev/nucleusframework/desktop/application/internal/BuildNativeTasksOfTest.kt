package dev.nucleusframework.desktop.application.internal

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildNativeTasksOfTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun selectsBuildNativeTasksWithoutCreatingTheOthers() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        project.tasks.register("buildNativeLinux")
        // What the Kotlin plugin does with an iOS task on a Linux arm64 host.
        project.tasks.register("kspKotlinIosArm64") { error("Unknown host target: linux aarch64") }

        val tasks = buildNativeTasksOf(project)

        assertEquals(listOf("buildNativeLinux"), tasks.map { it.name })
    }
}
