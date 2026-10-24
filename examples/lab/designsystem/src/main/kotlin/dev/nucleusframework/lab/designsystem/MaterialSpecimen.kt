package dev.nucleusframework.lab.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Material 3 components under test (samples, fields whose focus or IME behaviour is the
 * subject) on Material's own baseline scheme, light or dark with the Lab. A specimen is
 * judged against Material, never against colours derived from the Lab's Jewel chrome.
 */
@Composable
fun MaterialSpecimen(content: @Composable () -> Unit) {
    val scheme = if (LabTheme.isDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}
