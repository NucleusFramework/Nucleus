package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.NativeImageGarbageCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImageGcArgsTest {
    @Test
    fun `no collector keeps the serial heap option and adds no gc flag`() {
        assertEquals(
            listOf("-R:MaximumHeapSizePercent=25"),
            nativeImageGcArgs(gc = null, maxHeapSize = null, maxHeapSizePercent = 25),
        )
    }

    @Test
    fun `G1 sizes the heap with MaxRAMPercentage`() {
        assertEquals(
            listOf("--gc=G1", "-R:MaxRAMPercentage=40"),
            nativeImageGcArgs(
                gc = NativeImageGarbageCollector.G1,
                maxHeapSize = null,
                maxHeapSizePercent = 40,
            ),
        )
    }

    @Test
    fun `an absolute max heap wins over the percentage for every collector`() {
        NativeImageGarbageCollector.entries.forEach { gc ->
            val args = nativeImageGcArgs(gc = gc, maxHeapSize = "2g", maxHeapSizePercent = 25)
            assertEquals(listOf(gc.flag, "-R:MaxHeapSize=2g"), args)
        }
    }

    @Test
    fun `unrestricted collectors are kept on any toolchain and platform`() {
        val resolution =
            resolveNativeImageGc(
                requested = NativeImageGarbageCollector.EPSILON,
                isOracleGraalvm = false,
                isLinux = false,
                graalvmVersion = null,
                graalvmHome = "/opt/graalvm-ce",
            )
        assertEquals(NativeImageGarbageCollector.EPSILON, resolution.gc)
        assertNull(resolution.warning)
    }

    @Test
    fun `G1 is kept on Oracle GraalVM for Linux, whatever the version`() {
        val resolution =
            resolveNativeImageGc(
                requested = NativeImageGarbageCollector.G1,
                isOracleGraalvm = true,
                isLinux = true,
                graalvmVersion = "25.3.4.1",
                graalvmHome = "/opt/graalvm-oracle",
            )
        assertEquals(NativeImageGarbageCollector.G1, resolution.gc)
        assertNull(resolution.warning)
    }

    @Test
    fun `G1 is dropped on a community toolchain`() {
        val resolution =
            resolveNativeImageGc(
                requested = NativeImageGarbageCollector.G1,
                isOracleGraalvm = false,
                isLinux = true,
                graalvmVersion = "25.4.4.1.1",
                graalvmHome = "/opt/graalvm-ce",
            )
        assertNull(resolution.gc)
        assertNotNull(resolution.warning)
        assertTrue(resolution.warning!!.contains("requires Oracle GraalVM"))
        assertTrue(resolution.warning.contains("/opt/graalvm-ce"))
    }

    @Test
    fun `G1 is kept off Linux from 25_4 on`() {
        listOf("25.4", "25.4.4.1.1", "26.0.1").forEach { version ->
            val resolution =
                resolveNativeImageGc(
                    requested = NativeImageGarbageCollector.G1,
                    isOracleGraalvm = true,
                    isLinux = false,
                    graalvmVersion = version,
                    graalvmHome = "/opt/graalvm-oracle",
                )
            assertEquals("G1 should be kept on $version", NativeImageGarbageCollector.G1, resolution.gc)
            assertNull(resolution.warning)
        }
    }

    @Test
    fun `G1 is dropped off Linux before 25_4`() {
        listOf("25.3.4.1", "25.0.1", "24.1.2").forEach { version ->
            val resolution =
                resolveNativeImageGc(
                    requested = NativeImageGarbageCollector.G1,
                    isOracleGraalvm = true,
                    isLinux = false,
                    graalvmVersion = version,
                    graalvmHome = "/opt/graalvm-oracle",
                )
            assertNull("G1 should be dropped on $version", resolution.gc)
            assertTrue(resolution.warning!!.contains("requires GraalVM 25.4 or newer outside Linux"))
            assertTrue(resolution.warning.contains(version))
        }
    }

    @Test
    fun `G1 is dropped off Linux when the toolchain version cannot be read`() {
        val resolution =
            resolveNativeImageGc(
                requested = NativeImageGarbageCollector.G1,
                isOracleGraalvm = true,
                isLinux = false,
                graalvmVersion = null,
                graalvmHome = "/opt/graalvm-oracle",
            )
        assertNull(resolution.gc)
        assertTrue(resolution.warning!!.contains("could not be read"))
    }

    @Test
    fun `nothing requested resolves to nothing`() {
        val resolution =
            resolveNativeImageGc(
                requested = null,
                isOracleGraalvm = true,
                isLinux = true,
                graalvmVersion = "25.4.4.1.1",
                graalvmHome = "/opt/graalvm-oracle",
            )
        assertNull(resolution.gc)
        assertNull(resolution.warning)
    }
}
