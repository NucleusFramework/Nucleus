@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.LocalSystemTheme
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import org.jetbrains.skiko.SystemTheme

/**
 * Feeds Compose's [androidx.compose.foundation.isSystemInDarkTheme] from
 * Nucleus's reactive OS detector.
 *
 * Compose 1.12 made [LocalSystemTheme] internal and typed it as Skiko's
 * [SystemTheme]. Official `isSystemInDarkTheme()` now polls the OS about once
 * a second; providing the local from [isSystemInDarkMode] keeps every call
 * site on Nucleus's live detector instead of that poll.
 *
 * The value is computed *outside* the provider, so the detector never reads the
 * local it is about to set (preview path of [isSystemInDarkMode] falls back to
 * `isSystemInDarkTheme()`).
 */
@Composable
internal fun ProvideNucleusSystemTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkMode()
    CompositionLocalProvider(
        LocalSystemTheme provides if (isDark) SystemTheme.DARK else SystemTheme.LIGHT,
        content = content,
    )
}
