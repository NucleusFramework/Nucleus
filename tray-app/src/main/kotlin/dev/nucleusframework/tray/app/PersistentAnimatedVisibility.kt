package dev.nucleusframework.tray.app

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.InternalAnimationApi
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMaxOfOrDefault
import kotlin.math.max

@Composable
internal fun PersistentAnimatedVisibility(
    visibleState: MutableTransitionState<Boolean>,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn() + expandIn(),
    exit: ExitTransition = fadeOut() + shrinkOut(),
    label: String = "PersistentAnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    // Hoisted transition identical to AnimatedVisibility(visibleState = ...)
    val transition = rememberTransition(visibleState, label)
    AnimatedVisibilityImplPersistent(
        transition = transition,
        // Boolean target in MutableTransitionState<Boolean>
        visible = { it },
        modifier =
            modifier.layout { measurable, constraints ->
                // Report 0 size only during lookahead when target is "not visible"
                val placeable = measurable.measure(constraints)
                val (w, h) =
                    if (isLookingAhead && !transition.targetState) {
                        IntSize.Zero
                    } else {
                        IntSize(placeable.width, placeable.height)
                    }
                layout(w, h) { placeable.place(0, 0) }
            },
        enter = enter,
        exit = exit,
        content = content,
    )
}

@Composable
private fun <T> AnimatedVisibilityImplPersistent(
    transition: Transition<T>,
    visible: (T) -> Boolean,
    modifier: Modifier,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedEnterExitImplPersistent(
        transition = transition,
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        // <-- never dispose content; it stays composed after exit finishes
        shouldDisposeBlock = { _, _ -> false },
        content = content,
    )
}

// --- Scope "maison" (public API only) ---
private class AVScopeImpl(
    override var transition: Transition<EnterExitState>,
) : AnimatedVisibilityScope {
    val targetSize = mutableStateOf(IntSize.Zero)
}

private class AVMeasurePolicy(
    private val scope: AVScopeImpl,
) : MeasurePolicy {
    private var hasLookaheadOccurred = false

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        var maxW = 0
        var maxH = 0
        val placeables =
            measurables.map {
                it.measure(constraints).also { p ->
                    if (p.width > maxW) maxW = p.width
                    if (p.height > maxH) maxH = p.height
                }
            }
        if (isLookingAhead) {
            hasLookaheadOccurred = true
            scope.targetSize.value = IntSize(maxW, maxH)
        } else if (!hasLookaheadOccurred) {
            scope.targetSize.value = IntSize(maxW, maxH)
        }
        return layout(maxW, maxH) { placeables.forEach { it.place(0, 0) } }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        m: List<IntrinsicMeasurable>,
        h: Int,
    ) = m.maxOfOrNull { it.minIntrinsicWidth(h) } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        m: List<IntrinsicMeasurable>,
        w: Int,
    ) = m.maxOfOrNull { it.minIntrinsicHeight(w) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        m: List<IntrinsicMeasurable>,
        h: Int,
    ) = m.maxOfOrNull { it.maxIntrinsicWidth(h) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        m: List<IntrinsicMeasurable>,
        w: Int,
    ) = m.maxOfOrNull { it.maxIntrinsicHeight(w) } ?: 0
}

@OptIn(ExperimentalTransitionApi::class, InternalAnimationApi::class)
@Composable
private fun <T> AnimatedEnterExitImplPersistent(
    transition: Transition<T>,
    visible: (T) -> Boolean,
    modifier: Modifier,
    enter: EnterTransition,
    exit: ExitTransition,
    // kept for API symmetry
    shouldDisposeBlock: (EnterExitState, EnterExitState) -> Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val child =
        transition.createChildTransition(label = "EnterExitTransition") { parent ->
            transition.targetEnterExit(visible, parent)
        }

    // Scope is a plain object -> it's fine to remember it keyed by the child transition
    val scope = remember(child) { AVScopeImpl(child) }

    // Built-in animateEnterExit (public API) to attach enter/exit effects
    val containerModifier =
        with(scope) {
            Modifier.animateEnterExit(enter = enter, exit = exit, label = "Built-in")
        }

    // Keep the subtree always composed; collapse to 0x0 when fully hidden & idle
    val collapseModifier =
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)

            val isActiveOrAnimating =
                visible(transition.currentState) ||
                    visible(transition.targetState) ||
                    transition.isSeeking ||
                    transition.hasInitialValueAnimations

            if (!isActiveOrAnimating) {
                layout(0, 0) { /* keep composed, take no space */ }
            } else {
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
        }

    Layout(
        content = { scope.content() },
        modifier = modifier.then(containerModifier).then(collapseModifier),
        measurePolicy = remember(scope) { AVMeasurePolicy(scope) },
    )
}

private val Transition<EnterExitState>.exitFinished: Boolean
    get() = currentState == EnterExitState.PostExit && targetState == EnterExitState.PostExit

private class AnimatedVisibilityScopeImpl(
    transition: Transition<EnterExitState>,
) : AnimatedVisibilityScope {
    override var transition: Transition<EnterExitState> = transition
    internal val targetSize = mutableStateOf(IntSize.Zero)
}

private class AnimatedEnterExitMeasurePolicy(
    val scope: AnimatedVisibilityScopeImpl,
) : MeasurePolicy {
    var hasLookaheadOccurred = false

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        var maxWidth = 0
        var maxHeight = 0
        val placeables =
            measurables.fastMap {
                it.measure(constraints).apply {
                    maxWidth = max(maxWidth, width)
                    maxHeight = max(maxHeight, height)
                }
            }
        if (isLookingAhead) {
            hasLookaheadOccurred = true
            scope.targetSize.value = IntSize(maxWidth, maxHeight)
        } else if (!hasLookaheadOccurred) {
            scope.targetSize.value = IntSize(maxWidth, maxHeight)
        }
        return layout(maxWidth, maxHeight) { placeables.fastForEach { it.place(0, 0) } }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ) = measurables.fastMaxOfOrDefault(0) { it.minIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ) = measurables.fastMaxOfOrDefault(0) { it.minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ) = measurables.fastMaxOfOrDefault(0) { it.maxIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ) = measurables.fastMaxOfOrDefault(0) { it.maxIntrinsicHeight(width) }
}

// Convert Boolean visibility to EnterExitState, mirroring framework behavior.
@Composable
private fun <T> Transition<T>.targetEnterExit(
    visible: (T) -> Boolean,
    targetState: T,
): EnterExitState =
    key(this) {
        if (this.isSeeking) {
            if (visible(targetState)) {
                EnterExitState.Visible
            } else {
                if (visible(this.currentState)) EnterExitState.PostExit else EnterExitState.PreEnter
            }
        } else {
            val hasBeenVisible = remember { mutableStateOf(false) }
            if (visible(currentState)) hasBeenVisible.value = true
            if (visible(targetState)) {
                EnterExitState.Visible
            } else {
                if (hasBeenVisible.value) EnterExitState.PostExit else EnterExitState.PreEnter
            }
        }
    }
