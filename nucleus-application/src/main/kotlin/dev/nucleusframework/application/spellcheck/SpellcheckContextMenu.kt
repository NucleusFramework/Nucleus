@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.nucleusframework.application.spellcheck

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextRange
import dev.nucleusframework.application.contextmenu.LocalContextMenuDivider
import dev.nucleusframework.spellcheck.SpellChecker
import dev.nucleusframework.spellcheck.SpellcheckMenuModel
import dev.nucleusframework.spellcheck.SpellcheckSession
import dev.nucleusframework.spellcheck.SpellcheckWord
import dev.nucleusframework.spellcheck.applySuggestion
import dev.nucleusframework.spellcheck.buildSpellcheckMenuModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import java.util.Locale

internal const val SPELLCHECK_IDLE_DELAY_MS: Long = 150L

internal fun spellcheckRecomputeDelayMs(text: String): Long {
    if (text.isEmpty() || text.last().isWhitespace()) return 0L
    return SPELLCHECK_IDLE_DELAY_MS
}

internal fun spellcheckRangesStillValid(
    text: String,
    ranges: List<SpellcheckWord>,
): List<SpellcheckWord> =
    ranges.filter { range ->
        range.end <= text.length && text.substring(range.start, range.end) == range.word
    }

/**
 * Where [SpellcheckContextMenu] inserts its suggestions relative to the
 * field's existing items (Cut/Copy/Paste and any app extras).
 *
 * Compose walks context-menu data inner-first, so a parent
 * `ContextMenuDataProvider` always lands at the **bottom**. [Top] prepends
 * at display time and keeps the ambient `LocalContextMenuRepresentation`
 * (Jewel or any custom chrome).
 *
 * Provide a custom `LocalTextContextMenu` / representation **outside** this
 * wrap. Apps that build the item list themselves should insert
 * [NucleusSpellcheckInstaller.menuItems] at the desired index instead.
 */
public enum class SpellcheckMenuPlacement {
    /** Suggestions, then a separator, then Cut/Copy/Paste and app items. */
    Top,

    /** Cut/Copy/Paste and app items, then a separator, then suggestions. */
    Bottom,
}

/**
 * Chrome/Electron-style spellcheck around an existing `TextField` /
 * `BasicTextField` / Jewel `TextField`.
 *
 * Injects suggestions into the field's Cut/Copy/Paste menu and draws a red
 * wavy underline under misspelled words (IME layout only — never on a
 * Material/Jewel decoration label).
 *
 * The ambient `LocalContextMenuRepresentation` is never replaced — whatever
 * chrome the app installed keeps drawing the menu. Separators therefore only
 * appear when that renderer publishes one through `LocalContextMenuDivider`
 * (`NativeContextMenuProvider`, or Jewel's `ContextMenuDivider` via
 * `JewelDecoratedWindow` / `ProvideJewelSpellcheckMenu`); with Compose's own
 * representation the suggestions are appended without a divider.
 *
 * No-op when the native spellcheck engine is unavailable.
 *
 * Language follows [SpellChecker.locale] (itself [Locale.getDefault] until
 * overridden). Pass [locale] to check this field in another language;
 * pass [session] to supply a fully custom engine. [session] wins when both
 * are set.
 *
 * @param locale language for this field, or `null` to use [SpellChecker.locale]
 * @param menuPlacement [SpellcheckMenuPlacement.Bottom] (default) appends
 *   after existing items; [SpellcheckMenuPlacement.Top] prepends before them.
 */
@Composable
public fun SpellcheckContextMenu(
    text: String,
    onTextChange: (String) -> Unit,
    session: SpellcheckSession? = null,
    locale: Locale? = null,
    menuPlacement: SpellcheckMenuPlacement = SpellcheckMenuPlacement.Bottom,
    content: @Composable () -> Unit,
) {
    val current = rememberAvailableSession(session, locale)
    if (current == null) {
        content()
        return
    }
    val separator = LocalContextMenuDivider.current
    val rangesState = remember { mutableStateOf(emptyList<SpellcheckWord>()) }
    val latestText = remember { mutableStateOf(text) }
    latestText.value = text
    val latestOnTextChange = rememberUpdatedState(onTextChange)
    CollectMisspellings(current, latestText, rangesState)
    var inputRequest by remember { mutableStateOf<PlatformTextInputMethodRequest?>(null) }
    var pendingClickInRoot by remember { mutableStateOf<Offset?>(null) }
    val interceptor =
        remember {
            PlatformTextInputInterceptor { request, nextHandler ->
                inputRequest = request
                nextHandler.startInputMethod(request)
            }
        }
    val menuItems = {
        val request = inputRequest
        val clickOffset =
            pendingClickInRoot?.let { click ->
                request?.let { clickOffsetInField(it, click) }
            }
        val anchor =
            spellcheckAnchor(
                clickOffset = clickOffset,
                selection = request?.value?.invoke()?.selection,
            )
        spellcheckContextMenuItems(
            text = latestText.value,
            session = current,
            ranges = spellcheckRangesStillValid(latestText.value, rangesState.value),
            separator = separator,
            onTextChange = latestOnTextChange.value,
            placement = menuPlacement,
            anchor = anchor,
        )
    }
    ProvideSpellcheckMenuItems(
        items = menuItems,
        placement = menuPlacement,
        onMenuClosed = { pendingClickInRoot = null },
    ) {
        InterceptPlatformTextInput(interceptor) {
            SpellcheckImeUnderlineBox(
                inputRequest = inputRequest,
                ranges = rangesState.value,
                onSecondaryClickInRoot = { pendingClickInRoot = it },
                content = content,
            )
        }
    }
}

/**
 * [SpellcheckContextMenu] for a [TextFieldState] field.
 *
 * @param locale See [SpellcheckContextMenu].
 * @param menuPlacement See [SpellcheckContextMenu].
 */
@Composable
public fun SpellcheckContextMenu(
    state: TextFieldState,
    session: SpellcheckSession? = null,
    locale: Locale? = null,
    menuPlacement: SpellcheckMenuPlacement = SpellcheckMenuPlacement.Bottom,
    content: @Composable () -> Unit,
) {
    SpellcheckContextMenu(
        text = state.text.toString(),
        onTextChange = { new ->
            state.edit { replace(0, length, new) }
        },
        session = session,
        locale = locale,
        menuPlacement = menuPlacement,
        content = content,
    )
}

@Composable
private fun rememberAvailableSession(
    session: SpellcheckSession?,
    locale: Locale?,
): SpellcheckSession? {
    val targetLocale = locale ?: SpellChecker.locale
    var resolved by remember(session, targetLocale) {
        mutableStateOf(
            session
                ?: SpellChecker.sessionIfReady?.takeIf { it.locale == targetLocale },
        )
    }
    LaunchedEffect(session, targetLocale) {
        if (session != null) {
            resolved = session.takeIf { it.isAvailable }
            return@LaunchedEffect
        }
        if (resolved?.isAvailable == true && resolved?.locale == targetLocale) {
            return@LaunchedEffect
        }
        resolved =
            withContext(Dispatchers.IO) { SpellChecker.ensureSession(targetLocale) }
                .takeIf { it.isAvailable }
    }
    return resolved?.takeIf { it.isAvailable }
}

@Composable
private fun CollectMisspellings(
    session: SpellcheckSession,
    latestText: androidx.compose.runtime.MutableState<String>,
    rangesState: androidx.compose.runtime.MutableState<List<SpellcheckWord>>,
) {
    LaunchedEffect(session) {
        snapshotFlow { latestText.value }
            .distinctUntilChanged()
            .transformLatest { value ->
                val wait = spellcheckRecomputeDelayMs(value)
                if (wait > 0L) delay(wait)
                emit(value)
            }.collect { value ->
                rangesState.value =
                    if (value.isEmpty()) {
                        emptyList()
                    } else {
                        withContext(Dispatchers.Default) { session.misspellings(value) }
                    }
            }
    }
}

@Composable
private fun SpellcheckImeUnderlineBox(
    inputRequest: PlatformTextInputMethodRequest?,
    ranges: List<SpellcheckWord>,
    onSecondaryClickInRoot: (Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    var boxOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    val latestClick = rememberUpdatedState(onSecondaryClickInRoot)
    Box(
        Modifier
            .onGloballyPositioned { boxOriginInRoot = it.positionInRoot() }
            .detectSecondaryClickInRoot(
                originInRoot = { boxOriginInRoot },
                onClick = { latestClick.value(it) },
            ).drawWithContent {
                drawContent()
                val request = inputRequest ?: return@drawWithContent
                val fieldText = request.value().text
                if (fieldText.isEmpty()) return@drawWithContent
                val layout = request.textLayoutResult() ?: return@drawWithContent
                val valid = spellcheckRangesStillValid(fieldText, ranges)
                if (valid.isEmpty()) return@drawWithContent
                val clipInRoot = request.textClippingRectInRoot()
                val textOrigin =
                    resolveSpellcheckTextOriginInRoot(
                        unclippedTextOffsetInRoot = request.unclippedTextOffsetInRoot(),
                        textClippingRectInRoot = clipInRoot,
                    ) ?: return@drawWithContent
                drawImeAlignedSquiggles(
                    layout = layout,
                    ranges = valid,
                    textOriginInRoot = textOrigin,
                    fieldOriginInRoot = boxOriginInRoot,
                    clipInRoot = clipInRoot,
                )
            },
        content = { content() },
    )
}

internal fun spellcheckContextMenuItems(
    text: String,
    session: SpellcheckSession,
    ranges: List<SpellcheckWord>,
    separator: ContextMenuItem?,
    onTextChange: (String) -> Unit,
    placement: SpellcheckMenuPlacement = SpellcheckMenuPlacement.Bottom,
    anchor: TextRange? = null,
): List<ContextMenuItem> {
    if (ranges.isEmpty() || anchor == null) return emptyList()
    val target = misspellingAt(ranges, anchor) ?: return emptyList()
    val model = buildSpellcheckMenuModel(target.word, session, target) ?: return emptyList()
    val suggestions =
        model.suggestions.map { suggestion ->
            ContextMenuItem(suggestion) {
                onTextChange(applySuggestion(text, model, suggestion))
            }
        }
    return spellcheckMenuSections(
        suggestions = suggestions,
        addToDictionaryLabel = SpellcheckMenuModel.localizedAddToDictionaryLabel(),
        onAddToDictionary = { session.addToDictionary(target.word) },
        separator = separator,
        placement = placement,
    )
}

/**
 * Classic desktop menu around suggestions and "Add to dictionary".
 * [SpellcheckMenuPlacement.Bottom] leads with a separator (after Cut/Copy/Paste);
 * [SpellcheckMenuPlacement.Top] trails with one (before Cut/Copy/Paste).
 *
 * A `null` [separator] means the ambient renderer cannot draw one — the
 * sections are then emitted without any, never with a stand-in row.
 */
internal fun spellcheckMenuSections(
    suggestions: List<ContextMenuItem>,
    addToDictionaryLabel: String,
    onAddToDictionary: () -> Unit,
    separator: ContextMenuItem?,
    placement: SpellcheckMenuPlacement = SpellcheckMenuPlacement.Bottom,
): List<ContextMenuItem> =
    buildList {
        if (separator != null && placement == SpellcheckMenuPlacement.Bottom) add(separator)
        addAll(suggestions)
        if (separator != null) add(separator)
        add(ContextMenuItem(addToDictionaryLabel) { onAddToDictionary() })
        if (separator != null && placement == SpellcheckMenuPlacement.Top) add(separator)
    }

/**
 * Builds extra context-menu items for [word]. Used by tests, the in-repo
 * consumer, and apps that own the menu list (insert the returned block
 * at the top or bottom of `buildList`).
 *
 * Fields that can use the wrap should prefer [SpellcheckContextMenu].
 */
public object NucleusSpellcheckInstaller {
    /**
     * Suggestions plus "Add to dictionary" for [word].
     *
     * [separator] is inserted around the suggestions. Pass
     * `LocalContextMenuDivider.current` so the block only carries dividers
     * when the ambient renderer draws them; `null` (the default) emits none.
     *
     * [menuPlacement] only changes which side of the block gets the
     * outer separator — put the returned list at the matching index.
     */
    public fun menuItems(
        word: String,
        session: SpellcheckSession,
        onSuggestion: (String) -> Unit,
        onAddToDictionary: () -> Unit,
        separator: ContextMenuItem? = null,
        menuPlacement: SpellcheckMenuPlacement = SpellcheckMenuPlacement.Bottom,
    ): List<ContextMenuItem> {
        val model = buildSpellcheckMenuModel(word, session) ?: return emptyList()
        return spellcheckMenuSections(
            suggestions =
                model.suggestions.map { suggestion ->
                    ContextMenuItem(suggestion) { onSuggestion(suggestion) }
                },
            addToDictionaryLabel = model.addToDictionaryLabel,
            onAddToDictionary = onAddToDictionary,
            separator = separator,
            placement = menuPlacement,
        )
    }
}

@Composable
private fun ProvideSpellcheckMenuItems(
    items: () -> List<ContextMenuItem>,
    placement: SpellcheckMenuPlacement,
    onMenuClosed: () -> Unit,
    content: @Composable () -> Unit,
) {
    val delegate = LocalContextMenuRepresentation.current
    val latestClosed = rememberUpdatedState(onMenuClosed)
    val representation =
        remember(delegate) {
            SpellcheckMenuSessionRepresentation(delegate) { latestClosed.value() }
        }
    CompositionLocalProvider(LocalContextMenuRepresentation provides representation) {
        when (placement) {
            SpellcheckMenuPlacement.Bottom ->
                ContextMenuDataProvider(items = items, content = content)
            SpellcheckMenuPlacement.Top -> {
                val base = LocalTextContextMenu.current
                val latestItems = rememberUpdatedState(items)
                val menu =
                    remember(base) {
                        SpellcheckTopTextContextMenu(base) { latestItems.value() }
                    }
                CompositionLocalProvider(LocalTextContextMenu provides menu, content = content)
            }
        }
    }
}

private class SpellcheckMenuSessionRepresentation(
    private val delegate: ContextMenuRepresentation,
    private val onClosed: () -> Unit,
) : ContextMenuRepresentation {
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        if (state.status !is ContextMenuState.Status.Open) {
            onClosed()
        }
        delegate.Representation(state, items)
    }
}

private class SpellcheckTopTextContextMenu(
    private val base: TextContextMenu,
    private val extraItems: () -> List<ContextMenuItem>,
) : TextContextMenu {
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        val delegate = LocalContextMenuRepresentation.current
        val latestExtra = rememberUpdatedState(extraItems)
        val representation =
            remember(delegate) {
                SpellcheckTopMenuRepresentation(delegate) { latestExtra.value() }
            }
        CompositionLocalProvider(LocalContextMenuRepresentation provides representation) {
            base.Area(textManager, state, content)
        }
    }
}

private class SpellcheckTopMenuRepresentation(
    private val delegate: ContextMenuRepresentation,
    private val extraItems: () -> List<ContextMenuItem>,
) : ContextMenuRepresentation {
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        delegate.Representation(state) {
            val extra = extraItems()
            if (extra.isEmpty()) items() else extra + items()
        }
    }
}
