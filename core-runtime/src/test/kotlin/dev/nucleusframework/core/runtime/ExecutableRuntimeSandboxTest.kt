package dev.nucleusframework.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutableRuntimeSandboxTest {
    @Test
    fun `app sandbox container id marks the process sandboxed whatever the format`() {
        assertTrue(ExecutableRuntime.isSandboxed(ExecutableType.PKG, "com.example.app"))
        assertTrue(ExecutableRuntime.isSandboxed(ExecutableType.DMG, "com.example.app"))
        assertTrue(ExecutableRuntime.isSandboxed(ExecutableType.DEV, "com.example.app"))
    }

    @Test
    fun `a pkg without the app sandbox is not sandboxed`() {
        assertFalse(ExecutableRuntime.isSandboxed(ExecutableType.PKG, null))
        assertFalse(ExecutableRuntime.isSandboxed(ExecutableType.PKG, ""))
    }

    @Test
    fun `appx and flatpak are sandboxed by construction`() {
        assertTrue(ExecutableRuntime.isSandboxed(ExecutableType.APPX, null))
        assertTrue(ExecutableRuntime.isSandboxed(ExecutableType.FLATPAK, null))
    }

    @Test
    fun `direct distribution formats are not sandboxed`() {
        val direct =
            listOf(
                ExecutableType.DMG,
                ExecutableType.NSIS,
                ExecutableType.DEB,
                ExecutableType.APPIMAGE,
                ExecutableType.DEV,
            )
        for (type in direct) {
            assertFalse(type.name, ExecutableRuntime.isSandboxed(type, null))
        }
    }
}
