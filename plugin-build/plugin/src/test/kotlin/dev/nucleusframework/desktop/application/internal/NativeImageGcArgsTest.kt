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
                graalvmHome = "/opt/graalvm-ce",
            )
        assertEquals(NativeImageGarbageCollector.EPSILON, resolution.gc)
        assertNull(resolution.warning)
    }

    @Test
    fun `G1 is kept on Oracle GraalVM for Linux`() {
        val resolution =
            resolveNativeImageGc(
                requested = NativeImageGarbageCollector.G1,
                isOracleGraalvm = true,
                isLinux = true,
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
                graalvmHome = "/opt/graalvm-ce",
            )
        assertNull(resolution.gc)
        assertNotNull(resolution.warning)
        assertTrue(resolution.warning!!.contains("requires Oracle GraalVM"))
        assertTrue(resolution.warning!!.contains("/opt/graalvm-ce"))
    }

    @Test
    fun `G1 is dropped off Linux`() {
        val resolution =
            resolveNativeImageGc(
                requested = NativeImageGarbageCollector.G1,
                isOracleGraalvm = true,
                isLinux = false,
                graalvmHome = "/opt/graalvm-oracle",
            )
        assertNull(resolution.gc)
        assertTrue(resolution.warning!!.contains("only supported on Linux"))
    }

    @Test
    fun `nothing requested resolves to nothing`() {
        val resolution =
            resolveNativeImageGc(
                requested = null,
                isOracleGraalvm = true,
                isLinux = true,
                graalvmHome = "/opt/graalvm-oracle",
            )
        assertNull(resolution.gc)
        assertNull(resolution.warning)
    }
}
