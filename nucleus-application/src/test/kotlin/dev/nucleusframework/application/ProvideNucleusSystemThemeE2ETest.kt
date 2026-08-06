package dev.nucleusframework.application

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.nucleusframework.darkmodedetector.getPlatformDarkModeDetector
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Live E2E: actually flips the OS color-scheme and asserts that under
 * [ProvideNucleusSystemTheme], both Nucleus [isSystemInDarkMode] and Compose's
 * official [isSystemInDarkTheme] track the change.
 */
@OptIn(ExperimentalTestApi::class)
class ProvideNucleusSystemThemeE2ETest {
    @Test
    fun officialIsSystemInDarkThemeTracksLiveOsToggle() {
        assumeTrue(
            "Linux gsettings + session bus required for live theme toggle",
            LinuxColorSchemeToggle.isAvailable,
        )

        runComposeUiTest {
            var nucleus: Boolean? by mutableStateOf(null)
            var composeOfficial: Boolean? by mutableStateOf(null)

            setContent {
                ProvideNucleusSystemTheme {
                    nucleus = isSystemInDarkMode()
                    composeOfficial = isSystemInDarkTheme()
                }
            }
            waitForIdle()

            val original = LinuxColorSchemeToggle.read()
            println("E2E start: scheme=$original nucleus=$nucleus compose=$composeOfficial")

            try {
                // Force light, then dark (order independent of current scheme).
                for (scheme in listOf("prefer-light", "prefer-dark", "prefer-light")) {
                    val expectDark = LinuxColorSchemeToggle.isDarkScheme(scheme)
                    LinuxColorSchemeToggle.write(scheme)
                    awaitTheme(
                        expectedDark = expectDark,
                        readNucleus = { nucleus },
                        readCompose = { composeOfficial },
                        label = scheme,
                    )
                    assertEquals(
                        "after $scheme: official must match nucleus",
                        nucleus,
                        composeOfficial,
                    )
                    assertEquals(
                        "after $scheme: raw detector must match",
                        expectDark,
                        getPlatformDarkModeDetector().isDark(),
                    )
                    println(
                        "E2E OK scheme=$scheme detector=${getPlatformDarkModeDetector().isDark()} " +
                            "nucleus=$nucleus compose=$composeOfficial",
                    )
                }
            } finally {
                LinuxColorSchemeToggle.write(original)
                // Best-effort restore wait so we don't leave the host mid-transition.
                runCatching {
                    awaitTheme(
                        expectedDark = LinuxColorSchemeToggle.isDarkScheme(original),
                        readNucleus = { nucleus },
                        readCompose = { composeOfficial },
                        label = "restore:$original",
                    )
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.awaitTheme(
        expectedDark: Boolean,
        readNucleus: () -> Boolean?,
        readCompose: () -> Boolean?,
        label: String,
        timeoutMs: Long = 15_000,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            waitForIdle()
            val n = readNucleus()
            val c = readCompose()
            val d = getPlatformDarkModeDetector().isDark()
            if (n == expectedDark && c == expectedDark && d == expectedDark) {
                assertNotNull(n)
                assertNotNull(c)
                return
            }
            Thread.sleep(100)
        }
        waitForIdle()
        assertTrue(
            "timed out waiting for theme=$label expectedDark=$expectedDark " +
                "detector=${getPlatformDarkModeDetector().isDark()} " +
                "nucleus=${readNucleus()} compose=${readCompose()}",
            false,
        )
    }
}
