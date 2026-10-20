package dev.nucleusframework.lab.probes.window.theming.gallery.material3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamicColorScheme

private data class ColorInfo(
    val title: String,
    val color: @Composable () -> Color,
    val textColor: @Composable () -> Color,
)

@Composable
internal fun ColorScreen(seedColor: Color) {
    val items =
        listOf(
            listOf(
                ColorInfo("primary", { MaterialTheme.colorScheme.primary }, { MaterialTheme.colorScheme.onPrimary }),
                ColorInfo("onPrimary", { MaterialTheme.colorScheme.onPrimary }, { MaterialTheme.colorScheme.primary }),
                ColorInfo(
                    "primaryContainer",
                    { MaterialTheme.colorScheme.primaryContainer },
                    { MaterialTheme.colorScheme.onPrimaryContainer },
                ),
                ColorInfo(
                    "onPrimaryContainer",
                    { MaterialTheme.colorScheme.onPrimaryContainer },
                    { MaterialTheme.colorScheme.primaryContainer },
                ),
            ),
            listOf(
                ColorInfo(
                    "secondary",
                    { MaterialTheme.colorScheme.secondary },
                    { MaterialTheme.colorScheme.onSecondary },
                ),
                ColorInfo(
                    "onSecondary",
                    { MaterialTheme.colorScheme.onSecondary },
                    { MaterialTheme.colorScheme.secondary },
                ),
                ColorInfo(
                    "secondaryContainer",
                    { MaterialTheme.colorScheme.secondaryContainer },
                    { MaterialTheme.colorScheme.onSecondaryContainer },
                ),
                ColorInfo(
                    "onSecondaryContainer",
                    { MaterialTheme.colorScheme.onSecondaryContainer },
                    { MaterialTheme.colorScheme.secondaryContainer },
                ),
            ),
            listOf(
                ColorInfo(
                    "tertiary",
                    { MaterialTheme.colorScheme.tertiary },
                    { MaterialTheme.colorScheme.onTertiary },
                ),
                ColorInfo(
                    "onTertiary",
                    { MaterialTheme.colorScheme.onTertiary },
                    { MaterialTheme.colorScheme.tertiary },
                ),
                ColorInfo(
                    "tertiaryContainer",
                    { MaterialTheme.colorScheme.tertiaryContainer },
                    { MaterialTheme.colorScheme.onTertiaryContainer },
                ),
                ColorInfo(
                    "onTertiaryContainer",
                    { MaterialTheme.colorScheme.onTertiaryContainer },
                    { MaterialTheme.colorScheme.tertiaryContainer },
                ),
            ),
            listOf(
                ColorInfo(
                    "error",
                    { MaterialTheme.colorScheme.error },
                    { MaterialTheme.colorScheme.onError },
                ),
                ColorInfo(
                    "onError",
                    { MaterialTheme.colorScheme.onError },
                    { MaterialTheme.colorScheme.error },
                ),
                ColorInfo(
                    "errorContainer",
                    { MaterialTheme.colorScheme.errorContainer },
                    { MaterialTheme.colorScheme.onErrorContainer },
                ),
                ColorInfo(
                    "onErrorContainer",
                    { MaterialTheme.colorScheme.onErrorContainer },
                    { MaterialTheme.colorScheme.errorContainer },
                ),
            ),
            listOf(
                ColorInfo(
                    "background",
                    { MaterialTheme.colorScheme.background },
                    { MaterialTheme.colorScheme.onBackground },
                ),
                ColorInfo(
                    "onBackground",
                    { MaterialTheme.colorScheme.onBackground },
                    { MaterialTheme.colorScheme.background },
                ),
            ),
            listOf(
                ColorInfo(
                    "surface",
                    { MaterialTheme.colorScheme.surface },
                    { MaterialTheme.colorScheme.onSurface },
                ),
                ColorInfo(
                    "onSurface",
                    { MaterialTheme.colorScheme.onSurface },
                    { MaterialTheme.colorScheme.surface },
                ),
                ColorInfo(
                    "surfaceVariant",
                    { MaterialTheme.colorScheme.surfaceVariant },
                    { MaterialTheme.colorScheme.onSurfaceVariant },
                ),
                ColorInfo(
                    "onSurfaceVariant",
                    { MaterialTheme.colorScheme.onSurfaceVariant },
                    { MaterialTheme.colorScheme.surfaceVariant },
                ),
            ),
            listOf(
                ColorInfo(
                    "outline",
                    { MaterialTheme.colorScheme.outline },
                    { Color.White },
                ),
                ColorInfo(
                    "inverseSurface",
                    { MaterialTheme.colorScheme.inverseSurface },
                    { MaterialTheme.colorScheme.surface },
                ),
                ColorInfo(
                    "inverseOnSurface",
                    { MaterialTheme.colorScheme.inverseOnSurface },
                    { MaterialTheme.colorScheme.onSurface },
                ),
                ColorInfo(
                    "inversePrimary",
                    { MaterialTheme.colorScheme.inversePrimary },
                    { MaterialTheme.colorScheme.primary },
                ),
                ColorInfo(
                    "scrim",
                    { MaterialTheme.colorScheme.scrim },
                    { Color.White },
                ),
                ColorInfo(
                    "surfaceTint",
                    { MaterialTheme.colorScheme.surfaceTint },
                    { MaterialTheme.colorScheme.inversePrimary },
                ),
            ),
        )

    LazyColumn {
        item {
            Row {
                Text(
                    modifier = Modifier.padding(8.dp).weight(1f),
                    text = "Light ColorScheme",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    modifier = Modifier.padding(8.dp).weight(1f),
                    text = "Dark ColorScheme",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        items(items) { colorGroup ->
            Row {
                val lightColorScheme = dynamicColorScheme(seedColor = seedColor, isDark = false, isAmoled = false)
                MaterialTheme(colorScheme = lightColorScheme) {
                    PaletteCard(modifier = Modifier.padding(8.dp).weight(1f), list = colorGroup)
                }

                val darkColorScheme = dynamicColorScheme(seedColor = seedColor, isDark = true, isAmoled = false)
                MaterialTheme(colorScheme = darkColorScheme) {
                    PaletteCard(modifier = Modifier.padding(8.dp).weight(1f), list = colorGroup)
                }
            }
        }
    }
}

@Composable
private fun PaletteCard(
    list: List<ColorInfo>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(2.dp),
    ) {
        Column {
            list.forEach { colorInfo ->
                Text(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colorInfo.color())
                            .padding(16.dp),
                    text = colorInfo.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = colorInfo.textColor(),
                )
            }
        }
    }
}
