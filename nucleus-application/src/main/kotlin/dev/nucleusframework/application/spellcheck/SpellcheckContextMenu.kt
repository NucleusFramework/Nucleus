@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.nucleusframework.application.spellcheck

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * Chrome/Electron-style spellcheck around an existing `TextField` /
 * `BasicTextField` / Jewel `TextField`.
 *
 * Injects suggestions into the field's Cut/Copy/Paste menu and draws a red
 * wavy underline under misspelled words (IME layout only — never on a
 * Material/Jewel decoration label).
 *
 * Jewel separators (`ContextMenuDivider`) come from
 * `LocalSpellcheckMenuSeparator`, provided by `JewelDecoratedWindow` /
 * `ProvideJewelSpellcheckMenu`.
 *
 * No-op when Hunspell is unavailable.
 */
@Composable
public fun SpellcheckContextMenu(
    text: String,
    onTextChange: (String) -> Unit,
    session: SpellcheckSession? = null,
    content: @Composable () -> Unit,
) {
    val current = rememberAvailableSession(session)
    if (current == null) {
        content()
        return
    }
    val separator = LocalSpellcheckMenuSeparator.current
    val rangesState = remember { mutableStateOf(emptyList<SpellcheckWord>()) }
    val latestText = remember { mutableStateOf(text) }
    latestText.value = text
    CollectMisspellings(current, latestText, rangesState)
    var inputRequest by remember { mutableStateOf<PlatformTextInputMethodRequest?>(null) }
    val interceptor =
        remember {
            PlatformTextInputInterceptor { request, nextHandler ->
                inputRequest = request
                nextHandler.startInputMethod(request)
            }
        }
    ProvideSpellcheckSeparators {
        ContextMenuDataProvider(
            items = {
                spellcheckContextMenuItems(
                    text = text,
                    session = current,
                    ranges = spellcheckRangesStillValid(text, rangesState.value),
                    separator = separator,
                    onTextChange = onTextChange,
                )
            },
        ) {
            InterceptPlatformTextInput(interceptor) {
                SpellcheckImeUnderlineBox(
                    inputRequest = inputRequest,
                    ranges = rangesState.value,
                    content = content,
                )
            }
        }
    }
}

/**
 * [SpellcheckContextMenu] for a [TextFieldState] field.
 */
@Composable
public fun SpellcheckContextMenu(
    state: TextFieldState,
    session: SpellcheckSession? = null,
    content: @Composable () -> Unit,
) {
    SpellcheckContextMenu(
        text = state.text.toString(),
        onTextChange = { new ->
            state.edit { replace(0, length, new) }
        },
        session = session,
        content = content,
    )
}

@Composable
private fun rememberAvailableSession(session: SpellcheckSession?): SpellcheckSession? {
    var resolved by remember(session) { mutableStateOf(session ?: SpellChecker.sessionIfReady) }
    LaunchedEffect(session) {
        if (resolved?.isAvailable == true) return@LaunchedEffect
        resolved = session ?: withContext(Dispatchers.IO) { SpellChecker.ensureSession() }
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
    content: @Composable () -> Unit,
) {
    var boxOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .onGloballyPositioned { boxOriginInRoot = it.positionInRoot() }
            .drawWithContent {
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
    separator: ContextMenuItem,
    onTextChange: (String) -> Unit,
): List<ContextMenuItem> {
    if (ranges.isEmpty()) return emptyList()
    val suggestions = ArrayList<ContextMenuItem>()
    val unique = ranges.distinctBy { it.word }.take(2)
    for (range in unique) {
        val model = buildSpellcheckMenuModel(range.word, session, range) ?: continue
        for (suggestion in model.suggestions) {
            suggestions +=
                ContextMenuItem(suggestion) {
                    onTextChange(applySuggestion(text, model, suggestion))
                }
        }
    }
    val first = ranges.first()
    return spellcheckMenuSections(
        suggestions = suggestions,
        addToDictionaryLabel = SpellcheckMenuModel.DEFAULT_ADD_TO_DICTIONARY_LABEL,
        onAddToDictionary = { session.addToDictionary(first.word) },
        separator = separator,
    )
}

/**
 * Classic desktop menu: hairline, suggestions, hairline, then
 * "Add to dictionary". [SpellcheckContextMenuSeparator] is drawn by
 * [ProvideSpellcheckSeparators]; Jewel supplies `ContextMenuDivider`.
 */
internal fun spellcheckMenuSections(
    suggestions: List<ContextMenuItem>,
    addToDictionaryLabel: String,
    onAddToDictionary: () -> Unit,
    separator: ContextMenuItem,
): List<ContextMenuItem> =
    buildList {
        add(separator)
        addAll(suggestions)
        add(separator)
        add(ContextMenuItem(addToDictionaryLabel) { onAddToDictionary() })
    }

/**
 * Builds extra context-menu items for [word]. Used by tests and the
 * in-repo consumer; apps should wrap fields with [SpellcheckContextMenu].
 */
public object NucleusSpellcheckInstaller {
    /**
     * Suggestions plus "Add to dictionary" for [word].
     *
     * [separator] is inserted around the suggestions. Defaults to
     * [SpellcheckContextMenuSeparator]; Jewel supplies `ContextMenuDivider`.
     */
    public fun menuItems(
        word: String,
        session: SpellcheckSession,
        onSuggestion: (String) -> Unit,
        onAddToDictionary: () -> Unit,
        separator: ContextMenuItem = SpellcheckContextMenuSeparator,
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
        )
    }
}
