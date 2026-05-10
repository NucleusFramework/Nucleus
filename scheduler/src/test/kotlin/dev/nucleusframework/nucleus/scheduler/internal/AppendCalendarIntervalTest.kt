package dev.nucleusframework.nucleus.scheduler.internal

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppendCalendarIntervalTest {
    private fun generate(expression: String): String {
        val sb = StringBuilder()
        MacOSLaunchdScheduler.appendCalendarInterval(sb, expression)
        return sb.toString()
    }

    @Test
    fun `daily at specific time`() {
        val plist = generate("*-*-* 09:30:00")

        assertTrue(plist.contains("<key>StartCalendarInterval</key>"))
        assertTrue(plist.contains("<key>Hour</key>"))
        assertTrue(plist.contains("<integer>9</integer>"))
        assertTrue(plist.contains("<key>Minute</key>"))
        assertTrue(plist.contains("<integer>30</integer>"))
    }

    @Test
    fun `every hour sets only Minute`() {
        val plist = generate("*-*-* *:00:00")

        assertTrue(plist.contains("<key>Minute</key>"))
        assertTrue(plist.contains("<integer>0</integer>"))
        assertTrue(!plist.contains("<key>Hour</key>"), "Hourly should not set Hour")
    }

    @Test
    fun `specific weekday`() {
        val plist = generate("Mon *-*-* 08:30:00")

        assertTrue(plist.contains("<key>Weekday</key>"))
        assertTrue(plist.contains("<integer>1</integer>"), "Monday should be weekday 1")
        assertTrue(plist.contains("<integer>8</integer>"), "Hour should be 8")
        assertTrue(plist.contains("<integer>30</integer>"), "Minute should be 30")
    }

    @Test
    fun `weekday range generates array`() {
        val plist = generate("Mon..Fri *-*-* 18:00:00")

        assertTrue(plist.contains("<array>"), "Day range should produce an array")
        // Monday=1 through Friday=5
        for (day in 1..5) {
            assertTrue(plist.contains("<integer>$day</integer>"), "Should contain weekday $day")
        }
    }

    @Test
    fun `sunday is weekday 0`() {
        val plist = generate("Sun *-*-* 10:00:00")

        assertTrue(plist.contains("<key>Weekday</key>"))
        assertTrue(plist.contains("<integer>0</integer>"), "Sunday should be weekday 0")
    }

    @Test
    fun `unsupported expression throws`() {
        assertFailsWith<IllegalArgumentException> {
            generate("*-*-01 00:00:00")
        }
    }
}
