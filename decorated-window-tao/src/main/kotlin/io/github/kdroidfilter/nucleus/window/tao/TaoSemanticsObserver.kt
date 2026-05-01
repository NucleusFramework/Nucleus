@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState

/**
 * Compose `PlatformContext.SemanticsOwnerListener` that walks the
 * SemanticsOwner tree on every change and pushes a flat snapshot to a
 * [TaoAccessibilityController].
 *
 * Mirrors `compose-multiplatform-core/desktop/AccessibilityController.kt`'s
 * BFS sync but skips the per-node Java `AccessibleContext` shadow tree
 * because our consumers (NSAccessibility) work off a serialised snapshot
 * rather than a live object graph.
 *
 * Invariants:
 *  - `pushSnapshot` runs on the macOS main thread (the same thread that
 *    drains `TaoMainDispatcher`); we trust the caller to schedule us there.
 *  - Nodes are emitted in semantic / visual order (root first, then DFS in
 *    `replacedChildren` order), which the C parser relies on to build the
 *    children arrays in one pass.
 */
internal class TaoSemanticsObserver(
    private val controller: TaoAccessibilityController,
    private val densityProvider: () -> Float,
    private val onScheduleSync: (TaoSemanticsObserver) -> Unit,
) : PlatformContext.SemanticsOwnerListener {

    private val owners = mutableListOf<SemanticsOwner>()
    private var dirty = false

    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        if (!owners.contains(semanticsOwner)) {
            owners.add(semanticsOwner)
            dirty = true
            if (!controller.isDisposed) onScheduleSync(this)
        }
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        if (owners.remove(semanticsOwner)) {
            dirty = true
            if (!controller.isDisposed) onScheduleSync(this)
        }
    }

    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        dirty = true
        onScheduleSync(this)
    }

    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
        dirty = true
        onScheduleSync(this)
    }

    /**
     * Builds a fresh snapshot from the current SemanticsOwner state and
     * pushes it to the controller. Idempotent; the caller debounces.
     */
    fun syncIfDirty() {
        if (!dirty || controller.isDisposed) return
        // Refresh the gating decision: if an AX client has touched the tree
        // since our last push, the next `pushSnapshot` MUST go through even
        // if we'd otherwise be in the idle window.
        controller.maybeForceResync()
        dirty = false
        val nodes = ArrayList<TaoA11yNode>(64)
        val liveIds = HashSet<Long>()
        val density = densityProvider()
        for (owner in owners) {
            val root = owner.rootSemanticsNode
            if (root.layoutInfo.let { it.isPlaced && it.isAttached }) {
                walk(root, parentId = 0L, density = density, out = nodes, liveIds = liveIds)
            }
        }
        controller.clearStaleHandlers(liveIds)
        controller.pushSnapshot(nodes)
    }

    private fun walk(
        node: SemanticsNode,
        parentId: Long,
        density: Float,
        out: ArrayList<TaoA11yNode>,
        liveIds: HashSet<Long>,
    ) {
        if (node.isInvisibleToA11y()) return

        val id = nodeIdToLong(node.id)
        liveIds.add(id)

        val (role, flags, actions) = describe(node)
        val bounds = node.boundsInWindow
        // Bounds are in window-local pixels at the current density. ObjC wants
        // window-local logical points (= pixels / density at scale=1, but
        // Compose reports `boundsInWindow` already in raw px). Convert by
        // dividing by density so values match AppKit's points-based input to
        // `convertRect:toView:`.
        val invDensity = if (density > 0f) 1f / density else 1f

        val onClick = node.config.getOrNull(SemanticsActions.OnClick)?.action
        val setProgress = node.config.getOrNull(SemanticsActions.SetProgress)?.action
        val setText = node.config.getOrNull(SemanticsActions.SetText)?.action
        val requestFocus = node.config.getOrNull(SemanticsActions.RequestFocus)?.action
        val scrollBy = node.config.getOrNull(SemanticsActions.ScrollBy)?.action
        val pageUp = node.config.getOrNull(SemanticsActions.PageUp)?.action
        val pageDown = node.config.getOrNull(SemanticsActions.PageDown)?.action
        val pageLeft = node.config.getOrNull(SemanticsActions.PageLeft)?.action
        val pageRight = node.config.getOrNull(SemanticsActions.PageRight)?.action
        val dismiss = node.config.getOrNull(SemanticsActions.Dismiss)?.action
        val setSelection = node.config.getOrNull(SemanticsActions.SetSelection)?.action
        val customActionsList: List<androidx.compose.ui.semantics.CustomAccessibilityAction> =
            node.config.getOrNull(SemanticsActions.CustomActions) ?: emptyList()
        // Page actions are preferred when present (they correspond to AppKit's
        // "scroll by page" semantics that VO+Cmd+arrow triggers). Fall back to
        // ScrollBy with a heuristic step (~ a viewport's worth) if the node
        // only exposes raw scroll.
        val viewportH = node.boundsInWindow.height / (if (density > 0f) density else 1f)
        val viewportW = node.boundsInWindow.width / (if (density > 0f) density else 1f)
        val onScrollUp = pageUp?.let { { it.invoke(); Unit } }
            ?: scrollBy?.let { fn -> { fn.invoke(0f, -viewportH * 0.9f); Unit } }
        val onScrollDown = pageDown?.let { { it.invoke(); Unit } }
            ?: scrollBy?.let { fn -> { fn.invoke(0f, +viewportH * 0.9f); Unit } }
        val onScrollLeft = pageLeft?.let { { it.invoke(); Unit } }
            ?: scrollBy?.let { fn -> { fn.invoke(-viewportW * 0.9f, 0f); Unit } }
        val onScrollRight = pageRight?.let { { it.invoke(); Unit } }
            ?: scrollBy?.let { fn -> { fn.invoke(+viewportW * 0.9f, 0f); Unit } }
        controller.setActionHandlers(
            id,
            TaoAccessibilityController.ActionHandlers(
                onClick = onClick?.let { { it.invoke() } },
                onIncrement = setProgress?.let { fn -> { stepProgress(node, fn, +1) } },
                onDecrement = setProgress?.let { fn -> { stepProgress(node, fn, -1) } },
                onSetText = setText?.let { fn ->
                    { newText -> fn.invoke(androidx.compose.ui.text.AnnotatedString(newText)) }
                },
                onRequestFocus = requestFocus?.let { { it.invoke() } },
                onScrollUp = onScrollUp,
                onScrollDown = onScrollDown,
                onScrollLeft = onScrollLeft,
                onScrollRight = onScrollRight,
                onDismiss = dismiss?.let { { it.invoke() } },
                onSetSelection = setSelection?.let { fn ->
                    // Compose's SetSelection is `(start, end, traversalMode) -> Boolean`.
                    // VoiceOver doesn't expose a traversalMode concept; pass false
                    // to mean "set the editable cursor / selection directly".
                    { start, end -> fn.invoke(start, end, false) }
                },
                customActions = customActionsList.map { ca -> { ca.action.invoke() } },
                onScrollBy = scrollBy?.let { fn ->
                    { dx, dy -> fn.invoke(dx, dy) }
                },
            ),
        )

        val label = computeLabel(node)
        val valueString = computeValueString(node)
        val rangeInfo = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        val hRange = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        val vRange = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        // ScrollAxisRange exposes value/maxValue as `() -> Float` lambdas
        // (re-evaluated every frame). Capture the current snapshot here —
        // VoiceOver re-queries via the AXScrollBar setter on each scroll.
        val hScrollMax = hRange?.maxValue?.invoke() ?: 0f
        val hScrollValue = hRange?.value?.invoke() ?: 0f
        val vScrollMax = vRange?.maxValue?.invoke() ?: 0f
        val vScrollValue = vRange?.value?.invoke() ?: 0f
        val selection = node.config.getOrNull(SemanticsProperties.TextSelectionRange)
        val (selStart, selEnd) = if (selection != null) {
            // Compose stores selection as [TextRange] over the editable text's
            // chars. AppKit / NSAccessibility expect UTF-16 code-unit offsets,
            // which match Kotlin's String char count for BMP-only strings.
            // For surrogate pair code points the mapping is identical (Kotlin
            // also counts UTF-16 code units), so passing through is correct.
            selection.start to selection.end
        } else {
            0 to 0
        }

        out.add(
            TaoA11yNode(
                nodeId = id,
                parentId = parentId,
                role = role,
                flags = flags,
                actions = actions,
                frameX = bounds.left * invDensity,
                frameY = bounds.top * invDensity,
                frameW = bounds.width * invDensity,
                frameH = bounds.height * invDensity,
                minValue = rangeInfo?.range?.start ?: 0f,
                maxValue = rangeInfo?.range?.endInclusive ?: 0f,
                numericValue = rangeInfo?.current ?: 0f,
                selectionStart = selStart,
                selectionEnd = selEnd,
                hScrollMax = hScrollMax,
                hScrollValue = hScrollValue,
                vScrollMax = vScrollMax,
                vScrollValue = vScrollValue,
                label = label,
                valueString = valueString,
                customActions = customActionsList.map { it.label },
            ),
        )

        for (child in node.children) {
            if (child.layoutInfo.let { it.isPlaced && it.isAttached }) {
                walk(child, parentId = id, density = density, out = out, liveIds = liveIds)
            }
        }
    }

    private fun stepProgress(
        node: SemanticsNode,
        setProgress: (Float) -> Boolean,
        direction: Int,
    ) {
        val info = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) ?: return
        val span = info.range.endInclusive - info.range.start
        if (span <= 0f) return
        val step = span * 0.1f * direction
        val target = (info.current + step).coerceIn(info.range.start, info.range.endInclusive)
        setProgress(target)
    }

    private fun describe(node: SemanticsNode): Triple<TaoA11yRole, Int, Int> {
        val cfg = node.config
        val composeRole = cfg.getOrNull(SemanticsProperties.Role)
        val hasText = cfg.contains(SemanticsProperties.Text)
        val hasEditable = cfg.contains(SemanticsProperties.EditableText)
        val isHeading = cfg.contains(SemanticsProperties.Heading)
        val toggleable = cfg.getOrNull(SemanticsProperties.ToggleableState)
        val isPassword = cfg.contains(SemanticsProperties.Password)
        val rangeInfo = cfg.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        val onClick = cfg.contains(SemanticsActions.OnClick)
        val setProgress = cfg.contains(SemanticsActions.SetProgress)

        // Collection-aware role detection: a node carrying CollectionInfo
        // becomes an AXTable (or AXOutline if 1-column) and items inside
        // (with CollectionItemInfo) become AXRow / AXCell. This is a coarse
        // mapping — full table protocol (rows[]/columns[]/header) is left for
        // a follow-up but the role naming alone enables VO+arrow row nav.
        val collectionInfo = cfg.getOrNull(SemanticsProperties.CollectionInfo)
        val collectionItem = cfg.getOrNull(SemanticsProperties.CollectionItemInfo)

        // Detect scroll axes early — a LazyColumn carries both CollectionInfo
        // and HorizontalScrollAxisRange/VerticalScrollAxisRange. Mapping it
        // to Table/Outline strips the scroll bar children we install on
        // AXScrollArea, so prioritise ScrollArea when scroll is present.
        val hasScrollAxes = cfg.contains(SemanticsActions.ScrollBy) ||
            cfg.contains(SemanticsProperties.HorizontalScrollAxisRange) ||
            cfg.contains(SemanticsProperties.VerticalScrollAxisRange)
        var role = when {
            collectionInfo != null && !hasScrollAxes ->
                if (collectionInfo.columnCount <= 1) TaoA11yRole.Outline
                else TaoA11yRole.Table
            collectionInfo != null && hasScrollAxes -> TaoA11yRole.ScrollArea
            collectionItem != null -> TaoA11yRole.Row
            composeRole == Role.Button -> TaoA11yRole.Button
            composeRole == Role.Checkbox -> TaoA11yRole.Checkbox
            composeRole == Role.Switch -> TaoA11yRole.Switch
            composeRole == Role.RadioButton -> TaoA11yRole.RadioButton
            composeRole == Role.Tab -> TaoA11yRole.Tab
            composeRole == Role.Image -> TaoA11yRole.Image
            composeRole == Role.DropdownList -> TaoA11yRole.PopupMenu
            else -> when {
                isHeading -> TaoA11yRole.Heading
                hasEditable -> TaoA11yRole.TextField
                rangeInfo != null && setProgress -> TaoA11yRole.Slider
                rangeInfo != null -> TaoA11yRole.Progress
                // Scroll containers must beat hasText/onClick — a
                // `Modifier.verticalScroll` wrapper around a Column of texts
                // would otherwise demote to StaticText (merged-config flag).
                cfg.contains(SemanticsActions.ScrollBy) ||
                cfg.contains(SemanticsProperties.HorizontalScrollAxisRange) ||
                cfg.contains(SemanticsProperties.VerticalScrollAxisRange) -> TaoA11yRole.ScrollArea
                // Clickable wins over plain text: a `Box.clickable { Text(…) }`
                // composable (e.g. the "Clear" button or tab labels in sample-tao)
                // should announce as a button, with the Text becoming the label.
                onClick -> TaoA11yRole.Button
                hasText -> TaoA11yRole.StaticText
                else -> TaoA11yRole.Group
            }
        }

        var flags = 0
        // Anything carrying meaningful semantics is announceable.
        val hasAnyContent = hasText || hasEditable || onClick || toggleable != null ||
            composeRole != null || rangeInfo != null
        if (hasAnyContent) flags = flags or TaoA11yFlag.IS_ELEMENT
        if (!cfg.contains(SemanticsProperties.Disabled)) flags = flags or TaoA11yFlag.ENABLED
        if (cfg.getOrNull(SemanticsProperties.Focused) == true) flags = flags or TaoA11yFlag.FOCUSED
        if (cfg.getOrNull(SemanticsProperties.Selected) == true) flags = flags or TaoA11yFlag.SELECTED
        when (toggleable) {
            ToggleableState.On -> flags = flags or TaoA11yFlag.CHECKED
            ToggleableState.Indeterminate -> flags = flags or TaoA11yFlag.MIXED
            else -> Unit
        }
        if (isHeading) flags = flags or TaoA11yFlag.HEADING
        if (isPassword) flags = flags or TaoA11yFlag.PASSWORD
        if (cfg.contains(SemanticsProperties.IsDialog)) flags = flags or TaoA11yFlag.MODAL
        when (cfg.getOrNull(SemanticsProperties.LiveRegion)) {
            androidx.compose.ui.semantics.LiveRegionMode.Polite ->
                flags = flags or TaoA11yFlag.LIVE_REGION_POLITE
            androidx.compose.ui.semantics.LiveRegionMode.Assertive ->
                flags = flags or TaoA11yFlag.LIVE_REGION_ASSERTIVE
            else -> Unit
        }

        var actions = 0
        if (onClick) actions = actions or TaoA11yAction.CLICK
        if (setProgress) {
            actions = actions or TaoA11yAction.INCREMENT or TaoA11yAction.DECREMENT
        }
        if (cfg.contains(SemanticsActions.SetText)) actions = actions or TaoA11yAction.SET_TEXT
        if (cfg.contains(SemanticsActions.RequestFocus)) {
            actions = actions or TaoA11yAction.REQUEST_FOCUS
        }
        // Expose scroll bits whenever the node carries either page actions or
        // a generic ScrollBy. The handler wiring above synthesises the
        // missing direction from the available primitive.
        val hasScroll = cfg.contains(SemanticsActions.ScrollBy) ||
            cfg.contains(SemanticsActions.PageUp) ||
            cfg.contains(SemanticsActions.PageDown) ||
            cfg.contains(SemanticsActions.PageLeft) ||
            cfg.contains(SemanticsActions.PageRight)
        if (hasScroll) {
            actions = actions or TaoA11yAction.SCROLL_UP or TaoA11yAction.SCROLL_DOWN or
                TaoA11yAction.SCROLL_LEFT or TaoA11yAction.SCROLL_RIGHT
        }
        if (cfg.contains(SemanticsActions.Dismiss)) actions = actions or TaoA11yAction.DISMISS

        // Slider role implies enabled + element.
        if (role == TaoA11yRole.Slider) flags = flags or TaoA11yFlag.IS_ELEMENT

        return Triple(role, flags, actions)
    }

    private fun computeLabel(node: SemanticsNode): String {
        val cfg = node.config
        cfg.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { return it }
        // For controls that visually carry their label as text, surface the
        // text — VoiceOver expects a label even on Buttons that contain a
        // child Text composable.
        val text = cfg.getOrNull(SemanticsProperties.Text)
        if (text != null && text.isNotEmpty()) {
            return text.joinToString(" ") { it.text }
        }
        return ""
    }

    private fun computeValueString(node: SemanticsNode): String {
        val cfg = node.config
        cfg.getOrNull(SemanticsProperties.EditableText)?.let { return it.text }
        cfg.getOrNull(SemanticsProperties.StateDescription)?.let { return it }
        return ""
    }

    private fun SemanticsNode.isInvisibleToA11y(): Boolean {
        @Suppress("DEPRECATION")
        return config.contains(SemanticsProperties.InvisibleToUser) ||
            config.contains(SemanticsProperties.HideFromAccessibility)
    }

    /**
     * SemanticsNode ids are Compose `LayoutNode.semanticsId` (Int). We promote
     * to Long for the wire format; node id 0 is reserved for "no parent" so
     * we shift by +1 to keep ids non-zero.
     */
    private fun nodeIdToLong(id: Int): Long = (id.toLong() and 0xFFFFFFFFL) + 1L
}
