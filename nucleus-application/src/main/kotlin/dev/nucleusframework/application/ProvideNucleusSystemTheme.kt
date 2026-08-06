package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode

/**
 * Feeds Compose's [androidx.compose.foundation.isSystemInDarkTheme] from
 * Nucleus's reactive OS detector.
 *
 * On Compose Desktop 1.11, `isSystemInDarkTheme()` only reads
 * [LocalSystemTheme], whose default is a non-reactive Skiko snapshot. Providing
 * the local from [isSystemInDarkMode] makes every official call site (and any
 * library that uses it) track OS dark-mode changes the same way Nucleus does.
 *
 * The value is computed *outside* the provider, so the detector never reads the
 * local it is about to set (preview path of [isSystemInDarkMode] falls back to
 * `isSystemInDarkTheme()`).
 */
@OptIn(InternalComposeUiApi::class)
@Composable
internal fun ProvideNucleusSystemTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkMode()
    CompositionLocalProvider(
        LocalSystemTheme provides if (isDark) SystemTheme.Dark else SystemTheme.Light,
        content = content,
    )
}
