package dev.nucleusframework.spellcheck

import org.junit.Assume.assumeFalse
import java.nio.file.Files
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
            assertEquals(Locale.US, session.locale)
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

    @Test
    fun `darwin alias and nux alias pick the matching platform branch`() {
        val isolated = Files.createTempFile("nucleus-spellcheck-darwin-", ".dic")
        SpellcheckSession(
            locale = Locale.US,
            osName = "Darwin",
            userDictionaryFile = isolated,
        ).use { darwin ->
            val hostMac = System.getProperty("os.name", "").contains("Mac", ignoreCase = true)
            if (hostMac) {
                SpellcheckSession(locale = Locale.US, userDictionaryFile = isolated).use { host ->
                    assertEquals(host.isAvailable, darwin.isAvailable)
                    assertEquals(host.dictionaryTag, darwin.dictionaryTag)
                }
            } else {
                assertFalse(darwin.isAvailable)
            }
        }
        SpellcheckSession(
            locale = Locale.US,
            osName = "GNU/Linux",
            dictionaryDirectories = listOf(Path.of("/tmp/nucleus-spellcheck-missing-nux")),
        ).use { linux ->
            assertFalse(linux.isAvailable)
            assertEquals(null, linux.dictionaryTag)
        }
    }

    @Test
    fun `empty and blank words never check or persist on a no-op session`() {
        SpellcheckSession(locale = Locale.US, osName = "FreeBSD").use { session ->
            assertEquals(null, session.dictionaryTag)
            assertFalse(session.check(""))
            assertFalse(session.check("   "))
            assertEquals(emptyList(), session.suggest(""))
            assertFalse(session.addToDictionary(""))
            assertFalse(session.addToDictionary(" \t"))
            assertEquals(emptyList(), session.misspellings("helo world"))
            session.close()
            assertFalse(session.isAvailable)
            assertFalse(session.check("hello"))
        }
    }

    @Test
    fun `linux with dummy dictionaries stays a no-op without Hunspell`() {
        val dir = java.nio.file.Files.createTempDirectory("nucleus-spellcheck-dummy-")
        try {
            java.nio.file.Files
                .writeString(dir.resolve("en_US.aff"), "SET UTF-8")
            java.nio.file.Files
                .writeString(dir.resolve("en_US.dic"), "1\nhello")
            SpellcheckSession(
                locale = Locale.US,
                osName = "Linux",
                dictionaryDirectories = listOf(dir),
            ).use { session ->
                val hostLinux =
                    System.getProperty("os.name", "").contains("Linux", ignoreCase = true)
                if (!hostLinux) {
                    assertFalse(session.isAvailable)
                    assertFalse(session.check("hello"))
                    assertEquals(emptyList(), session.suggest("helo"))
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
