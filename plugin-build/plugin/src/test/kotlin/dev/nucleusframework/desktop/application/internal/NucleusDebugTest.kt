package dev.nucleusframework.desktop.application.internal

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NucleusDebugTest {
    private val project: Project = ProjectBuilder.builder().build()

    @Test
    fun `nothing requested adds no flag`() {
        assertTrue(nucleusDebugJvmArgs(applicationData(), null, project.logger).isEmpty())
    }

    @Test
    fun `the DSL maps to the runtime switches`() {
        val app = applicationData()
        app.nucleusOptimization = true
        app.debug.partialRedraw { it.tint = true }
        app.debug.recomposition {
            it.enabled = true
            it.top = 5
        }
        assertEquals(
            listOf(
                "-D$PARTIAL_REDRAW_TINT_PROPERTY=true",
                "-D$RECOMPOSITION_PROPERTY=true",
                "-D$RECOMPOSITION_TOP_PROPERTY=5",
            ),
            nucleusDebugJvmArgs(app, null, project.logger),
        )
    }

    @Test
    fun `the Gradle property adds to the DSL`() {
        val app = applicationData()
        app.nucleusOptimization = true
        app.debug.partialRedraw { it.tint = true }
        assertEquals(
            listOf(
                "-D$PARTIAL_REDRAW_TINT_PROPERTY=true",
                "-D$PARTIAL_REDRAW_STATS_PROPERTY=true",
                "-D$PARTIAL_REDRAW_VERIFY_PROPERTY=true",
            ),
            nucleusDebugJvmArgs(app, " stats, verify,tint ", project.logger),
        )
    }

    @Test
    fun `unknown switches are ignored`() {
        assertTrue(nucleusDebugJvmArgs(applicationData(), "overdraw,", project.logger).isEmpty())
    }

    @Test
    fun `partial redraw switches still pass while partial redraw is off`() {
        // Only warned about: the runtime falls back to full frames, nothing breaks.
        assertEquals(
            listOf("-D$PARTIAL_REDRAW_STATS_PROPERTY=true"),
            nucleusDebugJvmArgs(applicationData(), "stats", project.logger),
        )
    }

    private fun applicationData(): JvmApplicationData = project.objects.newInstance(JvmApplicationData::class.java)
}
