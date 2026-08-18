package dev.nucleusframework.core.runtime

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
        assertEquals(ExecutableType.ZIP, ExecutableRuntime.parseType("zip"))
        assertEquals(ExecutableType.TAR, ExecutableRuntime.parseType("tar"))
        assertEquals(ExecutableType.TAR, ExecutableRuntime.parseType("tar.gz"))
        assertEquals(ExecutableType.SEVEN_Z, ExecutableRuntime.parseType("7z"))
    }

    @Test
    fun `parses appimage as APPIMAGE not DEV`() {
        assertEquals(ExecutableType.APPIMAGE, ExecutableRuntime.parseType("appimage"))
        assertEquals(ExecutableType.APPIMAGE, ExecutableRuntime.parseType(".appimage"))
        assertEquals(ExecutableType.APPIMAGE, ExecutableRuntime.parseType("APPIMAGE"))
    }

    @Test
    fun `parses type variants`() {
        assertEquals(ExecutableType.EXE, ExecutableRuntime.parseType(".EXE"))
        assertEquals(ExecutableType.EXE, ExecutableRuntime.parseType("  .exe  "))
        assertEquals(ExecutableType.MSI, ExecutableRuntime.parseType(".msi"))
        assertEquals(ExecutableType.DMG, ExecutableRuntime.parseType(".dmg"))
        assertEquals(ExecutableType.PKG, ExecutableRuntime.parseType(".pkg"))
        assertEquals(ExecutableType.DEB, ExecutableRuntime.parseType(".deb"))
        assertEquals(ExecutableType.RPM, ExecutableRuntime.parseType(".rpm"))
        assertEquals(ExecutableType.APPX, ExecutableRuntime.parseType(".appx"))
        assertEquals(ExecutableType.SNAP, ExecutableRuntime.parseType(".snap"))
        assertEquals(ExecutableType.FLATPAK, ExecutableRuntime.parseType(".flatpak"))
        assertEquals(ExecutableType.ZIP, ExecutableRuntime.parseType(".zip"))
        assertEquals(ExecutableType.TAR, ExecutableRuntime.parseType(".tar.gz"))
        assertEquals(ExecutableType.SEVEN_Z, ExecutableRuntime.parseType(".7z"))
        assertEquals(ExecutableType.DEV, ExecutableRuntime.parseType("app-image"))
        assertEquals(ExecutableType.DEV, ExecutableRuntime.parseType("dev"))
        assertEquals(ExecutableType.DEV, ExecutableRuntime.parseType("development"))
    }

    @Test
    fun `parses every remaining alias`() {
        assertEquals(ExecutableType.PACMAN, ExecutableRuntime.parseType("pacman"))
        assertEquals(ExecutableType.PACMAN, ExecutableRuntime.parseType(".pacman"))
        assertEquals(ExecutableType.PACMAN, ExecutableRuntime.parseType(".pkg.tar.zst"))
        assertEquals(ExecutableType.ZIP, ExecutableRuntime.parseType("zip"))
        assertEquals(ExecutableType.NSIS_WEB, ExecutableRuntime.parseType("NSIS-WEB"))
        assertEquals(ExecutableType.PORTABLE, ExecutableRuntime.parseType(" PORTABLE "))
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

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "appimage")
            assertTrue(ExecutableRuntime.isAppImage())
            assertFalse(ExecutableRuntime.isDev())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "other")
            assertTrue(ExecutableRuntime.isDev())
            assertFalse(ExecutableRuntime.isMsi())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "exe")
            assertTrue(ExecutableRuntime.isExe())
            assertEquals(ExecutableType.EXE, ExecutableRuntime.type())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "nsis-web")
            assertTrue(ExecutableRuntime.isNsisWeb())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "portable")
            assertTrue(ExecutableRuntime.isPortable())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "appx")
            assertTrue(ExecutableRuntime.isAppX())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "dmg")
            assertTrue(ExecutableRuntime.isDmg())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "pkg")
            assertTrue(ExecutableRuntime.isPkg())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "deb")
            assertTrue(ExecutableRuntime.isDeb())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "flatpak")
            assertTrue(ExecutableRuntime.isFlatpak())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "pacman")
            assertTrue(ExecutableRuntime.isPacman())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "zip")
            assertTrue(ExecutableRuntime.isZip())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "tar.gz")
            assertTrue(ExecutableRuntime.isTar())

            System.setProperty(ExecutableRuntime.TYPE_PROPERTY, "7z")
            assertTrue(ExecutableRuntime.isSevenZ())
        } finally {
            restoreSystemProperty(ExecutableRuntime.TYPE_PROPERTY, previousValue)
        }
    }

    @Test
    fun `graalvm native-image flag and marker version are unset on HotSpot`() {
        assertFalse(ExecutableRuntime.isGraalVmNativeImage)
        // Dev/test JVMs have no plugin-written marker next to the java executable.
        val marker = ExecutableRuntime.markerVersion()
        if (marker != null) {
            assertTrue(marker.isNotBlank())
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
