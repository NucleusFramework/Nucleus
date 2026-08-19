package dev.nucleusframework.scheduler.internal

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

    @Test
    fun `saturday and wednesday weekdays`() {
        val saturday = generate("Sat *-*-* 22:00:00")
        assertTrue(saturday.contains("<integer>6</integer>"), "Saturday should be weekday 6")
        val wednesday = generate("Wed *-*-* 04:15:00")
        assertTrue(wednesday.contains("<integer>3</integer>"), "Wednesday should be weekday 3")
        assertTrue(wednesday.contains("<integer>4</integer>"))
        assertTrue(wednesday.contains("<integer>15</integer>"))
    }

    @Test
    fun `daily dict omits Weekday`() {
        val plist = generate("*-*-* 14:05:00")
        assertTrue(plist.contains("<key>StartCalendarInterval</key>"))
        assertTrue(plist.contains("<key>Hour</key>"))
        assertTrue(plist.contains("<integer>14</integer>"))
        assertTrue(plist.contains("<integer>5</integer>"))
        assertTrue(!plist.contains("<key>Weekday</key>"))
        assertTrue(!plist.contains("<array>"))
    }

    @Test
    fun `weekend range includes Saturday and Sunday`() {
        val plist = generate("Sat..Sun *-*-* 09:00:00")
        assertTrue(plist.contains("<array>"))
        assertTrue(plist.contains("<integer>6</integer>"), "Saturday")
        assertTrue(plist.contains("<integer>0</integer>"), "Sunday")
    }
}
