package dev.nucleusframework.spellcheck

import org.junit.Assume.assumeFalse
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpellcheckNoopTest {
    @Test
    fun `macos platform branch is a no-op when the native library cannot load`() {
        val hostMac =
            System.getProperty("os.name", "").contains("Mac", ignoreCase = true)
        assumeFalse("macOS host loads the real NSSpellChecker engine", hostMac)
        SpellcheckSession(locale = Locale.US, osName = "Mac OS X").use { session ->
            assertFalse(session.isAvailable)
            assertFalse(session.check("hello"))
            assertTrue(session.suggest("helo").isEmpty())
            assertFalse(session.addToDictionary("helo"))
        }
    }

    @Test
    fun `unsupported platform is a no-op`() {
        SpellcheckSession(locale = Locale.US, osName = "FreeBSD").use { session ->
            assertFalse(session.isAvailable)
            assertFalse(session.check("hello"))
            assertTrue(session.suggest("helo").isEmpty())
            assertFalse(session.addToDictionary("helo"))
        }
    }

    @Test
    fun `windows platform branch is a no-op when the native library cannot load`() {
        val hostWindows =
            System.getProperty("os.name", "").contains("Windows", ignoreCase = true)
        assumeFalse("Windows host loads the real ISpellChecker engine", hostWindows)
        SpellcheckSession(locale = Locale.US, osName = "Windows 11").use { session ->
            assertFalse(session.isAvailable)
            assertFalse(session.check("hello"))
            assertEquals(emptyList(), session.suggest("helo"))
            assertFalse(session.addToDictionary("helo"))
        }
    }

    @Test
    fun `linux with missing dictionaries is a no-op`() {
        SpellcheckSession(
            locale = Locale.US,
            osName = "Linux",
            dictionaryDirectories = listOf(Path.of("/tmp/nucleus-spellcheck-missing-dicts")),
        ).use { session ->
            assertFalse(session.isAvailable)
            assertFalse(session.check("hello"))
            assertTrue(session.suggest("helo").isEmpty())
            assertFalse(session.addToDictionary("helo"))
        }
    }
}
