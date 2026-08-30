package dev.nucleusframework.application.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.readText

class StartupProbeTest {
    private val previous = mutableMapOf<String, String?>()

    @Before
    fun saveProperties() {
        for (key in listOf(
            StartupProbe.DIR_PROPERTY,
            StartupProbe.EXIT_AFTER_MS_PROPERTY,
            StartupProbe.IDLE_MS_PROPERTY,
            StartupProbe.FORCE_GC_PROPERTY,
            StartupProbe.WORKLOAD_PROPERTY,
        )) {
            previous[key] = System.getProperty(key)
            System.clearProperty(key)
        }
        StartupProbe.resetForTests()
    }

    @After
    fun restoreProperties() {
        for ((key, value) in previous) {
            if (value == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, value)
            }
        }
        StartupProbe.resetForTests()
    }

    @Test
    fun `disabled when probe dir is unset`() {
        assertFalse(StartupProbe.isEnabled)
    }

    @Test
    fun `writes started and ready json`() {
        val dir = Files.createTempDirectory("startup-probe")
        System.setProperty(StartupProbe.DIR_PROPERTY, dir.toString())
        StartupProbe.resetForTests()
        StartupProbe.onEntered()

        val started = dir.resolve("started.json")
        assertTrue(started.toFile().exists())
        val startedText = started.readText()
        assertTrue(startedText.contains("\"event\":\"started\""))
        assertTrue(startedText.contains("\"pid\":"))
        assertTrue(startedText.contains("\"gc\":"))

        StartupProbe.onFirstFrame()
        val ready = dir.resolve("ready.json")
        assertTrue(ready.toFile().exists())
        val readyText = ready.readText()
        assertTrue(readyText.contains("\"schema\":1"))
        assertTrue(readyText.contains("\"event\":\"first-frame\""))
        assertTrue(readyText.contains("\"ttffFromJvmStartMs\":"))
        assertTrue(readyText.contains("\"heap\":"))
        assertTrue(readyText.contains("\"usedBytes\":"))
        assertTrue(readyText.contains("\"gcBeans\":"))

        val readyAgain = ready.readText()
        StartupProbe.onFirstFrame()
        assertEquals("second first-frame must be a no-op", readyAgain, ready.readText())
    }

    @Test
    fun `json escapes control characters`() {
        val json = jsonObject(mapOf("path" to "C:\\tmp\n\"x\""))
        assertTrue(json.contains("\\\\"))
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\\""))
    }

    @Test
    fun `detects collector from flags before bean names`() {
        assertEquals(
            "serial",
            StartupProbe.detectedCollector(
                inputArguments = listOf("-Xmx256m", "-XX:+UseSerialGC"),
                beanNames = listOf("G1 Young Generation"),
            ),
        )
        assertEquals(
            "g1",
            StartupProbe.detectedCollector(
                inputArguments = emptyList(),
                beanNames = listOf("G1 Young Generation", "G1 Old Generation"),
            ),
        )
        assertEquals(
            "serial",
            StartupProbe.detectedCollector(
                inputArguments = emptyList(),
                beanNames = listOf("Copy", "MarkSweepCompact"),
            ),
        )
        assertEquals(
            "serial",
            StartupProbe.detectedCollector(
                inputArguments = emptyList(),
                beanNames = listOf("young generation scavenger", "complete scavenger"),
            ),
        )
    }

    @Test
    fun `buildReadyJson includes timings and memory`() {
        val dir = Files.createTempDirectory("startup-probe-ready")
        System.setProperty(StartupProbe.DIR_PROPERTY, dir.toString())
        StartupProbe.resetForTests()
        StartupProbe.onEntered()
        val json = StartupProbe.buildReadyJson(nowEpochMs = 1_700_000_000_000, nowNano = System.nanoTime())
        assertTrue(json.contains("\"timings\":{"))
        assertTrue(json.contains("\"committedBytes\":"))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
    }
}
