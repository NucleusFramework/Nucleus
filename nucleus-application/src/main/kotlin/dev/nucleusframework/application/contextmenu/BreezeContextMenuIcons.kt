package dev.nucleusframework.application.contextmenu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 16×16 monochrome Breeze action icons. Unicode fallbacks (✂ / ⎘ / ☑)
 * pick different fonts and emoji presentation, so they never share a size.
 */
internal fun ContextMenuIcon.toBreezeVector(): ImageVector? =
    when (this) {
        ContextMenuIcon.Cut -> BreezeMenuIcons.Cut
        ContextMenuIcon.Copy -> BreezeMenuIcons.Copy
        ContextMenuIcon.Paste -> BreezeMenuIcons.Paste
        ContextMenuIcon.SelectAll -> BreezeMenuIcons.SelectAll
        ContextMenuIcon.Delete -> BreezeMenuIcons.Delete
        ContextMenuIcon.Folder -> BreezeMenuIcons.Folder
        is ContextMenuIcon.SfSymbol -> null
    }

private object BreezeMenuIcons {
    val Cut: ImageVector by lazy {
        breezeMenuIcon(
            "Cut",
            "M 5 2 C 4.567 2.75 4.34825 2.75 4.78125 3.5 L 7.40625 8 L 5.90625 10.21875 " +
                "C 5.63425 10.08075 5.325 10 5 10 C 3.895 10 3 10.895 3 12 C 3 13.105 " +
                "3.895 14 5 14 C 6.105 14 7 13.105 7 12 C 7 11.595 6.86325 11.22125 " +
                "6.65625 10.90625 C 6.65125 10.89825 6.66125 10.882 6.65625 10.875 " +
                "L 7.09375 10.25 C 7.89575 10.225 8.008 9.65725 8.5 9.65625 C 8.511 " +
                "9.65625 8.52025 9.65525 8.53125 9.65625 L 8.5625 9.71875 L 9.34375 " +
                "10.875 L 9.34375 10.90625 C 9.13775 11.22125 8.9980469 11.595 " +
                "8.9980469 12 C 8.9980469 13.105 9.8930469 14 10.998047 14 C 12.103047 " +
                "14 12.998047 13.105 12.998047 12 C 12.998047 10.895 12.103047 10 " +
                "10.998047 10 C 10.673047 10 10.36475 10.08075 10.09375 10.21875 " +
                "L 8.59375 8 C 8.59375 8 11.22875 3.492 11.21875 3.5 C 11.65175 2.75 " +
                "11.431047 2.75 10.998047 2 L 7.9980469 7.125 L 5 2 z M 7.9980469 8 " +
                "L 8 8 C 8.276 8 8.5 8.224 8.5 8.5 C 8.5 8.754 8.3075 8.968 8.0625 9 " +
                "L 7.9375 9 C 7.6925 8.968 7.4980469 8.754 7.4980469 8.5 C 7.4980469 " +
                "8.224 7.7220469 8 7.9980469 8 z M 4.9980469 11 C 5.5500469 11 " +
                "5.9980469 11.448 5.9980469 12 C 5.9980469 12.552 5.5500469 13 " +
                "4.9980469 13 C 4.4460469 13 3.9980469 12.552 3.9980469 12 C 3.9980469 " +
                "11.448 4.4460469 11 4.9980469 11 z M 10.998047 11 C 11.550047 11 " +
                "11.998047 11.448 11.998047 12 C 11.998047 12.552 11.550047 13 " +
                "10.998047 13 C 10.446047 13 9.9980469 12.552 9.9980469 12 C 9.9980469 " +
                "11.448 10.446047 11 10.998047 11 z",
        )
    }
    val Copy: ImageVector by lazy {
        breezeMenuIcon(
            "Copy",
            "M 3 2 L 3 12 L 6 12 L 6 14 L 14 14 L 14 7 L 11 4 L 10 4 L 8 2 L 3 2 Z " +
                "M 4 3 L 7 3 L 7 4 L 6 4 L 6 11 L 4 11 L 4 3 Z M 7 5 L 10 5 L 10 8 " +
                "L 13 8 L 13 13 L 7 13 L 7 5 Z",
        )
    }
    val Paste: ImageVector by lazy {
        breezeMenuIcon(
            "Paste",
            "M 5 2 L 5 3 L 3 3 L 3 14 L 7 14 L 8 14 L 12 14 L 13 14 L 13 13 L 13 9 " +
                "L 13 3 L 11 3 L 11 2 L 5 2 z M 4 4 L 5 4 L 5 5 L 11 5 L 11 4 L 12 4 " +
                "L 12 6 L 12 12 L 12 13 L 8 13 L 7 13 L 4 13 L 4 12 L 4 6 L 4 4 z " +
                "M 5 7 L 5 8 L 10 8 L 10 7 L 5 7 z M 5 10 L 5 11 L 8 11 L 8 10 L 5 10 z",
        )
    }
    val SelectAll: ImageVector by lazy {
        breezeMenuIcon(
            "SelectAll",
            "M 2 2 L 2 5 L 3 5 L 3 3 L 5 3 L 5 2 L 3 2 L 2 2 z M 11 2 L 11 3 L 13 3 " +
                "L 13 4 L 13 5 L 14 5 L 14 3 L 14 2 L 11 2 z M 4 4 L 4 7 L 7 7 L 7 4 " +
                "L 4 4 z M 9 4 L 9 7 L 12 7 L 12 4 L 9 4 z M 4 9 L 4 12 L 7 12 L 7 9 " +
                "L 4 9 z M 9 9 L 9 12 L 12 12 L 12 9 L 9 9 z M 2 11 L 2 12 L 2 13 " +
                "L 2 14 L 5 14 L 5 13 L 3 13 L 3 12 L 3 11 L 2 11 z M 13 11 L 13 12 " +
                "L 13 13 L 12 13 L 11 13 L 11 14 L 12 14 L 13 14 L 14 14 L 14 12 " +
                "L 14 11 L 13 11 z",
        )
    }
    val Delete: ImageVector by lazy {
        breezeMenuIcon(
            "Delete",
            "m5 2v2h1v-1h4v1h1v-2h-5zm-3 3v1h2v8h8v-8h2v-1zm3 1h6v7h-6z",
        )
    }
    val Folder: ImageVector by lazy {
        breezeMenuIcon(
            "Folder",
            "M 2 2 L 2 3 L 2 6 L 2 7 L 2 13 L 2 14 L 14 14 L 14 13 L 14 6 L 14 5 " +
                "L 14 4 L 9 4 L 7 2 L 7 2 L 7 2 L 3 2 L 2 2 z M 3 3 L 6.6 3 L 7.6 4 " +
                "L 7 4 L 7 4 L 7 4 L 5 6 L 3 6 L 3 3 z M 3 7 L 13 7 L 13 13 L 3 13 L 3 7 z",
        )
    }
}

private fun breezeMenuIcon(
    name: String,
    pathData: String,
): ImageVector {
    val builder =
        ImageVector.Builder(
            name = "BreezeMenu$name",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f,
        )
    builder.addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black),
    )
    return builder.build()
}
