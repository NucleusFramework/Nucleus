package dev.nucleusframework.tray.menu.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nucleusframework.tray.menu.ComposableTrayMenuScope
import dev.nucleusframework.tray.menu.KeyShortcut
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.utils.IconRenderProperties
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Records calls made by a user-supplied `@Composable ComposableTrayMenuScope.() -> Unit` lambda
 * during composition into a flat list of [MenuOp]s. Once composition finishes, [buildReplay] can
 * produce a non-composable `TrayMenuBuilder.() -> Unit` that emits the recorded operations into
 * any [TrayMenuBuilder] — including those constructed by the native (Windows/Linux/Mac/Awt)
 * tray impls.
 *
 * This is the bridge that lets users freely call `painterResource(...)` and other composable
 * functions inside the tray DSL without hoisting them above `application { … }`.
 */
internal class RecordingComposableScope : ComposableTrayMenuScope {
    // Snapshot-state backed: when only the user's menu lambda recomposes (a state it reads
    // changed), the DSL re-records into this list. Readers of snapshot() — the Tray wrapper
    // that computes the menu fingerprint — get invalidated by that write and re-record the
    // whole menu with a fresh scope, so label/enabled/checked changes reach the native menu
    // without the caller disposing the tray (issue #434).
    private val ops = mutableStateListOf<MenuOp>()

    fun snapshot(): List<MenuOp> = ops.toList()

    @Composable
    override fun Item(
        label: String,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        ops += MenuOp.PlainItem(label, isEnabled, shortcut, onClick)
    }

    @Composable
    override fun Item(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        ops += MenuOp.PainterItem(label, icon, iconRenderProperties, isEnabled, shortcut, onClick)
    }

    @Composable
    override fun Item(
        label: String,
        icon: ImageVector,
        iconTint: Color?,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        ops += MenuOp.VectorItem(label, icon, iconTint, iconRenderProperties, isEnabled, shortcut, onClick)
    }

    @Composable
    override fun CheckableItem(
        label: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
    ) {
        ops += MenuOp.PlainCheckable(label, checked, onCheckedChange, isEnabled, shortcut)
    }

    @Composable
    override fun CheckableItem(
        label: String,
        icon: Painter,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
    ) {
        ops += MenuOp.PainterCheckable(label, icon, iconRenderProperties, checked, onCheckedChange, isEnabled, shortcut)
    }

    @Composable
    override fun CheckableItem(
        label: String,
        icon: ImageVector,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        iconTint: Color?,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
    ) {
        ops +=
            MenuOp.VectorCheckable(
                label,
                icon,
                iconTint,
                iconRenderProperties,
                checked,
                onCheckedChange,
                isEnabled,
                shortcut,
            )
    }

    @Composable
    override fun SubMenu(
        label: String,
        isEnabled: Boolean,
        submenuContent: @Composable ComposableTrayMenuScope.() -> Unit,
    ) {
        val child = RecordingComposableScope()
        child.submenuContent()
        ops += MenuOp.PlainSubMenu(label, isEnabled, child.snapshot())
    }

    @Composable
    override fun SubMenu(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        submenuContent: @Composable ComposableTrayMenuScope.() -> Unit,
    ) {
        val child = RecordingComposableScope()
        child.submenuContent()
        ops += MenuOp.PainterSubMenu(label, icon, iconRenderProperties, isEnabled, child.snapshot())
    }

    @Composable
    override fun SubMenu(
        label: String,
        icon: ImageVector,
        iconTint: Color?,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        submenuContent: @Composable ComposableTrayMenuScope.() -> Unit,
    ) {
        val child = RecordingComposableScope()
        child.submenuContent()
        ops += MenuOp.VectorSubMenu(label, icon, iconTint, iconRenderProperties, isEnabled, child.snapshot())
    }

    @Composable
    override fun Item(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        // Resolve the resource during composition so the recorded op holds a plain Painter.
        Item(label, painterResource(icon), iconRenderProperties, isEnabled, shortcut, onClick)
    }

    @Composable
    override fun CheckableItem(
        label: String,
        icon: DrawableResource,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
    ) {
        CheckableItem(
            label,
            painterResource(icon),
            checked,
            onCheckedChange,
            iconRenderProperties,
            isEnabled,
            shortcut,
        )
    }

    @Composable
    override fun SubMenu(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        submenuContent: @Composable ComposableTrayMenuScope.() -> Unit,
    ) {
        SubMenu(label, painterResource(icon), iconRenderProperties, isEnabled, submenuContent)
    }

    @Composable
    override fun Divider() {
        ops += MenuOp.Divider
    }
}

/**
 * Recorded menu operation. Captures everything needed to later emit the same call against any
 * [TrayMenuBuilder] instance.
 */
internal sealed interface MenuOp {
    data class PlainItem(
        val label: String,
        val isEnabled: Boolean,
        val shortcut: KeyShortcut?,
        val onClick: () -> Unit,
    ) : MenuOp

    data class PainterItem(
        val label: String,
        val icon: Painter,
        val iconRenderProperties: IconRenderProperties,
        val isEnabled: Boolean,
        val shortcut: KeyShortcut?,
        val onClick: () -> Unit,
    ) : MenuOp

    data class VectorItem(
        val label: String,
        val icon: ImageVector,
        val iconTint: Color?,
        val iconRenderProperties: IconRenderProperties,
        val isEnabled: Boolean,
        val shortcut: KeyShortcut?,
        val onClick: () -> Unit,
    ) : MenuOp

    data class PlainCheckable(
        val label: String,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val isEnabled: Boolean,
        val shortcut: KeyShortcut?,
    ) : MenuOp

    data class PainterCheckable(
        val label: String,
        val icon: Painter,
        val iconRenderProperties: IconRenderProperties,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val isEnabled: Boolean,
        val shortcut: KeyShortcut?,
    ) : MenuOp

    data class VectorCheckable(
        val label: String,
        val icon: ImageVector,
        val iconTint: Color?,
        val iconRenderProperties: IconRenderProperties,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val isEnabled: Boolean,
        val shortcut: KeyShortcut?,
    ) : MenuOp

    data class PlainSubMenu(
        val label: String,
        val isEnabled: Boolean,
        val children: List<MenuOp>,
    ) : MenuOp

    data class PainterSubMenu(
        val label: String,
        val icon: Painter,
        val iconRenderProperties: IconRenderProperties,
        val isEnabled: Boolean,
        val children: List<MenuOp>,
    ) : MenuOp

    data class VectorSubMenu(
        val label: String,
        val icon: ImageVector,
        val iconTint: Color?,
        val iconRenderProperties: IconRenderProperties,
        val isEnabled: Boolean,
        val children: List<MenuOp>,
    ) : MenuOp

    data object Divider : MenuOp
}

/**
 * Replays the recorded menu into a [TrayMenuBuilder], as a plain (non-composable) lambda for the
 * native menu impls. The structure comes from this list, but every click resolves its
 * handler in [latest] at click time: a recomposition that keeps the structure (and so the native
 * menu) still hands its fresh lambdas, with their fresh captured values, to the next click.
 */
internal fun List<MenuOp>.asTrayMenuBuilderBlock(latest: () -> List<MenuOp> = { this }): TrayMenuBuilder.() -> Unit =
    {
        replay(this@asTrayMenuBuilderBlock, emptyList(), latest)
    }

private fun MenuOp.childrenOrNull(): List<MenuOp>? =
    when (this) {
        is MenuOp.PlainSubMenu -> children
        is MenuOp.PainterSubMenu -> children
        is MenuOp.VectorSubMenu -> children
        else -> null
    }

private fun List<MenuOp>.at(path: List<Int>): MenuOp? {
    var level: List<MenuOp> = this
    var op: MenuOp? = null
    path.forEachIndexed { depth, index ->
        op = level.getOrNull(index) ?: return null
        if (depth < path.lastIndex) level = op?.childrenOrNull() ?: return null
    }
    return op
}

private fun MenuOp.clickOrNull(): (() -> Unit)? =
    when (this) {
        is MenuOp.PlainItem -> onClick
        is MenuOp.PainterItem -> onClick
        is MenuOp.VectorItem -> onClick
        else -> null
    }

private fun MenuOp.checkedChangeOrNull(): ((Boolean) -> Unit)? =
    when (this) {
        is MenuOp.PlainCheckable -> onCheckedChange
        is MenuOp.PainterCheckable -> onCheckedChange
        is MenuOp.VectorCheckable -> onCheckedChange
        else -> null
    }

@Suppress("LongMethod")
private fun TrayMenuBuilder.replay(
    ops: List<MenuOp>,
    parent: List<Int>,
    latest: () -> List<MenuOp>,
) {
    ops.forEachIndexed { index, op ->
        val path = parent + index
        val click: () -> Unit = { (latest().at(path)?.clickOrNull() ?: op.clickOrNull())?.invoke() }
        val checkedChange: (Boolean) -> Unit = { checked ->
            (latest().at(path)?.checkedChangeOrNull() ?: op.checkedChangeOrNull())?.invoke(checked)
        }
        when (op) {
            is MenuOp.PlainItem -> Item(op.label, op.isEnabled, op.shortcut, click)
            is MenuOp.PainterItem ->
                Item(op.label, op.icon, op.iconRenderProperties, op.isEnabled, op.shortcut, click)
            is MenuOp.VectorItem ->
                Item(op.label, op.icon, op.iconTint, op.iconRenderProperties, op.isEnabled, op.shortcut, click)
            is MenuOp.PlainCheckable ->
                CheckableItem(op.label, op.checked, checkedChange, op.isEnabled, op.shortcut)
            is MenuOp.PainterCheckable ->
                CheckableItem(
                    op.label,
                    op.icon,
                    op.iconRenderProperties,
                    op.checked,
                    checkedChange,
                    op.isEnabled,
                    op.shortcut,
                )
            is MenuOp.VectorCheckable ->
                CheckableItem(
                    op.label,
                    op.icon,
                    op.iconTint,
                    op.iconRenderProperties,
                    op.checked,
                    checkedChange,
                    op.isEnabled,
                    op.shortcut,
                )
            is MenuOp.PlainSubMenu ->
                SubMenu(op.label, op.isEnabled) { replay(op.children, path, latest) }
            is MenuOp.PainterSubMenu ->
                SubMenu(op.label, op.icon, op.iconRenderProperties, op.isEnabled) { replay(op.children, path, latest) }
            is MenuOp.VectorSubMenu ->
                SubMenu(op.label, op.icon, op.iconTint, op.iconRenderProperties, op.isEnabled) {
                    replay(op.children, path, latest)
                }
            MenuOp.Divider -> Divider()
        }
    }
}

/**
 * Computes a stable structural fingerprint of the recorded operations. Used to feed
 * `LaunchedEffect` keys so that menu changes trigger a native menu rebuild while irrelevant
 * recompositions don't.
 */
internal fun List<MenuOp>.structuralFingerprint(): String {
    val sb = StringBuilder()
    fingerprint(this, sb)
    return sb.toString()
}

private fun fingerprint(
    ops: List<MenuOp>,
    sb: StringBuilder,
) {
    for (op in ops) {
        when (op) {
            is MenuOp.PlainItem ->
                sb
                    .append("I:")
                    .append(op.label)
                    .append(':')
                    .append(op.isEnabled)
                    .append(':')
                    .append(op.shortcut)
            is MenuOp.PainterItem ->
                sb
                    .append("PI:")
                    .append(op.label)
                    .append(':')
                    .append(op.isEnabled)
                    .append(':')
                    .append(op.icon.hashCode())
                    .append(':')
                    .append(op.shortcut)
            is MenuOp.VectorItem ->
                sb
                    .append("VI:")
                    .append(op.label)
                    .append(':')
                    .append(op.isEnabled)
                    .append(
                        ':',
                    ).append(op.icon.hashCode())
                    .append(':')
                    .append(op.iconTint)
                    .append(':')
                    .append(op.shortcut)
            is MenuOp.PlainCheckable ->
                sb
                    .append("C:")
                    .append(op.label)
                    .append(':')
                    .append(op.checked)
                    .append(':')
                    .append(op.isEnabled)
                    .append(':')
                    .append(op.shortcut)
            is MenuOp.PainterCheckable ->
                sb
                    .append("PC:")
                    .append(op.label)
                    .append(':')
                    .append(op.checked)
                    .append(
                        ':',
                    ).append(op.isEnabled)
                    .append(':')
                    .append(op.icon.hashCode())
                    .append(':')
                    .append(op.shortcut)
            is MenuOp.VectorCheckable ->
                sb
                    .append("VC:")
                    .append(op.label)
                    .append(':')
                    .append(op.checked)
                    .append(
                        ':',
                    ).append(op.isEnabled)
                    .append(':')
                    .append(op.icon.hashCode())
                    .append(':')
                    .append(op.iconTint)
                    .append(':')
                    .append(op.shortcut)
            is MenuOp.PlainSubMenu -> {
                sb
                    .append("S:")
                    .append(op.label)
                    .append(':')
                    .append(op.isEnabled)
                    .append('{')
                fingerprint(op.children, sb)
                sb.append('}')
            }
            is MenuOp.PainterSubMenu -> {
                sb
                    .append("PS:")
                    .append(op.label)
                    .append(':')
                    .append(op.isEnabled)
                    .append(':')
                    .append(op.icon.hashCode())
                    .append('{')
                fingerprint(op.children, sb)
                sb.append('}')
            }
            is MenuOp.VectorSubMenu -> {
                sb
                    .append("VS:")
                    .append(op.label)
                    .append(':')
                    .append(op.isEnabled)
                    .append(':')
                    .append(op.icon.hashCode())
                    .append(':')
                    .append(op.iconTint)
                    .append('{')
                fingerprint(op.children, sb)
                sb.append('}')
            }
            MenuOp.Divider -> sb.append("D")
        }
        sb.append('|')
    }
}
