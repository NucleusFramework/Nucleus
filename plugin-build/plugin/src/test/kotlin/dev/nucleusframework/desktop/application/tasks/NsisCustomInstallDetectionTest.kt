package dev.nucleusframework.desktop.application.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsisCustomInstallDetectionTest {
    private val macro = AbstractElectronBuilderPackageTask.CUSTOM_INSTALL_MACRO

    @Test
    fun `a customInstall declaration is detected`() {
        assertTrue(macro.containsMatchIn("!macro customInstall\n  DetailPrint \"x\"\n!macroend"))
        assertTrue(macro.containsMatchIn("!macro   customInstall\r\n!macroend"))
    }

    @Test
    fun `customInstallMode and mere mentions are not a customInstall declaration`() {
        val installModeOnly = "!macro customInstallMode\n  StrCpy \$isForceCurrentInstall \"1\"\n!macroend"
        assertFalse(macro.containsMatchIn(installModeOnly))
        assertFalse(macro.containsMatchIn("; see customInstall in the electron-builder docs"))
        assertFalse(macro.containsMatchIn("!insertmacro customInstall"))
    }
}
