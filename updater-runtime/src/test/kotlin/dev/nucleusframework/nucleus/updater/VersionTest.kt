package dev.nucleusframework.nucleus.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun `parse simple version`() {
        val v = Version.fromString("1.2.3")
        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
        assertEquals("", v.meta)
    }

    @Test
    fun `parse version with pre-release`() {
        val v = Version.fromString("1.0.0-beta.1")
        assertEquals(1, v.major)
        assertEquals(0, v.minor)
        assertEquals(0, v.patch)
        assertEquals("beta.1", v.meta)
    }

    @Test
    fun `parse version with only major`() {
        val v = Version.fromString("2")
        assertEquals(2, v.major)
        assertEquals(0, v.minor)
        assertEquals(0, v.patch)
    }

    @Test
    fun `parse version with major and minor`() {
        val v = Version.fromString("3.5")
        assertEquals(3, v.major)
        assertEquals(5, v.minor)
        assertEquals(0, v.patch)
    }

    @Test
    fun `parse invalid version returns zero`() {
        val v = Version.fromString("not-a-version")
        assertEquals(0, v.major)
        assertEquals(0, v.minor)
        assertEquals(0, v.patch)
    }

    @Test
    fun `compare major versions`() {
        assertTrue(Version.fromString("2.0.0") > Version.fromString("1.0.0"))
        assertTrue(Version.fromString("1.0.0") < Version.fromString("2.0.0"))
    }

    @Test
    fun `compare minor versions`() {
        assertTrue(Version.fromString("1.2.0") > Version.fromString("1.1.0"))
    }

    @Test
    fun `compare patch versions`() {
        assertTrue(Version.fromString("1.0.2") > Version.fromString("1.0.1"))
    }

    @Test
    fun `equal versions compare as zero`() {
        assertEquals(0, Version.fromString("1.2.3").compareTo(Version.fromString("1.2.3")))
    }

    @Test
    fun `release is greater than pre-release`() {
        assertTrue(Version.fromString("1.0.0") > Version.fromString("1.0.0-beta.1"))
    }

    @Test
    fun `pre-release is less than release`() {
        assertTrue(Version.fromString("1.0.0-beta.1") < Version.fromString("1.0.0"))
    }

    @Test
    fun `compare pre-release versions numerically`() {
        assertTrue(Version.fromString("1.0.0-beta.2") > Version.fromString("1.0.0-beta.1"))
        assertTrue(Version.fromString("1.0.0-beta.10") > Version.fromString("1.0.0-beta.2"))
    }

    @Test
    fun `compare pre-release versions alphabetically`() {
        assertTrue(Version.fromString("1.0.0-rc.1") > Version.fromString("1.0.0-beta.1"))
        assertTrue(Version.fromString("1.0.0-beta.1") > Version.fromString("1.0.0-alpha.1"))
    }

    @Test
    fun `toString formats correctly`() {
        assertEquals("1.2.3", Version.fromString("1.2.3").toString())
        assertEquals("1.0.0-beta.1", Version.fromString("1.0.0-beta.1").toString())
    }

    @Test
    fun `levelFrom detects major update`() {
        val level = Version.fromString("2.0.0").levelFrom(Version.fromString("1.5.3"))
        assertEquals(UpdateLevel.MAJOR, level)
    }

    @Test
    fun `levelFrom detects minor update`() {
        val level = Version.fromString("1.3.0").levelFrom(Version.fromString("1.2.5"))
        assertEquals(UpdateLevel.MINOR, level)
    }

    @Test
    fun `levelFrom detects patch update`() {
        val level = Version.fromString("1.2.4").levelFrom(Version.fromString("1.2.3"))
        assertEquals(UpdateLevel.PATCH, level)
    }

    @Test
    fun `levelFrom detects pre-release update`() {
        val level = Version.fromString("1.2.3-beta.2").levelFrom(Version.fromString("1.2.3-beta.1"))
        assertEquals(UpdateLevel.PRE_RELEASE, level)
    }

    @Test
    fun `levelFrom release over pre-release is pre-release level`() {
        val level = Version.fromString("1.2.3").levelFrom(Version.fromString("1.2.3-beta.1"))
        assertEquals(UpdateLevel.PRE_RELEASE, level)
    }

    @Test
    fun `whitespace in version string is trimmed`() {
        val v = Version.fromString("  1.2.3  ")
        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
    }
}
