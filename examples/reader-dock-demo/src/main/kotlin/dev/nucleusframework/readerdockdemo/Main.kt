package dev.nucleusframework.readerdockdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.application.Satellite
import dev.nucleusframework.application.Tab
import dev.nucleusframework.application.TabWindows
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.material.rememberMaterialTitleBarStyle
import dev.nucleusframework.window.material.rememberMaterialWindowStyle
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.TabWindowGroup
import dev.nucleusframework.window.tao.TabWorkspace

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF8AA4FF),
        surface = Color(0xFF1B1D22),
        surfaceContainer = Color(0xFF23262D),
        surfaceContainerHigh = Color(0xFF2B2F38),
        background = Color(0xFF14161A),
        outlineVariant = Color(0xFF3A3F4A),
    )

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF3F5DDB),
        surface = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFF2F3F7),
        surfaceContainerHigh = Color(0xFFE6E8EF),
        background = Color(0xFFEDEFF4),
        outlineVariant = Color(0xFFD5D8E0),
    )

/**
 * A right-to-left book reader whose seforim are tabs and whose every pane is a
 * satellite — the two multi-window archetypes composed.
 *
 * The pane tree of a classic split-pane reader — books | contents | notes on
 * the right, the text in the middle with the translation beside it, the
 * commentaries under both — is one `DockLayout`: the right side is *layered*,
 * so its three panes are three columns each with its own width and splitter,
 * and the side order puts the right side first so the commentaries stop at it
 * and run under the translation. The dividers are the reader's own 1 dp lines
 * with a 5 dp grip; the headers are the reader's own hover strips; the
 * *Islands* style turns every pane into a rounded card.
 *
 * On top of that, `TabWindows` owns the windows and each sefer is a `Tab`. The
 * strip is the top of the window; everything below it — the two activity bars
 * and the dock — is the reader's own chrome, hung on `windowBodyWrapper`. The
 * **dock belongs to the window** and the tabs change what it holds: the text
 * in the middle is the selected sefer, and every pane draws that same sefer.
 * Tear a tab out and the new window arrives with a dock of its own, so two
 * seforim are read side by side, each with its own pane widths, its own
 * commentaries, its own layout to save and restore. Nothing about a tab change
 * creates or destroys a panel — see [ReaderState].
 *
 * The books pane is the other half of the tie: clicking a sefer there selects
 * its tab, and the tab strip's "+" opens another.
 */
fun main() =
    nucleusApplication {
        val reader = remember { ReaderState() }
        val dark = isSystemInDarkMode()
        val colors = if (dark) DarkColors else LightColors

        ReaderTheme(colors) {
            TabWindows(
                workspace = reader.tabs,
                // Right to left, like the rest of the reader: the first sefer
                // is the rightmost tab and the "+" follows the last one
                // leftwards, and the strip animates the same way.
                strip = {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        ReaderTabStrip(reader, onNewBook = reader::openBook)
                    }
                },
                windowWrapper = { content ->
                    WindowBackground(colors.background)
                    WindowAppearance(if (dark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)
                    // This window joins its own pane workspace, once, for as
                    // long as it lives: that is what keeps a tab change from
                    // touching the dock at all.
                    reader.tabs.groupOf(nucleusWindow.unsafe.taoWindow)?.let {
                        JoinSatelliteWorkspace(reader.panesOfWindow(it.id))
                    }
                    Surface(Modifier.fillMaxSize(), color = colors.background) { content() }
                },
                // Under the tab strip, which stays at the very top of the
                // window: the dock and the activity bars belong to the window,
                // the text between them is whichever sefer the strip selected.
                windowBodyWrapper = { body ->
                    val group = reader.tabs.groupOf(nucleusWindow.unsafe.taoWindow)
                    if (group == null) body() else ReaderBody(reader, group) { body() }
                },
                onLastWindowClosed = ::exitApplication,
            )

            // Every sefer, declared once: the workspace decides which window
            // shows it, and the panes of that window draw it.
            for (book in reader.books) {
                key(book.id) {
                    Tab(reader.tabs, id = book.id, title = book.title) { BookText(reader, book) }
                    DropClosedTab(reader, book.id)
                }
            }

            // One dock of panes per reader window, declared at application
            // scope so they are not tied to whichever sefer is showing.
            for (group in rememberTabGroups(reader.tabs)) {
                key(group.id) { WindowPanes(reader, group, colors) }
            }
        }
    }

/**
 * The tab windows, mirrored out of the workspace through an effect: the groups
 * are created by `Tab`, declared after this list is read, and Compose drops an
 * invalidation aimed at a scope it has just composed.
 */
@Composable
private fun rememberTabGroups(workspace: TabWorkspace): List<TabWindowGroup> {
    var groups by remember(workspace) { mutableStateOf(workspace.groups.toList()) }
    LaunchedEffect(workspace) {
        snapshotFlow { workspace.groups.toList() }.collect { groups = it }
    }
    return groups
}

/**
 * The panes of one reader window: one satellite per [Pane], declared against
 * that window's workspace and drawing whichever sefer the window is showing.
 *
 * The entries are per window so a tab change creates and destroys nothing —
 * only the content changes, and what the reader remembers per book lives in
 * [ReaderState.stateOf].
 */
@Composable
private fun WindowPanes(
    reader: ReaderState,
    group: TabWindowGroup,
    colors: ColorScheme,
) {
    val workspace = reader.panesOfWindow(group.id)
    DisposableEffect(reader, group.id) {
        onDispose { reader.forgetWindow(group.id) }
    }
    // The selected tab of *this* window, resolved back to the sefer. The tab id
    // is the book id, which is what ties the two archetypes together without
    // either knowing about the other.
    val book = reader.tabs.selectedTab(group)?.let { reader.book(it.id) }

    ReaderTheme(colors) {
        for (pane in Pane.entries) {
            Satellite(
                workspace = workspace,
                id = pane.idIn(group.id),
                title = pane.title,
                initialPlacement = pane.home,
                initiallyOpen = pane.openAtStart,
                dockSides = if (pane.fixed) ReaderFixedDockSides else ReaderDockSides,
                floatable = !pane.fixed,
                reorderable = !pane.fixed,
                header = { PaneHeader(reader.style) },
                // Only reserved where the compositor owns the window move;
                // elsewhere the whole bar drags the pane and this is not composed.
                floatingCaption = { PaneMoveAffordance() },
            ) {
                Surface(Modifier.fillMaxSize(), color = colors.surface) {
                    PaneContent(reader, pane, book)
                }
            }
        }
    }
}

/**
 * One reader window: its two activity bars around the dock layout, all
 * right-to-left, with the selected sefer's text as the dock's content.
 */
@Composable
private fun ReaderBody(
    reader: ReaderState,
    group: TabWindowGroup,
    text: @Composable () -> Unit,
) {
    val workspace = reader.panesOfWindow(group.id)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(Modifier.fillMaxSize()) {
            // Start bar: at the right edge in RTL, toggling the navigation panes.
            ActivityBar {
                for (pane in listOf(Pane.Tree, Pane.Toc, Pane.Notes)) {
                    BarButton(pane.title.take(1), selected = reader.isOpen(group.id, pane)) {
                        reader.toggle(group.id, pane)
                    }
                }
            }
            VerticalDivider()
            DockLayout(
                workspace = workspace,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // The navigation runs the full height on the right; the
                // commentaries run under the text and the translation, not
                // under the navigation.
                sideOrder = listOf(DockSide.Right, DockSide.Bottom, DockSide.Left, DockSide.Top),
                // Books | contents | notes are three columns, not a stack.
                layeredSides = setOf(DockSide.Right),
                splitter = { ReaderSplitter(reader.style) },
                panel = { body -> PaneCard(reader.style) { body() } },
            ) {
                PaneCard(reader.style) { text() }
            }
            VerticalDivider()
            // End bar: the content panes, the style switch, the layout of this window.
            ActivityBar {
                for (pane in listOf(Pane.Targum, Pane.Comments, Pane.Sources)) {
                    BarButton(pane.title.take(1), selected = reader.isOpen(group.id, pane)) {
                        reader.toggle(group.id, pane)
                    }
                }
                Spacer(Modifier.height(BAR_GAP_DP.dp))
                BarButton("◫", selected = reader.style == ReaderStyle.Islands) {
                    reader.style = if (reader.style == ReaderStyle.Islands) ReaderStyle.Classic else ReaderStyle.Islands
                }
                Spacer(Modifier.weight(1f))
                BarButton("S", selected = false) { reader.saveLayout(group.id) }
                BarButton("R", selected = reader.savedLayout(group.id) != null) { reader.restoreLayout(group.id) }
                BarButton("⟲", selected = false) { reader.resetLayout(group.id) }
            }
        }
    }
}

/**
 * A sefer's text: the tab's own body, so it is composed in whichever window
 * shows the tab and its scroll position follows it there.
 */
@Composable
private fun BookText(
    reader: ReaderState,
    book: Book,
) {
    val state = reader.stateOf(book.id)
    val chapter = state.chapter.coerceIn(book.chapters.indices)
    Column(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(TEXT_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(TEXT_GAP_DP.dp),
        ) {
            Text("${book.title} · ${book.chapters[chapter]}", fontSize = TITLE_SP.sp, fontWeight = FontWeight.Bold)
            repeat(VERSES) { index ->
                Text(
                    "פסוק ${index + 1} — ${SAMPLE_TEXT.repeat(1 + index % 3)}",
                    fontSize = TEXT_SP.sp,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().height(BREADCRUMB_H_DP.dp).padding(horizontal = TEXT_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "תנ״ך  ›  ${book.title}  ›  ${book.chapters[chapter]}",
                fontSize = BREADCRUMB_SP.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A pane's body, for the sefer its window is showing: the books pane lists
 * every open sefer and selects its tab, the contents pane lists the sefer's
 * chapters, the rest list what they hold for the chapter in view.
 */
@Composable
private fun PaneContent(
    reader: ReaderState,
    pane: Pane,
    book: Book?,
) {
    if (book == null) {
        Box(Modifier.fillMaxSize().padding(PANE_PADDING_DP.dp), contentAlignment = Alignment.Center) {
            Text("אין ספר פתוח", fontSize = TEXT_SP.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val state = reader.stateOf(book.id)
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(PANE_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(ITEM_GAP_DP.dp),
    ) {
        when (pane) {
            // Every sefer of the app: clicking one brings its tab to the front.
            Pane.Tree ->
                for (candidate in reader.books) {
                    PaneItem(candidate.title, selected = candidate.id == book.id) { reader.show(candidate.id) }
                }
            // The chapters of this sefer; the text follows the choice.
            Pane.Toc ->
                book.chapters.forEachIndexed { index, name ->
                    PaneItem(name, selected = index == state.chapter) { state.chapter = index }
                }
            else ->
                repeat(ITEMS) { index ->
                    val label = "${pane.title} ${book.chapters[
                        state.chapter.coerceIn(
                            book.chapters.indices,
                        ),
                    ]}·${index + 1}"
                    PaneItem(label, selected = state.selected(pane) == index) { state.select(pane, index) }
                }
        }
    }
}

@Composable
private fun PaneItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontSize = TEXT_SP.sp,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ITEM_CORNER_DP.dp))
                .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(ITEM_PADDING_DP.dp),
    )
}

/**
 * Keeps the sefer list in step with the tab workspace: closing a tab is a
 * workspace call, and a book still declared once its tab is gone would be
 * registered again and hosted nowhere.
 */
@Composable
private fun DropClosedTab(
    reader: ReaderState,
    id: String,
) {
    val closed = reader.tabs.tab(id) == null
    LaunchedEffect(closed) {
        if (closed) reader.forget(id)
    }
}

@Composable
private fun ActivityBar(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(
                BAR_W_DP.dp,
            ).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = BAR_GAP_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BAR_GAP_DP.dp),
    ) { content() }
}

@Composable
private fun BarButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(BAR_BUTTON_DP.dp),
        colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (selected) colors.primary.copy(alpha = SELECTED_ALPHA) else Color.Transparent,
                contentColor = if (selected) colors.primary else colors.onSurfaceVariant,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) { Text(label, fontSize = BAR_LABEL_SP.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * Material colours plus the window-chrome styles derived from them.
 *
 * Established once, above the windows: the workspaces open them, and these
 * locals are bridged into every scene they create — the tab strips in the
 * title bars and the floating panes' own scenes included.
 */
@Composable
private fun ReaderTheme(
    colors: ColorScheme,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalTitleBarStyle provides rememberMaterialTitleBarStyle(colors),
            LocalDecoratedWindowStyle provides rememberMaterialWindowStyle(colors),
            content = content,
        )
    }
}

private const val BAR_W_DP = 48
private const val BAR_GAP_DP = 8
private const val BAR_BUTTON_DP = 36
private const val BAR_LABEL_SP = 14
private const val SELECTED_ALPHA = 0.18f
private const val TEXT_PADDING_DP = 24
private const val TEXT_GAP_DP = 12
private const val TITLE_SP = 26
private const val TEXT_SP = 17
private const val VERSES = 40
private const val BREADCRUMB_H_DP = 28
private const val BREADCRUMB_SP = 12
private const val PANE_PADDING_DP = 8
private const val ITEM_GAP_DP = 2
private const val ITEM_PADDING_DP = 6
private const val ITEM_CORNER_DP = 6
private const val ITEMS = 40
private const val SAMPLE_TEXT = "בְּרֵאשִׁית בָּרָא אֱלֹהִים אֵת הַשָּׁמַיִם וְאֵת הָאָרֶץ. "
