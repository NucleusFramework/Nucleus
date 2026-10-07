package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material.Colors as M2Colors

@Composable
internal fun TypographyPage() {
    val type = MaterialTheme.typography
    val styles: List<Pair<String, TextStyle>> =
        listOf(
            "H1" to type.h1,
            "H2" to type.h2,
            "H3" to type.h3,
            "H4" to type.h4,
            "H5" to type.h5,
            "H6" to type.h6,
            "Subtitle 1" to type.subtitle1,
            "Subtitle 2" to type.subtitle2,
            "Body 1" to type.body1,
            "Body 2" to type.body2,
            "BUTTON" to type.button,
            "Caption" to type.caption,
            "OVERLINE" to type.overline,
        )
    Page {
        Demo("Type scale") {
            styles.forEach { (name, style) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${style.fontSize.value.toInt()} sp",
                        style = type.caption,
                        modifier = Modifier.size(width = 56.dp, height = 20.dp),
                    )
                    Text(name, style = style, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun ColorsPage() {
    Page {
        Demo("This window's palette") { Palette(MaterialTheme.colors) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Demo("lightColors()", Modifier.weight(1f)) { Palette(lightColors()) }
            Demo("darkColors()", Modifier.weight(1f)) { Palette(darkColors()) }
        }
    }
}

@Composable
private fun Palette(colors: M2Colors) {
    val roles =
        listOf(
            Triple("primary", colors.primary, colors.onPrimary),
            Triple("primaryVariant", colors.primaryVariant, colors.onPrimary),
            Triple("secondary", colors.secondary, colors.onSecondary),
            Triple("secondaryVariant", colors.secondaryVariant, colors.onSecondary),
            Triple("background", colors.background, colors.onBackground),
            Triple("surface", colors.surface, colors.onSurface),
            Triple("error", colors.error, colors.onError),
        )
    Column(Modifier.fillMaxWidth()) {
        roles.forEach { (name, background, content) ->
            Box(Modifier.fillMaxWidth().background(background).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(name, color = content, style = MaterialTheme.typography.body2)
            }
        }
    }
}

@Composable
internal fun SurfacesPage() {
    val elevations = listOf(0, 1, 2, 4, 6, 8, 12, 16, 24)
    Page {
        Demo("Card elevation: the shadow grows with the dp, the surface stays put") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(8.dp),
            ) {
                elevations.forEach { elevation ->
                    Card(Modifier.size(104.dp), elevation = elevation.dp) {
                        Box(contentAlignment = Alignment.Center) { Text("$elevation dp") }
                    }
                }
            }
        }
        Demo("Dark theme overlay: in dark, higher surfaces turn lighter (ElevationOverlay)") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(8.dp),
            ) {
                listOf(0, 1, 4, 8, 16, 24).forEach { elevation ->
                    Surface(Modifier.size(width = 120.dp, height = 64.dp), elevation = elevation.dp) {
                        Box(contentAlignment = Alignment.Center) { Text("Surface $elevation") }
                    }
                }
            }
        }
        Demo("Shapes") {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(8.dp)) {
                Surface(Modifier.size(96.dp), shape = MaterialTheme.shapes.small, elevation = 4.dp) {
                    Box(contentAlignment = Alignment.Center) { Text("small") }
                }
                Surface(Modifier.size(96.dp), shape = MaterialTheme.shapes.medium, elevation = 4.dp) {
                    Box(contentAlignment = Alignment.Center) { Text("medium") }
                }
                Surface(Modifier.size(96.dp), shape = CutCornerShape(16.dp), elevation = 4.dp) {
                    Box(contentAlignment = Alignment.Center) { Text("cut") }
                }
                Surface(
                    Modifier.size(96.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colors.primary,
                    elevation = 8.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("circle") }
                }
                Surface(
                    Modifier.size(96.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f)),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("outlined") }
                }
            }
        }
        Demo("Dividers") {
            Text("Full width")
            Divider()
            Text("Inset by 56 dp")
            Divider(startIndent = 56.dp)
            Text("Thick, coloured")
            Divider(color = MaterialTheme.colors.secondary, thickness = 4.dp)
            Divider(color = Color.Transparent)
        }
    }
}
