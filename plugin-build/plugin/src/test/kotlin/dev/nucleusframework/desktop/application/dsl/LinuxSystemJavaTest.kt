package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxSystemJavaTest {
    @Test
    fun `majors match the enum names`() {
        assertEquals(17, LinuxSystemJava.Java17.major)
        assertEquals(21, LinuxSystemJava.Java21.major)
        assertEquals(25, LinuxSystemJava.Java25.major)
    }

    @Test
    fun `fromMajor round-trips every constant`() {
        LinuxSystemJava.entries.forEach { value ->
            assertEquals(value, LinuxSystemJava.fromMajor(value.major))
        }
        assertNull(LinuxSystemJava.fromMajor(11))
        assertNull(LinuxSystemJava.fromMajor(8))
    }

    @Test
    fun `deb depends require a full JRE and accept newer LTS alternatives`() {
        assertEquals(
            "java17-runtime | java-runtime (>= 17)",
            LinuxSystemJava.Java17.debDepends,
        )
        assertEquals("java21-runtime | java-runtime (>= 21)", LinuxSystemJava.Java21.debDepends)
        assertEquals("java25-runtime | java-runtime (>= 25)", LinuxSystemJava.Java25.debDepends)
        LinuxSystemJava.entries.forEach { value ->
            assertTrue(value.debDepends, !value.debDepends.contains("headless"))
        }
    }

    @Test
    fun `rpm requires the GUI OpenJDK package not the headless one`() {
        assertEquals(
            "(java-17-openjdk or java-21-openjdk or java-25-openjdk)",
            LinuxSystemJava.Java17.rpmRequires,
        )
        assertEquals("(java-21-openjdk or java-25-openjdk)", LinuxSystemJava.Java21.rpmRequires)
        assertEquals("java-25-openjdk", LinuxSystemJava.Java25.rpmRequires)
        LinuxSystemJava.entries.forEach { value ->
            assertTrue(value.rpmRequires, !value.rpmRequires.contains("headless"))
        }
    }

    @Test
    fun `pacman depends on the versioned java-runtime virtual package`() {
        assertEquals("java-runtime>=17", LinuxSystemJava.Java17.pacmanDepends)
        assertEquals("java-runtime>=21", LinuxSystemJava.Java21.pacmanDepends)
        assertEquals("java-runtime>=25", LinuxSystemJava.Java25.pacmanDepends)
    }
}
