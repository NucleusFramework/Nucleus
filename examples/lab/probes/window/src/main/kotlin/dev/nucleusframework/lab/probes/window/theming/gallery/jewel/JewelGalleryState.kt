// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license.
package dev.nucleusframework.lab.probes.window.theming.gallery.jewel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Banners
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Borders
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.BrushesShowcase
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Buttons
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Checkboxes
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.ChipsAndTrees
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.ComboBoxes
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Icons
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Links
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Menus
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.ProgressBar
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.RadioButtons
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Scrollbars
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.SegmentedControls
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Sliders
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.SplitLayouts
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Tabs
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.TextAreas
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.TextFields
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.Tooltips
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.components.TypographyShowcase
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.markdown.MarkdownPage
import org.jetbrains.jewel.intui.standalone.styling.default
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility
import org.jetbrains.jewel.ui.icon.IconKey

/** A showcase page; [framed] pages get the title and padding, the Markdown editor takes the whole area. */
@Immutable
internal class ViewInfo(
    val title: String,
    val iconKey: IconKey,
    val framed: Boolean = true,
    val content: @Composable () -> Unit,
)

/** The showcase's pages and selection (jewel-demo's `ComponentsViewModel`, held per window). */
internal class JewelGalleryState {
    private var outerSplitState by mutableStateOf(SplitLayoutState(0.5f))
    private var verticalSplitState by mutableStateOf(SplitLayoutState(0.5f))
    private var innerSplitState by mutableStateOf(SplitLayoutState(0.5f))

    private val alwaysVisibleScrollbarVisibility = ScrollbarVisibility.AlwaysVisible.default()
    private val whenScrollingScrollbarVisibility = ScrollbarVisibility.WhenScrolling.default()

    val views: List<ViewInfo> =
        listOf(
            ViewInfo(title = "Buttons", iconKey = ShowcaseIcons.Components.button, content = { Buttons() }),
            ViewInfo(
                title = "Radio Buttons",
                iconKey = ShowcaseIcons.Components.radioButton,
                content = { RadioButtons() },
            ),
            ViewInfo(title = "Checkboxes", iconKey = ShowcaseIcons.Components.checkbox, content = { Checkboxes() }),
            ViewInfo(title = "Combo Boxes", iconKey = ShowcaseIcons.Components.comboBox, content = { ComboBoxes() }),
            ViewInfo(title = "Menus", iconKey = ShowcaseIcons.Components.menu, content = { Menus() }),
            ViewInfo(title = "Chips and trees", iconKey = ShowcaseIcons.Components.tree, content = { ChipsAndTrees() }),
            ViewInfo(
                title = "Progressbar",
                iconKey = ShowcaseIcons.Components.progressBar,
                content = { ProgressBar() },
            ),
            ViewInfo(title = "Icons", iconKey = ShowcaseIcons.Components.toolbar, content = { Icons() }),
            ViewInfo(title = "Links", iconKey = ShowcaseIcons.Components.links, content = { Links() }),
            ViewInfo(title = "Borders", iconKey = ShowcaseIcons.Components.borders, content = { Borders() }),
            ViewInfo(
                title = "Segmented Controls",
                iconKey = ShowcaseIcons.Components.segmentedControls,
                content = { SegmentedControls() },
            ),
            ViewInfo(title = "Sliders", iconKey = ShowcaseIcons.Components.slider, content = { Sliders() }),
            ViewInfo(title = "Tabs", iconKey = ShowcaseIcons.Components.tabs, content = { Tabs() }),
            ViewInfo(title = "Tooltips", iconKey = ShowcaseIcons.Components.tooltip, content = { Tooltips() }),
            ViewInfo(title = "TextAreas", iconKey = ShowcaseIcons.Components.textArea, content = { TextAreas() }),
            ViewInfo(title = "TextFields", iconKey = ShowcaseIcons.Components.textField, content = { TextFields() }),
            ViewInfo(
                title = "Scrollbars",
                iconKey = ShowcaseIcons.Components.scrollbar,
                content = {
                    Scrollbars(
                        alwaysVisibleScrollbarVisibility = alwaysVisibleScrollbarVisibility,
                        whenScrollingScrollbarVisibility = whenScrollingScrollbarVisibility,
                    )
                },
            ),
            ViewInfo(
                title = "SplitLayout",
                iconKey = ShowcaseIcons.Components.splitlayout,
                content = {
                    SplitLayouts(
                        outerSplitState,
                        verticalSplitState,
                        innerSplitState,
                        onResetState = {
                            outerSplitState = SplitLayoutState(0.5f)
                            verticalSplitState = SplitLayoutState(0.5f)
                            innerSplitState = SplitLayoutState(0.5f)
                        },
                    )
                },
            ),
            ViewInfo(title = "Banners", iconKey = ShowcaseIcons.Components.banners, content = { Banners() }),
            ViewInfo(
                title = "Typography",
                iconKey = ShowcaseIcons.Components.typography,
                content = { TypographyShowcase() },
            ),
            ViewInfo(title = "Brushes", iconKey = ShowcaseIcons.Components.brush, content = { BrushesShowcase() }),
            ViewInfo(
                title = "Markdown",
                iconKey = ShowcaseIcons.markdown,
                framed = false,
                content = { MarkdownPage() },
            ),
        )

    var currentView: ViewInfo by mutableStateOf(views.first())
}
