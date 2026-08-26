package dev.nucleusframework.updater

import dev.nucleusframework.updater.exception.ParseException
import dev.nucleusframework.updater.internal.YamlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YamlParserEdgeCasesTest {
    @Test
    fun `blank lines and unknown top-level keys are ignored`() {
        val yaml =
            """
            version: 3.0.0

            path: leftover.bin
            sha512: ignored
            files:
              - url: leftover.bin
                sha512: hash
                size: 9

            releaseDate: '2026-01-01T00:00:00.000Z'
            extra: still-ignored
            """.trimIndent()

        val result = YamlParser.parse(yaml)
        assertEquals("3.0.0", result.version)
        assertEquals("2026-01-01T00:00:00.000Z", result.releaseDate)
        assertEquals(1, result.files.size)
        assertEquals("leftover.bin", result.files[0].url)
        assertEquals(9L, result.files[0].size)
    }

    @Test
    fun `dash url without a space after the dash is accepted`() {
        val yaml =
            """
            version: 1.0.0
            files:
              -url: App.exe
                sha512: abc
                size: 12
            releaseDate: '2026-01-01T00:00:00.000Z'
            """.trimIndent()

        val result = YamlParser.parse(yaml)
        assertEquals("App.exe", result.files.single().url)
        assertEquals("abc", result.files.single().sha512)
        assertEquals(12L, result.files.single().size)
    }

    @Test
    fun `non-numeric size and blockMapSize become zero or null`() {
        val yaml =
            """
            version: 1.0.0
            files:
              - url: App.deb
                sha512: abc
                size: not-a-number
                blockMapSize: nope
            releaseDate: today
            """.trimIndent()

        val result = YamlParser.parse(yaml)
        assertEquals(0L, result.files.single().size)
        assertNull(result.files.single().blockMapSize)
        assertEquals("today", result.releaseDate)
    }

    @Test
    fun `missing releaseDate stays empty`() {
        val yaml =
            """
            version: 9.9.9
            files:
              - url: App.zip
                sha512: z
                size: 1
            """.trimIndent()

        val result = YamlParser.parse(yaml)
        assertEquals("9.9.9", result.version)
        assertEquals("", result.releaseDate)
    }

    @Test
    fun `empty version value is treated as missing`() {
        val yaml =
            """
            version:
            files:
              - url: App.zip
                sha512: z
                size: 1
            """.trimIndent()

        try {
            YamlParser.parse(yaml)
            org.junit.Assert.fail("expected ParseException")
        } catch (e: ParseException) {
            assertEquals("Missing 'version' field in YAML metadata", e.message)
        }
    }

    @Test
    fun `file entries listed after another top-level key are flushed`() {
        val yaml =
            """
            files:
              - url: first.bin
                sha512: a
                size: 1
              - url: second.bin
                sha512: b
                size: 2
            version: 0.1.0
            """.trimIndent()

        val result = YamlParser.parse(yaml)
        assertEquals(listOf("first.bin", "second.bin"), result.files.map { it.url })
    }
}
