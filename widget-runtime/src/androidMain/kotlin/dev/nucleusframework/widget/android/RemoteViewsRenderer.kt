@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget.android

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.RemoteViews
import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.ElementAction
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.ImageProvider
import dev.nucleusframework.widget.ImageSize
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.R
import dev.nucleusframework.widget.ResolvedModifier
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.WidgetActionSignatures
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetElement
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** `RemoteViews.MAX_NESTED_VIEWS`: the system refuses deeper `addView` nesting. */
internal const val MAX_NESTING = 10

/** A widget frame holding only [text]: what is drawn instead of a widget that cannot be. */
internal fun noticeViews(
    context: Context,
    text: String,
): RemoteViews =
    RemoteViews(context.packageName, R.layout.nucleus_widget_root).apply {
        removeAllViews(android.R.id.background)
        addView(android.R.id.background, noticeText(context.packageName, text))
    }

private fun noticeText(
    packageName: String,
    text: String,
): RemoteViews =
    RemoteViews(packageName, R.layout.nucleus_text_normal).apply {
        setTextViewText(R.id.nucleus_element, text)
        setTextViewTextSize(R.id.nucleus_element, TypedValue.COMPLEX_UNIT_SP, NOTICE_SP)
    }

private const val NOTICE_SP = 12f

/**
 * Turns a composed widget into [RemoteViews]. RemoteViews only inflate layouts and call a
 * whitelist of setters, never set layout params: each element is one of this module's layouts, and
 * its size in the parent comes from the layout chosen (`nucleus_column_mw`: match the width, wrap
 * the height) or, for a leaf and a weighted container, from a sizing slot (`nucleus_slot_zw_2`: no
 * width of its own, weight 2) the element fills.
 *
 * Android caps nested `addView` at [MAX_NESTING] levels: containers get no slot unless weighted,
 * so a widget nests about as deep as its tree, and a deeper one is drawn as a short notice.
 */

internal class RemoteViewsRenderer(
    private val context: Context,
    private val appWidgetId: Int,
    private val provider: ComponentName,
    private val images: Map<ImageProvider, Bitmap>,
) {
    private val packageName = context.packageName
    private val density = context.resources.displayMetrics.density
    private var signatures: Map<String, String> = emptyMap()

    /**
     * How deep the last [render] nested its views (`addView` levels under the frame), counted
     * before the content is replaced by the fallback when it is over [MAX_NESTING].
     */
    var nesting: Int = 0
        private set

    /** Nesting level of each container built, by identity: a leaf is 0. */
    private val depths = IdentityHashMap<RemoteViews, Int>()

    /** Children added so far to each container, by identity: the next child's position. */
    private val childCounts = IdentityHashMap<RemoteViews, Int>()

    /**
     * What each view sets only sometimes (a background, a click, a color, a line limit), by
     * identity. Part of its stable id: a recycled view keeps what the last render set on it, so it
     * is only recycled when this render sets the same properties again.
     */
    private val recycleKeys = IdentityHashMap<RemoteViews, Int>()

    fun render(composed: WidgetElement): RemoteViews {
        signatures = WidgetActionSignatures.of(composed)
        depths.clear()
        childCounts.clear()
        recycleKeys.clear()
        val root = unwrap(composed)
        val frame = RemoteViews(packageName, R.layout.nucleus_widget_root)
        // The root's background is the widget's own: drawn on the frame, not inset by its padding.
        // Always set: the launcher reapplies onto the frame it shows, so a background the root no
        // longer has would otherwise stay.
        val frameBackground = root.modifier.background?.let(::backgroundOf) ?: R.drawable.nucleus_widget_background
        frame.setInt(android.R.id.background, SET_BACKGROUND, frameBackground)
        var content = element(root, Parent.Root, rootBackground = true)
        nesting = content?.let { (depths[it] ?: 0) + 1 } ?: 0
        // Android refuses the whole update past MAX_NESTING and the launcher keeps showing the last
        // render, clicks included: show that the widget cannot be drawn instead.
        if (nesting > MAX_NESTING) content = tooDeep()
        // The launcher reapplies new views onto the tree it shows when the root layout is the same:
        // without this, every addView would append to the children already there.
        frame.removeAllViews(android.R.id.background)
        if (content != null) nest(frame, android.R.id.background, content)
        return frame
    }

    private fun tooDeep(): RemoteViews =
        noticeText(context.packageName, "This widget nests $nesting levels deep; Android allows $MAX_NESTING.")

    /** Records that [views] sets the conditional property [value] (see [recycleKeys]). */
    private fun mark(
        views: RemoteViews,
        value: Any,
    ) {
        recycleKeys[views] =
            ((recycleKeys[views] ?: 0) * HASH_PRIME + value.toString().hashCode()).mod(STABLE_ID_STRIDE)
    }

    /** Adds [child] to [parent]'s view [id], keeping track of how deep the views nest. */
    private fun nest(
        parent: RemoteViews,
        id: Int,
        child: RemoteViews,
    ) {
        val index = childCounts[parent] ?: 0
        childCounts[parent] = index + 1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The launcher reuses a child with the same stable id and layout instead of inflating it
            // again: a tap is not lost on a view replaced under the finger, and redraws are cheaper.
            parent.addStableView(id, child, index * STABLE_ID_STRIDE + (recycleKeys[child] ?: 0))
        } else {
            parent.addView(id, child)
        }
        val depth = (depths[child] ?: 0) + 1
        if (depth > (depths[parent] ?: 0)) depths[parent] = depth
    }

    /**
     * The composition wraps the content in a root column: when the widget's own root is a single
     * column, it is the root, so its background is the widget's and it fills the frame (as macOS).
     */
    private fun unwrap(root: WidgetElement): WidgetElement {
        val only = (root as? WidgetElement.Column)?.children?.singleOrNull()
        return if (only is WidgetElement.Column && root.modifier == ResolvedModifier()) only else root
    }

    private enum class Parent { Root, Column, Row }

    /** How an element sizes itself in its parent: wrap, match, or a share of the parent's main axis. */
    private enum class Extent(
        val code: Char,
    ) {
        Wrap('w'),
        Match('m'),
        Weight('z'),
    }

    private class Sizing(
        val width: Extent,
        val height: Extent,
        val weight: Int,
    ) {
        val isWeighted get() = width == Extent.Weight || height == Extent.Weight
    }

    private fun sizing(
        modifier: ResolvedModifier,
        parent: Parent,
    ): Sizing =
        when (parent) {
            Parent.Root -> Sizing(Extent.Match, Extent.Match, 0)
            // fillMaxHeight in a column takes the height left: weight 1, like a Compose Spacer.
            Parent.Column ->
                Sizing(
                    if (modifier.fillMaxWidth) Extent.Match else Extent.Wrap,
                    if (modifier.fillMaxHeight) Extent.Weight else Extent.Wrap,
                    1,
                )
            Parent.Row ->
                Sizing(
                    if (modifier.weight != null || modifier.fillMaxWidth) Extent.Weight else Extent.Wrap,
                    if (modifier.fillMaxHeight) Extent.Match else Extent.Wrap,
                    (modifier.weight ?: 1).coerceIn(1, MAX_WEIGHT),
                )
        }

    private fun element(
        element: WidgetElement,
        parent: Parent,
        textGravity: Int = Gravity.START,
        rootBackground: Boolean = false,
    ): RemoteViews? {
        val sizing = sizing(element.modifier, parent)
        val views =
            when (element) {
                is WidgetElement.Column -> return container(element, sizing, rootBackground)
                is WidgetElement.Row -> return container(element, sizing, rootBackground)
                is WidgetElement.Text -> text(element, textGravity)
                is WidgetElement.Button -> button(element)
                is WidgetElement.Image -> image(element) ?: return null
                is WidgetElement.Toggle -> toggle(element)
                is WidgetElement.ChoiceInput -> return choice(element, sizing)
                is WidgetElement.Spacer -> RemoteViews(packageName, R.layout.nucleus_spacer)
                // Text and number entry are Windows-only (widget-windows): RemoteViews have no text field.
                is WidgetElement.TextInput, is WidgetElement.NumberInput -> return null
            }
        if (!rootBackground) decorate(views, element.modifier, LEAF_PADDING_H, LEAF_PADDING_V)
        click(views, element.modifier.onClick)
        return slot(sizing, views)
    }

    private fun container(
        element: WidgetElement,
        sizing: Sizing,
        rootBackground: Boolean,
    ): RemoteViews {
        val isColumn = element is WidgetElement.Column
        val children: List<WidgetElement>
        val spacing: Spacing
        val gravity: Int
        val textGravity: Int
        if (element is WidgetElement.Column) {
            children = element.children
            spacing = element.spacing
            gravity = horizontal(element.horizontalAlignment)
            textGravity = gravity
        } else {
            element as WidgetElement.Row
            children = element.children
            spacing = element.spacing
            gravity = vertical(element.verticalAlignment)
            textGravity = Gravity.START or gravity
        }
        // A weighted container fills a slot along its weighted axis.
        val width = if (sizing.width == Extent.Weight) Extent.Match else sizing.width
        val height = if (sizing.height == Extent.Weight) Extent.Match else sizing.height
        val views = RemoteViews(packageName, containerLayout(isColumn, width, height))
        views.setInt(ELEMENT, SET_GRAVITY, gravity)
        if (!rootBackground) decorate(views, element.modifier, CONTAINER_PADDING, CONTAINER_PADDING)
        click(views, element.modifier.onClick)
        addChildren(views, children, if (isColumn) Parent.Column else Parent.Row, spacing, textGravity)
        return if (sizing.isWeighted) slot(sizing, views) else views
    }

    private fun addChildren(
        views: RemoteViews,
        children: List<WidgetElement>,
        parent: Parent,
        spacing: Spacing,
        textGravity: Int,
    ) {
        views.removeAllViews(ELEMENT)
        var first = true
        for (child in children) {
            val childViews = element(child, parent, textGravity) ?: continue
            if (!first) gap(spacing, parent)?.let { nest(views, ELEMENT, it) }
            nest(views, ELEMENT, childViews)
            first = false
        }
    }

    /** The space between two children: an empty frame as large as its padding. */
    private fun gap(
        spacing: Spacing,
        parent: Parent,
    ): RemoteViews? {
        val px = px(spacingDp(spacing))
        if (px == 0) return null
        return RemoteViews(packageName, R.layout.nucleus_gap).apply {
            if (parent == Parent.Row) setViewPadding(ELEMENT, px, 0, 0, 0) else setViewPadding(ELEMENT, 0, px, 0, 0)
        }
    }

    private fun text(
        element: WidgetElement.Text,
        gravity: Int,
    ): RemoteViews {
        val layout =
            when (element.style.fontWeight) {
                FontWeight.Bold -> R.layout.nucleus_text_bold
                FontWeight.Light -> R.layout.nucleus_text_light
                FontWeight.Normal -> R.layout.nucleus_text_normal
            }
        return RemoteViews(packageName, layout).apply {
            setTextViewText(ELEMENT, element.text)
            setTextViewTextSize(ELEMENT, TypedValue.COMPLEX_UNIT_SP, textSp(element.style.fontSize))
            // TextView.setGravity is remotable from Android 12 only. Before, a text keeps its start
            // alignment and is placed by its parent's gravity, so only a text that fills the
            // parent's width loses its centering.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setInt(ELEMENT, SET_GRAVITY, gravity)
            // Bounded even though Text checks it: a non-positive value crashes the launcher's own
            // process (StaticLayout.getLineTop(-1) while measuring), not this app's.
            if (element.maxLines != Int.MAX_VALUE) {
                mark(this, element.maxLines)
                setInt(ELEMENT, "setMaxLines", element.maxLines.coerceAtLeast(1))
            }
            textColorOf(element.style.color)?.let {
                mark(this, element.style.color)
                textColor(this, it)
            }
        }
    }

    private fun button(element: WidgetElement.Button): RemoteViews =
        RemoteViews(packageName, R.layout.nucleus_button).apply {
            setTextViewText(ELEMENT, element.text)
            setBoolean(ELEMENT, "setEnabled", element.enabled)
            if (element.enabled) click(this, element.action)
        }

    private fun image(element: WidgetElement.Image): RemoteViews? {
        val bitmap = images[element.provider] ?: return null
        val layout =
            when (element.size) {
                ImageSize.Auto -> R.layout.nucleus_image_auto
                ImageSize.Stretch -> R.layout.nucleus_image_stretch
                ImageSize.Small -> R.layout.nucleus_image_small
                ImageSize.Medium -> R.layout.nucleus_image_medium
                ImageSize.Large -> R.layout.nucleus_image_large
                ImageSize.Icon -> R.layout.nucleus_image_icon
            }
        return RemoteViews(packageName, layout).apply {
            setImageViewBitmap(ELEMENT, bitmap)
            setContentDescription(ELEMENT, element.contentDescription)
        }
    }

    // Inputs write their value when clicked, like on WidgetKit: a switch flips, a choice is one
    // option per line. Before Android 12 RemoteViews take no CompoundButton: a line with a glyph.
    private fun toggle(element: WidgetElement.Toggle): RemoteViews {
        val click = WidgetClick(appWidgetId, null, null, element.id to (!element.value).toString())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            compound(R.layout.nucleus_switch, element.title, element.value, click)
        } else {
            legacyCheck(if (element.value) "☑" else "☐", element.title, click)
        }
    }

    private fun choice(
        element: WidgetElement.ChoiceInput,
        sizing: Sizing,
    ): RemoteViews {
        // A drop-down has no RemoteViews counterpart: both forms are a list of options.
        val width = if (sizing.width == Extent.Weight) Extent.Match else sizing.width
        val height = if (sizing.height == Extent.Weight) Extent.Match else sizing.height
        val views = RemoteViews(packageName, containerLayout(true, width, height))
        views.removeAllViews(ELEMENT)
        decorate(views, element.modifier, CONTAINER_PADDING, CONTAINER_PADDING)
        element.label?.let { label ->
            val text = WidgetElement.Text(label, SUBTLE_LABEL, 1, ResolvedModifier())
            nest(views, ELEMENT, slot(sizing(text.modifier, Parent.Column), text(text, Gravity.START)))
        }
        for (choice in element.choices) {
            val selected = choice.value == element.value
            val click = WidgetClick(appWidgetId, null, null, element.id to choice.value)
            val option =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    compound(R.layout.nucleus_radio, choice.title, selected, click)
                } else {
                    legacyCheck(if (selected) "◉" else "○", choice.title, click)
                }
            // A match_parent child gives a wrap_content LinearLayout no width: options only fill
            // a choice that fills its own width.
            nest(views, ELEMENT, slot(Sizing(width, Extent.Wrap, 0), option))
        }
        return if (sizing.isWeighted) slot(sizing, views) else views
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun compound(
        layout: Int,
        title: String,
        checked: Boolean,
        click: WidgetClick,
    ): RemoteViews =
        RemoteViews(packageName, layout).apply {
            setTextViewText(ELEMENT, title)
            setCompoundButtonChecked(ELEMENT, checked)
            setOnCheckedChangeResponse(ELEMENT, RemoteViews.RemoteResponse.fromPendingIntent(broadcast(click)))
        }

    private fun legacyCheck(
        glyph: String,
        title: String,
        click: WidgetClick,
    ): RemoteViews =
        RemoteViews(packageName, R.layout.nucleus_check_legacy).apply {
            setTextViewText(ELEMENT, "$glyph  $title")
            setOnClickPendingIntent(ELEMENT, broadcast(click))
        }

    /** Wraps a leaf (or a weighted container) in the slot that gives it its size in the parent. */
    private fun slot(
        sizing: Sizing,
        content: RemoteViews,
    ): RemoteViews {
        val name =
            buildString {
                append("nucleus_slot_").append(sizing.width.code).append(sizing.height.code)
                if (sizing.isWeighted) append('_').append(sizing.weight)
            }
        return RemoteViews(packageName, SLOTS.getValue(name)).also {
            it.removeAllViews(SLOT)
            nest(it, SLOT, content)
        }
    }

    private fun decorate(
        views: RemoteViews,
        modifier: ResolvedModifier,
        horizontalPadding: Int,
        verticalPadding: Int,
    ) {
        val background = modifier.background ?: return
        mark(views, background)
        views.setInt(ELEMENT, SET_BACKGROUND, backgroundOf(background))
        views.setViewPadding(
            ELEMENT,
            px(horizontalPadding),
            px(verticalPadding),
            px(horizontalPadding),
            px(verticalPadding),
        )
    }

    private fun click(
        views: RemoteViews,
        action: ElementAction?,
    ) {
        val intent =
            when (action) {
                null -> return
                is ElementAction.Execute ->
                    broadcast(
                        WidgetClick(appWidgetId, action.key, signatures[action.key]?.let(WidgetClick::digest), null),
                    )
                is ElementAction.OpenUrl -> openUrl(action.url)
            }
        mark(views, "click")
        views.setOnClickPendingIntent(ELEMENT, intent)
    }

    private fun broadcast(click: WidgetClick): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            click.toIntent().setComponent(provider),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // Handled by the launcher alone: a web page, or the app's own deep link.
    private fun openUrl(url: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Android 12+ resolves the color in the launcher, so it follows a later light / dark switch. */
    private fun textColor(
        views: RemoteViews,
        color: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColor(ELEMENT, "setTextColor", color)
        } else {
            views.setTextColor(ELEMENT, context.getColor(color))
        }
    }

    private fun px(dp: Int): Int = (dp * density).roundToInt()

    private companion object {
        /** Stable ids: the child's position times this, plus its recycle key below it. */
        const val STABLE_ID_STRIDE = 1 shl 16
        const val HASH_PRIME = 31
        val ELEMENT = R.id.nucleus_element
        val SLOT = R.id.nucleus_slot
        const val SET_BACKGROUND = "setBackgroundResource"
        const val SET_GRAVITY = "setGravity"
        const val MAX_WEIGHT = 4

        const val CONTAINER_PADDING = 8
        const val LEAF_PADDING_H = 8
        const val LEAF_PADDING_V = 4
        val SUBTLE_LABEL =
            dev.nucleusframework.widget.TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle)

        // Resolved once: the generated sizing layouts, by name.
        val SLOTS: Map<String, Int> =
            mapOf(
                "nucleus_slot_ww" to R.layout.nucleus_slot_ww,
                "nucleus_slot_wm" to R.layout.nucleus_slot_wm,
                "nucleus_slot_mw" to R.layout.nucleus_slot_mw,
                "nucleus_slot_mm" to R.layout.nucleus_slot_mm,
                "nucleus_slot_zw_1" to R.layout.nucleus_slot_zw_1,
                "nucleus_slot_zw_2" to R.layout.nucleus_slot_zw_2,
                "nucleus_slot_zw_3" to R.layout.nucleus_slot_zw_3,
                "nucleus_slot_zw_4" to R.layout.nucleus_slot_zw_4,
                "nucleus_slot_zm_1" to R.layout.nucleus_slot_zm_1,
                "nucleus_slot_zm_2" to R.layout.nucleus_slot_zm_2,
                "nucleus_slot_zm_3" to R.layout.nucleus_slot_zm_3,
                "nucleus_slot_zm_4" to R.layout.nucleus_slot_zm_4,
                "nucleus_slot_wz_1" to R.layout.nucleus_slot_wz_1,
                "nucleus_slot_wz_2" to R.layout.nucleus_slot_wz_2,
                "nucleus_slot_wz_3" to R.layout.nucleus_slot_wz_3,
                "nucleus_slot_wz_4" to R.layout.nucleus_slot_wz_4,
                "nucleus_slot_mz_1" to R.layout.nucleus_slot_mz_1,
                "nucleus_slot_mz_2" to R.layout.nucleus_slot_mz_2,
                "nucleus_slot_mz_3" to R.layout.nucleus_slot_mz_3,
                "nucleus_slot_mz_4" to R.layout.nucleus_slot_mz_4,
            )

        fun containerLayout(
            isColumn: Boolean,
            width: Extent,
            height: Extent,
        ): Int {
            val matchWidth = width == Extent.Match
            val matchHeight = height == Extent.Match
            return when {
                isColumn && matchWidth && matchHeight -> R.layout.nucleus_column_mm
                isColumn && matchWidth -> R.layout.nucleus_column_mw
                isColumn && matchHeight -> R.layout.nucleus_column_wm
                isColumn -> R.layout.nucleus_column_ww
                matchWidth && matchHeight -> R.layout.nucleus_row_mm
                matchWidth -> R.layout.nucleus_row_mw
                matchHeight -> R.layout.nucleus_row_wm
                else -> R.layout.nucleus_row_ww
            }
        }

        fun horizontal(alignment: Alignment.Horizontal): Int =
            when (alignment) {
                Alignment.Horizontal.Start -> Gravity.START
                Alignment.Horizontal.CenterHorizontally -> Gravity.CENTER_HORIZONTAL
                Alignment.Horizontal.End -> Gravity.END
            }

        fun vertical(alignment: Alignment.Vertical): Int =
            when (alignment) {
                Alignment.Vertical.Top -> Gravity.TOP
                Alignment.Vertical.CenterVertically -> Gravity.CENTER_VERTICAL
                Alignment.Vertical.Bottom -> Gravity.BOTTOM
            }

        // The host's spacing scale, in dp.
        @Suppress("MagicNumber")
        fun spacingDp(spacing: Spacing): Int =
            when (spacing) {
                Spacing.None -> 0
                Spacing.Small -> 4
                Spacing.Default -> 8
                Spacing.Medium -> 12
                Spacing.Large -> 16
                Spacing.ExtraLarge -> 24
            }

        // The host's text scale, in sp.
        @Suppress("MagicNumber")
        fun textSp(size: TextSize): Float =
            when (size) {
                TextSize.Small -> 12f
                TextSize.Default -> 14f
                TextSize.Medium -> 16f
                TextSize.Large -> 20f
                TextSize.ExtraLarge -> 32f
            }

        fun textColorOf(color: WidgetColor): Int? =
            when (color) {
                WidgetColor.Default -> null
                WidgetColor.Subtle -> R.color.nucleus_widget_text_subtle
                WidgetColor.Accent -> R.color.nucleus_widget_text_accent
                WidgetColor.Good -> R.color.nucleus_widget_text_good
                WidgetColor.Warning -> R.color.nucleus_widget_text_warning
                WidgetColor.Attention -> R.color.nucleus_widget_text_attention
            }

        fun backgroundOf(background: WidgetBackground): Int =
            when (background) {
                WidgetBackground.Default -> R.drawable.nucleus_widget_bg_default
                WidgetBackground.Emphasis -> R.drawable.nucleus_widget_bg_emphasis
                WidgetBackground.Accent -> R.drawable.nucleus_widget_bg_accent
                WidgetBackground.Good -> R.drawable.nucleus_widget_bg_good
                WidgetBackground.Warning -> R.drawable.nucleus_widget_bg_warning
                WidgetBackground.Attention -> R.drawable.nucleus_widget_bg_attention
            }
    }
}
