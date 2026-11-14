@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widgetandroiddemo

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Choice
import dev.nucleusframework.widget.ChoiceField
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.Image
import dev.nucleusframework.widget.ImageProvider
import dev.nucleusframework.widget.ImageSize
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.PlatformNumberField
import dev.nucleusframework.widget.PlatformTextField
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Spacer
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.Switch
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.TextStyle
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.actionOpenUrl
import dev.nucleusframework.widget.actionParametersOf
import dev.nucleusframework.widget.actionRunCallback
import dev.nucleusframework.widget.background
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.clickable
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.fillMaxHeight
import dev.nucleusframework.widget.fillMaxWidth
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.longStateKey
import dev.nucleusframework.widget.stringStateKey
import dev.nucleusframework.widget.updateWidgetState
import dev.nucleusframework.widget.weight
import java.io.ByteArrayOutputStream
import kotlin.random.Random

const val FUZZ_WIDGET = "Fuzz"

val FuzzSeedKey = longStateKey("seed")
val FuzzClicksKey = intStateKey("clicks")
val FuzzStormKey = intStateKey("storm")

/**
 * A widget whose whole tree comes from a seed: random containers, texts, buttons, images, inputs
 * and modifiers. [FuzzSpec.expected] is the oracle, computed from the spec alone: the views the
 * launcher must end up showing.
 */
class FuzzWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            val seed = state[FuzzSeedKey] ?: 0L
            val spec = FuzzSpec.generate(seed)
            for (node in spec.roots) Render(node, "r")
        }
    }

    @Composable
    private fun Render(
        node: FuzzNode,
        path: String,
    ) {
        val state = currentWidgetState()
        val modifier = node.mod.toModifier(path) { state.update { it[FuzzClicksKey] = (it[FuzzClicksKey] ?: 0) + 1 } }
        when (node) {
            is FuzzNode.Col ->
                Column(modifier, node.spacing, node.halign) {
                    node.children.forEachIndexed { i, child -> Render(child, "$path.$i") }
                }
            is FuzzNode.RowN ->
                Row(modifier, node.spacing, node.valign) {
                    node.children.forEachIndexed { i, child -> Render(child, "$path.$i") }
                }
            is FuzzNode.Txt ->
                Text(node.text, modifier, TextStyle(node.size, node.weight, node.color), node.maxLines)
            is FuzzNode.Btn ->
                when (node.kind) {
                    0 ->
                        Button(node.text, onClick = {
                            state.update { it[FuzzClicksKey] = (it[FuzzClicksKey] ?: 0) + 1 }
                        }, modifier = modifier, enabled = node.enabled)
                    1 -> Button(node.text, actionOpenUrl(node.url), modifier, node.enabled)
                    else ->
                        Button(
                            node.text,
                            actionRunCallback(
                                { id, _ -> updateWidgetState(id) { it[FuzzClicksKey] = (it[FuzzClicksKey] ?: 0) + 1 } },
                                actionParametersOf(),
                            ),
                            modifier,
                            node.enabled,
                        )
                }
            is FuzzNode.Img -> Image(node.source.provider(), node.description, modifier, node.size)
            is FuzzNode.Spc -> Spacer(modifier)
            is FuzzNode.Sw -> Switch(booleanStateKey(node.key), node.title, modifier, node.default)
            is FuzzNode.Ch -> ChoiceField(stringStateKey(node.key), node.choices, modifier, node.label, node.expanded)
            is FuzzNode.Field ->
                if (node.number) {
                    PlatformNumberField(intStateKey(node.key), modifier, "n")
                } else {
                    PlatformTextField(stringStateKey(node.key), modifier, "t")
                }
        }
    }
}

class FuzzMod(
    val fillWidth: Boolean,
    val fillHeight: Boolean,
    val weight: Int?,
    val background: WidgetBackground?,
    val clickable: Boolean,
) {
    fun toModifier(
        path: String,
        onClick: () -> Unit,
    ): WidgetModifier {
        var m: WidgetModifier = WidgetModifier
        if (fillWidth) m = m.fillMaxWidth()
        if (fillHeight) m = m.fillMaxHeight()
        weight?.let { m = m.weight(it) }
        background?.let { m = m.background(it) }
        if (clickable) m = m.clickable(onClick)
        return m
    }
}

enum class ImageSource {
    Asset,
    MissingAsset,
    Bytes,
    HugeBytes,
    GarbageBytes,
    Svg,
    BlackholeUrl,
    UnresolvableUrl,
    ;

    val decodable: Boolean get() = this == Asset || this == Bytes || this == HugeBytes

    fun provider(): ImageProvider =
        when (this) {
            Asset -> ImageProvider.appResource("widget/logo.png")
            MissingAsset -> ImageProvider.appResource("widget/missing.png")
            Bytes -> ImageProvider(FuzzAssets.small)
            HugeBytes -> ImageProvider(FuzzAssets.huge)
            GarbageBytes -> ImageProvider(byteArrayOf(1, 2, 3, 4, 5))
            Svg -> ImageProvider("<svg/>".encodeToByteArray(), "image/svg+xml")
            BlackholeUrl -> ImageProvider("http://10.255.255.1/x.png")
            UnresolvableUrl -> ImageProvider("https://nucleus-fuzz.invalid/x.png")
        }
}

/** Encoded test images, built once. */
object FuzzAssets {
    val small: ByteArray by lazy { png(24, 24) }
    val huge: ByteArray by lazy { png(4000, 3000) }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3366CC.toInt())
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}

sealed class FuzzNode {
    abstract val mod: FuzzMod

    class Col(
        val children: List<FuzzNode>,
        val spacing: Spacing,
        val halign: Alignment.Horizontal,
        override val mod: FuzzMod,
    ) : FuzzNode()

    class RowN(
        val children: List<FuzzNode>,
        val spacing: Spacing,
        val valign: Alignment.Vertical,
        override val mod: FuzzMod,
    ) : FuzzNode()

    class Txt(
        val text: String,
        val size: TextSize,
        val weight: FontWeight,
        val color: WidgetColor,
        val maxLines: Int,
        override val mod: FuzzMod,
    ) : FuzzNode()

    class Btn(
        val text: String,
        val kind: Int,
        val enabled: Boolean,
        val url: String,
        override val mod: FuzzMod,
    ) : FuzzNode()

    class Img(
        val source: ImageSource,
        val size: ImageSize,
        val description: String?,
        override val mod: FuzzMod,
    ) : FuzzNode()

    class Spc(
        override val mod: FuzzMod,
    ) : FuzzNode()

    class Sw(
        val key: String,
        val title: String,
        val default: Boolean,
        override val mod: FuzzMod,
    ) : FuzzNode()

    class Ch(
        val key: String,
        val choices: List<Choice>,
        val label: String?,
        val expanded: Boolean,
        override val mod: FuzzMod,
    ) : FuzzNode()

    class Field(
        val key: String,
        val number: Boolean,
        override val mod: FuzzMod,
    ) : FuzzNode()
}

class FuzzSpec(
    val seed: Long,
    val roots: List<FuzzNode>,
) {
    /** View classes the launcher must show, by simple class name, mirroring the Android host. */
    fun expected(): Map<String, Int> {
        val out = sortedMapOf<String, Int>()

        fun add(
            name: String,
            n: Int = 1,
        ) {
            if (n > 0) out[name] = (out[name] ?: 0) + n
        }
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        fun walk(node: FuzzNode) {
            when (node) {
                is FuzzNode.Col -> {
                    add("LinearLayout")
                    node.children.forEach(::walk)
                }
                is FuzzNode.RowN -> {
                    add("LinearLayout")
                    node.children.forEach(::walk)
                }
                is FuzzNode.Txt -> add("TextView")
                is FuzzNode.Btn -> add("Button")
                is FuzzNode.Img -> if (node.source.decodable) add("ImageView")
                is FuzzNode.Spc -> Unit
                is FuzzNode.Sw -> add(if (modern) "Switch" else "TextView")
                is FuzzNode.Ch -> {
                    add("LinearLayout")
                    if (node.label != null) add("TextView")
                    add(if (modern) "RadioButton" else "TextView", node.choices.size)
                }
                is FuzzNode.Field -> Unit
            }
        }
        // The composition's root column, unless the content is a single column it unwraps.
        val only = roots.singleOrNull()
        if (only !is FuzzNode.Col) add("LinearLayout")
        roots.forEach(::walk)
        return out
    }

    /** Whether composing it must fail (a non-positive maxLines): the render is refused. */
    fun invalid(): Boolean {
        fun bad(node: FuzzNode): Boolean =
            when (node) {
                is FuzzNode.Col -> node.children.any(::bad)
                is FuzzNode.RowN -> node.children.any(::bad)
                is FuzzNode.Txt -> node.maxLines < 1
                else -> false
            }
        return roots.any(::bad)
    }

    /** Depth in elements, the root column included. */
    fun depth(): Int {
        fun depth(node: FuzzNode): Int =
            when (node) {
                is FuzzNode.Col -> 1 + (node.children.maxOfOrNull(::depth) ?: 0)
                is FuzzNode.RowN -> 1 + (node.children.maxOfOrNull(::depth) ?: 0)
                is FuzzNode.Ch -> 2
                else -> 1
            }
        return 1 + (roots.maxOfOrNull(::depth) ?: 0)
    }

    companion object {
        private val Texts =
            listOf(
                "",
                " ",
                "a",
                "Hello",
                "👨‍👩‍👧‍👦 family",
                "שלום עולם",
                "&k=a0&s=x#%/?",
                "line\nbreak\n\nthird",
                "nul\u0000char",
                "%E2%9C%93 encoded",
                "null",
                "x".repeat(300),
                "word ".repeat(200),
                "\uD800 lone surrogate",
                "tab\there",
            )

        /** Negative seeds: -N is a chain of N nested columns around a text (-1000-N: weighted rows). */
        private fun chain(seed: Long): FuzzSpec {
            val weighted = seed <= -1000
            val n = (if (weighted) -seed - 1000 else -seed).toInt()
            val none = FuzzMod(false, false, null, null, false)
            val heavy = FuzzMod(true, false, 1, null, false)
            var node: FuzzNode = FuzzNode.Txt("leaf", TextSize.Default, FontWeight.Normal, WidgetColor.Default, 1, none)
            repeat(n) {
                node =
                    if (weighted) {
                        FuzzNode.RowN(listOf(node), Spacing.None, Alignment.Vertical.Top, heavy)
                    } else {
                        FuzzNode.Col(listOf(node), Spacing.None, Alignment.Horizontal.Start, none)
                    }
            }
            return FuzzSpec(seed, listOf(node))
        }

        /** Seed -2000: one lambda button and one clickable row per tricky text, for the click fuzz. */
        private fun labels(seed: Long): FuzzSpec {
            val none = FuzzMod(false, false, null, null, false)
            val row = FuzzMod(true, false, null, WidgetBackground.Emphasis, true)
            val small = TextSize.Small
            val children =
                Texts.flatMapIndexed { i, text ->
                    val label = text.take(40)
                    listOf(
                        FuzzNode.RowN(
                            listOf(FuzzNode.Txt("$i:$label", small, FontWeight.Normal, WidgetColor.Default, 1, none)),
                            Spacing.None,
                            Alignment.Vertical.Top,
                            row,
                        ),
                    )
                } + Texts.mapIndexed { i, text -> FuzzNode.Btn("b$i:${text.take(12)}", 0, true, "", none) }
            return FuzzSpec(seed, listOf(FuzzNode.Col(children, Spacing.None, Alignment.Horizontal.Start, none)))
        }

        fun generate(seed: Long): FuzzSpec {
            if (seed == -2000L) return labels(seed)
            if (seed < 0) return chain(seed)
            val r = Random(seed)
            val maxDepth = r.nextInt(1, 8)
            val rootCount = if (r.nextInt(4) == 0) r.nextInt(0, 4) else 1
            var keys = 0

            fun text() = Texts[r.nextInt(Texts.size)]

            fun mod(): FuzzMod =
                FuzzMod(
                    fillWidth = r.nextInt(4) == 0,
                    fillHeight = r.nextInt(5) == 0,
                    weight = if (r.nextInt(5) == 0) r.nextInt(1, 7) else null,
                    background = if (r.nextInt(4) == 0) WidgetBackground.entries.random(r) else null,
                    clickable = r.nextInt(5) == 0,
                )

            fun node(depth: Int): FuzzNode {
                val container = depth < maxDepth && r.nextInt(3) == 0
                if (container) {
                    val children = List(r.nextInt(0, 6)) { node(depth + 1) }
                    return if (r.nextBoolean()) {
                        FuzzNode.Col(children, Spacing.entries.random(r), Alignment.Horizontal.entries.random(r), mod())
                    } else {
                        FuzzNode.RowN(children, Spacing.entries.random(r), Alignment.Vertical.entries.random(r), mod())
                    }
                }
                return when (r.nextInt(9)) {
                    0, 1 ->
                        FuzzNode.Txt(
                            text(),
                            TextSize.entries.random(r),
                            FontWeight.entries.random(r),
                            WidgetColor.entries.random(r),
                            listOf(Int.MAX_VALUE, 1, 2, 0, -1, 1000).random(r),
                            mod(),
                        )
                    2 ->
                        FuzzNode.Btn(
                            text(),
                            r.nextInt(3),
                            r.nextBoolean(),
                            listOf("widgetdemo://fuzz", "not a url", "", "https://example.invalid/?a=b&c").random(r),
                            mod(),
                        )
                    3 ->
                        FuzzNode.Img(
                            ImageSource.entries.random(r),
                            ImageSize.entries.random(r),
                            if (r.nextBoolean()) text() else null,
                            mod(),
                        )
                    4 -> FuzzNode.Spc(mod())
                    5 -> FuzzNode.Sw("sw${keys++}", text(), r.nextBoolean(), mod())
                    6 ->
                        FuzzNode.Ch(
                            "ch${keys++}",
                            List(r.nextInt(0, 5)) { Choice(text(), listOf("v$it", text(), "&=?#", "").random(r)) },
                            if (r.nextBoolean()) text() else null,
                            r.nextBoolean(),
                            mod(),
                        )
                    7 -> FuzzNode.Field("f${keys++}", r.nextBoolean(), mod())
                    else ->
                        FuzzNode.Txt(
                            text(),
                            TextSize.Default,
                            FontWeight.Normal,
                            WidgetColor.Default,
                            Int.MAX_VALUE,
                            mod(),
                        )
                }
            }
            val roots =
                if (rootCount == 1 && r.nextBoolean()) {
                    listOf(
                        FuzzNode.Col(
                            List(r.nextInt(1, 6)) { node(2) },
                            Spacing.Small,
                            Alignment.Horizontal.Start,
                            mod(),
                        ),
                    )
                } else {
                    List(rootCount) { node(1) }
                }
            return FuzzSpec(seed, roots)
        }
    }
}
