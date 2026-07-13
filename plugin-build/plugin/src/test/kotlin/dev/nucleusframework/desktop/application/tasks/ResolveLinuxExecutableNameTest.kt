package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in issue #244: the snap name must be overridable independently of packageName.
 * electron-builder 26.x derives the snap name from the Linux executableName, so a
 * [dev.nucleusframework.desktop.application.dsl.SnapSettings.name] override must win for the
 * Snap target and be ignored for every other target.
 */
class ResolveLinuxExecutableNameTest {
    @Test
    fun `snap name overrides executableName for the snap target`() {
        assertEquals(
            "my-cool-app",
            resolveLinuxExecutableName(TargetFormat.Snap, snapName = "my-cool-app", executableName = "mypkg"),
        )
    }

    @Test
    fun `snap name is ignored for non-snap targets`() {
        assertEquals(
            "mypkg",
            resolveLinuxExecutableName(TargetFormat.Deb, snapName = "my-cool-app", executableName = "mypkg"),
        )
    }

    @Test
    fun `blank snap name falls back to executableName`() {
        assertEquals(
            "mypkg",
            resolveLinuxExecutableName(TargetFormat.Snap, snapName = "  ", executableName = "mypkg"),
        )
    }

    @Test
    fun `null snap name falls back to executableName for the snap target`() {
        assertEquals(
            "mypkg",
            resolveLinuxExecutableName(TargetFormat.Snap, snapName = null, executableName = "mypkg"),
        )
    }

    @Test
    fun `null executableName and no snap name resolves to null`() {
        assertNull(resolveLinuxExecutableName(TargetFormat.Snap, snapName = null, executableName = null))
    }
}
