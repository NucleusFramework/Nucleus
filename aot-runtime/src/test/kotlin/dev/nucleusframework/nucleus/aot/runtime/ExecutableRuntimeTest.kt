package dev.nucleusframework.nucleus.aot.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutableRuntimeTest {
    @Test
    fun `parses known executable types`() {
        assertEquals(ExecutableType.EXE, ExecutableRuntime.parseType("exe"))
        assertEquals(ExecutableType.MSI, ExecutableRuntime.parseType("msi"))
        assertEquals(ExecutableType.DMG, ExecutableRuntime.parseType("dmg"))
        assertEquals(ExecutableType.PKG, ExecutableRuntime.parseType("pkg"))
        assertEquals(ExecutableType.DEB, ExecutableRuntime.parseType("deb"))
        assertEquals(ExecutableType.RPM, ExecutableRuntime.parseType("rpm"))
    }

    @Test
    fun `parses new formats`() {
        assertEquals(ExecutableType.NSIS, ExecutableRuntime.parseType("nsis"))
        assertEquals(ExecutableType.NSIS_WEB, ExecutableRuntime.parseType("nsis-web"))
        assertEquals(ExecutableType.PORTABLE, ExecutableRuntime.parseType("portable"))
        assertEquals(ExecutableType.APPX, ExecutableRuntime.parseType("appx"))
        assertEquals(ExecutableType.SNAP, ExecutableRuntime.parseType("snap"))
        assertEquals(ExecutableType.FLATPAK, ExecutableRuntime.parseType("flatpak"))
        assertEquals(ExecutableType.APPIMAGE, ExecutableRuntime.parseType("appimage"))
        assertEquals(ExecutableType.ZIP, ExecutableRuntime.parseType("zip"))
        assertEquals(ExecutableType.TAR, ExecutableRuntime.parseType("tar"))
        assertEquals(ExecutableType.TAR, ExecutableRuntime.parseType("tar.gz"))
        assertEquals(ExecutableType.SEVEN_Z, ExecutableRuntime.parseType("7z"))
    }

    @Test
    fun `parses type variants`() {
        assertEquals(ExecutableType.EXE, ExecutableRuntime.parseType(".EXE"))
        assertEquals(ExecutableType.APPX, ExecutableRuntime.parseType(".appx"))
        assertEquals(ExecutableType.SNAP, ExecutableRuntime.parseType(".snap"))
        assertEquals(ExecutableType.TAR, ExecutableRuntime.parseType(".tar.gz"))
        assertEquals(ExecutableType.SEVEN_Z, ExecutableRuntime.parseType(".7z"))
        assertEquals(ExecutableType.DEV, ExecutableRuntime.parseType("app-image"))
    }

    @Test
    fun `returns dev for empty or unknown values`() {
        assertEquals(ExecutableType.DEV, ExecutableRuntime.parseType(null))
        assertEquals(ExecutableType.DEV, ExecutableRuntime.parseType(""))
        assertEquals(ExecutableType.DEV, ExecutableRuntime.parseType("unknown"))
    }

    @Test
    fun `reads type from custom system property`() {
        val propertyName = "nucleus.test.executable.type"
        val previousValue = System.getProperty(propertyName)
        try {
            System.setProperty(propertyName, "msi")
            assertEquals(ExecutableType.MSI, ExecutableRuntime.type(propertyName))
        } finally {
            restoreSystemProperty(propertyName, previousValue)
        }
    }

    @Test
    fun `boolean helpers expose target type and dev fallback`() {
        val previousValue = System.getProperty(ExecutableRuntime.TYPE_PROPERTY)
        try {
            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "msi")
            assertTrue(ExecutableRuntime.isMsi())
            assertFalse(ExecutableRuntime.isDev())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "rpm")
            assertTrue(ExecutableRuntime.isRpm())
            assertFalse(ExecutableRuntime.isDeb())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "nsis")
            assertTrue(ExecutableRuntime.isNsis())
            assertFalse(ExecutableRuntime.isExe())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "snap")
            assertTrue(ExecutableRuntime.isSnap())
            assertFalse(ExecutableRuntime.isDev())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "other")
            assertTrue(ExecutableRuntime.isDev())
            assertFalse(ExecutableRuntime.isMsi())
        } finally {
            restoreSystemProperty(ExecutableRuntime.TYPE_PROPERTY, previousValue)
        }
    }

    private fun restoreSystemProperty(
        name: String,
        value: String?,
    ) {
        if (value == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, value)
        }
    }
}
