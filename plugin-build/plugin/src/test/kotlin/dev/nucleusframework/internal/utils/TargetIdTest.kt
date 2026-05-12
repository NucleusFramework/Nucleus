package dev.nucleusframework.internal.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetIdTest {
    @Test
    fun `target id combines os and architecture ids`() {
        assertEquals("linux-x64", Target(OS.Linux, Arch.X64).id)
        assertEquals("macos-arm64", Target(OS.MacOS, Arch.Arm64).id)
    }
}
