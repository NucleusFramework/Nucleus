@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Paint as SkiaPaint

private const val SUBMENU_OPEN_DELAY_MS = 200L
private const val SUBMENU_CLOSE_DELAY_MS = 160L

internal class ContextMenuFlyoutColors(
    val surface: Color,
    val text: Color,
    val textDisabled: Color,
    val hover: Color,
    val separator: Color,
    val border: Color,
)

/**
 * One CSS `box-shadow` layer under the menu surface: the menu's rounded
 * rectangle grown by [spread], moved down by [offsetY] and blurred with the
 * CSS blur radius [blur] — a Gaussian whose standard deviation is half the
 * radius, as css-backgrounds-3 specifies and as GTK and Breeze both render.
 *
 * The OS menus the flyouts imitate all describe their shadow this way
 * (libadwaita's `_popovers.scss`, Breeze's `ShadowParams`, Fluent 2's shadow
 * tokens), so the themes carry those declarations verbatim. Compose's own
 * `Modifier.shadow` is a Material elevation model instead — and on desktop its
 * `ambientColor` / `spotColor` alphas are further multiplied by fixed 0.039 /
 * 0.19 factors — so no elevation value reproduces a given `box-shadow`.
 */
internal class ContextMenuBoxShadow(
    val offsetY: Dp,
    val blur: Dp,
    val color: Color,
    val spread: Dp = 0.dp,
)

internal class ContextMenuFlyoutTheme(
    val menuShape: RoundedCornerShape,
    val itemShape: RoundedCornerShape,
    val uiFont: FontFamily,
    val iconFont: FontFamily,
    val chevron: String,
    val chevronSize: TextUnit,
    val chevronAlpha: Float,
    val minWidth: Dp,
    val maxWidth: Dp,
    val menuPadding: PaddingValues,
    val itemHeight: Dp,
    val itemHorizontalPadding: Dp,
    val itemOuterHorizontalPadding: Dp,
    val separatorPadding: PaddingValues,
    val iconSize: Dp,
    val iconGap: Dp,
    val shadowPad: Dp,
    val shadows: (dark: Boolean) -> List<ContextMenuBoxShadow>,
    val showIcons: Boolean,
    val shortcutGap: Dp,
    val shortcutSize: TextUnit,
    val shortcutAlpha: Float,
    val colors: (dark: Boolean) -> ContextMenuFlyoutColors,
    val glyph: (ContextMenuIcon) -> String?,
    val vector: (ContextMenuIcon) -> ImageVector? = { null },
) {
    internal fun hasIcon(icon: ContextMenuIcon?): Boolean {
        if (icon == null) return false
        return vector(icon) != null || glyph(icon) != null
    }
}

@Composable
internal fun ContextMenuFlyout(
    status: ContextMenuState.Status.Open,
    entries: List<ContextMenuEntry>,
    theme: ContextMenuFlyoutTheme,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val menuDensity = LocalContextMenuDensity.current ?: LocalDensity.current
    DismissOnWindowFocusLoss(onDismiss)
    Popup(
        popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        CompositionLocalProvider(LocalDensity provides menuDensity) {
            ContextMenuFlyoutSurface(
                entries = entries,
                theme = theme,
                dark = dark,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * Closes the menu as soon as the owning window loses focus.
 *
 * The flyout is a native popup surface whose outside-click monitor only
 * observes this process, so a click that activates another application never
 * reaches it: on Windows `WH_MOUSE` is a thread-local hook, and on Linux the
 * scene layer is told about outside presses by the *parent window's* own
 * pointer input (`TaoPopupHostLinux.registerOutsidePressListener`) — Wayland
 * has no way to watch another surface's clicks at all. Window focus is the
 * one signal every backend does deliver, and it also covers dismissals with
 * no click behind them (Alt+Tab, the taskbar, a notification stealing
 * activation), which is what the OS menus do.
 *
 * Reads [LocalWindowInfo] from the *parent* scene: inside [Popup] the layer
 * publishes its own `WindowInfo` with `isWindowFocused` pinned to `true`.
 */
@Composable
private fun DismissOnWindowFocusLoss(onDismiss: () -> Unit) {
    val windowInfo = LocalWindowInfo.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }
            .windowFocusLosses()
            .collect { currentOnDismiss() }
    }
}

/**
 * Emits once per focused → unfocused transition, ignoring a leading unfocused
 * run so a backend that has not yet reported focus when the menu opens does
 * not dismiss it immediately.
 */
internal fun Flow<Boolean>.windowFocusLosses(): Flow<Unit> =
    dropWhile { focused -> !focused }
        .filter { focused -> !focused }
        .map { }

@Composable
private fun ContextMenuFlyoutSurface(
    entries: List<ContextMenuEntry>,
    theme: ContextMenuFlyoutTheme,
    dark: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = theme.colors(dark)
    val reserveIcon =
        theme.showIcons &&
            entries.any { entry ->
                entry is ContextMenuEntry.Item && theme.hasIcon(entry.icon)
            }
    val maxWidth = theme.maxWidth.takeOrElse { 320.dp }
    Box(Modifier.padding(theme.shadowPad)) {
        Column(
            Modifier
                .widthIn(min = theme.minWidth, max = maxWidth)
                .boxShadows(theme.shadows(dark), theme.menuShape)
                .width(IntrinsicSize.Max)
                .clip(theme.menuShape)
                .border(1.dp, colors.border, theme.menuShape)
                .background(colors.surface)
                .padding(theme.menuPadding),
        ) {
            entries.forEach { entry ->
                when (entry) {
                    is ContextMenuEntry.Separator ->
                        Box(
                            Modifier
                                .padding(theme.separatorPadding)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.separator),
                        )
                    is ContextMenuEntry.Item ->
                        ContextMenuFlyoutRow(
                            label = entry.label,
                            enabled = entry.enabled,
                            icon = entry.icon,
                            shortcut = entry.shortcut,
                            reserveIcon = reserveIcon,
                            chevron = false,
                            theme = theme,
                            colors = colors,
                            onClick = {
                                onDismiss()
                                entry.onClick()
                            },
                        )
                    is ContextMenuEntry.Submenu ->
                        ContextMenuFlyoutSubmenu(
                            entry = entry,
                            reserveIcon = reserveIcon,
                            theme = theme,
                            dark = dark,
                            colors = colors,
                            onDismiss = onDismiss,
                        )
                }
            }
        }
    }
}

/**
 * Draws [shadows] behind the content, each as the content's rounded rectangle
 * of [shape] under a blur mask. The content is opaque and drawn on top, so
 * nothing of the shadow shows through the surface itself, as with CSS.
 */
private fun Modifier.boxShadows(
    shadows: List<ContextMenuBoxShadow>,
    shape: RoundedCornerShape,
): Modifier =
    drawWithCache {
        val radius = shape.topStart.toPx(size, this)
        val layers =
            shadows.map { shadow ->
                val sigma = shadow.blur.toPx() / 2f
                val paint =
                    SkiaPaint().apply {
                        color = shadow.color.toArgb()
                        if (sigma > 0f) maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma)
                    }
                val spread = shadow.spread.toPx()
                val offsetY = shadow.offsetY.toPx()
                val rect =
                    RRect.makeLTRB(
                        -spread,
                        offsetY - spread,
                        size.width + spread,
                        size.height + offsetY + spread,
                        radius + spread,
                    )
                rect to paint
            }
        onDrawBehind {
            drawIntoCanvas { canvas ->
                layers.forEach { (rect, paint) -> canvas.nativeCanvas.drawRRect(rect, paint) }
            }
        }
    }

@Composable
private fun ContextMenuFlyoutSubmenu(
    entry: ContextMenuEntry.Submenu,
    reserveIcon: Boolean,
    theme: ContextMenuFlyoutTheme,
    dark: Boolean,
    colors: ContextMenuFlyoutColors,
    onDismiss: () -> Unit,
) {
    val rowInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowInteraction.collectIsHoveredAsState()
    var flyoutHovered by remember { mutableStateOf(false) }
    var showFlyout by remember { mutableStateOf(false) }
    LaunchedEffect(rowHovered, flyoutHovered) {
        if (rowHovered || flyoutHovered) {
            delay(SUBMENU_OPEN_DELAY_MS)
            showFlyout = true
        } else {
            delay(SUBMENU_CLOSE_DELAY_MS)
            showFlyout = false
        }
    }
    Box {
        ContextMenuFlyoutRow(
            label = entry.label,
            enabled = true,
            icon = null,
            shortcut = null,
            reserveIcon = reserveIcon,
            chevron = true,
            theme = theme,
            colors = colors,
            interactionSource = rowInteraction,
            onClick = { showFlyout = true },
        )
        if (showFlyout) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 0),
                properties = PopupProperties(focusable = false),
            ) {
                val flyoutInteraction = remember { MutableInteractionSource() }
                val hoveringFlyout by flyoutInteraction.collectIsHoveredAsState()
                LaunchedEffect(hoveringFlyout) {
                    flyoutHovered = hoveringFlyout
                }
                Box(Modifier.hoverable(flyoutInteraction)) {
                    ContextMenuFlyoutSurface(
                        entries = entry.items,
                        theme = theme,
                        dark = dark,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextMenuFlyoutRow(
    label: String,
    enabled: Boolean,
    icon: ContextMenuIcon?,
    shortcut: String?,
    reserveIcon: Boolean,
    chevron: Boolean,
    theme: ContextMenuFlyoutTheme,
    colors: ContextMenuFlyoutColors,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit,
) {
    val hovered by interactionSource.collectIsHoveredAsState()
    val content = if (enabled) colors.text else colors.textDisabled
    val iconSp = with(LocalDensity.current) { theme.iconSize.toSp() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = theme.itemOuterHorizontalPadding)
            .clip(theme.itemShape)
            .hoverable(interactionSource, enabled = enabled)
            .background(if (hovered && enabled) colors.hover else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ).height(theme.itemHeight)
            .padding(horizontal = theme.itemHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (reserveIcon) {
            ContextMenuFlyoutIcon(
                icon = icon,
                content = content,
                iconSp = iconSp,
                theme = theme,
            )
            Spacer(Modifier.width(theme.iconGap))
        }
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style =
                TextStyle(
                    color = content,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = theme.uiFont,
                ),
            maxLines = 1,
        )
        if (!shortcut.isNullOrEmpty()) {
            Spacer(Modifier.width(theme.shortcutGap))
            BasicText(
                text = shortcut,
                style =
                    TextStyle(
                        color = if (enabled) content.copy(alpha = theme.shortcutAlpha) else colors.textDisabled,
                        fontSize = theme.shortcutSize,
                        fontFamily = theme.uiFont,
                    ),
                maxLines = 1,
            )
        }
        if (chevron) {
            Spacer(Modifier.width(theme.iconGap))
            BasicText(
                text = theme.chevron,
                style =
                    TextStyle(
                        color = content.copy(alpha = theme.chevronAlpha),
                        fontSize = theme.chevronSize,
                        fontFamily = theme.iconFont,
                    ),
            )
        }
    }
}

@Composable
private fun ContextMenuFlyoutIcon(
    icon: ContextMenuIcon?,
    content: Color,
    iconSp: TextUnit,
    theme: ContextMenuFlyoutTheme,
) {
    val vector = icon?.let(theme.vector)
    val glyph = icon?.let(theme.glyph)
    val iconModifier = Modifier.requiredSize(theme.iconSize)
    if (vector != null) {
        Box(
            iconModifier.paint(
                painter = rememberVectorPainter(vector),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(content),
            ),
        )
    } else if (glyph != null) {
        Box(iconModifier, contentAlignment = Alignment.Center) {
            BasicText(
                text = glyph,
                style =
                    TextStyle(
                        color = content,
                        fontSize = iconSp,
                        fontFamily = theme.iconFont,
                        textAlign = TextAlign.Center,
                    ),
            )
        }
    } else {
        Spacer(iconModifier)
    }
}
