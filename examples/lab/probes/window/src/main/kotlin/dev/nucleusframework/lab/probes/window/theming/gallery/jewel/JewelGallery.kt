// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license.
package dev.nucleusframework.lab.probes.window.theming.gallery.jewel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.modifier.trackActivation
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.standalone.styling.defaults
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.SelectableIconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.IconButtonMetrics
import org.jetbrains.jewel.ui.component.styling.IconButtonStyle
import org.jetbrains.jewel.ui.component.styling.LocalTooltipStyle
import org.jetbrains.jewel.ui.component.styling.TooltipAutoHideBehavior
import org.jetbrains.jewel.ui.component.styling.TooltipMetrics
import org.jetbrains.jewel.ui.component.styling.TooltipStyle
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.theme.iconButtonStyle
import org.jetbrains.jewel.ui.typography
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Jewel component showcase: IntelliJ's stripe toolbar of pages on the leading edge, the
 * page on the right. Expects an `IntUiTheme` around it.
 */
@Composable
fun JewelGallery(modifier: Modifier = Modifier) {
    val state = remember { JewelGalleryState() }
    Row(
        modifier.trackActivation().fillMaxSize().background(JewelTheme.globalColors.panelBackground).semantics {
            isTraversalGroup = true
        },
    ) {
        ComponentsToolBar(
            // See JBUI.CurrentTheme.Toolbar.stripeToolbarButton* defaults
            buttonMetrics =
                IconButtonMetrics.defaults(
                    cornerSize = CornerSize(6.dp),
                    padding = PaddingValues(5.dp),
                    minSize = DpSize(40.dp, 40.dp),
                ),
            views = state.views,
            currentView = state.currentView,
            setCurrentView = { state.currentView = it },
        )
        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
        // Several pages (banners, tooltips…) render Markdown: the styling must be provided, as
        // jewel-demo's app root did.
        ProvideMarkdownStyling { ComponentView(state.currentView) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComponentsToolBar(
    buttonMetrics: IconButtonMetrics,
    views: List<ViewInfo>,
    currentView: ViewInfo,
    setCurrentView: (ViewInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    ZeroDelayNeverHideTooltips {
        Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
            val iconButtonStyle = JewelTheme.iconButtonStyle
            val style = remember(iconButtonStyle) { IconButtonStyle(iconButtonStyle.colors, buttonMetrics) }
            views.forEach { viewInfo ->
                SelectableIconActionButton(
                    key = viewInfo.iconKey,
                    contentDescription = "Show ${viewInfo.title}",
                    selected = currentView == viewInfo,
                    onClick = { setCurrentView(viewInfo) },
                    style = style,
                    tooltip = { Text(viewInfo.title) },
                    tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.CenterEnd, Alignment.CenterEnd),
                    extraHints = arrayOf(Size(20)),
                )
            }
        }
    }
}

@Composable
private fun ComponentView(view: ViewInfo) {
    if (!view.framed) {
        view.content()
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(view.title, style = JewelTheme.typography.h1TextStyle)
        view.content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZeroDelayNeverHideTooltips(content: @Composable () -> Unit) {
    val currentTooltipStyle = LocalTooltipStyle.current
    val updatedStyle =
        remember(currentTooltipStyle) {
            TooltipStyle(
                colors = currentTooltipStyle.colors,
                metrics =
                    with(currentTooltipStyle.metrics) {
                        TooltipMetrics(
                            contentPadding = contentPadding,
                            showDelay = 0.milliseconds,
                            cornerSize = cornerSize,
                            borderWidth = borderWidth,
                            shadowSize = shadowSize,
                            placement = placement,
                            regularDisappearDelay = regularDisappearDelay,
                            fullDisappearDelay = fullDisappearDelay,
                        )
                    },
                autoHideBehavior = TooltipAutoHideBehavior.Never,
            )
        }

    CompositionLocalProvider(LocalTooltipStyle provides updatedStyle, content = content)
}
