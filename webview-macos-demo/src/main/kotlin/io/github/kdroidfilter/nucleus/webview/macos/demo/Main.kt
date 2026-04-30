package io.github.kdroidfilter.nucleus.webview.macos.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import io.github.kdroidfilter.nucleus.darkmodedetector.isSystemInDarkMode
import io.github.kdroidfilter.nucleus.webview.macos.MacWebView
import io.github.kdroidfilter.nucleus.webview.macos.rememberMacWebViewState
import io.github.kdroidfilter.nucleus.window.DecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.macOSLargeCornerRadius
import io.github.kdroidfilter.nucleus.window.material.MaterialDecoratedWindow
import io.github.kdroidfilter.nucleus.window.material.MaterialTitleBar
import io.github.kdroidfilter.nucleus.window.newFullscreenControls

/**
 * `MacWebView` inside `MaterialDecoratedWindow` with all browser navigation
 * placed directly in the native macOS title bar, à la Safari / Chrome.
 *
 * - Reactive system dark/light mode via `darkmode-detector`
 * - Manual override toggle (right side of the title bar)
 * - WKWebView fills the body area, no chrome below the title bar
 */
fun main() = application { Sample() }

@Composable
private fun ApplicationScope.Sample() {
    val systemDark = isSystemInDarkMode()
    var darkOverride by remember { mutableStateOf<Boolean?>(null) }
    val isDark = darkOverride ?: systemDark
    val colorScheme = if (isDark) AppDark else AppLight

    var url by remember { mutableStateOf("https://www.jetbrains.com") }
    var addressInput by remember { mutableStateOf(url) }
    val state = rememberMacWebViewState()

    MaterialTheme(colorScheme = colorScheme) {
        MaterialDecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "Nucleus WebView",
            bodyBackground = Color.Transparent,
        ) {
            MaterialTitleBar(
                modifier = Modifier.newFullscreenControls().macOSLargeCornerRadius(),
            ) { _ ->
                // ── Start side: leave room for native traffic lights + nav buttons.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.align(Alignment.Start).padding(start = 80.dp, end = 8.dp),
                ) {
                    NavButton(Icons.Rounded.ArrowBack, "Back") { state.goBack() }
                    NavButton(Icons.Rounded.ArrowForward, "Forward") { state.goForward() }
                    NavButton(Icons.Rounded.Refresh, "Reload") { state.reload() }
                }

                // ── Center: address bar.
                AddressBar(
                    value = addressInput,
                    onValueChange = { addressInput = it },
                    onSubmit = { url = addressInput.normalizeUrl().also { addressInput = it } },
                    isLoading = state.isLoading.value,
                    modifier = Modifier.widthIn(min = 280.dp, max = 560.dp).padding(horizontal = 8.dp),
                )

                // ── End side: theme toggle.
                Box(
                    modifier = Modifier.align(Alignment.End).padding(end = 8.dp),
                ) {
                    NavButton(
                        icon = if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        description = "Toggle theme",
                        onClick = { darkOverride = !isDark },
                    )
                }
            }

            Body(url = url, state = state)
        }
    }
}

@Composable
private fun DecoratedWindowScope.Body(
    url: String,
    state: io.github.kdroidfilter.nucleus.webview.macos.MacWebViewState,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        MacWebView(
            url = url,
            state = state,
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom-floating status pill (purely cosmetic).
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                tonalElevation = 6.dp,
                modifier = Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(999.dp),
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Native WKWebView · Compose overlay",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AddressBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(28.dp).clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp).fillMaxSize(),
        ) {
            Box(
                modifier = Modifier.size(13.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = FontWeight.Normal,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                            onSubmit(); true
                        } else false
                    },
            )
        }
    }
}

private fun String.normalizeUrl(): String =
    when {
        isBlank() -> "about:blank"
        startsWith("http://") || startsWith("https://") -> this
        contains(' ') || !contains('.') -> "https://duckduckgo.com/?q=${this.replace(" ", "+")}"
        else -> "https://$this"
    }

// Modern colour schemes — clean and neutral.
private val AppLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF2A6DF4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E4FF),
    onPrimaryContainer = Color(0xFF001A41),
    surface = Color(0xFFFAFAFA),
    surfaceContainerLowest = Color.White,
    surfaceContainer = Color(0xFFF1F2F4),
    surfaceContainerHigh = Color(0xFFE9EBEE),
    onSurface = Color(0xFF1B1C1F),
    onSurfaceVariant = Color(0xFF45474B),
    outlineVariant = Color(0xFFCDD0D6),
)

private val AppDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF8FB3FF),
    onPrimary = Color(0xFF002B6E),
    primaryContainer = Color(0xFF1C3F8A),
    onPrimaryContainer = Color(0xFFD9E4FF),
    surface = Color(0xFF101113),
    surfaceContainerLowest = Color(0xFF09090B),
    surfaceContainer = Color(0xFF18191C),
    surfaceContainerHigh = Color(0xFF202125),
    onSurface = Color(0xFFE4E5E8),
    onSurfaceVariant = Color(0xFFB7B9BD),
    outlineVariant = Color(0xFF3A3C40),
)
