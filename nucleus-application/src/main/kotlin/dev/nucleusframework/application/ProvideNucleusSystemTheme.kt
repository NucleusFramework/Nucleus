@file:Suppress("DEPRECATION")

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
 * Official `isSystemInDarkTheme()` polls the OS about once a second; providing
 * [LocalSystemTheme] from [isSystemInDarkMode] keeps every call site on
 * Nucleus's live detector instead of that poll. Compose 1.12.1 deprecates the
 * local (public by mistake) but still reads it, so it remains the only hook.
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
