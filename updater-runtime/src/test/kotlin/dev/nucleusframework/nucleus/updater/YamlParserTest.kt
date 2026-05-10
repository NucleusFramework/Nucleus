package dev.nucleusframework.nucleus.updater

import dev.nucleusframework.nucleus.updater.exception.ParseException
import dev.nucleusframework.nucleus.updater.internal.YamlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YamlParserTest {
    @Test
    fun `parse complete yaml`() {
        val yaml =
            """
            version: 1.2.3
            files:
              - url: App-1.2.3-linux-amd64.deb
                sha512: abc123def456
                size: 12345678
                blockMapSize: 1234
              - url: App-1.2.3-linux-arm64.deb
                sha512: xyz789
                size: 87654321
            path: App-1.2.3-linux-amd64.deb
            sha512: abc123def456
            releaseDate: '2025-01-15T10:30:00.000Z'
            """.trimIndent()

        val result = YamlParser.parse(yaml)

        assertEquals("1.2.3", result.version)
        assertEquals("2025-01-15T10:30:00.000Z", result.releaseDate)
        assertEquals(2, result.files.size)

        val file1 = result.files[0]
        assertEquals("App-1.2.3-linux-amd64.deb", file1.url)
        assertEquals("abc123def456", file1.sha512)
        assertEquals(12345678L, file1.size)
        assertEquals(1234L, file1.blockMapSize)

        val file2 = result.files[1]
        assertEquals("App-1.2.3-linux-arm64.deb", file2.url)
        assertEquals("xyz789", file2.sha512)
        assertEquals(87654321L, file2.size)
        assertNull(file2.blockMapSize)
    }

    @Test
    fun `parse single file yaml`() {
        val yaml =
            """
            version: 2.0.0
            files:
              - url: App-2.0.0.dmg
                sha512: checksum123
                size: 50000000
            path: App-2.0.0.dmg
            sha512: checksum123
            releaseDate: '2025-06-01T00:00:00.000Z'
            """.trimIndent()

        val result = YamlParser.parse(yaml)

        assertEquals("2.0.0", result.version)
        assertEquals(1, result.files.size)
        assertEquals("App-2.0.0.dmg", result.files[0].url)
    }

    @Test
    fun `parse yaml with quoted values`() {
        val yaml =
            """
            version: '1.0.0'
            files:
              - url: "App-1.0.0.exe"
                sha512: 'hash123'
                size: 100
            releaseDate: "2025-01-01T00:00:00.000Z"
            """.trimIndent()

        val result = YamlParser.parse(yaml)

        assertEquals("1.0.0", result.version)
        assertEquals("App-1.0.0.exe", result.files[0].url)
        assertEquals("hash123", result.files[0].sha512)
    }

    @Test(expected = ParseException::class)
    fun `parse yaml without version throws`() {
        val yaml =
            """
            files:
              - url: App.deb
                sha512: hash
                size: 100
            releaseDate: '2025-01-01T00:00:00.000Z'
            """.trimIndent()

        YamlParser.parse(yaml)
    }

    @Test
    fun `parse yaml with empty files list`() {
        val yaml =
            """
            version: 1.0.0
            files:
            releaseDate: '2025-01-01T00:00:00.000Z'
            """.trimIndent()

        val result = YamlParser.parse(yaml)

        assertEquals("1.0.0", result.version)
        assertEquals(0, result.files.size)
    }

    @Test
    fun `parse real electron-builder format`() {
        val yaml =
            """
            version: 1.0.0
            files:
              - url: NucleusDemo-1.0.0-linux-amd64.deb
                sha512: VkJl1gDqcBHYbYhMb0HRIHwh/wCW3JQ8PJfRkJdhvE1ybWBYQlpHDFPot3nMBxyzMOcaVCBN0wBq9GJHUxxtSQ==
                size: 68461240
                blockMapSize: 71732
              - url: NucleusDemo-1.0.0-linux-arm64.deb
                sha512: qJ8a5gFDCwv0R2rW6lM3kN9pQ7sT4uX1yB5eH8jK0mN3pR6sV9wZ2cF5hJ8kL1nQ4rT7vX0yB3dG6iK9mP2sA==
                size: 65432100
            path: NucleusDemo-1.0.0-linux-amd64.deb
            sha512: VkJl1gDqcBHYbYhMb0HRIHwh/wCW3JQ8PJfRkJdhvE1ybWBYQlpHDFPot3nMBxyzMOcaVCBN0wBq9GJHUxxtSQ==
            releaseDate: '2025-01-15T10:30:00.000Z'
            """.trimIndent()

        val result = YamlParser.parse(yaml)

        assertEquals("1.0.0", result.version)
        assertEquals(2, result.files.size)
        assertEquals(68461240L, result.files[0].size)
        assertEquals(71732L, result.files[0].blockMapSize)
    }
}
