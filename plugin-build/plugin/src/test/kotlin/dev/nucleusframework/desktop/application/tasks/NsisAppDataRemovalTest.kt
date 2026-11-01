package dev.nucleusframework.desktop.application.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NsisAppDataRemovalTest {
    @Test
    fun `plain file names are kept verbatim`() {
        assertEquals("ZstdDemo", appDataDirNameOrNull("ZstdDemo"))
        assertEquals("My App", appDataDirNameOrNull("My App"))
        assertEquals("com.example.app", appDataDirNameOrNull("com.example.app"))
    }

    @Test
    fun `names that would escape the app data directory are refused`() {
        // Each of these would make `RMDir /r "$APPDATA\<name>"` hit %APPDATA% itself or beyond.
        for (unsafe in listOf("", ".", "..", "a\\b", "a/b", "..\\Local", "C:", "name.", "name ")) {
            assertNull("'$unsafe' must be refused", appDataDirNameOrNull(unsafe))
        }
    }

    @Test
    fun `removal mirrors electron-builder's delete-app-data condition`() {
        val script = buildString { appendAppDataRemoval("My App") }

        assertTrue(script.contains("RMDir /r \"\$APPDATA\\My App\""))
        assertTrue(script.contains("\${GetOptions} \$R0 \"--delete-app-data\" \$R1"))
        assertTrue(script.contains("\${ifNot} \${isUpdated}"))
        assertTrue(script.contains("SetShellVarContext current"))
    }

    @Test
    fun `dollar signs are escaped for NSIS`() {
        val script = buildString { appendAppDataRemoval("A\$B") }

        assertTrue(script.contains("RMDir /r \"\$APPDATA\\A\$\$B\""))
    }
}
