package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.tasks.AbstractServeUpdateFeedTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterLaunchSettingsTest {
    @Test
    fun `settings map to the environment names the runtime reads`() {
        assertEquals("NUCLEUS_UPDATER_FEED_URL", UpdaterLaunchSettings.environmentName("nucleus.updater.feedUrl"))
        assertEquals("NUCLEUS_UPDATER_SIMULATE", UpdaterLaunchSettings.environmentName("nucleus.updater.simulate"))
        assertEquals(
            "NUCLEUS_UPDATER_SIMULATE_JUST_UPDATED_FROM",
            UpdaterLaunchSettings.environmentName("nucleus.updater.simulate.justUpdatedFrom"),
        )
    }

    @Test
    fun `byte rates accept plain, k and m suffixes`() {
        assertEquals(2_000_000L, UpdaterLaunchSettings.parseByteRate("2000000"))
        assertEquals(512L * 1024, UpdaterLaunchSettings.parseByteRate("512k"))
        assertEquals(3L * 1024 * 1024 / 2, UpdaterLaunchSettings.parseByteRate("1.5M"))
        assertNull(UpdaterLaunchSettings.parseByteRate("fast"))
        assertNull(UpdaterLaunchSettings.parseByteRate("0"))
    }

    @Test
    fun `the feed server parses single byte ranges`() {
        val parse = AbstractServeUpdateFeedTask.Companion::parseRange
        assertEquals(10L to 19L, parse("bytes=10-19", 100))
        assertEquals(90L to 99L, parse("bytes=90-", 100))
        assertEquals(80L to 99L, parse("bytes=-20", 100))
        assertEquals("clamped to the end", 95L to 99L, parse("bytes=95-500", 100))
        assertEquals("unsatisfiable", -1L to -1L, parse("bytes=100-120", 100))
        assertNull("multi-range is served whole", parse("bytes=0-1,5-6", 100))
        assertNull(parse("items=0-1", 100))
    }
}
