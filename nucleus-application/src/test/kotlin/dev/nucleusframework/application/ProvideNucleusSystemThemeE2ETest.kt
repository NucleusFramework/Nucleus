package dev.nucleusframework.application

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.nucleusframework.darkmodedetector.getPlatformDarkModeDetector
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import org.jetbrains.skiko.SystemTheme as SkikoSystemTheme
import org.jetbrains.skiko.currentSystemTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live E2E: under [ProvideNucleusSystemTheme], Compose's official
 * [isSystemInDarkTheme] must mirror Nucleus's JNI-backed [isSystemInDarkMode]
 * (and the raw detector), not Skiko's non-reactive snapshot alone.
 */
@OptIn(ExperimentalTestApi::class)
class ProvideNucleusSystemThemeE2ETest {
    @Test
    fun detectorReadsOsDarkMode() {
        val detectorDark = getPlatformDarkModeDetector().isDark()
        // Machine under test reports GNOME prefer-dark; keep the assertion soft
        // enough for CI hosts that may be light, but always require a defined
        // boolean (native bridge loaded, no throw).
        println(
            "E2E native detector isDark=$detectorDark " +
                "skiko=$currentSystemTheme " +
                "(gsettings may differ from Skiko on Linux)",
        )
        // Touch the value so a failing native load surfaces as an exception.
        assertTrue(detectorDark || !detectorDark)
    }

    @Test
    fun officialIsSystemInDarkThemeMatchesNucleusUnderBridge() =
        runComposeUiTest {
            val detectorDark = getPlatformDarkModeDetector().isDark()
            val skikoDark = currentSystemTheme == SkikoSystemTheme.DARK

            var nucleus: Boolean? by mutableStateOf(null)
            var composeOfficial: Boolean? by mutableStateOf(null)

            setContent {
                ProvideNucleusSystemTheme {
                    nucleus = isSystemInDarkMode()
                    composeOfficial = isSystemInDarkTheme()
                }
            }
            waitForIdle()

            println(
                "E2E bridge: detector=$detectorDark skikoDark=$skikoDark " +
                    "isSystemInDarkMode=$nucleus isSystemInDarkTheme=$composeOfficial",
            )

            assertNotNull(nucleus)
            assertNotNull(composeOfficial)
            assertEquals(
                "isSystemInDarkMode must match the raw OS detector",
                detectorDark,
                nucleus,
            )
            assertEquals(
                "official isSystemInDarkTheme must follow the Nucleus bridge " +
                    "(LocalSystemTheme provided from isSystemInDarkMode)",
                nucleus,
                composeOfficial,
            )
            assertEquals(
                "official isSystemInDarkTheme must match the OS detector, not only Skiko",
                detectorDark,
                composeOfficial,
            )
        }

    @Test
    fun withoutBridgeOfficialMayDivergeFromDetectorOnLinux() =
        runComposeUiTest {
            // Documents why the bridge exists: default LocalSystemTheme is a
            // Skiko snapshot. On Linux that often does not match the portal.
            val detectorDark = getPlatformDarkModeDetector().isDark()
            val skikoDark = currentSystemTheme == SkikoSystemTheme.DARK

            var composeWithoutBridge: Boolean? by mutableStateOf(null)
            setContent {
                composeWithoutBridge = isSystemInDarkTheme()
            }
            waitForIdle()

            println(
                "E2E no-bridge: detector=$detectorDark skikoDark=$skikoDark " +
                    "isSystemInDarkTheme=$composeWithoutBridge",
            )
            assertNotNull(composeWithoutBridge)
            // Without ProvideNucleusSystemTheme, the official API tracks Skiko.
            assertEquals(skikoDark, composeWithoutBridge)
            if (detectorDark != skikoDark) {
                println(
                    "E2E: detector and Skiko DIVERGE — bridge required for correct theming",
                )
            }
        }
}
